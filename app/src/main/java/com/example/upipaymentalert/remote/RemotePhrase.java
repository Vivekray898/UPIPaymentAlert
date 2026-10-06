// ============================================================================
// Remote Phrase — UPI Payment Alert (remote announcement layer)
//
// THE DRIFT GUARD (Q7).
//
// The child never receives the spoken phrase; it receives amountPaise and must
// produce wording identical to what the owner spoke. Rather than copy the
// phrasing (which would silently drift the day SmsParser changes), the child
// rebuilds a synthetic body and feeds it to the UNMODIFIED
// SmsParser.getAmountFromMessageBody - so there is exactly one implementation of
// the wording, and SmsParser.java is not touched by this feature at all.
//
// Locale.US is deliberate: several Indian locales render %d with native digits,
// which the parser's regex would not match, and the child would then announce
// "unknown amount" while the owner announced a number.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;

import com.example.upipaymentalert.smsparser.SmsParser;

import java.util.Locale;

public final class RemotePhrase {

    private static final String PREFS = "UPI_PREFS";

    private RemotePhrase() { }

    /**
     * Synthetic body for a known amount. Mirrors the "RS" form the parser's
     * regex already handles, e.g. 125050 -> "RS 1250.50".
     * A negative amount (unknown) yields an empty body, which the parser maps to
     * its existing "unknown amount" sentence.
     */
    public static String syntheticBody(long amountPaise) {
        if (amountPaise < 0) return "";
        return "RS " + (amountPaise / 100L) + "."
                + String.format(Locale.US, "%02d", amountPaise % 100L);
    }

    /** Speak-ready phrase for this device's own language setting. */
    public static String phraseFor(Context context, long amountPaise) {
        String lang = "English";
        try {
            lang = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("language", "English");
        } catch (Exception ignored) {
            // fall through to the default language
        }
        try {
            return new SmsParser().getAmountFromMessageBody(syntheticBody(amountPaise), lang);
        } catch (Exception e) {
            return "Received payment of an unknown amount";
        }
    }
}
