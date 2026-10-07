// ============================================================================
// Remote Ingest — UPI Payment Alert (remote announcement layer)
//
// The CHILD-side pipeline, in the order the plan specifies:
//   parse -> pairing gate -> decrypt -> validate -> dedupe -> speak -> discard
//
// Contract:
//   * NEVER throws. A malformed, forged, replayed or truncated message is
//     discarded with at most an eventId in the log.
//   * Nothing is persisted except the opaque eventId ring. No amounts, no
//     history, no payload survives this method.
//   * Speech reuses the SAME ForegroundTtsService entry point the owner uses
//     (ACTION_SPEAK + EXTRA_TEXT), so language/volume/speed/segmented-speaking
//     and the 60-second phrase dedupe are shared rather than forked.
//
// Phase 1 has no wire, so this is reachable only from the debug hook. Phase 2
// adds FcmReceiveService.onMessageReceived, which calls this same method.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.vivekray898.upipaymentalert.ForegroundTtsService;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

public final class RemoteIngest {

    private static final String TAG = "UPIPaymentAlert";
    /** Reject a timestamp more than a day ahead, or more than a year old. */
    private static final long MAX_FUTURE_MS = 86400000L;
    private static final long MAX_AGE_MS = 365L * 86400000L;

    private RemoteIngest() { }

    /**
     * @return true when the message was accepted (spoken, or a pairing completed)
     */
    public static boolean handle(Context context, String envelopeJson) {
        try {
            if (context == null || envelopeJson == null) return false;

            JSONObject env = RemoteEnvelope.parse(envelopeJson);
            if (env == null) {
                Log.w(TAG, "remote: malformed envelope dropped");
                return false;
            }
            String kind = env.optString("k", "");
            String envPairId = env.optString("pairId", "");
            String eventId = env.optString("eventId", "");
            String nonceB64 = env.optString("n", "");
            String ctB64 = env.optString("ct", "");

            List<RemotePairingStore.Pairing> pairings = RemotePairingStore.loadAll(context);
            RemotePairingStore.Pairing p = null;
            if (pairings != null) {
                for (RemotePairingStore.Pairing candidate : pairings) {
                    if (candidate != null && envPairId.equals(candidate.pairId)) {
                        p = candidate;
                        break;
                    }
                }
            }
            if (p == null) {
                // Deliberately does not log the pairId value.
                Log.w(TAG, "remote: envelope for an unknown pairing dropped");
                return false;
            }

            byte[] key;
            if (RemoteEnvelope.KIND_PAIR.equals(kind)) {
                String epkB64 = env.optString("epk", "");
                if (!epkB64.isEmpty()) {
                    // ECIES header: the introduction was sealed to our static
                    // public key with the sender's ephemeral key. Recompute
                    // the pairing key from epk — no shared pairSecret needed,
                    // so the registry never held key material.
                    RemoteIdentity id = RemoteIdentity.load(context);
                    if (id == null) {
                        Log.w(TAG, "remote: no local identity, cannot open introduction");
                        return false;
                    }
                    PublicKey eph = RemoteCrypto.decodePublic(RemoteCrypto.unb64(epkB64));
                    if (!RemoteCrypto.isValidP256PublicKey(eph)) {
                        Log.w(TAG, "remote: introduction carried an invalid key, dropped");
                        return false;
                    }
                    key = RemoteCrypto.deriveKey(RemoteCrypto.ecdh(id.priv, eph),
                            RemoteCrypto.unb64(envPairId), "pairing");
                } else {
                    // Legacy long-blob flow: sealed with the shared pairSecret.
                    if (p.pendingSecretB64.isEmpty()) {
                        Log.w(TAG, "remote: unexpected introduction dropped");
                        return false;
                    }
                    key = RemoteCrypto.deriveKey(RemoteCrypto.unb64(p.pendingSecretB64),
                            RemoteCrypto.unb64(envPairId), "pairing");
                }
            } else if (RemoteEnvelope.KIND_PAY.equals(kind)) {
                if (!p.isComplete()) {
                    Log.w(TAG, "remote: payment before pairing completed, dropped");
                    return false;
                }
                key = RemoteCrypto.unb64(p.kMsgB64);
            } else {
                Log.w(TAG, "remote: unknown envelope kind dropped");
                return false;
            }

            byte[] plain;
            try {
                plain = RemoteCrypto.open(key, RemoteCrypto.unb64(nonceB64),
                        RemoteEnvelope.aad(kind, envPairId, eventId), RemoteCrypto.unb64(ctB64));
            } catch (Exception authFail) {
                // Forged, tampered, relabelled or sealed with another pairing's
                // key. This is the one place a hostile relay gets stopped.
                Log.w(TAG, "remote: authentication failed eventId=" + eventId);
                return false;
            }

            JSONObject inner = RemoteEnvelope.parseInner(new String(plain, StandardCharsets.UTF_8));
            if (inner == null) {
                Log.w(TAG, "remote: unparsable payload eventId=" + eventId);
                return false;
            }
            if (!eventId.equals(inner.optString("eventId", ""))) {
                Log.w(TAG, "remote: envelope/payload eventId mismatch, dropped eventId=" + eventId);
                return false;
            }

            if (RemoteEnvelope.KIND_PAIR.equals(kind)) {
                return completePairing(context, p, inner);
            }
            return announce(context, inner, eventId, envPairId);
        } catch (Exception e) {
            Log.w(TAG, "remote: ingest failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /**
     * The owner's introduction arrived: learn its public key, derive the session
     * key by ECDH, and store the completed pairing. The send token it carries is
     * only a relay credential - it cannot decrypt anything.
     */
    private static boolean completePairing(Context context, RemotePairingStore.Pairing p,
                                          JSONObject inner) {
        try {
            String ownerPubB64 = inner.optString("ownerPub", "");
            String sendToken = inner.optString("sendToken", "");
            PublicKey ownerPub = RemoteCrypto.decodePublic(RemoteCrypto.unb64(ownerPubB64));
            if (!RemoteCrypto.isValidP256PublicKey(ownerPub)) {
                Log.w(TAG, "remote: introduction carried an invalid key, dropped");
                return false;
            }
            RemoteIdentity id = RemoteIdentity.load(context);
            if (id == null) {
                Log.w(TAG, "remote: no local identity, cannot complete pairing");
                return false;
            }
            byte[] secret = RemoteCrypto.ecdh(id.priv, ownerPub);
            byte[] kMsg = RemoteCrypto.deriveKey(secret, RemoteCrypto.unb64(p.pairId), "message");

            // child never stores a delivery address for itself
            RemotePairingStore.Pairing done = new RemotePairingStore.Pairing(
                    p.pairId, RemotePairingStore.Role.CHILD, ownerPubB64,
                    RemoteCrypto.b64(kMsg), sendToken, "",
                    PairingCode.fingerprint(kMsg), "", p.pairSecretB64);
            List<RemotePairingStore.Pairing> current = RemotePairingStore.loadAll(context);
            if (current == null) current = new ArrayList<>();
            boolean replaced = false;
            List<RemotePairingStore.Pairing> updated = new ArrayList<>();
            for (RemotePairingStore.Pairing existing : current) {
                if (existing != null && existing.pairId.equals(p.pairId)) {
                    updated.add(done);
                    replaced = true;
                } else if (existing != null) {
                    updated.add(existing);
                }
            }
            if (!replaced) updated.add(done);
            boolean ok = RemotePairingStore.saveAll(context, updated);
            Log.d(TAG, "remote: pairing completed vcode=" + done.verificationCode);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "remote: pairing completion failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Validate, dedupe, speak. Returns true only when speech was dispatched. */
    private static boolean announce(Context context, JSONObject inner, String eventId, String pairId) {
        long amountPaise = inner.optLong("amountPaise", -1L);
        String currency = inner.optString("currency", "");
        long ts = inner.optLong("timestampMs", 0L);

        if (!"INR".equals(currency)) {
            Log.w(TAG, "remote: unsupported currency dropped eventId=" + eventId);
            return false;
        }
        long now = System.currentTimeMillis();
        if (ts > now + MAX_FUTURE_MS || (ts > 0 && ts < now - MAX_AGE_MS)) {
            Log.w(TAG, "remote: implausible timestamp dropped eventId=" + eventId);
            return false;
        }
        if (RemoteChildStore.seenBefore(context, pairId, eventId)) {
            Log.d(TAG, "remote: duplicate suppressed eventId=" + eventId);
            return false;
        }

        String phrase = RemotePhrase.phraseFor(context, amountPaise);
        try {
            // Same service, same action, same extras as the owner's local path:
            // one speech stack, sharing language/volume/speed/segmented speaking
            // and the existing 60-second phrase dedupe as a second layer.
            Intent svc = new Intent(context, ForegroundTtsService.class);
            svc.setAction(ForegroundTtsService.ACTION_SPEAK);
            svc.putExtra(ForegroundTtsService.EXTRA_TEXT, phrase);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "remote: could not dispatch speech eventId=" + eventId);
            return false;
        }
        // Persist child-side payment history (audit trail + UI) before speech.
        // A write failure is swallowed and must never block the TTS dispatch below.
        try {
            RemotePaymentHistoryStore.append(context,
                    RemotePaymentEvent.capture(amountPaise, "", phrase, "remote", "", ""));
        } catch (Exception ignored) {
        }

        // eventId only. Never the amount, never the phrase.
        Log.d(TAG, "remote: announced eventId=" + eventId);
        return true;
    }
}
