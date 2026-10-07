// ============================================================================
// Remote Envelope — UPI Payment Alert (remote announcement layer)
//
// The wire format. Everything the relay can see is in here, and it is
// deliberately almost nothing:
//
//   {"v":1,"k":"pay","pairId":"..","eventId":"..","n":"<b64 nonce>","ct":"<b64 ct||tag>"}
//
// v/k/pairId/eventId/n are cleartext because they are either needed to decrypt
// (nonce) or needed to route/discard cheaply (pairId, eventId), and none of them
// carries payment information. The amount, currency, timestamp and source live
// INSIDE the AEAD ciphertext.
//
// eventId appears twice on purpose: outside (for pre-decrypt replay rejection
// and as AAD binding) and authenticated inside the ciphertext. A mismatch means
// tampering and the message is discarded.
// ============================================================================
package com.example.upipaymentalert.remote;

import com.example.upipaymentalert.PaymentEvent;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public final class RemoteEnvelope {

    public static final int VERSION = 1;
    public static final String KIND_PAIR = "pair";
    public static final String KIND_PAY = "pay";

    /** Hard cap on envelope size; an FCM data payload allows 4096 bytes. */
    public static final int MAX_CHARS = 1024;

    private RemoteEnvelope() { }

    /** AAD binds version, kind, pairing and event identity to the ciphertext. */
    public static byte[] aad(String kind, String pairId, String eventId) {
        return (VERSION + "|" + kind + "|" + pairId + "|" + eventId).getBytes(StandardCharsets.UTF_8);
    }

    public static String build(String kind, String pairId, String eventId,
                              String nonceB64, String ctB64) throws Exception {
        return build(kind, pairId, eventId, nonceB64, ctB64, "");
    }

    /**
     * Build an envelope. When epkB64 is non-empty it carries the sender's
     * ephemeral P-256 public key (the ECIES header): the receiver derives the
     * introduction key from it instead of from a shared pairSecret. The field
     * is plaintext by design — a key offer, not a secret — and parsers that
     * do not know it ignore it, so the legacy blob flow keeps working.
     */
    public static String build(String kind, String pairId, String eventId,
                              String nonceB64, String ctB64, String epkB64) throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", VERSION);
        o.put("k", kind);
        o.put("pairId", pairId);
        o.put("eventId", eventId);
        o.put("n", nonceB64);
        o.put("ct", ctB64);
        if (epkB64 != null && !epkB64.isEmpty()) {
            o.put("epk", epkB64);
        }
        return o.toString();
    }

    /** Parse + structurally validate a received envelope. Null when unusable. */
    public static JSONObject parse(String json) {
        try {
            if (json == null || json.isEmpty()) return null;
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", 0) != VERSION) return null;
            String k = o.optString("k", "");
            if (!KIND_PAIR.equals(k) && !KIND_PAY.equals(k)) return null;
            if (o.optString("pairId", "").isEmpty()) return null;
            if (o.optString("eventId", "").isEmpty()) return null;
            if (o.optString("n", "").isEmpty()) return null;
            if (o.optString("ct", "").isEmpty()) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort eventId extraction, used only for log lines. Never throws. */
    public static String eventIdOf(String envelopeJson) {
        try {
            JSONObject o = new JSONObject(envelopeJson);
            return o.optString("eventId", "?");
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- inner payloads (these live INSIDE the ciphertext) ---------------

    /**
     * The minimum viable payment payload (Q6). Note what is absent: no payer
     * name, no UPI id, no raw SMS body, no bank name, no display text.
     */
    public static String paymentPayload(PaymentEvent ev, String currency) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", KIND_PAY);
        o.put("v", VERSION);
        o.put("eventId", ev.getEventId());
        o.put("amountPaise", ev.getAmountPaise());
        o.put("currency", currency);
        o.put("timestampMs", ev.getTimestampMs());
        o.put("source", ev.getSource() == null ? "SMS" : ev.getSource().name());
        return o.toString();
    }

    /**
     * The pairing introduction: the owner's public key plus a random send token
     * that only authenticates the owner to the relay. The send token is NOT
     * derived from the session key, so the relay gains no cryptographic power.
     */
    public static String pairPayload(String ownerPubB64, String sendTokenB64,
                                    String eventId) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", KIND_PAIR);
        o.put("v", VERSION);
        // RemoteIngest cross-checks the inner eventId against the envelope's
        // eventId before accepting ANY payload (pair or pay), so the
        // introduction must carry it too.
        o.put("eventId", eventId);
        o.put("ownerPub", ownerPubB64);
        o.put("sendToken", sendTokenB64);
        return o.toString();
    }

    public static JSONObject parseInner(String json) {
        try {
            if (json == null || json.isEmpty()) return null;
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", 0) != VERSION) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }
}
