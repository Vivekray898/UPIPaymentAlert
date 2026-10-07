// ============================================================================
// Pairing Code — UPI Payment Alert (remote announcement layer)
//
// PURE JAVA (no android.*, no org.json) so the JVM harness exercises the real
// encoder/decoder rather than a copy of it.
//
// The transferable artifact between the two phones. Layout, self-describing so
// the parse can never be ambiguous:
//
//   [ver:1][pubLenHi:1][pubLenLo:1][pairSecret:16][pairId:16][pubX509:pubLen]
//
// Why a delimiter ("UPIP1.") rather than a bare base64 string: a human label
// such as "PAIR:" is made of characters that ARE in the base64 alphabet, so a
// purely "tolerant" decoder would silently absorb it as DATA and corrupt the
// payload. Anything before the last '.' is therefore discarded, which makes a
// pasted label harmless instead of dangerous. The decode still skips
// whitespace and grouping spaces, and a bare payload still works.
//
// The short numeric code is NOT the transport (see §11 of the plan: a 6-digit
// code cannot carry a public key). It is the post-pairing VERIFICATION
// FINGERPRINT, derived from the session key, that both devices display so a
// human can confirm the pairing was not man-in-the-middled.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Locale;

public final class PairingCode {

    public static final int VERSION = 1;
    private static final String PREFIX = "UPIP1.";
    private static final int PAIR_ID_LEN = 16;

    private PairingCode() { }

    /** Decoded pairing blob: everything the owner needs to reach the child. */
    public static final class Blob {
        public final byte[] pub;         // child's X.509 public key (validated)
        public final byte[] pairSecret;  // never crosses the relay
        public final byte[] pairId;
        Blob(byte[] pub, byte[] pairSecret, byte[] pairId) {
            this.pub = pub;
            this.pairSecret = pairSecret;
            this.pairId = pairId;
        }
        public String pairIdString() { return RemoteCrypto.b64(this.pairId); }
    }

    /** Build the string shown on the child device. */
    public static String encode(PublicKey pub, byte[] pairSecret, byte[] pairId) {
        byte[] x509 = RemoteCrypto.encodePublic(pub);
        if (x509 == null || pairSecret == null || pairSecret.length != RemoteCrypto.PAIR_SECRET_LEN
                || pairId == null || pairId.length != PAIR_ID_LEN) {
            return null;
        }
        byte[] out = new byte[3 + pairSecret.length + pairId.length + x509.length];
        out[0] = (byte) VERSION;
        out[1] = (byte) ((x509.length >> 8) & 0xFF);
        out[2] = (byte) (x509.length & 0xFF);
        System.arraycopy(pairSecret, 0, out, 3, pairSecret.length);
        System.arraycopy(pairId, 0, out, 3 + pairSecret.length, pairId.length);
        System.arraycopy(x509, 0, out, 3 + pairSecret.length + pairId.length, x509.length);
        return PREFIX + RemoteCrypto.b64(out);
    }

    /**
     * Decode a pasted pairing string. Returns null (never throws) for anything
     * malformed, including a public key that does not parse as an EC key — the
     * caller then shows "invalid pairing code" rather than pairing with junk.
     */
    public static Blob decode(String s) {
        if (s == null) return null;
        try {
            int dot = s.lastIndexOf('.');
            String payload = dot >= 0 ? s.substring(dot + 1) : s;
            byte[] bytes = RemoteCrypto.unb64(payload);
            int fixed = 3 + RemoteCrypto.PAIR_SECRET_LEN + PAIR_ID_LEN;
            if (bytes == null || bytes.length < fixed) return null;
            if ((bytes[0] & 0xFF) != VERSION) return null;

            int pubLen = ((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF);
            if (pubLen <= 0 || bytes.length != fixed + pubLen) return null;

            byte[] secret = new byte[RemoteCrypto.PAIR_SECRET_LEN];
            byte[] pairId = new byte[PAIR_ID_LEN];
            byte[] pub = new byte[pubLen];
            System.arraycopy(bytes, 3, secret, 0, secret.length);
            System.arraycopy(bytes, 3 + secret.length, pairId, 0, pairId.length);
            System.arraycopy(bytes, 3 + secret.length + pairId.length, pub, 0, pubLen);

            // The length field is authoritative, but the key must be a real
            // point ON the P-256 curve. Note this cannot be delegated to
            // decodePublic(): the harness showed a corrupted point still
            // parses, so parsing is not validation.
            PublicKey key = RemoteCrypto.decodePublic(pub);
            if (!RemoteCrypto.isValidP256PublicKey(key)) return null;
            return new Blob(pub, secret, pairId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Six-digit verification fingerprint derived from a session key. Both
     * devices show the same digits for the same pairing; if they differ, the
     * pairing was tampered with.
     *
     * Locale.US is deliberate: several Indian locales format %d with native
     * digits, which would make the two devices display non-matching strings.
     */
    public static String fingerprint(byte[] key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
            long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16)
                    | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            return String.format(Locale.US, "%06d", v % 1000000L);
        } catch (Exception e) {
            return "------";
        }
    }

    /** Display helper: "ABCDEFGH" -> "ABCD EFGH" (spaces are skipped on decode). */
    public static String group(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(s, i, Math.min(s.length(), i + 4));
        }
        return sb.toString();
    }
}
