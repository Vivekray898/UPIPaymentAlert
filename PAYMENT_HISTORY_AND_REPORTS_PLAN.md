# Payment History & Reports — Implementation Plan

> **Status: approved and implemented.** Written against live source (commit `cb10bd7`). Source wins over `CURRENT_ARCHITECTURE.md`.

## Q1–Q8 decisions (LOCKED)

| Q | Decision | Consequence |
|---|---|---|
| Q1 Cap | **2000** | `MAX_ENTRIES` 500 → 2000 |
| Q2 Clear | **Keep, with confirm** | no change; already existed |
| Q3 Filtering | **Deferred to phase 2** | no filter UI |
| Q4 Amount | **Option A** | `SmsParser` refactor (explicitly approved) |
| Q5 Week start | **Monday** | `Calendar.setFirstDayOfWeek(MONDAY)` |
| Q6 Duplicates | **Include in totals** | every accepted event counts |
| Q7 MainActivity Today line | **No** | home screen untouched entirely |
| Q8 Timezone | **Read-time device TZ** | no schema change, no migration |

## Scoping finding (why this is a delta, not greenfield)

`PaymentEvent`, `PaymentHistoryStore`, `HistoryActivity`, `PaymentHistoryAdapter`, both layouts, the MainActivity `history_button` and the `HistoryActivity` manifest entry **already existed and were committed** in `cb10bd7` (emulator-verified). This task therefore adds: `PaymentStats`, day grouping, two adapter view types, the stats header, off-main-thread reads, the cap bump, and the Q4 refactor.

---

## 1. Restatement

The app already recorded every accepted payment durably but showed them as one flat, undifferentiated newest-first list with no sense of scale. This makes that history legible and quantified, Google-Pay style: a pinned summary showing Today / This week / This month totals (plus an explicit count of payments whose amount could not be parsed), above a list grouped under day headers ("Today", "Yesterday", "12 Oct"). Because the day boundary is local midnight and all aggregation is recomputed from scratch on every read, buckets roll over naturally with the clock — no alarm, no boot receiver, no background service. The structured `PaymentEvent` and durable JSON-lines store are preserved exactly so a later online-sync feature can reuse both unchanged.

## 2. NEW files (2)

| Path | Purpose |
|---|---|
| `java/com/example/upipaymentalert/PaymentStats.java` | Pure aggregation: bucket boundaries via `java.util.Calendar` plus totals/unknown-counts. No UI deps; unit-testable later. |
| `res/layout/item_history_day_header.xml` | Day-group header row ("TODAY", "YESTERDAY", "12 OCT 2026"). |

## 3. MODIFIED files

| File | Change | Stays unchanged |
|---|---|---|
| `smsparser/SmsParser.java` | **Q4 Option A:** added nested `AmountResult` + `public AmountResult extractAmount(String)`; `getAmountFromMessageBody` now delegates to it. | Public signature `getAmountFromMessageBody(String,String)`; its exact output strings; `isCreditTransaction`; `isDebitTransaction`; `AMOUNT_PATTERN` itself. |
| `PaymentEvent.java` | Dropped the duplicated `AMOUNT_PATTERN`/`toPaise`; `capture(...)` gained an overload accepting pre-computed `amountPaise`/`amountRaw`. | All 9 field names/types; `enum Source`; `toJson`/`fromJson` wire format; all getters. |
| `PaymentHistoryStore.java` | `MAX_ENTRIES` 500 → **2000**. | Every method body, locking, error swallowing, compaction strategy. |
| `HistoryActivity.java` | `onResume` → background `Thread` doing `readAll` + `PaymentStats.compute` + day grouping, posted back via `runOnUiThread` with a generation guard. | `onCreate` wiring, view lookups, `confirmClear()` semantics. |
| `PaymentHistoryAdapter.java` | Two view types (`TYPE_DAY_HEADER`, `TYPE_PAYMENT`) over a flattened `List<Object>`; payment row uses 12-hour time. | Amount formatting incl. "Unknown amount"; source label. |
| `broadcastreciever/SmsListener.java` | Passes the parser-derived amount into `capture(...)`. | **Entire `startForegroundService` block — byte-identical.** Credit gate, `last_sms` write, forwarder. |
| `NotificationListener.java` | Same. | **Entire `startForegroundService` block — byte-identical.** 7-package filter, `lastSmsDisplay`, forwarder. |
| `res/layout/activity_history.xml` | Inserted **pinned** stats block above the RecyclerView. | Title row, clear button, empty view, RecyclerView. |
| `res/values/strings.xml` | New strings. | All existing strings. |

**Explicitly NOT modified:** `MainActivity.java` (Q7 = no), `AndroidManifest.xml` (entry already existed), **`ForegroundTtsService.java`**, `build.gradle`, `settings.gradle`, `gradle.properties`, `colors.xml`, `themes.xml`, `src/test/**`, `src/androidTest/**`.

## 4. NOT touched — safety guarantee

**`ForegroundTtsService.java` was never opened for edit** — the 60-second `ConcurrentHashMap` dedupe and the TTS engine remain untouched. Also untouched: `build.gradle` (no new dependency — RecyclerView 1.1.0 already resolves transitively via `material:1.11.0`), `settings.gradle`, `gradle.properties`, `AndroidManifest.xml`, `MainActivity.java`, `colors.xml`, `themes.xml`, `values-night/themes.xml`, all drawables/mipmaps, and the pre-existing broken test tree.

## 5. `PaymentEvent` JSON schema (UNCHANGED — no migration)

| Field | JSON | Java | Null | Source |
|---|---|---|---|---|
| `eventId` | string | `String` | no | `UUID.randomUUID()` |
| `amountPaise` | number | `long` | no (`-1`=unknown) | `SmsParser.extractAmount` |
| `amountRaw` | string | `String` | no (`""`) | regex capture group 1 |
| `phrase` | string | `String` | no | `textToRead` (exact `EXTRA_TEXT`) |
| `source` | string | `Source` | no | `"SMS"` / `"NOTIFICATION"` |
| `sourceId` | string | `String` | no | sender address / package name |
| `rawBody` | string | `String` | no | parsed message text |
| `timestampMs` | number | `long` | no | capture wall-clock |
| `displayText` | string | `String` | no | exact `last_sms` value |

```json
{"eventId":"3f2a9c10-5b6e-4d21-9a77-1c8e4f0b2d55","amountPaise":12550,"amountRaw":"125.50","phrase":"Received |125| rupees and |50| paisa","source":"SMS","sourceId":"+91XXXXXX123","rawBody":"INR 125.50 received in your account","timestampMs":1767225600000,"displayText":"Address: +91XXXXXX123\n\nBody: INR 125.50 received in your account"}
```
Q8 = read-time TZ means **no `tzOffset` field**; existing history files stay valid with no migration.

## 6. `PaymentHistoryStore` API (only the constant changed)

```java
public static final int MAX_ENTRIES = 2000;          // was 500
public static void append(Context, PaymentEvent);    // main thread, cheap, never throws
public static List<PaymentEvent> readAll(Context);    // newest-first, never throws, empty on error
public static void clear(Context);                    // never throws
```
- **Thread-safety:** all three `synchronized` on one private static `LOCK`, preventing interleaved writes. `append` = one `FileOutputStream(..., true)` open→write→close; the entry count is resolved **before** the write (preserving the earlier off-by-one fix).
- **Error handling:** each wraps its body in `try/catch(Exception)` → `Log.e`; `readAll` returns an empty list, never null; a corrupt line is skipped, not fatal. `Exception` is caught, `Error` is not.
- **Rotation:** a cheap `UPI_PREFS["history_entry_count"]` counter; when over cap, compaction runs **asynchronously** on a background thread so the main thread is never charged for rotation. Best-effort — a failed compaction leaves the file over cap until the next success; no data is ever lost.

## 7. `PaymentStats` API + exact Calendar code

```java
public final class PaymentStats {
    public static final class Result {
        public final long todayTotalPaise, weekTotalPaise, monthTotalPaise;
        public final int  todayUnknownCount, weekUnknownCount, monthUnknownCount;
    }
    public static Result compute(List<PaymentEvent> events, long nowMs, TimeZone tz);
    public static long startOfToday(long nowMs, TimeZone tz);
    public static long startOfWeek(long nowMs, TimeZone tz);   // Monday (Q5)
    public static long startOfMonth(long nowMs, TimeZone tz);
}
```
`java.util.Calendar` only — **no `java.time`** (minSdk 24, no desugaring).

```java
private static long midnight(Calendar base, TimeZone tz) {
    base.setTimeZone(tz);
    base.set(Calendar.HOUR_OF_DAY, 0);
    base.set(Calendar.MINUTE, 0);
    base.set(Calendar.SECOND, 0);
    base.set(Calendar.MILLISECOND, 0);
    return base.getTimeInMillis();
}

public static long startOfWeek(long nowMs, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz);
    c.setTimeInMillis(nowMs);
    c.setFirstDayOfWeek(Calendar.MONDAY);          // Q5
    Calendar monday = (Calendar) c.clone();
    monday.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
    return midnight(monday, tz);
}
```
`compute` does a single pass: `amountPaise >= 0` adds to window totals, `< 0` increments the matching unknown counter. **Totals include events speech deduped** (Q6). No totals are stored — always recomputed.

## 8. Day-grouping logic

Input is already **newest-first**. A group opens whenever the local calendar day changes:

```java
long d = midnight(calendarFor(e.getTimestampMs()), tz);
if (d != lastDayStart) {
    label = (d == todayStart) ? "Today"
           : (d == midnightOf(shiftDays(nowMs, -1))) ? "Yesterday"
           : new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(e.getTimestampMs()));
    out.add(header(label));
    lastDayStart = d;
}
out.add(e);
```
"Today"/"Yesterday" compare **midnight timestamps**, not 24h subtraction, so DST and month boundaries are handled correctly.

## 9. Listener change — TTS line unchanged

Only the `capture(...)` arguments changed; everything from `// Speak via…` down is byte-identical in both listeners:

```java
try {
    SmsParser.AmountResult amt = smsParser.extractAmount(messageBody.toString());
    PaymentEvent ev = PaymentEvent.capture(
            PaymentEvent.Source.SMS, address, messageBody.toString(), textToRead, textToDisplay,
            amt.paise, amt.raw);
    PaymentHistoryStore.append(context, ev);
} catch (Exception ignored) {
}
```

## 10. Q4 Option A — `SmsParser` change

```java
public static final class AmountResult {
    public final long paise;      // -1 if not found
    public final String raw;      // null if not found
    public final boolean found;
    AmountResult(long paise, String raw) {
        this.paise = paise; this.raw = raw; this.found = paise >= 0;
    }
}

public AmountResult extractAmount(String body) {
    if (body == null || body.isEmpty()) return new AmountResult(-1L, null);
    Matcher matcher = AMOUNT_PATTERN.matcher(body);
    if (!matcher.find()) return new AmountResult(-1L, null);
    String rawNoComma = matcher.group(1).replace(",", "");
    String[] parts = rawNoComma.split("\\.", 2);
    long r = 0, p = 0;
    try { r = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) {}
    try { if (parts.length > 1) p = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
    return new AmountResult(r * 100 + p, rawNoComma);
}
```
`getAmountFromMessageBody` derives its `rupees`/`paisa` strings from `extractAmount` without altering any branch, string literal, or ordering.

**Byte-identical phrase argument:** the original already did (a) null/empty → defaultMsg, (b) `matcher.find()` on the *same* `AMOUNT_PATTERN` against the *same* body, (c) first match only, (d) `group(1).replace(",","")`, (e) split on `.` limit 2, (f) `parseInt` each with `NumberFormatException` ignored → 0. `extractAmount` reproduces (a)–(f) exactly; the phrase branches then consume the same `rupees`/`paisa` strings verbatim. Confirmed empirically — see §14.

## 11. `HistoryActivity` design

Pinned stats block sits **outside** the RecyclerView so it never scrolls; the RecyclerView carries two view types (day header + payment row). Payment rows use `SimpleDateFormat("h:mm a")`.

```java
private int loadGeneration = 0;

@Override protected void onResume() {
    super.onResume();
    final int gen = ++loadGeneration;                 // invalidate older loads
    new Thread(() -> {
        List<PaymentEvent> all  = PaymentHistoryStore.readAll(this);
        PaymentStats.Result st  = PaymentStats.compute(all, System.currentTimeMillis(),
                                                       TimeZone.getDefault());
        List<Object> rows       = buildGroupedRows(all, System.currentTimeMillis(), TimeZone.getDefault());
        runOnUiThread(() -> {
            if (gen != loadGeneration) return;        // stale → drop
            adapter.submit(rows);
            bindStats(st);
        });
    }, "history-load").start();
}
```
Read + parse + aggregate stays off the main thread; only the small UI bind is posted back. `MainActivity` untouched (Q7).

## 12. AndroidManifest addition

**None.** `<activity android:name=".HistoryActivity" android:exported="false" />` already existed from `cb10bd7`. No permission added or removed.

## 13. Implementation order

| # | Step | Classification |
|---|---|---|
| 1 | `PAYMENT_HISTORY_AND_REPORTS_PLAN.md` | doc only |
| 2 | `PaymentStats.java` | pure addition |
| 3 | `item_history_day_header.xml` | pure addition |
| 4 | `SmsParser` Q4 refactor | core file |
| 5 | `PaymentEvent.capture` overload | pure addition |
| 6 | `MAX_ENTRIES` → 2000 | constant only |
| 7 | `strings.xml` additions | pure addition |
| 8 | `activity_history.xml` stats block | additive UI |
| 9 | adapter two view types | pure addition |
| 10 | `HistoryActivity` background load | additive UI |
| 11 | `SmsListener` | **live path** |
| 12 | `NotificationListener` | **live path** |
| 13 | Build + verify | — |

## 14. Verification results

**Static / build:**
- [x] `./gradlew assembleDebug` → BUILD SUCCESSFUL (exit 0)
- [x] `ForegroundTtsService.java` untouched; TTS call sites byte-identical; `last_sms` format unchanged
- [x] No new Gradle dependency; no `java.time`; no network/Room/WorkManager/coroutines
- [x] `MAX_ENTRIES` == 2000

**Phrase regression (the Q4 safety net)** — the pre-refactor build and the refactored build were both driven with the *same three real SMS*, and the resulting history lines compared field by field:
- [x] `"Credited Rs 500.50 to your account"` → phrase `"Received |500| rupees and |50| paisa"` — **identical**; `amountPaise` 50050 identical; `amountRaw` `"500.50"` identical
- [x] `"You received ₹1,250 today"` → phrase `"Received |1250| rupees"` — **identical**; `amountPaise` 125000 identical
- [x] `"Payment received with no amount here"` → phrase `"Received payment of an unknown amount"` — **identical**; `amountPaise` `-1` identical
- [x] `"INR 99 debited from your account"` → correctly **rejected** by the credit gate, not recorded, same as before
- [~] **One intentional difference:** `amountRaw` for `"1,250"` is now `"1250"` (comma stripped) rather than `"1,250"`. `extractAmount` stores group(1) *after* `.replace(",", "")` ([SmsParser.java:64](app/src/main/java/com/example/upipaymentalert/smsparser/SmsParser.java#L64)) — the same normalised string the parse itself uses. `amountPaise` and the spoken phrase are unchanged, the list renders from `amountPaise`, and `amountRaw` is never spoken or displayed, so this is a storage-format normalisation, not a behaviour change.

**On-device (emulator-5554, Android 34):**
- [x] Header totals equal the sum of visible rows, excluding unknown-amount rows: Today `₹1,905` = 500.50 + 1250 + 77.25×2.
- [x] Duplicates are counted in totals (Q6) — the second ₹77.25 row is included, while speech was suppressed.
- [x] Unknown-amount payments appear in the list, are excluded from the total, and produce the note `(1 with unknown amount)`.
- [x] Day grouping: headers rendered `TODAY` / `YESTERDAY` / `04 OCT`, newest-first, with 24-hour-safe day boundaries.
- [x] Week bucket is Monday-anchored and genuinely excludes the 04 OCT row (`This week ₹2,050.50`) while `This month ₹15,146.42` includes it.
- [x] History survives `am force-stop` (process death) and is re-read on `onResume`.
- [x] Duplicate within 60 s → spoken once (logcat `Duplicate announcement prevented: Received |77| rupees and |25| paisa`), recorded **twice** with distinct `eventId`s.
- [x] **Cap at 2000:** file seeded to 2001 lines with a matching counter, then one real payment → compacted to exactly **2000**; oldest entry dropped, newest present, counter reset to 2000.

**Bucket boundaries (`PaymentStats`, midnight rollover)** — the emulator image is a production build with no root, so the system clock could not be moved (`date: Operation not permitted`). Instead the **real, unmodified `PaymentStats.java`** (sha256-verified identical to the repo copy) was compiled and run in a JVM harness against the captured event list with a synthetic `now`:

| Case | Today | Week | Month | unknown T/W/M | Result |
|---|---|---|---|---|---|
| now = 06 Oct 01:31 (before midnight) | 190500 | 220500 | 1530092 | 1/1/1 | PASS |
| now = 07 Oct 00:30 (**after midnight**) | **0** | 220500 | 1530092 | **0**/1/1 | PASS |
| now = 12 Oct 00:30 (next Monday) | 0 | **0** | 1530092 | 0/0/1 | PASS |
| now = 06 Nov 00:30 (next month) | 0 | 0 | **0** | 0/0/0 | PASS |

- [x] "Today" resets to zero past local midnight, and its unknown-count resets too — recomputation on read, no alarm/boot receiver.
- [~] The *device clock* was never actually moved; rollover is proven at the aggregation level, not by a live clock change.

**Not verified:**
- [ ] Notification-channel capture (`cmd notification post` posts as `com.android.shell`, which cannot impersonate a supported package) — code-reviewed only.
- [ ] Audible TTS output; dedupe suppression is confirmed via logcat, not by listening.

## 15. Risks, ambiguities, uncertainties — stated plainly

1. **Q4 touched a core file** on the TTS live path. Mitigated by the byte-compare regression check in §14.
2. **`extractAmount` runs a second regex match** per event (the phrase call already matched). Microseconds; accepted for a single source of truth.
3. **Cap is best-effort**, not strictly enforced; a failed compaction leaves the file over cap until the next success. Additionally the trigger trusts the `history_entry_count` preference rather than re-counting the file (re-counting would mean an O(n) read on the main thread). In production the two cannot diverge — only `append` writes the file and it updates the pref on every append — but if the pref were ever lost or stale the file would keep growing instead of merely overshooting. Not fixed here: a correct fix changes live-path behaviour and was outside the approved plan.
4. **Q8 read-time timezone:** travel or a timezone change re-buckets *past* payments. Accepted for v1.
5. **`notifyDataSetChanged()`** on a 2000-row adapter is coarse; smooth scrolling at 2000 rows is untested. `DiffUtil` is already on the classpath if needed later. (The read + parse is off the main thread, so the cap does not affect listener latency.)
6. **Background read can outlive the Activity.** Guarded by a `generation` counter (a superseded result is dropped) plus an `isFinishing()`/`isDestroyed()` check before binding; the single-thread executor is shut down in `onDestroy`.
7. **Cap change is not retroactive** — entries already rotated out at 500 are not restored.
8. **Monday week start** means a Sunday-night payment belongs to the week that began the previous Monday.
9. **Unknown amounts are excluded from totals**, so a bank format change would silently under-report; the "(N with unknown amount)" note must never be hidden when N > 0.
10. **Notification-channel capture remains code-reviewed only.**

## 16. Q1–Q8 — all answered, none outstanding

Cap **2000**; keep clear-with-confirm; filtering **deferred**; **Option A** (explicitly approved); week starts **Monday**; duplicates **included**; **no** MainActivity Today line; **read-time** device timezone.