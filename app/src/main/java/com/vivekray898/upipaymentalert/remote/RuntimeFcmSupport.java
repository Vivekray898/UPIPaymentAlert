// ============================================================================
// Pairing-time FCM access.
//
// This helper is used by the child-side pairing flow when FCM is configured.
// The app now expects firebase-messaging on the classpath (see app/build.gradle),
// so this uses the direct FirebaseMessaging API instead of reflection.
//
// What this does NOT do:
//   - It does NOT initialize FirebaseApp. If the app adds
//     google-services.json, FirebaseApp initialization is handled by
//     FirebaseInitProvider (or the app’s own explicit init), not by this file.
// ============================================================================

package com.vivekray898.upipaymentalert.remote;

import com.google.firebase.messaging.FirebaseMessaging;

public final class RuntimeFcmSupport {

    private RuntimeFcmSupport() { }

    /**
     * Fetch the current FCM token from the SDK.
     *
     * This is only called when the app is built with firebase-messaging on the
     * classpath. In that configuration FirebaseMessaging.getInstance() is
     * available and getToken() returns a Task<String>.
     */
    public static String getFcmTokenSafely() {
        try {
            return FirebaseMessaging.getInstance().getToken().getResult();
        } catch (Exception e) {
            return null;
        }
    }
}
