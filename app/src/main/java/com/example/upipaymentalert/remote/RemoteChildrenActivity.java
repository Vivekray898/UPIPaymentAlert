// ============================================================================
// Remote Children Activity — UPI Payment Alert (owner-side child management)
//
// Shows every paired child currently stored on this OWNER device and lets the
// owner remove one pairing at a time (targeted unpair), without disturbing other
// paired children.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class RemoteChildrenActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.example.upipaymentalert.R.layout.activity_remote_children);

        TextView emptyTv = findViewById(com.example.upipaymentalert.R.id.remote_children_empty_tv);
        RecyclerView rv = findViewById(com.example.upipaymentalert.R.id.remote_children_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));

        List<RemotePairingStore.Pairing> pairs = RemotePairingStore.loadAll(this);
        boolean hasAny = pairs != null && !pairs.isEmpty();

        RemoteChildrenAdapter adapter = new RemoteChildrenAdapter(pairs, (context, pairId) -> {
            new AlertDialog.Builder(context)
                    .setTitle(com.example.upipaymentalert.R.string.remote_child_remove_confirm_title)
                    .setMessage(com.example.upipaymentalert.R.string.remote_child_remove_confirm_msg)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        boolean removed = RemotePairingStore.clearPairing(context, pairId);
                        if (removed) {
                            Toast.makeText(context,
                                    com.example.upipaymentalert.R.string.remote_child_removed,
                                    Toast.LENGTH_SHORT).show();
                            recreate();
                        } else {
                            Toast.makeText(context,
                                    com.example.upipaymentalert.R.string.remote_unavailable,
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(com.example.upipaymentalert.R.string.remote_child_removed, null)
                    .show();
        });
        rv.setAdapter(adapter);

        if (emptyTv != null) {
            emptyTv.setVisibility(hasAny ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (rv != null) {
            rv.setVisibility(hasAny ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }
}
