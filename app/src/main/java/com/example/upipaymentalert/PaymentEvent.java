// ============================================================================
// Payment Event — UPI Payment Alert
// Immutable structured record of ONE accepted payment event.
//
// This is the first structured payment type in the app. Both detection
// channels (SMS and NotificationListener) create one of these per accepted
// payment and hand it to PaymentHistoryStore for durable storage.
//
// IMPORTANT (Q4 Option A): the numeric amount is NOT re-derived here. The
// amount is extracted once by SmsParser.extractAmount(...) and passed in, so
// the spoken phrase and the stored number provably come from the same parse
// and can never disagree. This class holds no regex of its own.
// ============================================================================
package com.example.upipaymentalert;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class PaymentEvent {

    /** Where the event came from. */
    public enum Source { SMS, NOTIFICATION }

    private final String eventId;
    private final long amountPaise;   // -1 when unknown
    private final String amountRaw;   // "" when unknown
    private final String phrase;      // exactly what was handed to EXTRA_TEXT
    private final Source source;
    private final String sourceId;    // sender address OR package name
    private final String rawBody;
    private final long timestampMs;
    private final String displayText; // the exact "last_sms" value

    private PaymentEvent(String eventId, long amountPaise, String amountRaw, String phrase,
                         Source source, String sourceId, String rawBody, long timestampMs,
                         String displayText) {
        this.eventId = eventId;
        this.amountPaise = amountPaise;
        this.amountRaw = amountRaw;
        this.phrase = phrase;
        this.source = source;
        this.sourceId = sourceId;
        this.rawBody = rawBody;
        this.timestampMs = timestampMs;
        this.displayText = displayText;
    }

    /**
     * Build an event at capture time.
     *
     * The amount is supplied by the caller (normally from
     * SmsParser.extractAmount) so that the phrase that was spoken and the
     * amount recorded here are derived from one and the same parse.
     *
     * @param source      originating channel
     * @param sourceId    sender address (SMS) or package name (NOTIFICATION); may be null
     * @param rawBody     full extracted text that was parsed
     * @param phrase      the exact phrase string passed to the TTS service
     * @param displayText the exact string written to UPI_PREFS["last_sms"]
     * @param amountPaise amount in paise, or -1 when unknown
     * @param amountRaw   raw captured amount text, or "" when unknown
     */
    public static PaymentEvent capture(Source source, String sourceId, String rawBody,
                                       String phrase, String displayText,
                                       long amountPaise, String amountRaw) {
        return new PaymentEvent(
                UUID.randomUUID().toString(),
                amountPaise,
                amountRaw == null ? "" : amountRaw,
                phrase == null ? "" : phrase,
                source,
                sourceId == null ? "" : sourceId,
                rawBody == null ? "" : rawBody,
                System.currentTimeMillis(),
                displayText == null ? "" : displayText);
    }

    /** Reconstruct an event read back from disk. Never throws. */
    static PaymentEvent fromJson(JSONObject o) {
        Source src;
        try {
            src = Source.valueOf(o.optString("source", "SMS"));
        } catch (Exception e) {
            src = Source.SMS;
        }
        return new PaymentEvent(
                o.optString("eventId", ""),
                o.optLong("amountPaise", -1L),
                o.optString("amountRaw", ""),
                o.optString("phrase", ""),
                src,
                o.optString("sourceId", ""),
                o.optString("rawBody", ""),
                o.optLong("timestampMs", 0L),
                o.optString("displayText", ""));
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("eventId", eventId);
        o.put("amountPaise", amountPaise);
        o.put("amountRaw", amountRaw);
        o.put("phrase", phrase);
        o.put("source", source.name());
        o.put("sourceId", sourceId);
        o.put("rawBody", rawBody);
        o.put("timestampMs", timestampMs);
        o.put("displayText", displayText);
        return o;
    }

    public String getEventId() { return eventId; }
    public long getAmountPaise() { return amountPaise; }
    public String getAmountRaw() { return amountRaw; }
    public String getPhrase() { return phrase; }
    public Source getSource() { return source; }
    public String getSourceId() { return sourceId; }
    public String getRawBody() { return rawBody; }
    public long getTimestampMs() { return timestampMs; }
    public String getDisplayText() { return displayText; }
}