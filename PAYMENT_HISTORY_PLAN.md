# Payment Received History — Implementation Plan & Record

> **Status: IMPLEMENTED and built green.** This document is both the approved plan and a record of what was actually built. Written against the live source; where `CURRENT_ARCHITECTURE.md` and source disagree, source wins.

## User decisions (Q1–Q4) — as approved

| Q | Decision |
|---|---|
| Q1 Max size | **500 entries** |
| Q2 Clear button | **Yes, with confirmation dialog** |
| Q3 Filtering | **Deferred to v2** (`source`/`sourceId` still stored, so v2 needs no schema change) |
| Q4 Amount | **Option B — `SmsParser` untouched**; new code re-runs the same regex in one place |

## 1. Restatement

The app persisted exactly one payment artifact (`UPI_PREFS["last_sms"]` → `@id/view_sms_tv`). This added a durable, scrollable list of every accepted payment as a JSON-lines file at `context.getFilesDir()/payment_history.jsonl` (append-on-capture, capped at 500), via a new structured `PaymentEvent` + `PaymentHistoryStore` + `HistoryActivity` (RecyclerView, newest-first). The TTS pipeline and the 60-second in-memory dedupe were left byte-identical; history records events that speech may later suppress as duplicates.

## 2. NEW files (6, all under `app/src/main/`)

| Path | Purpose | Built? |
|---|---|---|
| `java/com/example/upipaymentalert/PaymentEvent.java` | Immutable POJO; nested `enum Source { SMS, NOTIFICATION }`; JSON (de)serialize via `org.json`; `capture()` re-runs the SmsParser amount regex. | ✅ |
| `java/com/example/upipaymentalert/PaymentHistoryStore.java` | Durable JSON-lines store: `append` (non-blocking, never throws), `readAll` (newest-first), `clear`, 500-cap with async compaction. | ✅ |
| `java/com/main/../java/com/example/upipaymentalert/HistoryActivity.java` | Hosts the history RecyclerView; clear-with-confirm; empty state. | ✅ |
| `java/com/example/upipaymentalert/PaymentHistoryAdapter.java` | One row per `PaymentEvent` (time, amount, source, body preview). | ✅ |
| `res/layout/activity_history.xml` | Title row + clear button, RecyclerView, empty-state TextView. | ✅ |
| `res/layout/item_payment_history.xml` | One history row. | ✅ |

No new Gradle dependency. `androidx.recyclerview:recyclerview:1.1.0` was already on `debugRuntimeClasspath` transitively via `material:1.11.0` (verified with `./gradlew :app:dependencies`).

## 3. MODIFIED files — precise change

| File | Change | Verified unchanged |
|---|---|---|
| `broadcastreciever/SmsListener.java` | 2 imports + one `try`-wrapped `PaymentEvent.capture` + `PaymentHistoryStore.append` block, inserted between the `last_sms` write and the TTS `try`. | Action guard, extraction, `textToDisplay`, forwarder, credit gate, `textToRead`, `last_sms` write, **entire TTS block**. |
| `NotificationListener.java` | 2 imports + one capture block, same position. `last_sms` value hoisted into `lastSmsDisplay` (character-identical, reused as `displayText`). | 7-pkg filter, extras extraction, `messageBody`, forwarder, credit gate, `textToRead`, **entire TTS block**. |
| `MainActivity.java` | `onCreate` only: `historyBtn` findViewById + `setOnClickListener` → `startActivity(HistoryActivity)`. | `onResume`, all `setup*`, permission methods, forwarder UI, `last_sms` rendering, TTS service start. |
| `AndroidManifest.xml` | +1 `<activity android:name=".HistoryActivity" android:exported="false" />`. | All permissions, app attrs, and the 4 existing components. |
| `res/layout/activity_main.xml` | +1 `<Button id=history_button>`; `upi_apps_tv` re-anchored to it. | Every other view/id/constraint. |
| `res/values/strings.xml` | +9 `<string>` entries. | All existing entries. |

## 4. NOT touched — safety guarantee

`ForegroundTtsService.java` (hard invariant 2 — verified still Jul 17, never opened for edit). Also untouched: `SmsParser.java` (Q4 Option B — verified still Jul 17), all Gradle/settings files, `proguard-rules.pro`, `colors.xml`/`themes.xml`/`values-night`, drawables/mipmaps, `src/test/**` and `src/androidTest/**` (pre-existing broken, out of scope), `docs/`, `README.md`, `CHANGELOG.md`.

## 5. `PaymentEvent` JSON schema

| Field | JSON | Java | Nullable | Source |
|---|---|---|---|---|
| `eventId` | string | `String` | no | `UUID.randomUUID()` at capture |
| `amountPaise` | number | `long` | no (`-1`=unknown) | regex (Option B) |
| `amountRaw` | string | `String` | no (`""`) | regex capture 1, e.g. `"125.50"` |
| `phrase` | string | `String` | no | `textToRead` (exactly the `EXTRA_TEXT`) |
| `source` | string | `Source` | no | `"SMS"`/`"NOTIFICATION"` |
| `sourceId` | string | `String` | no | sender address / package name |
| `rawBody` | string | `String` | no | parsed message text |
| `timestampMs` | number | `long` | no | `System.currentTimeMillis()` at capture |
| `displayText` | string | `String` | no | the exact `last_sms` value |

## 6. `PaymentHistoryStore` API

```java
public static final int MAX_ENTRIES = 500;
public static void append(Context, PaymentEvent);   // cheap, non-blocking, never throws
public static List<PaymentEvent> readAll(Context);   // newest-first, never throws (empty on error)
public static void clear(Context);                   // never throws
```

- **Thread-safety:** all three `synchronized` on one static lock (prevents interleaved writes). `append` does one `FileOutputStream(..., append=true)` open→write→close.
- **Error handling (invariant 5):** each method wraps its body in `try/catch(Exception)` → `Log.e`. `append` returns normally; `readAll` returns empty list, never null. `Exception` caught; `Error`/`Throwable` not (real OOM not masked).
- **Cap rotation:** a cheap `history_entry_count` in `UPI_PREFS`; when over cap, compaction runs **async on a background thread** (never on main). Best-effort: if compaction fails the file grows until the next success; data is never lost. Corrupt lines on read are skipped, not fatal.

## 7. Listener changes — before/after (TTS line identical)

**`SmsListener.onReceive` after** (inserted block; the TTS block below is byte-identical to before):
```java
            prefs.edit().putString("last_sms", textToDisplay).apply();

            // Payment history capture (fire-and-forget; cannot affect the TTS call below)
            try {
                PaymentEvent ev = PaymentEvent.capture(
                        PaymentEvent.Source.SMS, address, messageBody.toString(), textToRead, textToDisplay);
                PaymentHistoryStore.append(context, ev);
            } catch (Exception ignored) {
            }

            // Speak via the foreground TTS service (start or deliver intent)
            try {
                Intent svc = new Intent(context.getApplicationContext(), com.vivekray898.upipaymentalert.ForegroundTtsService.class);
                svc.setAction(com.vivekray898.upipaymentalert.ForegroundTtsService.ACTION_SPEAK);
                svc.putExtra(com.vivekray898.upipaymentalert.ForegroundTtsService.EXTRA_TEXT, textToRead);
                ...
            } catch (Exception ignored) { }
```

**`NotificationListener.onNotificationPosted` after** (same shape; `lastSmsDisplay` is character-identical to the old inline expression):
```java
        String lastSmsDisplay = "App: " + packageName + "\n\nBody: " + messageBody;
        prefs.edit().putString("last_sms", lastSmsDisplay).apply();

        // Payment history capture (fire-and-forget; cannot affect the TTS call below)
        try {
            PaymentEvent ev = PaymentEvent.capture(
                    PaymentEvent.Source.NOTIFICATION, packageName, messageBody, textToRead, lastSmsDisplay);
            PaymentHistoryStore.append(getApplicationContext(), ev);
        } catch (Exception ignored) { }
```

Capture sits **after** the credit gate and `last_sms` write, so only **accepted** payments are recorded.

## 8. HistoryActivity design

- **Layout:** title + clear `Button` (right) · `RecyclerView` (`layout_weight=1`) · empty-state `TextView` (gone when populated).
- **Row:** formatted time · amount (`₹ n` or "Unknown amount") · `SMS`/`App · sourceId` · `rawBody` (2 lines, ellipsized). Reuses existing `jigar_*` colors.
- **Adapter:** `RecyclerView.Adapter<VH>` with `submit(List)`. Display only; touches no storage.
- **Activity:** reads `PaymentHistoryStore.readAll` in `onResume`; clear button → `AlertDialog` confirm → `clear` + refresh + toast. No filtering (Q3).
- **Manifest:** `<activity android:name=".HistoryActivity" android:exported="false" />`.

## 9. Implementation order (as executed)

| Step | What | Live-path? |
|---|---|---|
| 1 | `PaymentEvent.java` | pure addition |
| 2 | `PaymentHistoryStore.java` | pure addition |
| 3 | layouts + strings | pure addition |
| 4 | `HistoryActivity` + adapter | pure addition |
| 5 | Manifest `HistoryActivity` | additive |
| 6 | `activity_main.xml` button | additive |
| 7 | **`MainActivity.onCreate`** | first live-path touch |
| 8 | **`SmsListener.onReceive`** | live-path |
| 9 | **`NotificationListener.onNotificationPosted`** | live-path |
| 10 | Build | — |

## 10. Verification checklist

- [x] `./gradlew assembleDebug` → **BUILD SUCCESSFUL** (exit 0)
- [x] `HistoryActivity` declared in the built manifest (aapt2 `xmltree`)
- [x] All 4 new classes present in the APK dex (`classes4.dex` / `classes5.dex`)
- [x] `ForegroundTtsService.java` untouched (mtime unchanged, Jul 17)
- [x] `SmsParser.java` untouched (mtime unchanged, Jul 17)
- [x] TTS `startForegroundService` blocks byte-identical in both listeners
- [x] `last_sms` format unchanged in both listeners (SMS inline; Notification hoisted to an identical string)
**On-device (emulator-5554, Android 34) — verified:**
- [x] Real SMS (`adb emu sms send 12345 "...credited with Rs 500.50"`) produced a correct record: `amountPaise=50050`, `amountRaw="500.50"`, `phrase="Received |500| rupees and |50| paisa"`, `source="SMS"`, `sourceId="12345"`.
- [x] `last_sms` format unchanged: `Address: 12345\n\nBody: ...`.
- [x] **Duplicate within 60s:** identical SMS 11s later -> TWO history rows (distinct `eventId`s) AND logcat `Duplicate announcement prevented: Received |500| rupees and |50| paisa`. Speech suppressed by the untouched dedupe; history recorded both.
- [x] History survives **process death** (`am force-stop`): 2 entries intact after relaunch.
- [x] **Cap enforcement:** seeded 500 lines + counter 500, then one real SMS -> file stayed at exactly **500** lines, exactly one oldest entry dropped, counter reset to 500, new entry parsed (`amountPaise=77700`).
- [x] `HistoryActivity` opens from the MainActivity button (tap-verified via uiautomator); renders newest-first with `₹ 500.50`, `SMS · 12345`, timestamps.
- [x] **Clear** shows confirm dialog ("Clear history?" / "This permanently deletes all recorded payment history. Continue?"); OK deletes the file, resets counter to 0, and shows the empty state.
- [x] No `PaymentHistoryStore` errors and no FATAL exceptions in logcat across the whole session.
- [x] **Off-by-one fix verified:** after moving the count resolution before the write, `history_entry_count` matches the file line count exactly across consecutive payments (previously `file=1, counter=2`).

**Not verified on-device:**
- [ ] **Notification channel capture.** `cmd notification post` posts as `com.android.shell`, which fails the 7-package filter, so a supported-app notification could not be injected. The notification path shares `PaymentEvent`/`PaymentHistoryStore` and differs only in constructing the event; its capture is exercised only in code review, not at runtime.
- [ ] Audible TTS output (emulator audio not verified); dedupe suppression is confirmed via logcat rather than by listening.

## 11. Risks / ambiguities

1. **Q4 Option B duplicates the regex** — `PaymentEvent` holds a copy of `SmsParser.AMOUNT_PATTERN` with a sync comment. If the parser's regex changes, the stored number can drift from the spoken phrase. Accepted trade-off; revisit Option A if it matters.
2. **Cap is best-effort, not strictly enforced** — async compaction can fail and the file stays over 500 until the next success. Chosen so the main thread is never charged for rotation (invariant 6).
3. **`history_entry_count` drift — FIXED.** An earlier revision resolved the entry count *after* writing the new line, so the `countLines()` fallback double-counted it whenever the stored counter was missing or stale (observed as `file=1, counter=2`). `append()` now resolves the count **before** the write. Verified on-device: counter matches file at every step (1/1, 2/2, 3/3). Impact before the fix was benign (compaction recomputes from real lines, so nothing was lost), but the cap could trigger early.
4. **Main-thread append latency is unmeasured** on real storage — a single small append wrapped so it can never break TTS, but not benchmarked.
5. **RecyclerView is transitive, not declared** — resolves to 1.1.0 today; a future dependency bump removing it would break `HistoryActivity`.
6. **Q4 edge case:** `Rs 0` yields `amountPaise = 0` in the number path while the parser's phrase omits zero components — accepted divergence.

## 12. Future online-sync reuse (deferred, per task)

A sync layer can later read `PaymentEvent` off disk (`readAll`) or observe the same event at the capture point, reusing this exact type and the same durable-store pattern — **without touching `SmsParser` or `ForegroundTtsService`**. No network code was added in this task.