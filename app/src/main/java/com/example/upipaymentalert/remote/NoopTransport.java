// ============================================================================
// No-op Transport — UPI Payment Alert (remote announcement layer)
//
// Phase-1 transport. It deliberately does nothing: no socket, no DNS, no
// Firebase, no permission. The app therefore contains NO network code until the
// relay and google-services.json exist and phase 2 is approved.
//
// send() returns false, which the drainer reads as "not delivered" and keeps
// the envelope queued for a later attempt — so nothing is lost while the wire
// is unconfigured.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;

public final class NoopTransport implements RemoteTransport {

    public static final RemoteTransport INSTANCE = new NoopTransport();

    private NoopTransport() { }

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean isAvailable(Context context) {
        return false;
    }

    @Override
    public boolean send(Context context, String pairId, String sendTokenB64, String envelopeJson) {
        // Intentionally empty: no wire is configured yet.
        return false;
    }
}
