// ============================================================================
// Remote Identity — UPI Payment Alert (remote announcement layer)
//
// This device's long-lived P-256 key pair, persisted AES-GCM-wrapped under the
// Keystore KEK. The private key is the device's identity: it never leaves the
// device and is never sent anywhere.
//
// Losing the KEK (uninstall, "clear data", factory reset, or a backup restored
// onto a different device, since the Keystore key does not travel with a
// backup) makes the record undecryptable. That degrades to "re-pair required"
// and never to a crash or a plaintext key.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public final class RemoteIdentity {

    private static final String TAG = "UPIPaymentAlert";
    private static final String FILE = "remote_identity.enc";

    public final PrivateKey priv;
    public final PublicKey pub;

    private RemoteIdentity(PrivateKey priv, PublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    public static RemoteIdentity generate() {
        try {
            KeyPair kp = RemoteCrypto.generateKeyPair();
            return new RemoteIdentity(kp.getPrivate(), kp.getPublic());
        } catch (Exception e) {
            Log.w(TAG, "remote: identity generation failed (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public String pubB64() {
        return RemoteCrypto.b64(RemoteCrypto.encodePublic(pub));
    }

    /** Load the stored identity, or null when absent/undecryptable. */
    public static RemoteIdentity load(Context context) {
        try {
            byte[] json = RemoteKeys.readWrapped(context, FILE);
            if (json == null) return null;
            JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
            PrivateKey priv = RemoteCrypto.decodePrivate(RemoteCrypto.unb64(o.optString("priv", "")));
            PublicKey pub = RemoteCrypto.decodePublic(RemoteCrypto.unb64(o.optString("pub", "")));
            if (priv == null || pub == null) return null;
            return new RemoteIdentity(priv, pub);
        } catch (Exception e) {
            Log.w(TAG, "remote: identity load failed (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** Load the stored identity, generating and saving one on first use. */
    public static RemoteIdentity loadOrCreate(Context context) {
        RemoteIdentity id = load(context);
        if (id != null) return id;
        id = generate();
        if (id == null) return null;
        return save(context, id) ? id : null;
    }

    public static boolean save(Context context, RemoteIdentity id) {
        try {
            if (id == null) return false;
            JSONObject o = new JSONObject();
            o.put("priv", RemoteCrypto.b64(RemoteCrypto.encodePrivate(id.priv)));
            o.put("pub", RemoteCrypto.b64(RemoteCrypto.encodePublic(id.pub)));
            return RemoteKeys.writeWrapped(context, FILE,
                    o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "remote: identity save failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static void clear(Context context) {
        RemoteKeys.delete(context, FILE);
    }

    /** Enter the PrivateKey through GeneralSecurityException handoff. */
    public static RemoteIdentity from(KeyPair kp) {
        return new RemoteIdentity(kp.getPrivate(), kp.getPublic());
    }
}
