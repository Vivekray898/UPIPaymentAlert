// ============================================================================
// Remote Child Store — UPI Payment Alert (remote announcement layer)
//
// The child's replay defence (Q5): the last 200 eventIds, persisted so a
// redelivered FCM message cannot cause a second announcement even across a
// process death or reboot. This is SEPARATE from the owner's 60-second phrase
// dedupe map in ForegroundTtsService, which this feature never touches.
//
// FAIL-OPEN by design: an I/O failure reports "not seen", so the payment is
// announced. Announcing a real payment matters more than suppressing a rare
// duplicate, and the FGS phrase dedupe is still there as a second layer.
// ============================================================================
package com.vivekray898.upipaymentalert.remote;

import android.content.Context;
import android.util.Log;

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

public final class RemoteChildStore {

    private static final String TAG = "UPIPaymentAlert";
    private static final String DIR = "remote_seen";
    private static final String FILE = "remote_seen.jsonl";
    public static final int MAX_IDS = 200;

    private static final Object LOCK = new Object();

    private RemoteChildStore() { }

    private static File fileFor(Context context, String pairId) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            // if we cannot create the dir, fall back to a single shared file
            return new File(context.getFilesDir(), "remote_seen.jsonl");
        }
        return new File(dir, pairId.hashCode() + ".jsonl");
    }

    /**
     * @return true when this eventId has ALREADY been announced for this pairing,
     *         in which case the caller must discard the message. A new eventId is
     *         recorded here (before speech) so a crash mid-utterance cannot
     *         double-announce.
     */
    public static boolean seenBefore(Context context, String pairId, String eventId) {
        if (context == null || pairId == null || pairId.isEmpty()
                || eventId == null || eventId.isEmpty()) return false;
        try {
            synchronized (LOCK) {
                File f = fileFor(context, pairId);
                List<String> ids = readIds(f);
                if (ids.contains(eventId)) return true;
                ids.add(eventId);
                if (ids.size() > MAX_IDS) {
                    ids = new ArrayList<>(ids.subList(ids.size() - MAX_IDS, ids.size()));
                }
                writeIds(f, ids);
                return false;
            }
        } catch (Exception e) {
            // Fail open: announce the payment rather than silently dropping it.
            Log.w(TAG, "remote: child dedupe unavailable (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static int size(Context context, String pairId) {
        try {
            return readIds(fileFor(context, pairId)).size();
        } catch (Exception e) {
            return 0;
        }
    }

    public static void clear(Context context, String pairId) {
        try {
            File f = fileFor(context, pairId);
            if (f.exists()) f.delete();
        } catch (Exception e) {
            Log.w(TAG, "remote: child store clear failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Clear the shared legacy file when we have no pairId-specific naming. */
    public static void clearShared(Context context) {
        try {
            File f = new File(context.getFilesDir(), "remote_seen.jsonl");
            if (f.exists()) f.delete();
        } catch (Exception e) {
            Log.w(TAG, "remote: child store clearShared failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static List<String> readIds(File f) {
        List<String> out = new ArrayList<>();
        try {
            if (!f.exists()) return out;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String id = line.trim();
                if (!id.isEmpty()) out.add(id);
            }
            r.close();
        } catch (Exception e) {
            Log.w(TAG, "remote: child store read failed (" + e.getClass().getSimpleName() + ")");
        }
        return out;
    }

    private static void writeIds(File f, List<String> ids) {
        try {
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp, false), StandardCharsets.UTF_8));
            for (String id : ids) {
                w.write(id);
                w.write('\n');
            }
            w.flush();
            w.close();
            if (!tmp.renameTo(f)) {
                FileOutputStream fos = new FileOutputStream(f, false);
                BufferedWriter w2 = new BufferedWriter(
                        new OutputStreamWriter(fos, StandardCharsets.UTF_8));
                for (String id : ids) {
                    w2.write(id);
                    w2.write('\n');
                }
                w2.flush();
                w2.close();
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "remote: child store write failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
