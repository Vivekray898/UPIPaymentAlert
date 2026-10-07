// ============================================================================
// FCM token registration entry point — child token refresh for the remote layer.
//
// This file exists to keep the project buildable in the current no-FCM state.
// It holds the Supabase project URL and anon key used by the child token
// refresh endpoint (update_child_fcm_token) so MainActivity can wire them once
// at startup.
//
// What is in this file vs. what is deferred:
//   - Supabase URL / anon key config, and the static accessors used by
//     MainActivity.
//   - Actual FCM token refresh logic is deferred to a later phase, when
//     google-services.json and the firebase-messaging dependency are present.
//
// Buildability contract:
//   - This file does NOT import any Firebase SDK classes.
//   - It does NOT depend on google-services.json at compile time.
//   - It does NOT initialize FirebaseApp.
//
// Maintenance:
//   - If the project later adds real FCM token refresh, this file can gain that
//     logic, but it should keep the current static config fields because
//     MainActivity already references them.
// ============================================================================

package com.vivekray898.upipaymentalert.remote;

public final class FcmTokenRegistrar {

    private FcmTokenRegistrar() { }

    /**
     * Supabase project URL used for the token refresh endpoint.
     * Set from MainActivity when the app starts.
     */
    public static String supabaseUrl = null;

    /**
     * Supabase public anon key used to call the token refresh endpoint.
     * Set from MainActivity when the app starts.
     */
    public static String supabaseAnonKey = null;
}
