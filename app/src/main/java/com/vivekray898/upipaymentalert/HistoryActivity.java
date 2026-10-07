// ============================================================================
// Payment History Activity — UPI Payment Alert
// Browser for the durable payment history written by PaymentHistoryStore,
// grouped by day with Today / This week / This month totals (Google Pay style).
//
// THREADING: the file read + JSON parse + stats aggregation happen on a single
// background thread. With the cap at 2000 entries this is no longer cheap
// enough to do on the main thread (invariant 6). Results are posted back to the
// UI thread, and a generation counter discards results from a superseded load
// so a slow read cannot overwrite a newer one after fast-forwarding midnight.
//
// Filtering is deliberately deferred to v2 (Q3). Totals are always recomputed
// from the event list on read — nothing is stored — so day/week/month buckets
// roll over at local midnight with no alarm, boot receiver or background work.
// ============================================================================
package com.vivekray898.upipaymentalert;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class HistoryActivity extends AppCompatActivity {

    private PaymentHistoryAdapter adapter;
    private TextView emptyTv;
    private TextView todayTv;
    private TextView weekTv;
    private TextView monthTv;
    private TextView unknownTv;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private final AtomicInteger generation = new AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        emptyTv = findViewById(R.id.history_empty_tv);
        todayTv = findViewById(R.id.history_stat_today_value);
        weekTv = findViewById(R.id.history_stat_week_value);
        monthTv = findViewById(R.id.history_stat_month_value);
        unknownTv = findViewById(R.id.history_stat_unknown_tv);

        RecyclerView rv = findViewById(R.id.history_recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PaymentHistoryAdapter();
        rv.setAdapter(adapter);

        Button clearBtn = findViewById(R.id.history_clear_button);
        clearBtn.setOnClickListener(v -> confirmClear());

        io = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A pending read is allowed to finish; its callback is dropped by the
        // activity-is-destroyed check, and the executor thread is not a leak
        // because it is shut down here.
        if (io != null) io.shutdownNow();
    }

    private void refresh() {
        if (io == null) return;
        final int gen = generation.incrementAndGet();
        final long now = System.currentTimeMillis();
        final TimeZone tz = TimeZone.getDefault();

        try {
            io.execute(() -> {
                // readAll is already fully guarded: it never throws.
                List<PaymentEvent> list = PaymentHistoryStore.readAll(getApplicationContext());
                PaymentStats.Result stats =
                        PaymentStats.compute(list, now, tz);

                ui.post(() -> {
                    // Drop a result that a newer refresh has already superseded.
                    if (gen != generation.get()) return;
                    if (isFinishing() || isDestroyed()) return;

                    adapter.submit(list, now, this);
                    bindStats(stats);

                    if (emptyTv != null) {
                        emptyTv.setVisibility(
                                (list == null || list.isEmpty()) ? View.VISIBLE : View.GONE);
                    }
                });
            });
        } catch (Exception e) {
            // Never let a history failure crash the screen.
            android.util.Log.e("UPIPaymentAlert",
                    "HistoryActivity.refresh failed (swallowed): " + e.getMessage());
        }
    }

    private void bindStats(PaymentStats.Result s) {
        todayTv.setText(formatPaise(s.todayTotalPaise));
        weekTv.setText(formatPaise(s.weekTotalPaise));
        monthTv.setText(formatPaise(s.monthTotalPaise));

        // Show the note only for the widest window that actually has unknowns,
        // so the header stays honest without adding clutter.
        int unknown = Math.max(s.todayUnknownCount,
                Math.max(s.weekUnknownCount, s.monthUnknownCount));
        if (unknown > 0) {
            unknownTv.setText(getString(R.string.payment_history_stat_unknown, unknown));
            unknownTv.setVisibility(View.VISIBLE);
        } else {
            unknownTv.setVisibility(View.GONE);
        }
    }

    /** Paise -> "₹1,250.50" (Indian digit grouping, matching the row format). */
    static String formatPaise(long paise) {
        long rupees = paise / 100;
        long frac = paise % 100;
        String grouped = groupIndian(rupees);
        return frac == 0 ? "₹" + grouped : "₹" + grouped + "." + (frac < 10 ? "0" + frac : frac);
    }

    /** 1250 -> "1,250"; 1234567 -> "12,34,567" (last-three grouping). */
    private static String groupIndian(long n) {
        String s = Long.toString(n);
        if (s.length() <= 3) return s;
        String head = s.substring(0, s.length() - 3);
        String tail = s.substring(s.length() - 3);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = head.length() - 1; i >= 0; i--) {
            out.append(head.charAt(i));
            if (++count % 2 == 0 && i != 0) out.append(',');
        }
        return out.reverse() + "," + tail;
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.payment_history_clear_confirm_title)
                .setMessage(R.string.payment_history_clear_confirm_msg)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // Clearing also clears the totals for the current windows,
                    // because the totals are derived from the event list.
                    PaymentHistoryStore.clear(getApplicationContext());
                    refresh();
                    Toast.makeText(this, R.string.payment_history_cleared,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.payment_history_cancelled, null)
                .show();
    }
}