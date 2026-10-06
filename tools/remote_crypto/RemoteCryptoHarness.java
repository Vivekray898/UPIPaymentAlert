// ============================================================================
// Remote Crypto Harness — plain JVM, no Android, no new dependency.
//
// Purpose: prove the remote-announcement crypto BEFORE any Android code is
// allowed to depend on it, and prove the child-side phrase reconstruction is
// byte-identical to the owner-side phrase (the drift guard for Q7).
//
// Compile:  javac -d <out> RemoteCrypto.java SmsParser.java RemoteCryptoHarness.java
// Run:      java -cp <out> RemoteCryptoHarness
// Exit:     0 = all passed, 1 = at least one failure
// ============================================================================
import com.example.upipaymentalert.remote.PairingCode;
import com.example.upipaymentalert.remote.RemoteCrypto;
import com.example.upipaymentalert.smsparser.SmsParser;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;

public class RemoteCryptoHarness {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testEcdhAgreement();
        testHkdfDeterminismAndDomainSeparation();
        testAeadRoundTrip();
        testTamperRejection();
        testCrossPairingRejection();
        testKindDomainSeparation();
        testBase64RoundTrip();
        testPhraseEquivalence();

        System.out.println();
        System.out.println("PASSED=" + passed + " FAILED=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    // ---- 1. ECDH: both sides derive the same key -------------------------
    private static void testEcdhAgreement() throws Exception {
        KeyPair owner = RemoteCrypto.generateKeyPair();
        KeyPair child = RemoteCrypto.generateKeyPair();
        byte[] pairId = RemoteCrypto.randomBytes(16);

        byte[] sob = RemoteCrypto.ecdh(owner.getPrivate(), child.getPublic());
        byte[] scb = RemoteCrypto.ecdh(child.getPrivate(), owner.getPublic());
        check("ecdh: shared secret matches on both sides", Arrays.equals(sob, scb));

        byte[] kMsgOwner = RemoteCrypto.deriveKey(sob, pairId, "message");
        byte[] kMsgChild = RemoteCrypto.deriveKey(scb, pairId, "message");
        check("ecdh: K_msg identical on both sides", Arrays.equals(kMsgOwner, kMsgChild));
        check("ecdh: K_msg is 32 bytes", kMsgOwner.length == 32);

        // a third party must not be able to derive it
        KeyPair attacker = RemoteCrypto.generateKeyPair();
        byte[] sBad = RemoteCrypto.ecdh(attacker.getPrivate(), child.getPublic());
        byte[] kBad = RemoteCrypto.deriveKey(sBad, pairId, "message");
        check("ecdh: unrelated key pair derives a different K_msg", !Arrays.equals(kMsgOwner, kBad));
    }

    // ---- 2. HKDF: deterministic, and info separates domains --------------
    private static void testHkdfDeterminismAndDomainSeparation() throws Exception {
        byte[] ikm = new byte[32];
        for (int i = 0; i < ikm.length; i++) ikm[i] = (byte) i;
        byte[] salt = "pair-salt".getBytes("UTF-8");

        byte[] a = RemoteCrypto.hkdfSha256(ikm, salt, "info-a".getBytes("UTF-8"), 32);
        byte[] b = RemoteCrypto.hkdfSha256(ikm, salt, "info-a".getBytes("UTF-8"), 32);
        byte[] c = RemoteCrypto.hkdfSha256(ikm, salt, "info-b".getBytes("UTF-8"), 32);
        check("hkdf: deterministic for identical inputs", Arrays.equals(a, b));
        check("hkdf: different info yields a different key", !Arrays.equals(a, c));

        byte[] d = RemoteCrypto.hkdfSha256(ikm, "other-salt".getBytes("UTF-8"), "info-a".getBytes("UTF-8"), 32);
        check("hkdf: different salt yields a different key", !Arrays.equals(a, d));

        byte[] long50 = RemoteCrypto.hkdfSha256(ikm, salt, "x".getBytes("UTF-8"), 50);
        check("hkdf: multi-block expand returns the requested length", long50.length == 50);

        // known-answer test (RFC 5869 test case 1, SHA-256)
        byte[] katIkm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] katSalt = hex("000102030405060708090a0b0c");
        byte[] katInfo = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] katOkm = RemoteCrypto.hkdfSha256(katIkm, katSalt, katInfo, 42);
        String expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
        check("hkdf: RFC 5869 test vector 1 matches", expected.equals(toHex(katOkm)));
    }

    // ---- 3. AEAD round-trip in both directions ---------------------------
    private static void testAeadRoundTrip() throws Exception {
        byte[] key = RemoteCrypto.randomBytes(32);
        byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
        byte[] aad = "1|pay|pair|evt".getBytes("UTF-8");
        byte[] plain = "{\"amountPaise\":125050}".getBytes("UTF-8");

        byte[] ct = RemoteCrypto.seal(key, nonce, aad, plain);
        check("aead: ciphertext differs from plaintext", !Arrays.equals(ct, plain));
        check("aead: ciphertext carries a 16-byte tag", ct.length == plain.length + 16);
        byte[] back = RemoteCrypto.open(key, nonce, aad, ct);
        check("aead: owner->child round-trip", Arrays.equals(plain, back));

        // nonces are random per message
        byte[] n2 = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
        check("aead: independent nonces are not equal", !Arrays.equals(nonce, n2));
    }

    // ---- 4. Tamper / relabel rejection ----------------------------------
    private static void testTamperRejection() throws Exception {
        byte[] key = RemoteCrypto.randomBytes(32);
        byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
        String pairId = "pair-1";
        String eventId = "evt-1";
        byte[] plain = "{\"amountPaise\":1}".getBytes("UTF-8");

        byte[] aad = aad("pay", pairId, eventId);
        byte[] ct = RemoteCrypto.seal(key, nonce, aad, plain);

        // a) flipped ciphertext bit
        byte[] flipped = ct.clone();
        flipped[0] ^= 0x01;
        check("tamper: flipped ciphertext bit is rejected", openFails(key, nonce, aad, flipped));

        // b) flipped tag bit
        byte[] flippedTag = ct.clone();
        flippedTag[flippedTag.length - 1] ^= 0x01;
        check("tamper: flipped tag bit is rejected", openFails(key, nonce, aad, flippedTag));

        // c) relabelled eventId (AAD binding)
        check("tamper: relabelled eventId is rejected",
                openFails(key, nonce, aad("pay", pairId, "evt-2"), ct));

        // d) wrong pairId
        check("tamper: wrong pairId is rejected",
                openFails(key, nonce, aad("pay", "pair-2", eventId), ct));

        // e) truncated ciphertext
        check("tamper: truncated ciphertext is rejected",
                openFails(key, nonce, aad, Arrays.copyOf(ct, Math.max(1, ct.length - 3))));
    }

    // ---- 5. Cross-pairing rejection -------------------------------------
    private static void testCrossPairingRejection() throws Exception {
        KeyPair ownerA = RemoteCrypto.generateKeyPair();
        KeyPair childA = RemoteCrypto.generateKeyPair();
        KeyPair childB = RemoteCrypto.generateKeyPair();
        byte[] pairIdA = RemoteCrypto.randomBytes(16);
        byte[] pairIdB = RemoteCrypto.randomBytes(16);

        byte[] kA = RemoteCrypto.deriveKey(
                RemoteCrypto.ecdh(ownerA.getPrivate(), childA.getPublic()), pairIdA, "message");
        byte[] kB = RemoteCrypto.deriveKey(
                RemoteCrypto.ecdh(ownerA.getPrivate(), childB.getPublic()), pairIdB, "message");

        byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
        byte[] aad = aad("pay", "p", "e");
        byte[] ct = RemoteCrypto.seal(kA, nonce, aad, "secret".getBytes("UTF-8"));

        check("cross-pairing: pairing B key cannot open pairing A message", openFails(kB, nonce, aad, ct));

        // same child key but a different pairId must also fail (pairId is in salt + AAD)
        byte[] kAOtherId = RemoteCrypto.deriveKey(
                RemoteCrypto.ecdh(ownerA.getPrivate(), childA.getPublic()), pairIdB, "message");
        check("cross-pairing: same keys but different pairId is rejected",
                openFails(kAOtherId, nonce, aad, ct));
    }

    // ---- 6. 'pair' vs 'pay' domain separation ---------------------------
    private static void testKindDomainSeparation() throws Exception {
        KeyPair owner = RemoteCrypto.generateKeyPair();
        KeyPair child = RemoteCrypto.generateKeyPair();
        byte[] pairId = RemoteCrypto.randomBytes(16);
        byte[] secret = RemoteCrypto.ecdh(owner.getPrivate(), child.getPublic());

        byte[] kPair = RemoteCrypto.deriveKey(secret, pairId, "pairing");
        byte[] kMsg = RemoteCrypto.deriveKey(secret, pairId, "message");
        check("kind: K_pair and K_msg are different keys", !Arrays.equals(kPair, kMsg));

        byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
        byte[] ct = RemoteCrypto.seal(kPair, nonce, aad("pair", "p", "e"), "intro".getBytes("UTF-8"));
        check("kind: introduction sealed with K_pair cannot be opened with K_msg",
                openFails(kMsg, nonce, aad("pair", "p", "e"), ct));
        check("kind: introduction sealed with K_pair cannot be relabelled as a payment",
                openFails(kPair, nonce, aad("pay", "p", "e"), ct));
    }

    // ---- 7. base64url round-trip (length arithmetic is fiddly) ----------
    private static void testBase64RoundTrip() throws Exception {
        boolean allOk = true;
        for (int len = 0; len <= 200; len++) {
            byte[] in = RemoteCrypto.randomBytes(len);
            byte[] out = RemoteCrypto.unb64(RemoteCrypto.b64(in));
            if (out == null || !Arrays.equals(in, out)) {
                allOk = false;
                System.out.println("   b64 mismatch at length " + len);
            }
        }
        check("b64: round-trips every length 0..200", allOk);

        // the real pairing blob size: 1 + 2 + 16 + 16 + 91 = 126 bytes
        byte[] blob = RemoteCrypto.randomBytes(126);
        check("b64: 126-byte blob round-trips", Arrays.equals(blob, RemoteCrypto.unb64(RemoteCrypto.b64(blob))));

        // The real artifact: a PairingCode, pasted with a label and grouping.
        java.security.KeyPair kp = RemoteCrypto.generateKeyPair();
        byte[] secret = RemoteCrypto.randomBytes(RemoteCrypto.PAIR_SECRET_LEN);
        byte[] pid = RemoteCrypto.randomBytes(16);
        String code = PairingCode.encode(kp.getPublic(), secret, pid);
        check("pairing: encode produces a prefixed string", code != null && code.startsWith("UPIP1."));

        PairingCode.Blob bare = PairingCode.decode(code);
        check("pairing: bare code decodes", bare != null);
        check("pairing: pairSecret survives", bare != null && Arrays.equals(secret, bare.pairSecret));
        check("pairing: pairId survives", bare != null && Arrays.equals(pid, bare.pairId));
        check("pairing: public key survives",
                bare != null && Arrays.equals(kp.getPublic().getEncoded(), bare.pub));

        // a human pasting the display form, label included, must still work
        String messy = "PAIR WITH THIS:  " + PairingCode.group(code) + "\n";
        PairingCode.Blob fromMessy = PairingCode.decode(messy);
        check("pairing: pasted label + grouping + newline still decodes",
                fromMessy != null && Arrays.equals(secret, fromMessy.pairSecret)
                        && Arrays.equals(kp.getPublic().getEncoded(), fromMessy.pub));

        // tampering must be rejected, never silently accepted
        String corrupted = code.substring(0, code.length() - 4) + "AAAA";
        byte[] offCurve = kp.getPublic().getEncoded().clone();
        offCurve[offCurve.length - 1] ^= 0x01;
        check("invalid-curve: corrupted point does NOT parse as valid",
                !RemoteCrypto.isValidP256PublicKey(RemoteCrypto.decodePublic(offCurve)));
        check("invalid-curve: ECDH refuses an off-curve peer key", ecdhFails(kp, offCurve));
        check("invalid-curve: a genuine key still validates",
                RemoteCrypto.isValidP256PublicKey(kp.getPublic()));
        check("pairing: corrupted code is rejected", PairingCode.decode(corrupted) == null);
        check("pairing: garbage is rejected", PairingCode.decode("not a code at all") == null);
        check("pairing: null is rejected", PairingCode.decode(null) == null);

        // the fingerprint must be deterministic and 6 digits
        byte[] anyKey = RemoteCrypto.randomBytes(32);
        String f1 = PairingCode.fingerprint(anyKey);
        String f2 = PairingCode.fingerprint(anyKey);
        check("fingerprint: deterministic, 6 digits", f1.equals(f2) && f1.matches("[0-9]{6}"));
        check("fingerprint: differs for a different key",
                !f1.equals(PairingCode.fingerprint(RemoteCrypto.randomBytes(32))));
    }

    private static String grouped(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 4) {
            sb.append(s, i, Math.min(s.length(), i + 4)).append(' ');
        }
        return sb.toString();
    }

    // ---- 8. Phrase equivalence (the Q7 drift guard) ---------------------
    //
    // The child never receives the spoken phrase; it rebuilds it from
    // amountPaise by feeding the UNMODIFIED SmsParser a synthetic "RS n.nn"
    // body. This proves that reconstruction matches what the owner spoke for
    // the same amount, including for comma-formatted source messages.
    private static void testPhraseEquivalence() {
        SmsParser parser = new SmsParser();

        String[][] cases = {
                // owner SMS body                                            expected phrase
                {"Your a/c XX1234 is credited with Rs 1,250.50 on 06-10-26", "Received |1250| rupees and |50| paisa"},
                {"Rs 500 credited to your account",                          "Received |500| rupees"},
                {"INR 1.00 received in your account",                        "Received |1| rupees"},
                {"Amount received: MRP 0.99",                                "Received |99| paisa"},
                {"Rs 0.00 credited",                                         "Received payment of an unknown amount"},
                {"no amount here but credited",                              "Received payment of an unknown amount"},
        };

        for (String[] c : cases) {
            String ownerPhrase = parser.getAmountFromMessageBody(c[0], "English");
            long paise = parser.extractAmount(c[0]).paise;

            String childBody = syntheticBody(paise);
            String childPhrase = parser.getAmountFromMessageBody(childBody, "English");

            boolean phraseOk = c[1].equals(ownerPhrase);
            check("phrase: owner wording for [" + c[0].substring(0, Math.min(28, c[0].length())) + "]",
                    phraseOk);
            check("phrase: child rebuild == owner phrase (paise=" + paise + ")", childPhrase.equals(ownerPhrase));
        }

        // amounts the owner could not parse must degrade identically on the child
        check("phrase: unknown amount (-1) yields the unknown sentence",
                "Received payment of an unknown amount"
                        .equals(parser.getAmountFromMessageBody(syntheticBody(-1L), "English")));
        check("phrase: zero yields the unknown sentence",
                "Received payment of an unknown amount"
                        .equals(parser.getAmountFromMessageBody(syntheticBody(0L), "English")));

        // Hindi path must work through the same mechanism
        String hiOwner = parser.getAmountFromMessageBody("Rs 250 credited", "Hindi");
        String hiChild = parser.getAmountFromMessageBody(syntheticBody(25000L), "Hindi");
        check("phrase: Hindi rebuild == owner phrase", hiOwner.equals(hiChild));
    }

    /** Mirrors RemotePhrase.syntheticBody exactly. */
    private static String syntheticBody(long amountPaise) {
        if (amountPaise < 0) return "";
        return "RS " + (amountPaise / 100L) + "." + String.format(java.util.Locale.US, "%02d", amountPaise % 100L);
    }

    // ---- helpers ---------------------------------------------------------

    private static byte[] aad(String kind, String pairId, String eventId) throws Exception {
        return ("1|" + kind + "|" + pairId + "|" + eventId).getBytes("UTF-8");
    }

    private static boolean ecdhFails(java.security.KeyPair own, byte[] peerX509) {
        try {
            RemoteCrypto.ecdh(own.getPrivate(), RemoteCrypto.decodePublic(peerX509));
            return false;
        } catch (java.security.GeneralSecurityException expected) {
            return true;
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    private static boolean openFails(byte[] key, byte[] nonce, byte[] aad, byte[] ct) {
        try {
            RemoteCrypto.open(key, nonce, aad, ct);
            return false; // opened, so the defence failed
        } catch (java.security.GeneralSecurityException expected) {
            return true;
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + label);
        } else {
            failed++;
            System.out.println("FAIL  " + label);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
