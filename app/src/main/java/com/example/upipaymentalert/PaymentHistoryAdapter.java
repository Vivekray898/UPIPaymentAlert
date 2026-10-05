// ============================================================================
// Payment History Adapter — UPI Payment Alert
// Renders one PaymentEvent per row. Display only; touches no storage.
// ============================================================================
package com.example.upipaymentalert;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class PaymentHistoryAdapter extends RecyclerView.Adapter<PaymentHistoryAdapter.VH> {

    private final List<PaymentEvent> items = new ArrayList<>();
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US);

    static class VH extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView amount;
        final TextView source;
        final TextView body;

        VH(View v) {
            super(v);
            time = v.findViewById(R.id.history_item_time);
            amount = v.findViewById(R.id.history_item_amount);
            source = v.findViewById(R.id.history_item_source);
            body = v.findViewById(R.id.history_item_body);
        }
    }

    /** Replace the backing list (already newest-first from the store). */
    public void submit(List<PaymentEvent> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_payment_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PaymentEvent e = items.get(position);
        if (e == null) return;

        String timeText = e.getTimestampMs() > 0
                ? fmt.format(new Date(e.getTimestampMs()))
                : "";
        h.time.setText(timeText);

        String amountText;
        if (e.getAmountPaise() >= 0) {
            long rupees = e.getAmountPaise() / 100;
            long paise = e.getAmountPaise() % 100;
            amountText = paise == 0
                    ? "₹ " + rupees
                    : "₹ " + rupees + "." + (paise < 10 ? "0" + paise : paise);
        } else {
            amountText = h.itemView.getContext()
                    .getString(R.string.payment_history_amount_unknown);
        }
        h.amount.setText(amountText);

        String srcLabel = (e.getSource() == PaymentEvent.Source.NOTIFICATION)
                ? "App" : "SMS";
        h.source.setText(srcLabel + " · " + e.getSourceId());

        h.body.setText(e.getRawBody());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}