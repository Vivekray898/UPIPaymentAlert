// ============================================================================
// Remote Outbox — UPI Payment Alert (remote announcement layer)
//
// Durable JSON-lines queue at getFilesDir()/remote_outbox.jsonl, deliberately
// the same shape and error discipline as PaymentHistoryStore:
//   * append() is called from the main-thread listeners -> cheap, never throws
//   * a truncated final line is skipped on read (process killed mid-write)
//   * the queue is drained on a background thread
//
// Kept SEPARATE from payment_history.jsonl so history semantics and its own cap
// are untouched by this feature.
// ============================================================================
package com.example.upipaymentalert.remote;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RemoteOutbox {

    private static final String TAG = "UPIPaymentAlert";
    private static final String FILE = "remote_outbox.jsonl";

    /** Bounded so a broken wire cannot grow the queue without limit. */
    public static final int MAX_ENTRIES = 500;

    private static final Object LOCK = new Object();

    public static final class Entry {
        public final String pairId;
        public final String envelopeJson;
        public final int attempts;
        public final long dueMs;

        public Entry(String pairId, String envelopeJson, int attempts, long dueMs) {
            this.pairId = pairId == null ? "" : pairId;
            this.envelopeJson = envelopeJson == null ? "" : envelopeJson;
            this.attempts = attempts;
            this.dueMs = dueMs;
        }
    }

    private RemoteOutbox() { }

    private static File fileFor(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /** Enqueue one envelope. Cheap, NEVER throws. */
    public static void append(Context context, String pairId, String envelopeJson) {
        if (context == null || envelopeJson == null) return;
        try {
            synchronized (LOCK) {
                File f = fileFor(context);
                List<Entry> existing = readAllLocked(f);
                existing.add(new Entry(pairId, envelopeJson, 0, 0L));
                if (existing.size() > MAX_ENTRIES) {
                    // Drop the OLDEST: the newest payments matter most.
                    int excess = existing.size() - MAX_ENTRIES;
                    for (int i = 0; i < excess; i++) {
                        Log.w(TAG, "remote: outbox full, dropped oldest eventId="
                                + RemoteEnvelope.eventIdOf(existing.get(i).envelopeJson));
                    }
                    existing = new ArrayList<>(existing.subList(excess, existing.size()));
                }
                rewriteLocked(f, existing);
            }
        } catch (Exception e) {
            // MUST never propagate: the TTS call must not be affected.
            Log.w(TAG, "remote: outbox append failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    public static List<Entry> readAll(Context context) {
        try {
            synchronized (LOCK) {
                return readAllLocked(fileFor(context));
            }
        } catch (Exception e) {
            Log.w(TAG, "remote: outbox read failed (" + e.getClass().getSimpleName() + ")");
            return new ArrayList<>();
        }
    }

    public static void rewrite(Context context, List<Entry> entries) {
        try {
            synchronized (LOCK) {
                rewriteLocked(fileFor(context), entries);
            }
        } catch (Exception e) {
            Log.w(TAG, "remote: outbox rewrite failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    public static int size(Context context) {
        return readAll(context).size();
    }

    // ---- internals -------------------------------------------------------

    private static List<Entry> readAllLocked(File f) {
        List<Entry> out = new ArrayList<>();
        try {
            if (!f.exists()) return out;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    String env = o.optString("env", "");
                    if (!env.isEmpty()) {
                        out.add(new Entry(o.optString("pairId", ""), env,
                                o.optInt("a", 0), o.optLong("dueMs", 0L)));
                    }
                } catch (Exception bad) {
                    // A single corrupt/truncated line must not hide the rest.
                    Log.w(TAG, "remote: skipping corrupt outbox line");
                }
            }
            r.close();
        } catch (Exception e) {
            Log.w(TAG, "remote: outbox read failed (" + e.getClass().getSimpleName() + ")");
        }
        return out;
    }

    private static void rewriteLocked(File f, List<Entry> entries) {
        try {
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp, false), StandardCharsets.UTF_8));
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("pairId", e.pairId);
                o.put("env", e.envelopeJson);
                o.put("a", e.attempts);
                o.put("dueMs", e.dueMs);
                w.write(o.toString());
                w.write('\n');
            }
            w.flush();
            w.close();
            if (!tmp.renameTo(f)) {
                // Rename over an existing file can fail; fall back to in-place write.
                FileOutputStream fos = new FileOutputStream(f, false);
                BufferedWriter w2 = new BufferedWriter(
                        new OutputStreamWriter(fos, StandardCharsets.UTF_8));
                for (Entry e : entries) {
                    JSONObject o = new JSONObject();
                    o.put("pairId", e.pairId);
                    o.put("env", e.envelopeJson);
                    o.put("a", e.attempts);
                    o.put("dueMs", e.dueMs);
                    w2.write(o.toString());
                    w2.write('\n');
                }
                w2.flush();
                w2.close();
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "remote: outbox write failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
