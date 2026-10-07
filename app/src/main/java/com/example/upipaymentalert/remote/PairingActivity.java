// ============================================================================
// Pairing Activity — UPI Payment Alert (remote announcement layer)
//
// Both halves of the pairing ceremony (Q3, agreed with the user):
//
//   CHILD  "Pair as child device"  -> generates an identity + pair secret and
//          shows a pairing string for the owner to capture. It needs nothing
//          from the owner to produce it, and it never learns the owner's
//          address, because the owner is always the sender.
//
//   OWNER  paste that string      -> learns the child's public key, derives the
//          session key immediately, and enqueues an encrypted introduction that
//          carries the owner's public key back.
//
// DEVIATION FROM THE BRIEF, stated on screen rather than hidden: a 6-digit code
// cannot carry a public key, so the transferable artifact is a long string. The
// short numeric code is the VERIFICATION FINGERPRINT - both devices must show
// the same six digits, which is the human check against a man-in-the-middle.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.upipaymentalert.R;
import com.google.firebase.messaging.FirebaseMessaging;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public class PairingActivity extends AppCompatActivity {

    private static final String TAG = "UPIPaymentAlert";

    private TextView statusTv;
    private TextView codeTv;
    private EditText inputEt;

    // Live status refresh: pairing can complete in the BACKGROUND (the FCM
    // introduction arrives while this screen is visible), so onResume alone
    // would leave "Waiting for the owner device…" stale until the user
    // re-enters the screen. Tick every 2s while resumed; never while paused.
    private final android.os.Handler refreshHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshHandler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        statusTv = findViewById(R.id.pairing_status_tv);
        codeTv = findViewById(R.id.pairing_code_tv);
        inputEt = findViewById(R.id.pairing_input_et);

        Button asChild = findViewById(R.id.pairing_as_child_button);
        Button asOwner = findViewById(R.id.pairing_as_owner_button);
        Button copy = findViewById(R.id.pairing_copy_button);
        Button feed = findViewById(R.id.pairing_feed_button);
        Button unpair = findViewById(R.id.pairing_unpair_button);

        asChild.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAsChild();
            }
        });
        asOwner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAsOwner();
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCode();
            }
        });
        feed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processPasted();
            }
        });
        unpair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RemotePairingStore.clear(PairingActivity.this);
                RemoteChildStore.clearShared(PairingActivity.this);
                if (codeTv != null) codeTv.setText("");
                refreshStatus();
                toast(getString(R.string.remote_unpaired));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshHandler.post(refreshTick);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTick);
        super.onPause();
    }

    // ---- status ----------------------------------------------------------

    private void refreshStatus() {
        try {
            if (!RemoteKeys.isAvailable(this)) {
                statusTv.setText(getString(R.string.remote_unavailable));
                return;
            }
            RemotePairingStore.Pairing p = RemotePairingStore.load(this);
            if (p == null) {
                statusTv.setText(getString(R.string.remote_status_not_paired));
            } else if (!p.isComplete()) {
                statusTv.setText(getString(R.string.remote_status_pending));
            } else {
                statusTv.setText(getString(R.string.remote_status_paired,
                        p.role == RemotePairingStore.Role.OWNER ? "owner" : "child",
                        p.verificationCode));
            }
        } catch (Exception e) {
            statusTv.setText(getString(R.string.remote_status_not_paired));
        }
    }

    // ---- child side (6-digit code + Supabase) --------------------------

    private void startAsChild() {
        try {
            if (!RemoteKeys.isAvailable(this)) {
                toast(getString(R.string.remote_unavailable));
                return;
            }
            RemoteIdentity id = RemoteIdentity.loadOrCreate(this);
            if (id == null) {
                toast(getString(R.string.remote_unavailable));
                return;
            }

            // Generate a random 6-digit code
            String sixDigitCode = String.format(Locale.US, "%06d", new java.util.Random().nextInt(1000000));

            // Check if Supabase is configured; if not, fall back to long-blob flow
            if (FcmTransport.supabaseUrl == null || FcmTransport.supabaseUrl.isEmpty()) {
                // Fall back to legacy long-blob flow
                startAsChildLegacy(id, sixDigitCode);
                return;
            }

            // Fetch the FCM token asynchronously (Task.getResult() on an
            // incomplete task throws), then register OFF the main thread:
            // network on the UI thread throws NetworkOnMainThreadException.
            FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(fcmToken -> registerChildAsync(id, sixDigitCode, fcmToken))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "remote: fcm token fetch failed ("
                                + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
                        toast("Registration failed: could not get FCM token");
                    });
        } catch (Exception e) {
            Log.w(TAG, "remote: child start failed ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
            toast(getString(R.string.remote_code_invalid));
        }
    }

    /** Background half of the child flow: register, then show the code. */
    private void registerChildAsync(final RemoteIdentity id, final String sixDigitCode,
                                    final String fcmToken) {
        new Thread(() -> {
            try {
                final RemotePairingStore.LookupResult result = RemotePairingStore.registerPairing(
                        sixDigitCode, id.pubB64(), fcmToken);
                runOnUiThread(() -> {
                    if (!result.success) {
                        Log.w(TAG, "remote: child registration failed (" + result.error + ")");
                        toast("Registration failed: " + result.error);
                        return;
                    }

                    // Store pending pairing locally. pairId MUST be the SERVER's
                    // pair_id from registration: the owner derives its keys from that
                    // same string, and RemoteIngest drops any envelope whose pairId
                    // does not match the one stored here.
                    byte[] pairSecret = RemoteCrypto.randomBytes(RemoteCrypto.PAIR_SECRET_LEN);
                    RemotePairingStore.Pairing pending = new RemotePairingStore.Pairing(
                            result.pairId, RemotePairingStore.Role.CHILD,
                            "", "", "", fcmToken,
                            "", RemoteCrypto.b64(pairSecret), RemoteCrypto.b64(pairSecret));
                    RemotePairingStore.save(PairingActivity.this, pending);

                    // Show the 6-digit code and verification info. Rendered as
                    // HTML: a raw setText() would show literal <strong> tags —
                    // and COPY CODE copies whatever is displayed verbatim.
                    String codeHtml = getString(R.string.remote_pairing_code_title)
                            + "<br/><br/><b>" + sixDigitCode + "</b><br/><br>"
                            + getString(R.string.remote_pairing_code_instructions);
                    codeTv.setText(android.text.Html.fromHtml(
                            codeHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
                    codeTv.setTextIsSelectable(true);

                    toast(getString(R.string.remote_child_ready));
                    refreshStatus();
                });
            } catch (Exception e) {
                Log.w(TAG, "remote: child register failed ("
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
                runOnUiThread(() -> toast(getString(R.string.remote_code_invalid)));
            }
        }).start();
    }

    /** Legacy long-blob flow (when Supabase is not configured) */
    private void startAsChildLegacy(RemoteIdentity id, String sixDigitCode) {
        try {
            byte[] pairSecret = RemoteCrypto.randomBytes(RemoteCrypto.PAIR_SECRET_LEN);
            byte[] pairId = RemoteCrypto.randomBytes(16);
            String code = PairingCode.encode(id.pub, pairSecret, pairId);
            if (code == null) {
                toast(getString(R.string.remote_code_invalid));
                return;
            }
            RemotePairingStore.Pairing pending = new RemotePairingStore.Pairing(
                    RemoteCrypto.b64(pairId), RemotePairingStore.Role.CHILD,
                    "", "", "", "",
                    "", RemoteCrypto.b64(pairSecret), RemoteCrypto.b64(pairSecret));
            RemotePairingStore.save(this, pending);

            codeTv.setText(PairingCode.group(code));
            toast(getString(R.string.remote_child_ready));
            refreshStatus();
        } catch (Exception e) {
            toast(getString(R.string.remote_code_invalid));
        }
    }

    private void copyCode() {
        try {
            CharSequence text = codeTv.getText();
            String shown = text == null ? "" : String.valueOf(text);
            // Copy ONLY the 6 digits. The display may carry labels/markup, and
            // pasting anything but the digits makes the owner's paste invalid.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\d{6}").matcher(shown);
            String code = m.find() ? m.group(0) : "";
            if (code.isEmpty()) {
                toast(getString(R.string.remote_code_invalid));
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("pairing", code));
            }
            toast(getString(R.string.remote_copied));
        } catch (Exception e) {
            toast(getString(R.string.remote_code_invalid));
        }
    }

    // ---- owner side (6-digit code via Supabase) ------------------------

    private void startAsOwner() {
        try {
            if (!RemoteKeys.isAvailable(this)) {
                toast(getString(R.string.remote_unavailable));
                return;
            }
            String code = inputEt.getText() == null ? "" : inputEt.getText().toString().trim();

            // Check if Supabase is configured; if not, fall back to long-blob flow
            if (FcmTransport.supabaseUrl == null || FcmTransport.supabaseUrl.isEmpty()) {
                // Legacy long-blob flow
                startAsOwnerLegacy(code);
                return;
            }

            // 6-digit code flow via Supabase
            if (!code.matches("^\\d{6}$")) {
                toast(getString(R.string.remote_code_invalid));
                return;
            }

            RemoteIdentity id = RemoteIdentity.loadOrCreate(this);
            if (id == null) {
                toast(getString(R.string.remote_unavailable));
                return;
            }

            // The lookup (network) AND the rest of the flow run OFF the main
            // thread: network on the UI thread throws
            // NetworkOnMainThreadException. Everything resumes on the UI
            // thread via runOnUiThread.
            new Thread(() -> {
                final RemotePairingStore.LookupResult result =
                        RemotePairingStore.lookupAndConsumePairing(code);
                runOnUiThread(() -> finishOwnerPairing(id, result));
            }).start();
        } catch (Exception e) {
            Log.w(TAG, "remote: owner start failed ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
            toast(getString(R.string.remote_code_invalid));
        }
    }

    /**
     * Second half of the owner flow, back on the UI thread after the code was
     * consumed off-thread. Derives the session key, seals the ECIES
     * introduction and queues it for the drainer.
     */
    private void finishOwnerPairing(RemoteIdentity id, RemotePairingStore.LookupResult result) {
        try {
            if (!result.success) {
                Log.w(TAG, "remote: owner lookup failed (" + result.error + ")");
                toast("Lookup failed: " + result.error);
                return;
            }

            byte[] childPub = RemoteCrypto.unb64(result.childPubB64);
            java.security.PublicKey childPubObj = RemoteCrypto.decodePublic(childPub);
            byte[] pairIdBytes = RemoteCrypto.unb64(result.pairId);
            byte[] kMsg = RemoteCrypto.deriveKey(
                    RemoteCrypto.ecdh(id.priv, childPubObj),
                    pairIdBytes, "message");

            // ECIES introduction: sealed to the child's STATIC public key with
            // a throwaway ephemeral key. The shared pairSecret never leaves the
            // child (the registry holds no key material) — the child reopens
            // this with the epk header + its own private key.
            java.security.KeyPair eph = RemoteCrypto.generateKeyPair();
            byte[] kPair = RemoteCrypto.deriveKey(
                    RemoteCrypto.ecdh(eph.getPrivate(), childPubObj),
                    pairIdBytes, "pairing");

            // The send token authenticates this device to the relay
            byte[] sendToken = RemoteCrypto.randomBytes(32);
            String sendTokenB64 = RemoteCrypto.b64(sendToken);

            String pairId = result.pairId;

            String introEventId = UUID.randomUUID().toString();
            String inner = RemoteEnvelope.pairPayload(id.pubB64(), sendTokenB64, introEventId);
            byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
            byte[] ct = RemoteCrypto.seal(kPair, nonce,
                    RemoteEnvelope.aad(RemoteEnvelope.KIND_PAIR, pairId, introEventId),
                    inner.getBytes(StandardCharsets.UTF_8));
            String intro = RemoteEnvelope.build(RemoteEnvelope.KIND_PAIR, pairId,
                    introEventId, RemoteCrypto.b64(nonce), RemoteCrypto.b64(ct),
                    RemoteCrypto.b64(RemoteCrypto.encodePublic(eph.getPublic())));

            RemotePairingStore.Pairing done = new RemotePairingStore.Pairing(
                    pairId, RemotePairingStore.Role.OWNER,
                    result.childPubB64, RemoteCrypto.b64(kMsg), sendTokenB64,
                    result.childFcmToken,
                    PairingCode.fingerprint(kMsg), "", "");
            if (!RemotePairingStore.save(this, done)) {
                toast(getString(R.string.remote_unavailable));
                return;
            }
            RemoteOutbox.append(this, pairId, intro);
            RemoteDrainer.drainAsync(this, RemoteTransport.get(this));
            inputEt.setText("");
            toast(getString(R.string.remote_owner_done));
            refreshStatus();
        } catch (Exception e) {
            Log.w(TAG, "remote: owner finish failed ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
            toast(getString(R.string.remote_code_invalid));
        }
    }

    /** Legacy long-blob owner flow (when Supabase is not configured) */
    private void startAsOwnerLegacy(String pasted) {
        try {
            PairingCode.Blob blob = PairingCode.decode(pasted);
            if (blob == null) {
                toast(getString(R.string.remote_code_invalid));
                return;
            }
            RemoteIdentity id = RemoteIdentity.loadOrCreate(this);
            if (id == null) {
                toast(getString(R.string.remote_unavailable));
                return;
            }
            String pairId = RemoteCrypto.b64(blob.pairId);
            byte[] pairIdBytes = blob.pairId;

            byte[] kMsg = RemoteCrypto.deriveKey(
                    RemoteCrypto.ecdh(id.priv, RemoteCrypto.decodePublic(blob.pub)),
                    pairIdBytes, "message");
            byte[] kPair = RemoteCrypto.deriveKey(blob.pairSecret, pairIdBytes, "pairing");

            byte[] sendToken = RemoteCrypto.randomBytes(32);
            String sendTokenB64 = RemoteCrypto.b64(sendToken);

            String introEventId = UUID.randomUUID().toString();
            String inner = RemoteEnvelope.pairPayload(id.pubB64(), sendTokenB64, introEventId);
            byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
            byte[] ct = RemoteCrypto.seal(kPair, nonce,
                    RemoteEnvelope.aad(RemoteEnvelope.KIND_PAIR, pairId, introEventId),
                    inner.getBytes(StandardCharsets.UTF_8));
            String envelope = RemoteEnvelope.build(RemoteEnvelope.KIND_PAIR, pairId,
                    introEventId, RemoteCrypto.b64(nonce), RemoteCrypto.b64(ct));

            RemotePairingStore.Pairing done = new RemotePairingStore.Pairing(
                    pairId, RemotePairingStore.Role.OWNER,
                    RemoteCrypto.b64(blob.pub), RemoteCrypto.b64(kMsg), sendTokenB64,
                    "",
                    PairingCode.fingerprint(kMsg), "", RemoteCrypto.b64(blob.pairSecret));
            if (!RemotePairingStore.save(this, done)) {
                toast(getString(R.string.remote_unavailable));
                return;
            }
            RemoteOutbox.append(this, pairId, envelope);
            RemoteDrainer.drainAsync(this, RemoteTransport.get(this));
            inputEt.setText("");
            toast(getString(R.string.remote_owner_done));
            refreshStatus();
        } catch (Exception e) {
            toast(getString(R.string.remote_code_invalid));
        }
    }

    // ---- phase-1 manual transport ---------------------------------------

    /**
     * Phase 1 has no wire (NoopTransport), so this feeds a received envelope
     * straight into the real child pipeline. It is a genuine TRANSPORT input,
     * not a bypass: anything that does not authenticate with this device's
     * pairing key is discarded exactly as it would be from FCM. Phase 2 replaces
     * this with FcmReceiveService, which calls the very same RemoteIngest.handle.
     */
    private void processPasted() {
        try {
            String pasted = inputEt.getText() == null ? "" : inputEt.getText().toString().trim();
            if (pasted.isEmpty()) {
                // Distinct message: an empty box is not an invalid code.
                toast(getString(R.string.remote_feed_empty));
                return;
            }
            boolean ok = RemoteIngest.handle(getApplicationContext(), pasted);
            toast(ok ? getString(R.string.remote_feed_ok) : getString(R.string.remote_feed_rejected));
            inputEt.setText("");
            refreshStatus();
        } catch (Exception e) {
            toast(getString(R.string.remote_feed_rejected));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** Kept for symmetry with MainActivity; avoids an unused-import warning. */
    static Intent intentFor(Context context) {
        return new Intent(context, PairingActivity.class);
    }
}
