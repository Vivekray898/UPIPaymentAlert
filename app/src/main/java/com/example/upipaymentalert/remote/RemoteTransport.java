// ============================================================================
// Remote Transport — UPI Payment Alert (remote announcement layer)
//
// The seam that keeps the wire swappable. Phase 1 links NoopTransport, which
// opens no socket at all — which is why "remote layer off means zero network
// calls" is trivially true until phase 2.
//
// Phase 2 links RelayTransport (HTTPS POST to the stateless relay, which holds
// the FCM send credential). Nothing else in the layer changes.
//
// The owner never needs the child's network ADDRESS: it posts to the relay,
// authenticated with the send token, and the relay resolves the child's push
// token. That is what removes the stale-token failure mode.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;

public interface RemoteTransport {

    String name();

    boolean isAvailable(Context context);

    /**
     * Best-effort delivery of one already-encrypted envelope.
     *
     * @param pairId        routing id for the relay
     * @param sendTokenB64  owner-side bearer credential (not a decryption key)
     * @param envelopeJson  ciphertext envelope; the only payment-bearing field
     * @return true only when the transport is certain the relay accepted it
     */
    boolean send(Context context, String pairId, String sendTokenB64, String envelopeJson);

    /** The single place the active transport is chosen. */
    static RemoteTransport get(Context context) {
        return NoopTransport.INSTANCE;
    }
}
