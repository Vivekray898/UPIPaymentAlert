// ============================================================================
// Remote Drainer — UPI Payment Alert (remote announcement layer)
//
// Best-effort delivery of queued envelopes on a single background thread.
//
// NO WorkManager, NO JobScheduler, NO AlarmManager (the brief forbids
// auto-start machinery). Consequence, stated rather than hidden: a queued event
// is delivered on the next trigger — the next payment, or the app being opened.
// Durability is guaranteed by the outbox; promptness is not.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteDrainer {

    private static final String TAG = "UPIPaymentAlert";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** After this many failed attempts an envelope is dropped (and logged). */
    public static final int MAX_ATTEMPTS = 5;

    private RemoteDrainer() { }

    static long backoffMs(int attempts) {
        long ms = 2000L * (1L << Math.min(Math.max(attempts, 0), 7));
        return Math.min(ms, 300000L);
    }

    /** Fire-and-forget drain. Never throws, never blocks the caller. */
    public static void drainAsync(final Context context, final RemoteTransport transport) {
        if (context == null || transport == null) return;
        if (!RUNNING.compareAndSet(false, true)) return; // one drain at a time
        final Context app = context.getApplicationContext();
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        drainNow(app, transport);
                    } finally {
                        RUNNING.set(false);
                    }
                }
            }, "remote-drain").start();
        } catch (Exception e) {
            RUNNING.set(false);
            Log.w(TAG, "remote: drain could not start (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * One synchronous drain pass. Envelopes that are not due are left alone;
     * failures are re-queued with an incremented attempt count and a backoff.
     *
     * @return number of envelopes accepted by the transport
     */
    public static int drainNow(Context context, RemoteTransport transport) {
        int sent = 0;
        try {
            if (context == null || transport == null) return 0;
            List<RemoteOutbox.Entry> entries = RemoteOutbox.readAll(context);
            if (entries.isEmpty()) return 0;

            long now = System.currentTimeMillis();
            List<RemoteOutbox.Entry> keep = new ArrayList<>();
            boolean changed = false;
            boolean available = false;
            try {
                available = transport.isAvailable(context);
            } catch (Exception ignored) {
            }

            for (RemoteOutbox.Entry e : entries) {
                if (e.dueMs > now) {
                    keep.add(e); // not due yet
                    continue;
                }
                if (!available) {
                    // No wire configured: leave the queue untouched so a later
                    // attempt (after phase 2) can still deliver it.
                    keep.add(e);
                    continue;
                }
                boolean ok = false;
                try {
                    ok = transport.send(context, e.pairId, sendToken(context), e.envelopeJson);
                } catch (Exception ex) {
                    ok = false;
                }
                if (ok) {
                    sent++;
                    changed = true;
                    continue;
                }
                int attempts = e.attempts + 1;
                if (attempts > MAX_ATTEMPTS) {
                    changed = true;
                    Log.w(TAG, "remote: dropped after " + MAX_ATTEMPTS + " attempts eventId="
                            + RemoteEnvelope.eventIdOf(e.envelopeJson));
                } else {
                    changed = true;
                    keep.add(new RemoteOutbox.Entry(e.pairId, e.envelopeJson, attempts,
                            now + backoffMs(attempts)));
                }
            }
            if (changed) RemoteOutbox.rewrite(context, keep);
        } catch (Exception e) {
            Log.w(TAG, "remote: drain failed (" + e.getClass().getSimpleName() + ")");
        }
        return sent;
    }

    private static String sendToken(Context context) {
        RemotePairingStore.Pairing p = RemotePairingStore.load(context);
        return p == null ? "" : p.sendTokenB64;
    }
}
