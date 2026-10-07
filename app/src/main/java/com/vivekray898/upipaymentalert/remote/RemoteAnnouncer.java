// ============================================================================
// Remote Announcer — UPI Payment Alert (remote announcement layer)
//
// THE ONLY entry point the payment listeners call. Its contract:
//
//   * it is invoked AFTER the local TTS dispatch, never before it
//   * it NEVER throws (invariant 9) - every failure is swallowed and logged with
//     at most an eventId
//   * it NEVER blocks on the network: it encrypts (~120 bytes, microseconds),
//     appends one line to the outbox, and hands delivery to a background thread
//   * it does NOTHING AT ALL when the user has not enabled and paired, which is
//     what makes "remote off = zero network" true
//
// Nothing here logs an amount, a payer, a UPI id or a body. Only eventIds.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.vivekray898.upipaymentalert.PaymentEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RemoteAnnouncer {

    private static final String TAG = "UPIPaymentAlert";
    private static final String PREFS = "UPI_PREFS";
    /** Q10: hard OFF until the user enables it AND pairs. */
    public static final String KEY_ENABLED = "remote_enabled";
    /** Every supported parser pattern is INR-denominated. */
    private static final String CURRENCY = "INR";

    private RemoteAnnouncer() { }

    public static boolean isEnabled(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getBoolean(KEY_ENABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, enabled).apply();
        } catch (Exception e) {
            Log.w(TAG, "remote: could not persist enabled flag");
        }
    }

    /**
     * Hand one accepted payment to the remote layer. Called from
     * SmsListener/NotificationListener AFTER speech has been dispatched.
     */
    public static void onPaymentCaptured(Context context, PaymentEvent event) {
        if (context == null || event == null) return;
        try {
            if (!isEnabled(context)) return;

            List<RemotePairingStore.Pairing> pairings = RemotePairingStore.loadAll(context);
            // Only complete OWNER pairings send. A child device never relays.
            if (pairings == null || pairings.isEmpty()) return;
            List<RemotePairingStore.Pairing> owners = new ArrayList<>();
            for (RemotePairingStore.Pairing p : pairings) {
                if (p != null && p.isComplete() && p.role == RemotePairingStore.Role.OWNER) {
                    owners.add(p);
                }
            }
            if (owners.isEmpty()) return;

            String payload = RemoteEnvelope.paymentPayload(event, CURRENCY);
            for (RemotePairingStore.Pairing child : owners) {
                byte[] kMsg = RemoteCrypto.unb64(child.kMsgB64);
                if (kMsg == null || kMsg.length != RemoteCrypto.KEY_LEN) continue;

                byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
                byte[] ct = RemoteCrypto.seal(kMsg, nonce,
                        RemoteEnvelope.aad(RemoteEnvelope.KIND_PAY, child.pairId, event.getEventId()),
                        payload.getBytes(StandardCharsets.UTF_8));

                String envelope = RemoteEnvelope.build(RemoteEnvelope.KIND_PAY, child.pairId,
                        event.getEventId(), RemoteCrypto.b64(nonce), RemoteCrypto.b64(ct));

                if (envelope.length() > RemoteEnvelope.MAX_CHARS) {
                    Log.w(TAG, "remote: envelope over size cap, dropped eventId=" + event.getEventId());
                    continue;
                }
                RemoteOutbox.append(context, child.pairId, envelope);
            }
            RemoteDrainer.drainAsync(context, RemoteTransport.get(context));
        } catch (Exception e) {
            // Invariant 9: a remote failure must be invisible to the listener.
            Log.w(TAG, "remote: announce failed (" + e.getClass().getSimpleName()
                    + ") eventId=" + event.getEventId());
        }
    }
}
