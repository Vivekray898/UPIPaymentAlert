// ============================================================================
// Remote payment event — child-side payment history record shape.
//
// This file exists to keep the current commit buildable. The child-side payment
// history feature is wired in RemoteIngest, and this class is the event record
// shape it expects.
//
// What is in this file:
//   - A simple event record class with the fields RemoteIngest uses when it
//     persists a child-side remote payment.
//   - A static capture() helper that creates an event from the values RemoteIngest
//     already has at announcement time.
//
// What is deferred:
//   - Full child-side payment history storage/UI is built around
//     RemotePaymentHistoryStore / RemotePaymentHistoryAdapter / RemoteHistoryActivity,
//     which are added later in the remote feature branch.
//
// Buildability contract:
//   - This file does not depend on Firebase, Supabase, or any network class.
//   - It is a pure data class used locally by the child-side announcement path.
// ============================================================================

package com.vivekray898.upipaymentalert.remote;

public final class RemotePaymentEvent {

    /** Empty constructor only for the no-args serialization contract.
     *  Every field is defensively defaulted so the compiler does not require
     *  each final field to be assigned in this path.
     */
    private RemotePaymentEvent() {
        this.amountPaise = 0L;
        this.currency = "";
        this.phrase = "";
        this.source = "";
        this.pairId = "";
        this.timestampMs = 0L;
    }

    public final long amountPaise;
    public final String currency;
    public final String phrase;
    public final String source;
    public final String pairId;
    public final long timestampMs;

    public RemotePaymentEvent(long amountPaise, String currency, String phrase,
                              String source, String pairId, long timestampMs) {
        this.amountPaise = amountPaise;
        this.currency = currency == null ? "" : currency;
        this.phrase = phrase == null ? "" : phrase;
        this.source = source == null ? "" : source;
        this.pairId = pairId == null ? "" : pairId;
        this.timestampMs = timestampMs;
    }

    /**
     * Convenience capture used by the child-side announcement path.
     */
    public static RemotePaymentEvent capture(long amountPaise, String currency,
                                             String phrase, String source,
                                             String pairId, String unused) {
        return new RemotePaymentEvent(amountPaise, currency, phrase, source, pairId,
                                      System.currentTimeMillis());
    }
}
