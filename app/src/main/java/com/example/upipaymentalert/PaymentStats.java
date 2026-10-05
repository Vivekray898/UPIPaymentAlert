// ============================================================================
// Payment Stats — UPI Payment Alert
// Pure aggregation over an already-materialised List<PaymentEvent>.
//
// NOTHING IS STORED. Totals are recomputed from the event list on every read,
// so the day/week/month buckets roll over automatically when the clock passes
// local midnight. No alarm, no boot receiver, no background service.
//
// Uses java.util.Calendar ONLY (minSdk 24, core library desugaring NOT enabled).
// ============================================================================
package com.example.upipaymentalert;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public final class PaymentStats {

    private PaymentStats() { }

    /** Immutable snapshot of the three reporting windows. */
    public static final class Result {
        public final long todayTotalPaise;
        public final long weekTotalPaise;
        public final long monthTotalPaise;
        public final int  todayUnknownCount;
        public final int  weekUnknownCount;
        public final int  monthUnknownCount;

        Result(long todayTotalPaise, long weekTotalPaise, long monthTotalPaise,
               int todayUnknownCount, int weekUnknownCount, int monthUnknownCount) {
            this.todayTotalPaise = todayTotalPaise;
            this.weekTotalPaise = weekTotalPaise;
            this.monthTotalPaise = monthTotalPaise;
            this.todayUnknownCount = todayUnknownCount;
            this.weekUnknownCount = weekUnknownCount;
            this.monthUnknownCount = monthUnknownCount;
        }
    }

    /**
     * Single pass over the event list.
     *
     * amountPaise >= 0 -> added to the window totals.
     * amountPaise <  0 -> counted as an unknown-amount payment for that window
     *                    and deliberately EXCLUDED from the total (Q: totals must
     *                    not silently overstate what was parsed).
     *
     * Totals deliberately INCLUDE events that the TTS dedupe suppressed
     * (Q6): money was received even if it was not spoken twice.
     */
    public static Result compute(List<PaymentEvent> events, long nowMs, TimeZone tz) {
        long todayStart  = startOfToday(nowMs, tz);
        long weekStart   = startOfWeek(nowMs, tz);
        long monthStart  = startOfMonth(nowMs, tz);

        long today = 0L, week = 0L, month = 0L;
        int todayUnknown = 0, weekUnknown = 0, monthUnknown = 0;

        if (events != null) {
            for (int i = 0; i < events.size(); i++) {
                PaymentEvent e = events.get(i);
                if (e == null) continue;
                long ts = e.getTimestampMs();
                // Events with a non-positive timestamp cannot be bucketed
                // reliably; ignore them rather than guess a window.
                if (ts <= 0) continue;

                boolean inMonth = ts >= monthStart;
                boolean inWeek  = inMonth && ts >= weekStart;
                boolean inToday = inWeek  && ts >= todayStart;

                if (e.getAmountPaise() >= 0) {
                    long p = e.getAmountPaise();
                    if (inMonth) month += p;
                    if (inWeek)  week  += p;
                    if (inToday) today += p;
                } else {
                    if (inMonth) monthUnknown++;
                    if (inWeek)  weekUnknown++;
                    if (inToday) todayUnknown++;
                }
            }
        }
        return new Result(today, week, month, todayUnknown, weekUnknown, monthUnknown);
    }

    // ---- bucket boundaries ----------------------------------------------

    /** Midnight (00:00:00.000) of the local day containing nowMs. */
    public static long startOfToday(long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        return midnight(c, tz);
    }

    /** Midnight of the Monday on/before nowMs (Q5: week starts Monday). */
    public static long startOfWeek(long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.setFirstDayOfWeek(Calendar.MONDAY);
        Calendar first = (Calendar) c.clone();
        first.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
        return midnight(first, tz);
    }

    /** Midnight of the 1st of the local month containing nowMs. */
    public static long startOfMonth(long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.DAY_OF_MONTH, 1);
        return midnight(c, tz);
    }

    /** Midnight of the local day containing an arbitrary timestamp. */
    public static long startOfDay(long timeMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(timeMs);
        return midnight(c, tz);
    }

    private static long midnight(Calendar base, TimeZone tz) {
        base.setTimeZone(tz);
        base.set(Calendar.HOUR_OF_DAY, 0);
        base.set(Calendar.MINUTE, 0);
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);
        return base.getTimeInMillis();
    }
}