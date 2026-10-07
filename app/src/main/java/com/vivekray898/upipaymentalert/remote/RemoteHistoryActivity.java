// ============================================================================
// Remote history activity — child-side payment history screen shell.
//
// This file exists to keep the current commit buildable. The real child-side
// payment history UI is built around this activity and RemotePaymentHistoryAdapter
// later in the remote feature branch.
//
// What is in this file:
//   - An AppCompatActivity shell that the manifest and MainActivity can reference
//     without a missing-symbol failure.
//
// What is deferred:
//   - The actual history list, clear flow, and layout binding are added later.
//
// Buildability contract:
//   - This file does not depend on Firebase, Supabase, or any network class.
//   - It is a local UI shell used by the child-side history feature.
// ============================================================================

package com.vivekray898.upipaymentalert.remote;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class RemoteHistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Layout binding is added later with the child-side history UI.
    }
}
