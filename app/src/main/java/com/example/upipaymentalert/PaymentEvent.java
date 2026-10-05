// ============================================================================
// Payment Event — UPI Payment Alert
// Immutable structured record of ONE accepted payment event.
//
// This is the first structured payment type in the app. Both detection
// channels (SMS and NotificationListener) create one of these per accepted
// payment and hand it to PaymentHistoryStore for durable storage.
//
// IMPORTANT (Q4 Option B): SmsParser is intentionally NOT modified. To keep
// the spoken phrase byte-identical we never re-run the parser; instead we
// re-run a COPY of SmsParser's AMOUNT_PATTERN here to obtain the numeric
// amount. This is the one duplicated line of logic in the feature and it can
// drift from SmsParser if that regex is ever changed. See PaymentHistoryPlan.
// ============================================================================
package com.example.upipaymentalert;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaymentEvent {

    /** Where the event came from. */
    public enum Source { SMS, NOTIFICATION }

    // NOTE: duplicate of SmsParser.AMOUNT_PATTERN. Keep in sync if SmsParser changes.
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("(?i)(?:(?:RS|INR|MRP|₹)\\.?\\s*)(\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

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
     * @param source      originating channel
     * @param sourceId    sender address (SMS) or package name (NOTIFICATION); may be null
     * @param rawBody     full extracted text that was parsed
     * @param phrase      the exact phrase string passed to the TTS service
     * @param displayText the exact string written to UPI_PREFS["last_sms"]
     */
    public static PaymentEvent capture(Source source, String sourceId, String rawBody,
                                       String phrase, String displayText) {
        long paise = -1L;
        String raw = "";
        try {
            if (rawBody != null && !rawBody.isEmpty()) {
                Matcher m = AMOUNT_PATTERN.matcher(rawBody);
                if (m.find()) {
                    raw = m.group(1);
                    paise = toPaise(raw);
                }
            }
        } catch (Exception ignored) {
            // amount stays unknown; never let this break capture
        }
        return new PaymentEvent(
                UUID.randomUUID().toString(),
                paise,
                raw,
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

    /**
     * Convert a raw captured amount ("125.50", "1,250") to paise.
     * Returns -1 if it cannot be parsed.
     */
    private static long toPaise(String raw) {
        try {
            String cleaned = raw.replace(",", "");
            String[] parts = cleaned.split("\\.", 2);
            long rupees = Long.parseLong(parts[0]);
            long paise = 0L;
            if (parts.length > 1 && !parts[1].isEmpty()) {
                String frac = parts[1];
                if (frac.length() == 1) {
                    paise = Long.parseLong(frac) * 10L;
                } else {
                    paise = Long.parseLong(frac);
                }
            }
            return rupees * 100L + paise;
        } catch (Exception e) {
            return -1L;
        }
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