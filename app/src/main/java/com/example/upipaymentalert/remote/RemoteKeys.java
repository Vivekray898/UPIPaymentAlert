// ============================================================================
// Remote Keys — UPI Payment Alert (remote announcement layer)
//
// The only place that touches the Android Keystore. Holds ONE Keystore AES-256
// key (the KEK) and uses it to wrap everything that must not sit in plaintext:
// the device's EC private key and the pairing record.
//
// Why wrap a software EC key instead of using a Keystore EC key: Keystore-native
// EC key AGREEMENT is only available from API 31, and this app's minSdk is 24.
// Wrapping gives uniform behaviour across 24-34 while keeping the private key
// encrypted at rest.
//
// HARD FAIL policy (agreed with the user): if the Keystore is unusable the
// remote layer reports "unavailable" and cannot be enabled. Silently falling
// back to plaintext key storage would be worse than not having the feature.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.InvalidAlgorithmParameterException;

public final class RemoteKeys {

    private static final String TAG = "UPIPaymentAlert";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEK_ALIAS = "upialert_remote_kek";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private RemoteKeys() { }

    /** True when a KEK can be created/loaded. Never throws. */
    public static boolean isAvailable(Context context) {
        try {
            return kek() != null;
        } catch (Exception e) {
            Log.w(TAG, "remote: keystore unavailable (" + e.getClass().getName() + ": " + e.getMessage() + ")");
            return false;
        }
    }

    private static SecretKey kek() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        boolean existing = ks.containsAlias(KEK_ALIAS);
        if (!existing) {
            generateKek();
        }
        return (SecretKey) ks.getKey(KEK_ALIAS, null);
    }

    private static void generateKek() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        kg.generateKey();
    }

    private static void resetKek() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEK_ALIAS)) {
            ks.deleteEntry(KEK_ALIAS);
        }
        generateKek();
    }

    /** Seal bytes under the KEK: returns [iv(12)][ciphertext||tag], or null. */
    public static byte[] wrap(Context context, byte[] plain) {
        try {
            return wrapOnce(plain);
        } catch (InvalidAlgorithmParameterException e) {
            try {
                Log.w(TAG, "remote: resetting incompatible keystore key ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                resetKek();
                return wrapOnce(plain);
            } catch (Exception retry) {
                Log.w(TAG, "remote: key wrap retry failed ("
                        + retry.getClass().getName() + ": " + retry.getMessage() + ")");
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "remote: key wrap failed (" + e.getClass().getName() + ": " + e.getMessage() + ")");
            return null;
        }
    }

    private static byte[] wrapOnce(byte[] plain) throws Exception {
        SecretKey k = kek();
        if (k == null || plain == null) return null;
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] ct = c.doFinal(plain);
        byte[] iv = c.getIV();
        byte[] out = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LEN);
        System.arraycopy(ct, 0, out, IV_LEN, ct.length);
        return out;
    }

    /**
     * Open a blob produced by wrap(). Returns null when the KEK is gone or the
     * blob fails authentication — the caller then treats the device as
     * unpaired ("re-pair required") rather than crashing.
     */
    public static byte[] unwrap(Context context, byte[] blob) {
        try {
            if (blob == null || blob.length <= IV_LEN) return null;
            SecretKey k = kek();
            if (k == null) return null;
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
            return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        } catch (Exception e) {
            Log.w(TAG, "remote: key unwrap failed (" + e.getClass().getName() + ": " + e.getMessage() + ")");
            return null;
        }
    }

    // ---- wrapped file helpers -------------------------------------------

    public static boolean writeWrapped(Context context, String name, byte[] plain) {
        try {
            byte[] blob = wrap(context, plain);
            if (blob == null) return false;
            File f = new File(context.getFilesDir(), name);
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(blob);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "remote: write " + name + " failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static byte[] readWrapped(Context context, String name) {
        try {
            File f = new File(context.getFilesDir(), name);
            if (!f.exists()) return null;
            FileInputStream fis = new FileInputStream(f);
            byte[] all = new byte[(int) f.length()];
            int off = 0;
            while (off < all.length) {
                int n = fis.read(all, off, all.length - off);
                if (n <= 0) break;
                off += n;
            }
            fis.close();
            return unwrap(context, all);
        } catch (Exception e) {
            Log.w(TAG, "remote: read " + name + " failed (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public static void delete(Context context, String name) {
        try {
            File f = new File(context.getFilesDir(), name);
            if (f.exists()) f.delete();
        } catch (Exception e) {
            Log.w(TAG, "remote: delete " + name + " failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
