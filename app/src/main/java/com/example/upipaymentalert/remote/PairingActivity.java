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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.upipaymentalert.R;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class PairingActivity extends AppCompatActivity {

    private TextView statusTv;
    private TextView codeTv;
    private EditText inputEt;

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
                RemoteChildStore.clear(PairingActivity.this);
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

    // ---- child side ------------------------------------------------------

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
            byte[] pairSecret = RemoteCrypto.randomBytes(RemoteCrypto.PAIR_SECRET_LEN);
            byte[] pairId = RemoteCrypto.randomBytes(16);
            String code = PairingCode.encode(id.pub, pairSecret, pairId);
            if (code == null) {
                toast(getString(R.string.remote_code_invalid));
                return;
            }
            // Persist the pending pairing BEFORE showing the code: the child
            // needs its own secret to open the owner's introduction later.
            RemotePairingStore.Pairing pending = new RemotePairingStore.Pairing(
                    RemoteCrypto.b64(pairId), RemotePairingStore.Role.CHILD,
                    "", "", "", "",
                    RemoteCrypto.b64(pairSecret), RemoteCrypto.b64(pairSecret));
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
            if (text == null || text.length() == 0) {
                toast(getString(R.string.remote_code_invalid));
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                // Copy without the display spaces so the paste is unambiguous.
                cm.setPrimaryClip(ClipData.newPlainText("pairing",
                        String.valueOf(text).replace(" ", "")));
            }
            toast(getString(R.string.remote_copied));
        } catch (Exception e) {
            toast(getString(R.string.remote_code_invalid));
        }
    }

    // ---- owner side ------------------------------------------------------

    private void startAsOwner() {
        try {
            if (!RemoteKeys.isAvailable(this)) {
                toast(getString(R.string.remote_unavailable));
                return;
            }
            String pasted = inputEt.getText() == null ? "" : inputEt.getText().toString();
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

            // K_msg for payments; K_pair only to protect the one-shot introduction.
            byte[] kMsg = RemoteCrypto.deriveKey(
                    RemoteCrypto.ecdh(id.priv, RemoteCrypto.decodePublic(blob.pub)),
                    pairIdBytes, "message");
            byte[] kPair = RemoteCrypto.deriveKey(blob.pairSecret, pairIdBytes, "pairing");

            // The send token authenticates this device to the relay. It is NOT
            // derived from the session key, so the relay still cannot decrypt.
            byte[] sendToken = RemoteCrypto.randomBytes(32);
            String sendTokenB64 = RemoteCrypto.b64(sendToken);

            String inner = RemoteEnvelope.pairPayload(id.pubB64(), sendTokenB64);
            String introEventId = UUID.randomUUID().toString();
            byte[] nonce = RemoteCrypto.randomBytes(RemoteCrypto.NONCE_LEN);
            byte[] ct = RemoteCrypto.seal(kPair, nonce,
                    RemoteEnvelope.aad(RemoteEnvelope.KIND_PAIR, pairId, introEventId),
                    inner.getBytes(StandardCharsets.UTF_8));
            String envelope = RemoteEnvelope.build(RemoteEnvelope.KIND_PAIR, pairId,
                    introEventId, RemoteCrypto.b64(nonce), RemoteCrypto.b64(ct));

            RemotePairingStore.Pairing done = new RemotePairingStore.Pairing(
                    pairId, RemotePairingStore.Role.OWNER,
                    RemoteCrypto.b64(blob.pub), RemoteCrypto.b64(kMsg), sendTokenB64,
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
                toast(getString(R.string.remote_code_invalid));
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
