// ============================================================================
// Remote Crypto — UPI Payment Alert (remote announcement layer)
//
// PURE JAVA. This file deliberately imports nothing from android.* and nothing
// from org.json, so it can be compiled and exercised by a plain JVM harness
// (tools/remote_crypto/RemoteCryptoHarness.java) with no Android runtime and
// no new dependency. That is what makes the crypto provable in CI-free form.
//
// Primitive set (Q2, locked with the user):
//   - key agreement : ECDH on P-256 (secp256r1)
//   - KDF           : HKDF-SHA256, hand-rolled over Mac(HmacSHA256) because
//                     Java/Android has no built-in HKDF at this API level
//   - AEAD          : AES-256-GCM, 96-bit random nonce, 128-bit tag
//
// NOTHING here logs, and nothing here touches disk. All inputs/outputs are
// byte[] so the caller decides what is stored.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.ECPublicKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class RemoteCrypto {

    /** AES-256-GCM nonce length in bytes (96 bits, the GCM-recommended size). */
    public static final int NONCE_LEN = 12;
    /** Symmetric key length in bytes (AES-256). */
    public static final int KEY_LEN = 32;
    /** GCM authentication tag length in bits. */
    public static final int TAG_BITS = 128;
    /** Length of the pairing secret carried in the pairing code. */
    public static final int PAIR_SECRET_LEN = 16;

    private static final String CURVE = "secp256r1";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private RemoteCrypto() { }

    // ---- key pairs -------------------------------------------------------

    /** Generate a fresh P-256 key pair in software. */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(CURVE), RNG);
        return kpg.generateKeyPair();
    }

    /** X.509 SubjectPublicKeyInfo encoding (91 bytes for P-256). */
    public static byte[] encodePublic(PublicKey key) {
        return key == null ? null : key.getEncoded();
    }

    /** PKCS#8 encoding of the private key. */
    public static byte[] encodePrivate(PrivateKey key) {
        return key == null ? null : key.getEncoded();
    }

    public static PublicKey decodePublic(byte[] x509) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
    }

    public static PrivateKey decodePrivate(byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    // ---- key agreement ---------------------------------------------------

    /**
     * Raw ECDH shared secret (32 bytes for P-256). This is NOT a key on its
     * own — it must always be passed through {@link #hkdfSha256}.
     */
    public static byte[] ecdh(PrivateKey own, PublicKey peer) throws GeneralSecurityException {
        // Refuse an off-curve or wrong-curve peer key BEFORE any key agreement
        // runs. Without this check a hostile pairing blob could hand us a point
        // on a different curve (an "invalid curve attack") and the provider
        // would happily compute a shared secret that leaks information about
        // our private key.
        if (!isValidP256PublicKey(peer)) {
            throw new GeneralSecurityException("peer public key is not a valid P-256 point");
        }
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(own);
        ka.doPhase(peer, true);
        return ka.generateSecret();
    }

    // ---- KDF (RFC 5869) --------------------------------------------------

    /**
     * HKDF-SHA256 extract-then-expand.
     *
     * The salt is deliberately the public pairId rather than a second secret:
     * the IKM here is a uniformly random ECDH output, so a non-secret salt is
     * sound (RFC 5869 section 3.1) and it means only one secret has to be
     * transported during pairing.
     *
     * @param info domain-separation label; changing it yields an unrelated key
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen)
            throws GeneralSecurityException {
        if (ikm == null) throw new GeneralSecurityException("hkdf: null ikm");
        if (outLen <= 0 || outLen > 255 * 32) throw new GeneralSecurityException("hkdf: bad length");

        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(salt == null ? new byte[32] : salt, HMAC));
        byte[] prk = mac.doFinal(ikm);

        mac.init(new SecretKeySpec(prk, HMAC));
        byte[] okm = new byte[outLen];
        byte[] block = new byte[0];
        int pos = 0;
        int counter = 1;
        while (pos < outLen) {
            mac.reset();
            mac.update(block);
            if (info != null) mac.update(info);
            mac.update((byte) counter);
            block = mac.doFinal();
            int n = Math.min(block.length, outLen - pos);
            System.arraycopy(block, 0, okm, pos, n);
            pos += n;
            counter++;
        }
        return okm;
    }

    /** Derive a 32-byte AES key from an ECDH secret + pairId + a purpose label. */
    public static byte[] deriveKey(byte[] ecdhSecret, byte[] pairIdBytes, String purpose)
            throws GeneralSecurityException {
        byte[] info = ("UPIPaymentAlert/remote/v1/" + purpose).getBytes(StandardCharsets.UTF_8);
        return hkdfSha256(ecdhSecret, pairIdBytes, info, KEY_LEN);
    }

    // ---- AEAD ------------------------------------------------------------

    /** Seal: returns ciphertext||tag. */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        checkKey(key);
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(plaintext);
    }

    /**
     * Open: expects ciphertext||tag. Throws AEADBadTagException (a subclass of
     * GeneralSecurityException) when the ciphertext, the tag, the nonce or the
     * AAD do not match — which is the whole tamper/replay/relabel defence.
     */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextAndTag)
            throws GeneralSecurityException {
        checkKey(key);
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(ciphertextAndTag);
    }

    private static void checkKey(byte[] key) throws GeneralSecurityException {
        if (key == null || key.length != KEY_LEN) {
            throw new GeneralSecurityException("bad key length");
        }
    }

    // ---- peer key validation ---------------------------------------------

    private static final BigInteger P256_P =
            new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger P256_A = P256_P.subtract(BigInteger.valueOf(3));
    private static final BigInteger P256_B =
            new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    private static final BigInteger P256_N =
            new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger THREE = BigInteger.valueOf(3);

    /**
     * True only for a point that is genuinely on the NIST P-256 curve we use.
     *
     * This is deliberately hand-checked rather than trusted to
     * KeyFactory.generatePublic: the harness proved that a corrupted public
     * key still parses on the JVM, so "it parsed" is NOT evidence that it is a
     * usable point. The check verifies the field, the curve coefficients, the
     * group order, the coordinate ranges, and the curve equation
     * y^2 = x^3 + ax + b (mod p).
     */
    public static boolean isValidP256PublicKey(PublicKey key) {
        try {
            if (!(key instanceof ECPublicKey)) return false;
            ECPublicKey ec = (ECPublicKey) key;
            ECParameterSpec params = ec.getParams();
            if (params == null) return false;
            EllipticCurve curve = params.getCurve();
            if (curve == null || !(curve.getField() instanceof ECFieldFp)) return false;
            if (!P256_P.equals(((ECFieldFp) curve.getField()).getP())) return false;
            if (!P256_A.equals(curve.getA())) return false;
            if (!P256_B.equals(curve.getB())) return false;
            if (!P256_N.equals(params.getOrder())) return false;

            ECPoint w = ec.getW();
            if (w == null || ECPoint.POINT_INFINITY.equals(w)) return false;
            BigInteger x = w.getAffineX();
            BigInteger y = w.getAffineY();
            if (x == null || y == null) return false;
            if (x.signum() < 0 || x.compareTo(P256_P) >= 0) return false;
            if (y.signum() < 0 || y.compareTo(P256_P) >= 0) return false;

            BigInteger lhs = y.modPow(TWO, P256_P);
            BigInteger rhs = x.modPow(THREE, P256_P)
                    .add(P256_A.multiply(x))
                    .add(P256_B)
                    .mod(P256_P);
            return lhs.equals(rhs);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- randomness ------------------------------------------------------

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    // ---- base64url (hand-rolled) -----------------------------------------

    /**
     * base64url WITHOUT padding, implemented here on purpose:
     *   - android.util.Base64 is not on the JVM classpath (would break the harness)
     *   - java.util.Base64 requires API 26 and this app's minSdk is 24
     */
    public static String b64(byte[] in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(((in.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < in.length) {
            int n = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8) | (in[i + 2] & 0xFF);
            sb.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63])
              .append(B64[(n >>> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rem = in.length - i;
        if (rem == 1) {
            int n = (in[i] & 0xFF) << 16;
            sb.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]);
        } else if (rem == 2) {
            int n = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8);
            sb.append(B64[(n >>> 18) & 63]).append(B64[(n >>> 12) & 63]).append(B64[(n >>> 6) & 63]);
        }
        return sb.toString();
    }

    /**
     * Tolerant base64url decode: any character outside the alphabet (spaces,
     * newlines, dashes used as separators, a copy-paste artefact) is ignored,
     * so a user-pasted pairing string survives reformatting.
     */
    public static byte[] unb64(String s) {
        if (s == null) return null;
        int[] vals = new int[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '-' || c == '+') v = 62;
            else if (c == '_' || c == '/') v = 63;
            else continue; // separator / whitespace / noise
            vals[n++] = v;
        }
        int full = n / 4;
        int rem = n % 4;
        int outLen = full * 3 + (rem == 2 ? 1 : rem == 3 ? 2 : 0);
        byte[] out = new byte[outLen];
        int acc = 0;
        int bits = 0;
        int oi = 0;
        for (int i = 0; i < n && oi < outLen; i++) {
            acc = (acc << 6) | vals[i];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[oi++] = (byte) ((acc >>> bits) & 0xFF);
            }
        }
        return out;
    }
}
