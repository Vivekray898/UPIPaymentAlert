// ============================================================================
// Remote Children Adapter — UPI Payment Alert (owner-side paired children list)
//
// One row per paired OWNER-child relationship currently stored on this device.
// The remove button delegates to RemotePairingStore.clearPairing(pairId).
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class RemoteChildrenAdapter extends RecyclerView.Adapter<RemoteChildrenAdapter.VH> {

    public interface Listener {
        void onRemove(Context context, String pairId);
    }

    private final List<RemotePairingStore.Pairing> pairs;
    private final Listener listener;

    public RemoteChildrenAdapter(List<RemotePairingStore.Pairing> pairs, Listener listener) {
        this.pairs = pairs == null ? java.util.Collections.emptyList() : pairs;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(com.example.upipaymentalert.R.layout.item_remote_child, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        RemotePairingStore.Pairing p = pairs.get(position);
        Context ctx = h.itemView.getContext();
        h.code.setText(ctx.getString(com.example.upipaymentalert.R.string.remote_child_row_format,
                p.role == RemotePairingStore.Role.OWNER ? "owner" : "child",
                p.verificationCode));
        h.fcm.setText(!p.childFcmTokenB64.isEmpty()
                ? "FCM token registered"
                : "no FCM token");
        h.remove.setOnClickListener(view -> {
            if (listener != null) {
                listener.onRemove(ctx, p.pairId);
            }
        });
    }

    @Override
    public int getItemCount() {
        return pairs.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView code;
        final TextView fcm;
        final ImageButton remove;

        VH(View v) {
            super(v);
            code = v.findViewById(com.example.upipaymentalert.R.id.remote_child_code_tv);
            fcm = v.findViewById(com.example.upipaymentalert.R.id.remote_child_fcm_tv);
            remove = v.findViewById(com.example.upipaymentalert.R.id.remote_child_remove_button);
        }
    }
}
