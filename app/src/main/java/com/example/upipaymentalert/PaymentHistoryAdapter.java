// ============================================================================
// Payment History Adapter — UPI Payment Alert
// Renders a newest-first payment list grouped by local calendar day, Google Pay
// style: a "TODAY" / "YESTERDAY" / "12 Oct" header row followed by that day's
// payments. Display only; this class touches no storage and computes nothing
// that is persisted — grouping is derived from each event's timestampMs on
// every bind cycle, so day boundaries always reflect the current clock.
// ============================================================================
package com.example.upipaymentalert;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class PaymentHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** One rendered row: either a day header or a payment. */
    private static final class Row {
        final boolean isHeader;
        final String headerText;
        final PaymentEvent event;

        private Row(boolean isHeader, String headerText, PaymentEvent event) {
            this.isHeader = isHeader;
            this.headerText = headerText;
            this.event = event;
        }

        static Row header(String text) { return new Row(true, text, null); }

        static Row item(PaymentEvent e) { return new Row(false, null, e); }
    }

    private static final int TYPE_DAY_HEADER = 0;
    private static final int TYPE_PAYMENT = 1;

    private final List<Row> rows = new ArrayList<>();

    private final SimpleDateFormat dayFmt = new SimpleDateFormat("dd MMM", Locale.US);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", Locale.US);

    // ---- data ------------------------------------------------------------

    /**
     * Replace the backing list.
     *
     * @param events already newest-first (PaymentHistoryStore.readAll guarantees this)
     * @param nowMs  current time, injected so the caller controls the clock
     */
    public void submit(List<PaymentEvent> events, long nowMs, Context ctx) {
        rows.clear();
        if (events != null && ctx != null) {
            long currentDayStart = startOfDay(nowMs);
            long lastHeaderDayStart = Long.MIN_VALUE;
            for (PaymentEvent e : events) {
                if (e == null) continue;
                long dayStart = startOfDay(e.getTimestampMs());
                if (dayStart != lastHeaderDayStart) {
                    rows.add(Row.header(labelFor(ctx, dayStart, currentDayStart)));
                    lastHeaderDayStart = dayStart;
                }
                rows.add(Row.item(e));
            }
        }
        notifyDataSetChanged();
    }

    // ---- grouping --------------------------------------------------------

    /** Local midnight of the day containing {@code timeMs}. */
    private static long startOfDay(long timeMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * "TODAY" / "YESTERDAY" / "12 Oct".
     *
     * YESTERDAY is decided by subtracting one calendar day from today's
     * midnight rather than subtracting 24h, so a DST transition cannot make
     * yesterday's bucket appear to vanish.
     */
    private String labelFor(Context ctx, long dayStart, long currentDayStart) {
        if (dayStart == currentDayStart) {
            return ctx.getString(R.string.payment_history_day_today);
        }
        Calendar prev = Calendar.getInstance();
        prev.setTimeInMillis(currentDayStart);
        prev.add(Calendar.DAY_OF_MONTH, -1);
        if (dayStart == prev.getTimeInMillis()) {
            return ctx.getString(R.string.payment_history_day_yesterday);
        }
        return dayFmt.format(new Date(dayStart)).toUpperCase(Locale.US);
    }

    // ---- RecyclerView ----------------------------------------------------

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isHeader ? TYPE_DAY_HEADER : TYPE_PAYMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DAY_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_history_day_header, parent, false));
        }
        return new PaymentVH(inf.inflate(R.layout.item_payment_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).title.setText(row.headerText);
        } else if (holder instanceof PaymentVH) {
            bindPayment((PaymentVH) holder, row.event);
        }
    }

    private void bindPayment(PaymentVH h, PaymentEvent e) {
        if (e == null) return;

        h.time.setText(e.getTimestampMs() > 0 ? timeFmt.format(new Date(e.getTimestampMs())) : "");

        if (e.getAmountPaise() >= 0) {
            long rupees = e.getAmountPaise() / 100;
            long paise = e.getAmountPaise() % 100;
            h.amount.setText(paise == 0
                    ? "₹ " + rupees
                    : "₹ " + rupees + "." + (paise < 10 ? "0" + paise : paise));
        } else {
            h.amount.setText(h.itemView.getContext()
                    .getString(R.string.payment_history_amount_unknown));
        }

        String srcLabel = (e.getSource() == PaymentEvent.Source.NOTIFICATION) ? "App" : "SMS";
        h.source.setText(srcLabel + " · " + e.getSourceId());
        h.body.setText(e.getRawBody());
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    // ---- view holders ----------------------------------------------------

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderVH(View v) {
            super(v);
            title = v.findViewById(R.id.history_day_header_tv);
        }
    }

    static final class PaymentVH extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView amount;
        final TextView source;
        final TextView body;

        PaymentVH(View v) {
            super(v);
            time = v.findViewById(R.id.history_item_time);
            amount = v.findViewById(R.id.history_item_amount);
            source = v.findViewById(R.id.history_item_source);
            body = v.findViewById(R.id.history_item_body);
        }
    }
}