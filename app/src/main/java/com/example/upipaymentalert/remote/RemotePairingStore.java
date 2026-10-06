// ============================================================================
// Remote Pairing Store — UPI Payment Alert (remote announcement layer)
//
// The pairing record: who the peer is, which role this device plays, the session
// key, the send token and the verification fingerprint. Stored AES-GCM-wrapped
// under the Keystore KEK in getFilesDir()/remote_pair.enc.
//
// A CHILD record is created BEFORE the owner's introduction arrives (it holds
// the pairSecret the child generated, so the child can open that introduction).
// It is "pending" until kMsg is filled in — isComplete() draws that line.
//
// Every method swallows exceptions and degrades to "unpaired", because a lost
// Keystore must surface as "re-pair required", never as a crash.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public final class RemotePairingStore {

    private static final String TAG = "UPIPaymentAlert";
    private static final String FILE = "remote_pair.enc";

    public enum Role { OWNER, CHILD }

    public static final class Pairing {
        public final String pairId;
        public final Role role;
        public final String peerPubB64;      // peer's X.509 public key
        public final String kMsgB64;         // session key (empty while pending)
        public final String sendTokenB64;    // owner-side bearer credential
        public final String pairSecretB64;   // child's pairing secret; never sent anywhere
        public final String pendingSecretB64; // child, pending state only
        public final String verificationCode; // 6-digit fingerprint, same on both devices

        public Pairing(String pairId, Role role, String peerPubB64, String kMsgB64,
                       String sendTokenB64, String verificationCode,
                       String pendingSecretB64, String pairSecretB64) {
            this.pairId = pairId == null ? "" : pairId;
            this.role = role == null ? Role.OWNER : role;
            this.peerPubB64 = peerPubB64 == null ? "" : peerPubB64;
            this.kMsgB64 = kMsgB64 == null ? "" : kMsgB64;
            this.sendTokenB64 = sendTokenB64 == null ? "" : sendTokenB64;
            this.verificationCode = verificationCode == null ? "" : verificationCode;
            this.pendingSecretB64 = pendingSecretB64 == null ? "" : pendingSecretB64;
            this.pairSecretB64 = pairSecretB64 == null ? "" : pairSecretB64;
        }

        /** True once the session key exists on both sides. */
        public boolean isComplete() {
            return kMsgB64 != null && !kMsgB64.isEmpty();
        }
    }

    private RemotePairingStore() { }

    public static boolean save(Context context, Pairing p) {
        try {
            if (context == null || p == null) return false;
            JSONObject o = new JSONObject();
            o.put("pairId", p.pairId);
            o.put("role", p.role.name());
            o.put("peerPub", p.peerPubB64);
            o.put("kMsg", p.kMsgB64);
            o.put("sendToken", p.sendTokenB64);
            o.put("vcode", p.verificationCode);
            o.put("pending", p.pendingSecretB64);
            o.put("pairSecret", p.pairSecretB64);
            return RemoteKeys.writeWrapped(context, FILE,
                    o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing save failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Returns null when unpaired or when the record is undecryptable. */
    public static Pairing load(Context context) {
        try {
            if (context == null) return null;
            byte[] json = RemoteKeys.readWrapped(context, FILE);
            if (json == null) return null;
            JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
            Role role;
            try {
                role = Role.valueOf(o.optString("role", "OWNER"));
            } catch (Exception bad) {
                role = Role.OWNER;
            }
            return new Pairing(
                    o.optString("pairId", ""),
                    role,
                    o.optString("peerPub", ""),
                    o.optString("kMsg", ""),
                    o.optString("sendToken", ""),
                    o.optString("vcode", ""),
                    o.optString("pending", ""),
                    o.optString("pairSecret", ""));
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing load failed (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** True only for a complete pairing. */
    public static boolean isPaired(Context context) {
        Pairing p = load(context);
        return p != null && p.isComplete();
    }

    /**
     * Unpair: forget the pairing record and the device identity. The peer simply
     * stops authenticating, which is why this is safe to do unilaterally.
     */
    public static void clear(Context context) {
        try {
            RemoteKeys.delete(context, FILE);
            RemoteIdentity.clear(context);
        } catch (Exception e) {
            Log.w(TAG, "remote: unpair failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
