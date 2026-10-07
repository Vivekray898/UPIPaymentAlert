// ============================================================================
// FCM transport entry points — relay / Supabase config for the remote layer.
//
// This file exists to keep the project buildable in the current no-FCM state.
// It is the single place where the app stores the Supabase project URL and anon
// key that the remote flow uses for pairing and token refresh.
//
// What is in this file vs. what is deferred:
//   - Supabase URL / anon key config, and the static accessors used by
//     PairingActivity, RemotePairingStore, MainActivity, and RemoteTransport.
//   - FCM token fetching is handled by RuntimeFcmSupport so the no-FCM build
//     can fail clearly instead of missing a compile-time class.
//   - Actual FCM send (FCM HTTP v1) and the child-side receive service are
//     intentionally NOT in this file yet. Those are phase-2 additions that
//     require google-services.json and a working Firebase project.
//
// Buildability contract:
//   - This file does NOT import any Firebase SDK classes.
//   - It does NOT depend on google-services.json at compile time.
//   - It does NOT initialize FirebaseApp.
//
// Maintenance:
//   - If the project later adds google-services.json and the firebase-messaging
//     Gradle dep, this file can be expanded with the real FCM send path, but it
//     should still keep the current static config fields because the rest of the
//     remote layer already references them.
// ============================================================================

package com.vivekray898.upipaymentalert.remote;

import android.content.Context;

public final class FcmTransport {

    private FcmTransport() { }

    /**
     * Supabase project URL used for the relay endpoints.
     * Set from MainActivity when the app starts.
     */
    public static String supabaseUrl = null;

    /**
     * Supabase public anon key used to call the relay endpoints.
     * Set from MainActivity when the app starts.
     */
    public static String supabaseAnonKey = null;

    /**
     * The FCM transport instance used when Supabase is configured.
     * The real implementation is added later, when the FCM send path is wired.
     * For now, this is a buildable no-op placeholder that satisfies the
     * RemoteTransport.get() return type without a missing-symbol failure.
     */
    public static final RemoteTransport INSTANCE = new FcmTransport.NoopFcmTransport();

    /**
     * Phase-1 FCM transport placeholder. It deliberately does nothing and
     * returns false, so the drainer keeps the envelope queued until the real
     * FCM send path is wired.
     */
    private static final class NoopFcmTransport implements RemoteTransport {
        @Override
        public String name() {
            return "fcm(placeholder)";
        }

        @Override
        public boolean isAvailable(Context context) {
            return false;
        }

        @Override
        public boolean send(Context context, String pairId, String sendTokenB64,
                            String envelopeJson) {
            return false;
        }
    }
}
