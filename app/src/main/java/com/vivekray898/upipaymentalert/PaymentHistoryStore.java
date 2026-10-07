// ============================================================================
// Payment History Store — UPI Payment Alert
// Durable JSON-lines store at context.getFilesDir()/payment_history.jsonl
// (one PaymentEvent per line).
//
// CONTRACT (must not be violated):
//  * append() is called from the MAIN THREAD by the two detection listeners.
//    It must stay cheap and must NEVER throw — a history failure may never
//    prevent or delay the TTS announcement.
//  * readAll() / clear() are UI-only (HistoryActivity).
//  * Every public method swallows Exception, logs it, and returns normally.
//
// CAP HANDLING: naive synchronous trimming would read+rewrite ~MAX_ENTRIES
// lines on the main thread for every payment past the cap. Instead we count entries
// cheaply via UPI_PREFS["history_entry_count"] and, when over the cap, run
// compaction on a background thread. The cap is therefore best-effort: if a
// compaction fails the file simply grows until the next successful attempt.
// Data is never lost, and the main thread is never charged for rotation.
// ============================================================================
package com.vivekray898.upipaymentalert;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PaymentHistoryStore {

    private static final String TAG = "UPIPaymentAlert";
    private static final String FILE_NAME = "payment_history.jsonl";
    private static final String PREFS = "UPI_PREFS";
    private static final String KEY_COUNT = "history_entry_count";

    /**
     * Hard cap on retained entries (Q1: 2000).
     *
     * Raised from 500 to 2000 for the daily/weekly/monthly reports: a 500-entry
     * window can be exhausted well inside a single month, which would silently
     * truncate the monthly total. The raise applies FORWARD ONLY - entries already
     * rotated out under the old cap are gone and are not recoverable.
     */
    public static final int MAX_ENTRIES = 2000;

    private static final Object LOCK = new Object();

    private PaymentHistoryStore() { }

    private static File fileFor(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * Append one event. Cheap, non-blocking, never throws.
     */
    public static void append(Context context, PaymentEvent event) {
        if (context == null || event == null) return;
        try {
            synchronized (LOCK) {
                File f = fileFor(context);
                boolean exists = f.exists();

                // Resolve the entry count BEFORE writing, so that the fallback
                // countLines(f) does not already include the line we are about
                // to append (which previously caused a double-count/off-by-one
                // whenever the stored counter was missing or stale).
                int count = readCount(context);
                if (!exists || count <= 0) count = countLines(f);

                FileOutputStream fos = new FileOutputStream(f, true /* append */);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                w.write(event.toJson().toString());
                w.write('\n');
                w.flush();
                w.close();

                count += 1;
                writeCount(context, count);

                if (count > MAX_ENTRIES) {
                    compactAsync(context);
                }
            }
        } catch (Exception e) {
            // MUST never propagate: the TTS call must not be affected.
            Log.e(TAG, "PaymentHistoryStore.append failed (swallowed): " + e.getMessage());
        }
    }

    /**
     * Read all entries, newest first. Never throws; returns an empty list on failure.
     */
    public static List<PaymentEvent> readAll(Context context) {
        List<PaymentEvent> out = new ArrayList<>();
        if (context == null) return out;
        try {
            synchronized (LOCK) {
                File f = fileFor(context);
                if (!f.exists()) return out;
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        // A single corrupt line (e.g. process killed mid-write)
                        // must not hide the rest of the history.
                        out.add(PaymentEvent.fromJson(new JSONObject(line)));
                    } catch (Exception bad) {
                        Log.e(TAG, "Skipping corrupt history line: " + bad.getMessage());
                    }
                }
                r.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "PaymentHistoryStore.readAll failed (swallowed): " + e.getMessage());
            return new ArrayList<>();
        }
        // file is oldest-first on disk; UI wants newest-first
        Collections.reverse(out);
        return out;
    }

    /** SharedPreferences key holding the "latest payment" snapshot shown on the home screen. */
    private static final String KEY_LAST_SMS = "last_sms";

    /**
     * Delete the history file AND reset the home screen's "latest payment" box.
     * Never throws.
     *
     * The two are cleared together so that "Clear history" leaves no visible
     * trace of any payment anywhere in the app; previously the list was emptied
     * while {@code last_sms} kept showing the most recent payment, which read as
     * if the clear had failed.
     */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            synchronized (LOCK) {
                File f = fileFor(context);
                if (f.exists()) f.delete();
                writeCount(context, 0);
                // Remove only this key; the user's language/volume/speed/forwarder
                // settings live in the same prefs file and must survive a clear.
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().remove(KEY_LAST_SMS).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "PaymentHistoryStore.clear failed (swallowed): " + e.getMessage());
        }
    }

    // ---- internals -------------------------------------------------------

    private static void compactAsync(final Context context) {
        final Context app = context.getApplicationContext();
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    compact(app);
                }
            }, "payment-history-compact").start();
        } catch (Exception e) {
            Log.e(TAG, "compactAsync could not start (swallowed): " + e.getMessage());
        }
    }

    /** Rebuild the file keeping only the newest MAX_ENTRIES lines. */
    private static void compact(Context context) {
        try {
            File f = fileFor(context);
            if (!f.exists()) return;
            List<String> lines = new ArrayList<>();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
            r.close();

            if (lines.size() <= MAX_ENTRIES) {
                writeCount(context, lines.size());
                return;
            }
            // keep the newest MAX_ENTRIES (tail), oldest-first output order
            int from = lines.size() - MAX_ENTRIES;
            List<String> keep = lines.subList(from, lines.size());

            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp, false), "UTF-8"));
            for (String s : keep) {
                w.write(s);
                w.write('\n');
            }
            w.flush();
            w.close();

            // atomic-ish swap
            if (!f.delete()) {
                Log.e(TAG, "compact: could not delete original; leaving file untouched");
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(f)) {
                Log.e(TAG, "compact: rename failed; restoring is not possible");
                writeCount(context, countLines(f));
                return;
            }
            writeCount(context, keep.size());
        } catch (Exception e) {
            // Best-effort: the file stays over the cap until the next success.
            Log.e(TAG, "PaymentHistoryStore.compact failed (swallowed): " + e.getMessage());
        }
    }

    private static int countLines(File f) {
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) n++;
            }
            r.close();
        } catch (Exception ignored) {
        }
        return n;
    }

    private static int readCount(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getInt(KEY_COUNT, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeCount(Context context, int n) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putInt(KEY_COUNT, n).apply();
        } catch (Exception ignored) {
        }
    }
}