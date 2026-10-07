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
package com.vivekray898.upipaymentalert.remote;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RemotePairingStore {

    private static final String TAG = "UPIPaymentAlert";
    private static final String FILE = "remote_pair.enc";

    public enum Role { OWNER, CHILD }

    public static final class Pairing {
        public final String pairId;
        public final Role role;
        public final String peerPubB64;
        public final String kMsgB64;
        public final String sendTokenB64;
        public final String childFcmTokenB64;
        public final String pairSecretB64;
        public final String pendingSecretB64;
        public final String verificationCode;

        public Pairing(String pairId, Role role, String peerPubB64, String kMsgB64,
                       String sendTokenB64, String childFcmTokenB64,
                       String verificationCode, String pendingSecretB64,
                       String pairSecretB64) {
            this.pairId = pairId == null ? "" : pairId;
            this.role = role == null ? Role.OWNER : role;
            this.peerPubB64 = peerPubB64 == null ? "" : peerPubB64;
            this.kMsgB64 = kMsgB64 == null ? "" : kMsgB64;
            this.sendTokenB64 = sendTokenB64 == null ? "" : sendTokenB64;
            this.childFcmTokenB64 = childFcmTokenB64 == null ? "" : childFcmTokenB64;
            this.verificationCode = verificationCode == null ? "" : verificationCode;
            this.pendingSecretB64 = pendingSecretB64 == null ? "" : pendingSecretB64;
            this.pairSecretB64 = pairSecretB64 == null ? "" : pairSecretB64;
        }

        public boolean isComplete() {
            return kMsgB64 != null && !kMsgB64.isEmpty();
        }
    }

    public static final class LookupResult {
        public final String pairId;
        public final String childPubB64;
        public final String childFcmToken;
        public final boolean success;
        public final String error;

        public LookupResult(String pairId, String childPubB64, String childFcmToken,
                           boolean success, String error) {
            this.pairId = pairId;
            this.childPubB64 = childPubB64;
            this.childFcmToken = childFcmToken;
            this.success = success;
            this.error = error;
        }
    }

    private RemotePairingStore() { }

    public static boolean save(Context context, Pairing p) {
        return saveAll(context, p == null ? null : Collections.singletonList(p));
    }

    public static boolean saveAll(Context context, List<Pairing> pairings) {
        if (context == null || pairings == null || pairings.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Pairing p : pairings) {
                if (p == null) continue;
                JSONObject o = new JSONObject();
                o.put("pairId", p.pairId);
                o.put("role", p.role.name());
                o.put("peerPub", p.peerPubB64);
                o.put("kMsg", p.kMsgB64);
                o.put("sendToken", p.sendTokenB64);
                o.put("childFcm", p.childFcmTokenB64);
                o.put("vcode", p.verificationCode);
                o.put("pending", p.pendingSecretB64);
                o.put("pairSecret", p.pairSecretB64);
                arr.put(o);
            }
            root.put("pairings", arr);
            return RemoteKeys.writeWrapped(context, FILE,
                    root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing saveAll failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static List<Pairing> loadAll(Context context) {
        try {
            if (context == null) return Collections.emptyList();
            byte[] json = RemoteKeys.readWrapped(context, FILE);
            if (json == null) return Collections.emptyList();
            JSONObject root = new JSONObject(new String(json, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("pairings");
            if (arr == null) {
                // legacy single-pair record: treat as a one-element list
                return Collections.singletonList(legacyLoad(root));
            }
            List<Pairing> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    out.add(pairingFromJson(arr.getJSONObject(i)));
                } catch (Exception ignored) {
                }
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing loadAll failed (" + e.getClass().getSimpleName() + ")");
            return Collections.emptyList();
        }
    }

    /** Back-compat reader for the single-pair legacy record shape. */
    private static Pairing legacyLoad(JSONObject o) {
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
                o.optString("childFcm", ""),
                o.optString("vcode", ""),
                o.optString("pending", ""),
                o.optString("pairSecret", ""));
    }

    private static Pairing pairingFromJson(JSONObject o) {
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
                o.optString("childFcm", ""),
                o.optString("vcode", ""),
                o.optString("pending", ""),
                o.optString("pairSecret", ""));
    }

    /** Legacy single-pair shorthand kept for call sites that still want one. */
    public static Pairing load(Context context) {
        List<Pairing> all = loadAll(context);
        return all.isEmpty() ? null : all.get(0);
    }

    public static boolean isPaired(Context context) {
        List<Pairing> all = loadAll(context);
        if (all == null || all.isEmpty()) return false;
        for (Pairing p : all) {
            if (p != null && p.isComplete()) return true;
        }
        return false;
    }

    public static void clear(Context context) {
        try {
            RemoteKeys.delete(context, FILE);
            RemoteIdentity.clear(context);
        } catch (Exception e) {
            Log.w(TAG, "remote: unpair failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Unpair a single pairing by pairId. Kept for targeted owner-side unpair. */
    public static boolean clearPairing(Context context, String pairId) {
        if (context == null || pairId == null || pairId.isEmpty()) return false;
        try {
            List<Pairing> all = loadAll(context);
            List<Pairing> kept = new ArrayList<>();
            boolean removed = false;
            for (Pairing p : all) {
                if (p != null && pairId.equals(p.pairId)) {
                    removed = true;
                    continue;
                }
                kept.add(p);
            }
            if (!removed) return false;
            return saveAll(context, kept);
        } catch (Exception e) {
            Log.w(TAG, "remote: clearPairing failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    // ---- Supabase 6-digit code flow (phase 2) ---------------------------

    public static LookupResult lookupAndConsumePairing(String pairingCode) {
        if (pairingCode == null || !pairingCode.matches("^\\d{6}$")) {
            return new LookupResult("", "", "", false, "Invalid pairing code format");
        }
        if (FcmTransport.supabaseUrl == null || FcmTransport.supabaseUrl.isEmpty()
                || FcmTransport.supabaseAnonKey == null || FcmTransport.supabaseAnonKey.isEmpty()) {
            return new LookupResult("", "", "", false, "Supabase not configured");
        }
        try {
            String body = "{\"pairing_code\":\"" + pairingCode + "\"}";
            // Exactly one round trip: consuming the code is single-use, so a
            // second POST for the body would come back 404.
            HttpResult res = postOnce(
                    FcmTransport.supabaseUrl + "/functions/v1/lookup_and_consume_pairing",
                    body, FcmTransport.supabaseAnonKey);
            if (res.code == 429) {
                return new LookupResult("", "", "", false, "Too many attempts, try again later");
            }
            if (res.code != 200 || res.body.isEmpty()) {
                return new LookupResult("", "", "", false, "Pairing code not found, expired, or already used");
            }
            JSONObject json = new JSONObject(res.body);
            if (!json.optBoolean("success", false)) {
                return new LookupResult("", "", "", false, json.optString("error", "Unknown error"));
            }
            String pairId = json.optString("pair_id", "");
            String childPub = json.optString("child_pub_key", "");
            String childFcm = json.optString("child_fcm_token", "");
            if (pairId.isEmpty() || childPub.isEmpty()) {
                return new LookupResult("", "", "", false, "Incomplete response from server");
            }
            return new LookupResult(pairId, childPub, childFcm, true, "");
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing lookup failed (" + e.getClass().getSimpleName() + ")");
            return new LookupResult("", "", "", false, "Network error: " + e.getMessage());
        }
    }

    public static LookupResult registerPairing(String pairingCode, String childPubB64, String childFcmToken) {
        if (pairingCode == null || !pairingCode.matches("^\\d{6}$")) {
            return new LookupResult("", "", "", false, "Invalid pairing code format");
        }
        if (FcmTransport.supabaseUrl == null || FcmTransport.supabaseUrl.isEmpty()
                || FcmTransport.supabaseAnonKey == null || FcmTransport.supabaseAnonKey.isEmpty()) {
            return new LookupResult("", "", "", false, "Supabase not configured");
        }
        try {
            String body = "{\"pairing_code\":\"" + pairingCode
                    + "\",\"child_pub_key\":\"" + childPubB64
                    + "\",\"child_fcm_token\":\"" + (childFcmToken != null ? childFcmToken : "") + "\"}";
            HttpResult res = postOnce(
                    FcmTransport.supabaseUrl + "/functions/v1/create_pairing",
                    body, FcmTransport.supabaseAnonKey);
            if (res.code == 200 && !res.body.isEmpty()) {
                JSONObject json = new JSONObject(res.body);
                if (json.optBoolean("success", false)) {
                    return new LookupResult(json.optString("pair_id", ""), childPubB64, childFcmToken, true, "");
                }
            }
            if (res.code == 409) {
                return new LookupResult("", "", "", false, "Pairing code already in use");
            }
            if (res.code == 429) {
                return new LookupResult("", "", "", false, "Too many attempts, try again later");
            }
            return new LookupResult("", "", "", false, "Failed to register pairing");
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing registration failed (" + e.getClass().getSimpleName() + ")");
            return new LookupResult("", "", "", false, "Network error: " + e.getMessage());
        }
    }

    // ---- HTTP helpers ---------------------------------------------------

    /** Result of a single POST: status code plus body (success OR error). */
    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * Exactly ONE HTTP round trip. Posting once for the status and again for
     * the body is fatal against single-use endpoints: the second POST would
     * see an already-consumed pairing code and report a false failure.
     */
    private static HttpResult postOnce(String url, String body, String auth) throws Exception {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + auth);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            byte[] bytes = body.getBytes("UTF-8");
            java.io.OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.close();
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return new HttpResult(code, "");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return new HttpResult(code, sb.toString());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
        }
    }
}
