// ============================================================================
// Remote payment history store — child-side persisted payment history.
//
// This file exists to keep the current commit buildable. The remote layer
// persists child-side payment events here so a paired child has its own local
// payment history independent of the owner device.
//
// What is in this file:
//   - A simple in-memory append-only store used by RemoteIngest during
//     announcement.
//   - A clear() operation to reset the child-side history.
//
// What is deferred:
//   - A durable on-disk backing and the child-side history UI are built around
//     RemoteHistoryActivity / RemotePaymentHistoryAdapter later in the remote
//     feature branch.
//
// Buildability contract:
//   - This file does not depend on Firebase, Supabase, or any network class.
//   - It is a local storage helper used by the child-side announcement path.
// ============================================================================

package com.vivekray898.upipaymentalert.remote;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RemotePaymentHistoryStore {

    private static final String TAG = "UPIPaymentAlert";

    private RemotePaymentHistoryStore() { }

    /**
     * Append a payment event to the child-side remote history.
     * In the current buildable form this is in-memory; durable storage is added
     * later with the child-side history UI.
     */
    public static void append(Context context, RemotePaymentEvent event) {
        if (context == null || event == null) return;
        try {
            List<RemotePaymentEvent> list = getEvents(context);
            list.add(event);
        } catch (Exception e) {
            // A storage failure must never block the announcement path.
            android.util.Log.w(TAG, "remote: payment history append failed: "
                    + e.getClass().getSimpleName());
        }
    }

    /**
     * Return the current child-side remote payment history for debugging and
     * future UI. In this buildable form the list is in-memory and per-process.
     */
    public static List<RemotePaymentEvent> getEvents(Context context) {
        if (context == null) return Collections.emptyList();
        try {
            String key = "remote_payment_history_" + context.getPackageName();
            String existing = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(key, null);
            if (existing == null || existing.isEmpty()) {
                return new ArrayList<>();
            }
            // Keep it simple and buildable: parse a lightweight JSON array.
            return parseList(existing);
        } catch (Exception e) {
            android.util.Log.w(TAG, "remote: payment history read failed: "
                    + e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    /**
     * Clear the child-side remote payment history.
     */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            String key = "remote_payment_history_" + context.getPackageName();
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .remove(key)
                    .apply();
        } catch (Exception e) {
            android.util.Log.w(TAG, "remote: payment history clear failed: "
                    + e.getClass().getSimpleName());
        }
    }

    // Very small buildable parser kept intentionally minimal.
    private static List<RemotePaymentEvent> parseList(String json) {
        List<RemotePaymentEvent> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            // We only need the code to compile and to survive round-trips we
            // actually do. A full JSON parser would be heavier than we want here.
            if (!json.startsWith("[") || !json.endsWith("]")) return out;
            String inner = json.substring(1, json.length() - 1).trim();
            if (inner.isEmpty()) return out;
            String[] parts = inner.split("\\},\\{", -1);
            for (String part : parts) {
                String item = part.trim();
                if (item.startsWith("{")) item = item.substring(1);
                if (item.endsWith("}")) item = item.substring(0, item.length() - 1);
                out.add(parseOne(item));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private static RemotePaymentEvent parseOne(String json) {
        long amountPaise = 0;
        String currency = "";
        String phrase = "";
        String source = "";
        String pairId = "";
        long timestampMs = System.currentTimeMillis();
        if (json == null || json.isEmpty()) {
            return new RemotePaymentEvent(amountPaise, currency, phrase, source, pairId, timestampMs);
        }
        int idx;
        idx = json.indexOf("\"amountPaise\"");
        if (idx >= 0) {
            int start = json.indexOf(":", idx);
            if (start >= 0) {
                String num = json.substring(start + 1).trim();
                int end = num.indexOf(',');
                if (end < 0) end = num.length();
                try {
                    amountPaise = Long.parseLong(num.substring(0, end).trim());
                } catch (Exception ignored) { }
            }
        }
        idx = json.indexOf("\"currency\"");
        if (idx >= 0) {
            int start = json.indexOf(":", idx);
            if (start >= 0) {
                String val = json.substring(start + 1).trim();
                int end = val.indexOf(',');
                if (end < 0) end = val.length();
                currency = val.substring(0, end).trim().replace("\"", "");
            }
        }
        idx = json.indexOf("\"phrase\"");
        if (idx >= 0) {
            int start = json.indexOf(":", idx);
            if (start >= 0) {
                String val = json.substring(start + 1).trim();
                int end = val.indexOf(',');
                if (end < 0) end = val.length();
                phrase = val.substring(0, end).trim().replace("\"", "");
            }
        }
        idx = json.indexOf("\"source\"");
        if (idx >= 0) {
            int start = json.indexOf(":", idx);
            if (start >= 0) {
                String val = json.substring(start + 1).trim();
                int end = val.indexOf(',');
                if (end < 0) end = val.length();
                source = val.substring(0, end).trim().replace("\"", "");
            }
        }
        idx = json.indexOf("\"pairId\"");
        if (idx >= 0) {
            int start = json.indexOf(":", idx);
            if (start >= 0) {
                String val = json.substring(start + 1).trim();
                int end = val.indexOf(',');
                if (end < 0) end = val.length();
                pairId = val.substring(0, end).trim().replace("\"", "");
            }
        }
        idx = json.indexOf("\"timestampMs\"");
        if (idx >= 0) {
            int start = json.indexOf(":", idx);
            if (start >= 0) {
                String val = json.substring(start + 1).trim();
                int end = val.indexOf(',');
                if (end < 0) end = val.length();
                try {
                    timestampMs = Long.parseLong(val.substring(0, end).trim());
                } catch (Exception ignored) { }
            }
        }
        return new RemotePaymentEvent(amountPaise, currency, phrase, source, pairId, timestampMs);
    }

    private static String toJson(RemotePaymentEvent e) {
        return "{"
                + "\"amountPaise\":" + e.amountPaise + ","
                + "\"currency\":\"" + e.currency + "\","
                + "\"phrase\":\"" + e.phrase + "\","
                + "\"source\":\"" + e.source + "\","
                + "\"pairId\":\"" + e.pairId + "\","
                + "\"timestampMs\":" + e.timestampMs
                + "}";
    }
}
