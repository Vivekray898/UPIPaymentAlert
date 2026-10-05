// ============================================================================
// Payment History Activity — UPI Payment Alert
// Read-only browser for the durable payment history written by
// PaymentHistoryStore. Filtering is deliberately deferred to v2 (Q3).
// ============================================================================
package com.example.upipaymentalert;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class HistoryActivity extends AppCompatActivity {

    private PaymentHistoryAdapter adapter;
    private TextView emptyTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        emptyTv = findViewById(R.id.history_empty_tv);

        RecyclerView rv = findViewById(R.id.history_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PaymentHistoryAdapter();
        rv.setAdapter(adapter);

        Button clearBtn = findViewById(R.id.history_clear_button);
        clearBtn.setOnClickListener(v -> confirmClear());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<PaymentEvent> list = PaymentHistoryStore.readAll(this);
        adapter.submit(list);
        if (emptyTv != null) {
            emptyTv.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.payment_history_clear_confirm_title)
                .setMessage(R.string.payment_history_clear_confirm_msg)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    PaymentHistoryStore.clear(this);
                    refresh();
                    Toast.makeText(this, R.string.payment_history_cleared,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.payment_history_cancelled, null)
                .show();
    }
}