# UPIPaymentAlert — Current Architecture (Technical Baseline)

> **This document describes the implementation inspected in the repository at the time it was generated. It is a technical baseline for future modifications. When this document conflicts with source code, the source code is authoritative.**

## Document metadata

| Field | Value |
| --- | --- |
| Generation date | 2026-10-05 |
| Repository | `official-arvind/UPIPaymentAlert` |
| Git branch | **Not available** — this workspace has no `.git` directory (`git` reports "not a git repository"). |
| Git commit SHA | **Not available** — same reason as above. |
| Android SDK | compileSdk 34, targetSdk 34, minSdk 24 |
| Gradle | 8.7 (distribution `gradle-8.7-bin.zip`) |
| Android Gradle Plugin | 8.3.0 |
| Kotlin | **Not used.** The entire app is Java. No Kotlin plugin, no `.kt` files. |
| Language / toolchain | `sourceCompatibility` / `targetCompatibility` = Java 17 |
| Gradle runtime JDK | Java 21 (Eclipse Temurin 21.0.12.1). Gradle 8.7 supports JVM 8–21 and rejects the machine default Java 25. |
| Build status at generation | `./gradlew assembleDebug` → BUILD SUCCESSFUL; `./gradlew test` → **BUILD FAILED** (test source does not compile — see §14 / §23). |

This document is a record of **what exists**, not a redesign. Where a section of the requested outline does not apply to the actual code, that is stated explicitly rather than filled with invention.

---

## 1. Repository overview

### 1.1 Identity

- **Application ID / package:** `com.example.upipaymentalert`
- **Module name / root project name:** `UPIPaymentAlert` (single module `:app`)
- **App label:** `UPIPaymentAlert` (`@string/app_name`)
- **Version:** `versionCode 3`, `versionName "v2.0 Gold Edition"`
- **Language:** Java only. No Kotlin.

> Note: the two sample test files live under `com.example.paymentalert` (missing the `upi` segment), a stale package name that does not match the production code (`com.example.upipaymentalert`). This is cosmetic and does not affect the APK.

### 1.2 Gradle / toolchain

- **Gradle:** 8.7
- **Android Gradle Plugin:** `com.android.tools.build:gradle:8.3.0`
- **compileSdk:** 34 · **targetSdk:** 34 · **minSdk:** 24
- **Java source/target compatibility:** `VERSION_17`
- **No Kotlin**, **no Room**, **no DataStore**, **no Coroutines**, **no WorkManager**, **no OkHttp/Retrofit**, **no junit test dependency**.

`gradle.properties` (project): `android.useAndroidX=true`, `android.enableJetifier=true`, `org.gradle.parallel=true`, `org.gradle.caching=true`, plus JVM args (`-Xmx4096m` and `--add-opens` flags). The machine-local Gradle JVM pin (`org.gradle.java.home`) lives **outside** the repo, in `~/.gradle/gradle.properties`.

### 1.3 Directory / source tree

Only files that actually exist are listed.

```text
UPIPaymentAlert/
├── build.gradle                     # Root: buildscript (AGP 8.3.0), allprojects repos
├── settings.gradle                  # include ':app'; rootProject.name = "UPIPaymentAlert"
├── gradle.properties                # AndroidX, Jetifier, parallel, caching, JVM args
├── gradlew / gradlew.bat            # Gradle wrapper
├── gradle/wrapper/gradle-wrapper.properties   # distributionUrl = gradle-8.7-bin.zip
├── local.properties                 # sdk.dir (machine-specific, gitignored)
├── app/
│   ├── build.gradle                 # namespace, SDK levels, compileOptions, dependencies
│   ├── proguard-rules.pro           # empty/boilerplate (minifyEnabled=false anyway)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/upipaymentalert/
│       │   │   ├── MainActivity.java
│       │   │   ├── ForegroundTtsService.java
│       │   │   ├── NotificationListener.java
│       │   │   ├── broadcastreciever/
│       │   │   │   └── SmsListener.java
│       │   │   └── smsparser/
│       │   │       └── SmsParser.java
│       │   └── res/
│       │       ├── layout/activity_main.xml
│       │       ├── values/strings.xml
│       │       ├── values/colors.xml
│       │       ├── values/themes.xml
│       │       ├── values-night/themes.xml
│       │       ├── drawable/ (ic_logo, logo, ic_launcher_background)
│       │       └── mipmap-*/ (launcher icons)
│       ├── test/java/com/example/upipaymentalert/smsparser/
│       │   └── SmsParserTest.java
│       ├── test/java/com/example/paymentalert/
│       │   └── ExampleUnitTest.java          (stale package; boilerplate)
│       └── androidTest/java/com/example/paymentalert/
│           └── ExampleInstrumentedTest.java  (stale package; boilerplate)
├── docs/                            # Static GitHub-Pages website (index.html, app.js, etc.) — NOT part of the Android app
├── .github/                         # Workflow files (CI, if any)
├── .gradle/, .idea/                 # Local/IDE state
└── README.md, CHANGELOG.md
```

There is **no** custom `Application` subclass (the `<application>` tag in the manifest has no `android:name`). The process does no app-level initialization beyond the framework.

### 1.4 Components (from `AndroidManifest.xml`)

| Kind | Class | Exported | Notes |
| --- | --- | --- | --- |
| Activity | `.MainActivity` | `true` | `LAUNCHER`. The only UI entry point. |
| Foreground service | `.ForegroundTtsService` | `false` | `foregroundServiceType="specialUse"`, subtype `TTS Announcement`. TTS engine + dedupe. |
| Listener service | `.NotificationListener` | `true` | `BIND_NOTIFICATION_LISTENER_SERVICE`; intent-filter `android.service.notification.NotificationListenerService`. |
| BroadcastReceiver | `.broadcastreciever.SmsListener` | `true` | intent-filter `android.provider.Telephony.SMS_RECEIVED`, `priority="1000"`. |

### 1.5 Permissions declared

| Permission | Purpose in this codebase |
| --- | --- |
| `READ_SMS` | Read SMS content (used by the SMS forwarder / SMS handling). |
| `RECEIVE_SMS` | Receive the `SMS_RECEIVED` broadcast. |
| `SEND_SMS` | The "SMS forwarder" feature sends a text to a user-configured number (`SmsManager.sendTextMessage`). |
| `QUERY_ALL_PACKAGES` | `MainActivity` uses `PackageManager.queryIntentActivities` to list installed UPI apps. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to post the service notification. |
| `FOREGROUND_SERVICE` | Run `ForegroundTtsService` as a foreground service. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ requirement for `foregroundServiceType="specialUse"`. |
| `WRITE_SETTINGS` | Declared but **not exercised** by any code path found in this repo (volume override uses `AudioManager.setStreamVolume`, which does not require it). Possibly vestigial. |
| `ACCESS_FINE_LOCATION` | Declared but **not exercised** by any code path found in this repo. Possibly vestigial. |

---

## 2. Application startup flow

There is no `Application` subclass, so there is no custom process bootstrap. Startup is:

1. **`MainActivity.onCreate(Bundle)`** — the only entry point on normal launch.
   - `setContentView(R.layout.activity_main)`.
   - `prefs = getSharedPreferences("UPI_PREFS", MODE_PRIVATE)`.
   - **Starts `ForegroundTtsService` immediately** (`startForegroundService` on `API>=O`, else `startService`). This is what keeps the TTS/de duplication window alive and keeps the TTS instance warm.
   - Binds views: `permissionStatusTv`.
   - `setupLanguageSpinner()`, `setupVolumeSlider()`, `setupSpeedSlider()`, `setupUpiAppsSpinner()`, `setupTestButtons()`, `setupSmsForwarder()`.
   - Wires the "grant permissions" button to `requestAllPermissions()`.
   - `checkPermissionsStatus()` — initial permission readout.

   **Signature:** `void onCreate(Bundle)` · **Caller:** framework · **Called:** `setContentView`, the setup* methods, `checkPermissionsStatus()`.
   **Persistent state read:** `UPI_PREFS` (`language`, `volume`, `speed_progress`, `forwarder_number`, `forwarder_app_filter`, `forwarder_type_filter`).
   **Side effects:** starts a foreground service; registers all UI listeners.

2. **`MainActivity.onResume()`** — re-runs `checkPermissionsStatus()` and, if the `last_sms` pref is non-empty, renders it into `@id/view_sms_tv`. Called on every resume.

3. **`ForegroundTtsService.onCreate()`** — `createNotificationChannel()`, then `startForeground(12345, n)` with a persistent notification, then constructs `new TextToSpeech(...)`. On init callback it sets `Locale.getDefault()` and installs the utterance-progress listener. This service is process-lifetime once started.

After this, the UI is available and the two passive channels (SMS receiver, notification listener) are "armed" purely by virtue of the manifest — no in-process registration is required for them; the OS dispatches them independently of `MainActivity`.

> Startup does **not** load any configuration file. All configuration lives in `SharedPreferences("UPI_PREFS")`. No analytics, no crash-reporting, no remote config, no network call occurs at startup.

---

## 3. Complete payment detection pipeline

There is no single "pipeline" object. The flow is: **two independent entry points → shared keyword gate → shared text parser → shared foreground TTS service (with an in-memory dedupe map).** Both channels hand off the same-shaped `Intent` to `ForegroundTtsService`, so deduplication is shared across channels.

### 3.1 SMS channel (end to end)

```text
Telephony.SMS_RECEIVED broadcast (OS)
   ↓
SmsListener.onReceive(Context, Intent)                 [broadcastreciever/SmsListener.java]
   ├── Telephony.Sms.Intents.getMessagesFromIntent(intent) → SmsMessage[]
   ├── per message: address = getOriginatingAddress()   (last part wins)
   │                body  += getMessageBody()            (concatenated)
   ├── textToDisplay = "Address: <addr>\n\nBody: <body>"
   ├── read SharedPreferences "UPI_PREFS"
   ├── FORWARDER: if forwarder_number != ""
   │      type ∈ {Credited,Debited,Both} via SmsParser.isCreditTransaction / isDebitTransaction
   │      if match → SmsManager.sendTextMessage(forwardNumber, "FWD SMS: " + body)
   ├── GATE: if !SmsParser.isCreditTransaction(body) → return  (no announcement)
   ├── textToRead = SmsParser.getAmountFromMessageBody(body, language)
   ├── prefs.put("last_sms", textToDisplay)             [display-only]
   └── startForegroundService(ForegroundTtsService, ACTION_SPEAK, EXTRA_TEXT=textToRead)
                                                            ↓
                                                (see 3.3 shared tail)
```

**Key classes/methods (SMS):**

| Class | Method | Input | Output | What it does | Caller |
| --- | --- | --- | --- | --- | --- |
| `SmsListener` | `onReceive(Context, Intent)` | broadcast `Context`, `Intent` | `void` | Extracts sender + body, runs forwarder, credit gate, parses, fires TTS service. | OS |

Threading: `onReceive` runs on the process main thread (broadcast callbacks); the SMS work is all synchronous. Nothing is queued or backgrounded here.

### 3.2 Notification channel (end to end)

```text
System delivers StatusBarNotification
   ↓
NotificationListener.onNotificationPosted(StatusBarNotification sbn)   [NotificationListener.java]
   ├── packageName = sbn.getPackageName()
   ├── isUpiApp = packageName contains one of 7 hardcoded packages
   │      else → return
   ├── notification = sbn.getNotification(); if null → return
   ├── title = extras.EXTRA_TITLE; text = extras.EXTRA_TEXT
   ├── messageBody = title + " " + text
   ├── read SharedPreferences "UPI_PREFS"
   ├── FORWARDER: if forwarder_number != "" AND
   │      (forwarder_app_filter == "all" OR == packageName) AND type filter matches
   │      → SmsManager.sendTextMessage(forwardNumber, "FWD NOTIF: " + messageBody)
   ├── GATE: if !SmsParser.isCreditTransaction(messageBody) → return
   ├── textToRead = SmsParser.getAmountFromMessageBody(messageBody, language)
   ├── prefs.put("last_sms", "App: <pkg>\n\nBody: " + messageBody)
   └── startForegroundService(ForegroundTtsService, ACTION_SPEAK, EXTRA_TEXT=textToRead)
                                                            ↓
                                                (see 3.3 shared tail)
```

Note: `onNotificationPosted` runs on the main thread of the (already alive) listener-service process. All steps are synchronous.

### 3.3 Shared tail — ForegroundTtsService (dedupe + announce)

```text
ForegroundTtsService.onStartCommand(Intent, flags, startId)
   └── if action == ACTION_SPEAK:
          text = intent.getStringExtra(EXTRA_TEXT)
          if text != null && !text.isEmpty():
              handleSpeakRequest(text)
              └── System.currentTimeMillis()
              ├── purge recentAnnouncements entries older than 60_000 ms
              ├── if recentAnnouncements.containsKey(text) → Log.d "Duplicate…" ; return   [DEDUPE HIT]
              ├── recentAnnouncements.put(text, now)
              └── speak(text)   [see §8]
   return START_STICKY
```

**Async/threading:** `startOn
Command` is delivered on the service main thread; `handleSpeakRequest` and `speak` run there. TTS utterances are enqueued on the platform TTS engine (backgrounded by the engine itself), not on a custom thread.

---

## 4. SMS handling

### 4.1 Receiver
- **Class:** `com.example.upipaymentalert.broadcastreciever.SmsListener extends BroadcastReceiver`
- **Manifest:** `<receiver ... android:exported="true">` with intent-filter `android.provider.Telephony.SMS_RECEIVED`, `android:priority="1000"`.
- **Action handled:** exactly `Telephony.Sms.Intents.SMS_RECEIVED_ACTION` (the code first checks `intent.getAction()` equals that constant; any other action is ignored).
- **Permissions required:** `RECEIVE_SMS` (to receive), `READ_SMS` (content), `SEND_SMS` (forwarder only).

### 4.2 SMS extraction
- `Telephony.Sms.Intents.getMessagesFromIntent(intent)` → `SmsMessage[]`.
- Loop:
  - `address = smsMessage.getOriginatingAddress()` — **overwritten each iteration**, so if a multipart message yields multiple `SmsMessage`s in one intent, the *last* part's sender is kept.
  - `messageBody.append(smsMessage.getMessageBody())` — **concatenated** with no separator.
- **Multipart handling (static finding):** The code relies on the OS delivering all parts in a single `SMS_RECEIVED` intent. Android does not guarantee a long SMS arrives as one intent; parts can arrive as separate broadcasts. There is **no** multipart reassembly (no `Telephony.SMS_RECEIVED` part-index bookkeeping / accumulation across broadcasts) in the receiver. (Correct handling of a long SMS split across multiple `SMS_RECEIVED` broadcasts **cannot be determined** from static source inspection and is not guaranteed by the current code.)

- **Sender extraction:** `getOriginatingAddress()` only; it is used solely to build the `last_sms` display string. It is **not** used for matching or routing.

### 4.3 Normalization / filtering
- There is **no explicit normalization** (no case-folding for matching, no whitespace collapsing). The parser lower-cases the body internally for keyword checks, but the raw body is what the amount regex sees.
- **Filtering / gating:** `SmsParser.isCreditTransaction(body)`. Only credit-like messages proceed to announcement; everything else returns early. Debit/failed/restricted keywords actively *reject* a message.

### 4.4 Parser selection & matching
- There is exactly **one** parser: `com.example.upipaymentalert.smsparser.SmsParser`.
- **No bank-specific parsers, no per-sender routing, no per-amount-
format classification. Whether a message is "supported" is decided entirely by (a) the credit keyword gate and (b) the currency regex finding a number.

### 4.5 Field extraction (what actually exists)
- **Amount extraction:** `SmsParser.getAmountFromMessageBody(body, language)`.
  - Regex (case-insensitive): matches a number immediately preceded by one of `RS`, `INR`, `MRP`, or `₹` (with an optional `.` and optional whitespace). Captured form: `(\d+(?:,\d{3})*(?:\.\d{1,2})?)` — integer with optional thousands separators and optional 1–2 decimal places (paise).
  - First match only (`matcher.find()`).
  - Commas stripped; split on `.` into rupees / paise.
  - Produces a **spoken phrase** (see §6), not a numeric field.
- **Sender / payer extraction:** **None.** The originating SMS address is captured only for display. No payer name is extracted from the body.
- **Transaction / reference ID extraction:** **None.**
- **Date / time extraction:** **None.**
- **Bank / provider detection (SMS):** **None.** The SMS path is bank-agnostic; it does not inspect the sender to decide a bank.

### 4.6 Success / failure / malformed / duplicate
- **Announce:** `isCreditTransaction(body)` is true **and** the amount regex finds a match.
- **Skip (no announce):** non-credit message (gate), or no amount matched (regex) → the parser returns a default "unknown amount" phrase, which is **still** announced if the gate passed. Note: passing the gate but failing the regex still yields an announcement with the "unknown amount" text.
- **Malformed:** no try/catch around parsing; a `NumberFormatException` inside `getAmountFromMessageBody` is individually caught (values default to 0 → "unknown amount"). No other exception handling in the receiver.
- **Duplicate SMS:** not handled at the receiver/parser level. Deduplication happens only in the TTS service (§7), keyed on the rendered announcement text within a 60 s window.

---

## 5. Notification handling

The app uses a real `NotificationListenerService`.

- **Service class:** `com.example.upipaymentalert.NotificationListener extends NotificationListenerService`.
- **Manifest declaration:**
  ```xml
  <service android:name=".NotificationListener"
           android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
           android:exported="true">
    <intent-filter>
      <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
  </service>
  ```
- **Required permission:** the system grants `BIND_NOTIFICATION_LISTENER_SERVICE` when the user enables this app under *Settings → Notifications → Notification access*. This is a special permission, not requested via `requestPermissions`.
- **Lifecycle:** the system binds/unbounds the service; there is no `onStartCommand` (it's a bound service). The app never starts it explicitly.
- **Notification callback:** `onNotificationPosted(StatusBarNotification sbn)` (also `onListenerConnected` is inherited but not overridden).

### 5.1 Package filtering (hard-coded allow-list)
A notification is processed **only** if its package name `contains` one of these **seven** packages:

| # | Package substring | Likely app |
| --- | --- | --- |
| 1 | `com.phonepe.app` | PhonePe |
| 2 | `com.google.android.apps.nbu.paisa.user` | Google Pay (Paytm's UPI variant) |
| 3 | `net.one97.paytm` | Paytm |
| 4 | `in.amazon.mShop.android.shopping` | Amazon Shopping |
| 5 | `com.sbi.upi.intent` | SBI (SBI UPI intent package) |
| 6 | `in.org.npci.upiapp` | UPI (NPCI / Bharat UPI) |
| 7 | `com.dreamplug.androidapp` | Cred |

> Matching is a substring `contains`, so any package that merely contains one of these substrings would also pass. No other packages are processed. Support is **entirely** this static list — there is no user-configurable app list and no dynamic discovery.

### 5.2 Text extraction
- `extras.getString(Notification.EXTRA_TITLE, "")` → `title`
- `extras.getString(Notification.EXTRA_TEXT, "")` → `text`
- `messageBody = title + " " + text`
- No custom extras (no `EXTRA_BIG_TEXT`, `EXTRA_TEXT_LINES`, or `EXTRA_
_* fields are read.
- **Known limitation:** Payment apps frequently put the amount in `EXTRA_TITLE`/`EXTRA_TEXT`, but some split them or use `EXTRA_BIG_TEXT`; only `EXTRA_TITLE` + `EXTRA_TEXT` are read. Whether a specific app's notification is correctly captured depends on that app placing the amount in those two fields. **This is app-specific and cannot be verified statically for every supported app.**

### 5.3 Parser selection / extraction
- Identical to the SMS path: single `SmsParser` instance, same `isCreditTransaction` gate, same `getAmountFromMessageBody(messageBody, language)`.
- `messageBody` is `title + " " + text`, so the credit keywords and the currency regex both run against that concatenated string.

### 5.4 Duplicate handling
- Handled solely by the shared TTS-service dedupe map (§7). Because both SMS and notification produce the *same* rendered phrase for the *same* amount, a payment that arrives via both channels within 60 s is announced once.

### 5.5 Announcement flow
- `startForegroundService(ForegroundTtsService, ACTION_SPEAK, EXTRA_TEXT=textToRead)` (same as SMS path).

### 5.6 Rebind / restart behavior
- **Not implemented** — there is no explicit reconnection/rebind logic, no `onListenerDisconnected` handling, and no self-respawn. The system is responsible for re-binding the listener service; the app does not manage its own re-attachment. **Behavior after the listener process is killed and re-bound by the system cannot be fully determined from static source.**

### 5.7 Supported apps (definitive list)
Exactly the seven packages above. No other package is processed by the notification path, regardless of how its text is formatted.

---

## 6. Payment data model

**There is no dedicated payment data model class.** No `Payment`, `PaymentEvent`, `Transaction`, or similar POJO/`data class`/sealed type exists anywhere in the source. The only "payload" that flows through the app is:

1. **The rendered announcement text** — a single `String` (e.g. `Received |125| rupees` or the Hindi equivalent) passed as `Intent` extra `EXTRA_TEXT`.
2. **The display string** — `SharedPreferences["last_sms"]`, a `"Address: …\n\nBody: …"` or `"App: …\n\nBody: …"` blob shown in the UI.

Consequences:
- **No structured fields** are captured for payer name, transaction/reference ID, timestamp, currency, bank, or source-app identity.
- The phrase embeds the amount between `|` markers (used by the TTS engine to vary speech rate, see §8).
- The only enum-like constants in the code are:
  - `ForegroundTtsService.ACTION_SPEAK = "com.example.upipaymentalert.action.SPEAK"`
  - `ForegroundTtsService.EXTRA_TEXT = "extra_text"`
  - `NotificationListener`'s inline package substrings (not constants).
  - The language spinner's two literal strings `"English"` / `"Hindi"`.
- There is **no `Source` enum** (e.g. `SMS`, `NOTIFICATION`) even though two channels exist. The source-app package is written into the `last_sms` display string but is not carried forward as data.

> **Future-sync relevance:** If an online sync layer needs a structured record (payer, reference, timestamp, amount as a number, source channel), that structure does **not** yet exist and would have to be introduced. The current pipeline is effectively *text-in → phrase-out*.

---

## 7. Duplicate detection / deduplication

Deduplication is implemented **only** inside the foreground TTS service, and it is **text-based, in-memory, time-windowed**.

### 7.1 Class & field
- **Class:** `com.example.upipaymentalert.ForegroundTtsService`
- **Field:** `private final ConcurrentHashMap<String, Long> recentAnnouncements = new ConcurrentHashMap<>();`
- **Window:** `private static final long DEDUPLICATION_WINDOW_MS = 60000;` (60 seconds)

### 7.2 Method
- **`private void handleSpeakRequest(String text)`**

### 7.3 Dedupe key
- **The exact rendered announcement string** — e.g. `"Received |125| rupees"`. It is *not* a transaction ID, not a payer, not an amount as a number, and not a channel.

### 7.4 Algorithm
Pseudo-code (faithful to source):
```java
long now = System.currentTimeMillis();
// 1. Prune expired entries
for (Map.Entry<String, Long> e : recentAnnouncements.entrySet()) {
    if (now - e.getValue() > DEDUPLICATION_WINDOW_MS) recentAnnouncements.remove(e.getKey());
}
// 2. Dedupe check
if (recentAnnouncements.containsKey(text)) {
    Log.d(TAG, "Duplicate announcement prevented: " + text);
    return;                       // DROP
}
// 3. Register + announce
recentAnnouncements.put(text, now);
speak(text);
```

### 7.5 Storage & retention
- **Storage:** in-memory only (`ConcurrentHashMap`). **No persistence** (no `SharedPreferences`, no file, no DB).
- **Retention:** 60 s per entry; entries are pruned lazily on every new `handleSpeakRequest` call.
- **Cleanup:** lazy, on next call. No background timer, no scheduler.

### 7.6 Behavior matrix
| Scenario | Current behavior |
| --- | --- |
| Same payment, same channel, < 60 s | Suppressed (dedupe hit). |
| Same payment, same channel, > 60 s | Announced again (window expired). |
| Same payment via SMS **and** notification, < 60 s | Announced **once** — both channels produce the identical phrase, so the second hits the map. |
| Two *different* payments of the same amount, < 60 s | **Suppressed** (keyed on phrase, not identity). This is a false-negative. |
| Two *different* payments of the same amount, > 60 s | Announced. |
| No transaction ID available | N/A — there is no transaction ID in the model; dedupe does not reference it. |
| Timestamp differs | N/A — the phrase contains no timestamp; the wall-clock is only the map's timestamp. |
| Service process is killed and restarted | `recentAnnouncements` is **empty** after restart; a repeat within the "same" 60 s window would be announced again. |

### 7.7 Concurrency
- `handleSpeakRequest` is called from `onStartCommand`, which runs on the service's main thread, so in practice calls are serialized.
- `recentAnnouncements` is a `ConcurrentHashMap`, so individual `put`/`containsKey`/`remove` are thread-safe.
- The **prune-then-check-then-put** sequence is **not atomic**; two concurrent calls with the same text could theoretically both pass the `containsKey` check before either `put`. Under the current single-threaded dispatch this does not happen, but the code does not rely on or enforce that invariant.

### 7.8 Small worked example
- `t=0`   SMS "Rs 100 received" → phrase `Received |100| rupees` → put, announce.
- `t=5s` Notification "Got 100 Rupees" from PhonePe → phrase `Received |100| rupees` → **containsKey true → dropped** (Log "Duplicate announcement prevented").
- `t=61s` SMS "Rs 100 received" again → phrase still `Received |100| rupees` → prune removes the old entry (now−0 > 60 000) → `containsKey` false → put, announce.

---

## 8. Voice announcement system (TTS)

All TTS lives in `com.example.upipaymentalert.ForegroundTtsService`.

### 8.1 Engine & initialization
- **Engine:** platform `android.speech.tts.TextToSpeech` (one instance, created in `onCreate`).
- **Locale:** on init, `tts.setLanguage(Locale.getDefault())`. On each utterance, `speak()` calls `setLanguage` again based on `SharedPreferences["language"]`:
  - `"Hindi"` (case-insensitive) → `new Locale("hi", "IN")`
  - otherwise → `Locale.UK`
  - If the chosen locale is `LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED`, it falls back to `Locale.UK`.

### 8.2 Queue behavior
- Each segment is enqueued with `TextToSpeech.QUEUE_ADD`</think>

<tool_call>
<function=run_terminal_command>
<parameter=command>
cat >> CURRENT_ARCHITECTURE.md <<'EOF'
, so consecutive payments/segments play back-to-back without stopping the engine. Utterance IDs are `"FG_UPI_TTS_" + System.currentTimeMillis() + "_" + i` (where `i` is the segment index).

### 8.3 Segmented speech & speed
- The phrase is split on the literal `|` character: `text.split("\\|", -1)`.
- The parser emits a phrase whose *odd* segments (indices 1, 3, …) are the amount pieces and whose *even* segments (0, 2, …) are the connective words.
- Per-segment rate:
  - **Even index** (connective words): `0.9f`
  - **Odd index** (amount pieces): `Math.max(0.1f, customSpeed - 0.1f)` where `customSpeed = SharedPreferences["speed_value"]` (default `1.0f`).
- `tts.setPitch(1.0f)` is fixed per utterance.
- So by default (`customSpeed = 1.0`), amount segments speak at `0.9f` and connectives at `0.9f` too; as the user raises the slider, only the amount segments get faster.

### 8.4 Audio stream / volume
- `speak()` writes `KEY_PARAM_VOLUME = volumePercent / 100f` into the utterance `Bundle` (`volumePercent = SharedPreferences["volume"]`, default `15`).
- **Independently**, on `onStart` of an utterance, `setTtsVolume()`:
  - Captures the current `AudioManager.getStreamVolume(STREAM_MUSIC)` into `originalVolume` (only if not already captured).
  - Computes `targetVol = maxVol * (volumePercent / 100f)`.
  - `am.setStreamVolume(STREAM_MUSIC, targetVol, 0)` — **no UI flag**, so the system volume HUD is not shown.
- On `onDone` / `onError`: `restoreVolume()`:
  - Sleeps 150 ms (`Thread.sleep(150)` — main-thread, blocking), then if TTS is not still speaking, restores `originalVolume` and clears it.
- This is a *whole-stream* volume override for `STREAM_MUSIC`, not a TTS-specific gain. It affects every music-stream audio on the device for the duration of an announcement.

### 8.5 Audio focus / interruption
- **No `AudioManager.requestFocus` / `abandonAudioFocus` calls** are present. Audio focus is **not** managed.
- **Interruption behavior:** not implemented. If another TTS stream is active, the behavior is engine-defined.

### 8.6 Repeated announcements
- The dedupe map (§7) is the only repeat-prevention mechanism. There is no separate "repeat every N seconds" timer.

### 8.7 Failure handling
- `speak()` wraps the whole body in `try { … } catch (Exception e) { Log.e(TAG, "TTS Error: " + e.getMessage()); }`.
- `setupTtsListener()`'s `onError` → `restoreVolume()`.
- If `tts` is null (engine init failed), `speak()` silently does nothing.
- `setTtsVolume` / `restoreVolume` each have their own `try/catch` with `Log.e` on failure.

### 8.8 Lifecycle
- **`onCreate`** — creates notification channel, `startForeground(12345, n)`, builds `TextToSpeech` with an init `OnStatusListener`; on success sets `Locale.getDefault()` and installs the progress listener.
- **`onStartCommand`** — handles `ACTION_SPEAK`; returns `START_STICKY`.
- **`onDestroy`** — `tts.shutdown()`; `tts = null`.
- **`onBind`** — returns `null` (not a bound service).

### 8.9 End-to-end trace
```text
(SMS or Notification)
   → SmsListener / NotificationListener
   → Intent(ACTION_SPEAK, EXTRA_TEXT=phrase)
   → startForegroundService(ForegroundTtsService)
   → ForegroundTtsService.onStartCommand
   → handleSpeakRequest(text)
        → dedupe check (ConcurrentHashMap, 60 s)
        → speak(text)
             → setLanguage (UK or hi-IN)
             → set KEY_PARAM_VOLUME
             → setPitch(1.0)
             → for each segment split by "|":
                  setSpeechRate (even 0.9 / odd max(0.1, customSpeed-0.1))
                  tts.speak(segment, QUEUE_ADD, params, "FG_UPI_TTS_…" )
   → UtteranceProgressListener.onStart → setTtsVolume (override STREAM_MUSIC)
   → UtteranceProgressListener.onDone/onError → restoreVolume
   → audio out via device speaker
```

---

## 9. Persistence / local storage

**Only one persistence mechanism is used: `android.content.SharedPreferences`.** No Room, no SQLite, no `DataStore`, no file/JSON persistence, no in-memory singleton state shared across components.

### 9.1 `UPI_PREFS`
- **File name:** `UPI_PREFS` (created with `MODE_PRIVATE` in both `MainActivity` and `ForegroundTtsService`; the notification listener uses the same name).
- **Keys and where they are read/written:**

| Key | Type | Default | Written by | Read by | Purpose |
| --- | --- | --- | --- | --- | --- |
| `language` | `String` | `"English"` | `MainActivity.setupLanguageSpinner` | `SmsListener`, `NotificationListener`, `ForegroundTtsService.speak` | TTS language. |
| `volume` | `int` | `15` | `MainActivity.setupVolumeSlider` | `ForegroundTtsService.setTtsVolume`, `speak` | Announcement volume % (0–100). |
| `speed_progress` | `int` | `5` | `MainActivity.setupSpeedSlider` | `MainActivity.setupSpeedSlider` | 0–15 slider position. |
| `speed_value` | `float` | `1.0` | `MainActivity.setupSpeedSlider` | `ForegroundTtsService.speak` | Segmented-speech speed multiplier. |
| `forwarder_number` | `String` | `""` | `MainActivity.setupSmsForwarder` (Set button) | `SmsListener`, `NotificationListener` | Phone number to forward matching SMS/notifications to. |
| `forwarder_app_filter` | `String` | `"all"` | `MainActivity.setupSmsForwarder` (payment-filter spinner) | `NotificationListener` | Package filter for forwarder (`"all"` or a specific package). |
| `forwarder_type_filter` | `String` | `"Credited"` | `MainActivity.setupSmsForwarder` (type spinner) | `SmsListener`, `NotificationListener` | One of `Credited` / `Debited` / `Both`. |
| `last_sms` | `String` | `""` | `SmsListener.onReceive`, `NotificationListener.onNotificationPosted` | `MainActivity.onResume` | Display-only snapshot of the last processed payment. |

- **Read locations:** `MainActivity.onCreate/onResume`, `SmsListener.onReceive`, `NotificationListener.onNotificationPosted`, `ForegroundTtsService.speak/setTtsVolume`.
- **Write locations:** `MainActivity` (all UI controls), `SmsListener` / `NotificationListener` (`last_sms`).
- **Deletion / cleanup:** none. Preferences persist for the life of the app install; clearing app data is the only removal path.
- **Migration:** none (no schema, no versioned migration).

### 9.2 TTS in-memory map
- `recentAnnouncements` (§7) is process-lifetime and vanishes on process death. It is *state* but not *persistence*.

### 9.3 No other storage
- No `File`/`FileOutputStream`, no `Room`, no `DataStore`, no `ContentProvider`, no `Room` DAO, no raw `SQLiteDatabase`.

---

## 10. Settings and configuration

### 10.1 User-configurable settings

| Setting | UI location | Storage key | Default | Runtime effect |
| --- | --- | --- | --- | --- |
| Announcement language | Language spinner (`@id/lang_spinner`) | `language` | `English` | Selects `Locale.UK` vs `hi-IN`; changes the phrase template. |
| Announcement volume | Volume `SeekBar` (`@id/volume_slider`, max 100) | `volume` | `15` | Percentage of `STREAM_MUSIC` max; also sets `KEY_PARAM_VOLUME`. |
| Announcement speed | Speed `SeekBar` (`@id/speed_speed
                `SeekBar` (`@id/speed_slider`, max 15; 0.5–2.0×) | `speed_progress` | `speed_value` | `5` | Multiplier on segment rate; stored both as a position and a float. |

Arguments:
| Setting | UI location | Storage key | Default | Why the value matters for the current implementation |
| --- | --- | --- | --- | --- |
| Language | `lang_spinner` | `language` | `English` | Drives both the phrase template (Hindi/English) and the TTS locale (`hi-IN` vs `Locale.UK`). |
| Volume | `volume_slider` (0–100) | `volume` | `15` | Fraction of `STREAM_MUSIC` max used for the override + raw TTS volume param. |
| Speed | `speed_slider` (0–15 → 0.5–2.0×) | `speed_progress`, `speed_value` | `5` (→1.0×) | Determines the odd-segment speech rate. |
| Forwarder phone | `forwarder_number_input` | `forwarder_number` | `""` (empty = disabled) | If non-empty, matching SMS/notifications are resent to this number via `SmsManager`. |
| Forwarder app filter | `forwarder_payment_filter_spinner` | `forwarder_app_filter` | `all` | For *notification* forwarder only: which app's notifications are forwarded (`"all"` or a specific package). |
| Forwarder type filter | `forwarder_type_filter_spinner` | `forwarder_type_filter` | `Credited` | Which transaction *kind* is forwarded/announced: `Credited`, `Debited`, or `Both`. |
| Last message snapshot | *(read-only, shown in `@id/view_sms_tv`)* | `last_sms` | `""` | Not user-edited; shows the most recently processed payment for the user. |

### 10.2 Hard-coded / build-time configuration

| Setting | Value | Where it lives | Notes |
| --- | --- | --- | --- |
| Gradle distribution | `8.7` | `gradle/wrapper/gradle-wrapper.properties` | Not changed. |
| Android Gradle Plugin | `8.3.0` | root `build.gradle` | Not changed. |
| compileSdk / targetSdk | 34 / 34 | `app/build.gradle` | Not changed. |
| minSdk | 24 | `app/build.gradle` | Not changed. |
| Java source/target | `VERSION_17` | `app/build.gradle` | Not changed. |
| Currency regex (amount) | `(?i)(?:(?:RS|INR|MRP|₹)\.?\s*)(\d+(?:,\d{3})*(?:\.\d{1,2})?)` | `SmsParser.java` (static `AMOUNT_PATTERN`) | Detects amounts prefixed by `RS`, `INR`, `MRP`, or `₹`. |
| Credit/debit keywords | Various Hindi + English keywords | `SmsParser.isCreditTransaction`, `SmsParser.isDebitTransaction` | See §4 / below. |
| Notification package list | 7 substrings (PhonePe, GPay/Paisa, Paytm, Amazon, SBI, NPCI, Cred) | `NotificationListener.java` | Hard-coded allow-list; `contains`. |
| Dedupe window | `60_000 ms` | `ForegroundTtsService.java` | Only in-memory; resets on process death. |
| TTS service notification channel ID | `"upi_tts_channel"` | `ForegroundTtsService.java` | Low importance foreground notification. |
| TTS service notification ID | `12345` | `ForegroundTtsService.java` | Forrest ID. |
| Default TTS locale fallback | `Locale.UK` | `ForegroundTtsService.java` | Used when Hindi locale or user locale is unsupported. |
| SMS forwarder prefix | `"FWD SMS: "` / `"FWD NOTIF: "` | `SmsListener.java` / `NotificationListener.java` | Prefix added to forwarded bodies. |

**Credit-transaction keywords (used verbatim in `isCreditTransaction`):**
`credited`, `received`, `deposited`, `deposit`, `added`, `paid to you`, `paid you`, `sent to you`, `sent you`, `transfer from`, plus Hindi: `प्राप्त`, `जमा`, `मिले`.

**Debit-transaction keywords (used verbatim in `isDebitTransaction`):**
`debited`, `debit`, `withdrawn`, `withdrawal`, `spent`, `deducted`, `charges`, `paid`, `sent`, plus the Hindi keywords `प्राप्त`, `जमा`, `मिले` which **reject** a debit gate.

**Rejection keywords in `isCreditTransaction`:** `debited`, `debit`, `withdrawn`, `withdrawal`, `failed`, `declined`, `spent`, `deducted`, `charges`, and context checks on bare `paid` / `sent` / `transfer to` (without the "to you" / "you" qualifier).

---

## 11. Android Manifest

Complete component inventory (all from `app/src/main/AndroidManifest.xml`):

- **Permissions (10 declared):**
  `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `QUERY_ALL_PACKAGES`, `WRITE_SETTINGS` (with `tools:ignore="ProtectedPermissions"`), `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (Android 14+).

- **Application attributes:** `allowBackup="true"`, icon `@mipmap/ic_launcher`, label `@string/app_name`, roundIcon `@mipmap/ic_launcher_round`, `supportsRtl="true"`, theme `@style/Theme.UPIPaymentAlert`. **No `android:name`** → no custom `Application` class.

- **Activity:** `.MainActivity`, `android:exported="true"`, LAUNCHER intent-filter (`ACTION_MAIN` / `CATEGORY_LAUNCHER`).

- **Services:**
  - `.ForegroundTtsService` — `android:exported="false"`, `android:foregroundServiceType="specialUse"`, with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="TTS Announcement">`. Started by `MainActivity` at boot of the UI and on demand by the two listeners.
  - `.NotificationListener` — `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`, `exported="true"`, intent-filter `android.service.notification.NotificationListenerService`.

- **Receiver:** `.broadcastreciever.SmsListener` — `android:exported="true"`, intent-filter `android.provider.Telephony.SMS_RECEIVED` with `android:priority="1000"`.

- **Boot / startup behavior:** There is **no** `BOOT_COMPLETED` intent-filter in the manifest. The app does **not** register to auto-start on device boot from the manifest. The only automatic start path observed is `MainActivity.onCreate` starting `ForegroundTtsService` — which only happens when the user opens the app.

- **SMS permissions:** `RECEIVE_SMS` + `READ_SMS` required for the SMS path; `SEND_SMS` required for the forwarder feature. `SMS_RECEIVED` requests are runtime-granted (handled in `MainActivity.checkPermissionsStatus` / `requestAllPermissions`).

- **Notification listener permission:** `BIND_NOTIFICATION_LISTENER_SERVICE` is the system permission that the user grants via the notification-access system UI; the app never requests it with `requestPermissions`.

---

## 12. Background execution

| Situation | Documented behavior |
| --- | --- |
| App is open (UI visible) | `MainActivity` is the root; `ForegroundTtsService` is already started and foregrounded; both listeners are bound/registered by the manifest. |
| App in background (process alive) | Active SMS receiver + notification listener continue to work; `ForegroundTtsService` stays running (foreground) and can still speak. SMS visibility may be limited by app standby depending on the channel, but `RC` RECEIVE_SMS receiver of priority 1000 still receives broadcasts while the app is backgrounded. |
| App process killed | `ForegroundTtsService` is **not** re-created until something re-starts it. The first new SMS receipt or notification from a supported app will **not** be spoken because `startForegroundService` would be needed; in the SMS path `SmsListener` does call `startForegroundService` so a newly arriving SMS can re-launch it. Overall: behavior after process death is **partially determined** — the SMS path can revive the service, but there is no guarantee the listener service is alive to handle a notification at the same moment. |
| Screen locked | Not handled specially in code. TTS still speaks if the process/engine permit it. Screen-on/wake-locks are not requested. |
| Phone reboots | **No `BOOT_COMPLETED` receiver**. The service is not auto-started on boot. The user must open the app (or a payment must arrive and the SMS path must re-launch the service) for announcements to resume. |
| Android background execution limits | The app uses a *persistent foreground service* (`ForegroundTtsService` with a notification) to stay alive. Because the TTS service is foreground, it is less likely to be killed than a plain background service. No `WorkManager` or `JobScheduler` is used. |
| Notification listener reconnects | The system handles binding. The code does **not** override `onListenerDisconnected` / `onListenerConnected` / `onNotificationPosted` re-subscribe logic. **Re-attach behavior after being unbound cannot be fully determined statically.** |
| SMS arrives while UI is closed | `SmsListener.onReceive` runs regardless of UI (the receiver is a standalone `<receiver>`); it **does** call `startForegroundService` for the TTS service, so the first SMS after the service was killed can wake it. |

---

## 13. Threading and asynchronous execution

### 13.1 Concurrency primitives present
- `java.util.concurrent.ConcurrentHashMap` — used **only** by `ForegroundTtsService.recentAnnouncements` (§7).
- No Kotlin coroutines, no `ExecutorService`, no `Handler`/`Looper` usage beyond the platform framework.
- No `WorkManager`, no `AsyncTask`, no custom thread pools.

### 13.2 Asynchronous paths
| Path | Mechanism | Thread | Result / side effect |
| --- | --- | --- | --- |
| `SMS_RECEIVED` broadcast → listener | OS dispatches `onReceive` | Process main thread (broadcast) | Synchronous; if forwarder fires, `SmsManager.sendTextMessage` is a blocking call; `startForegroundService` is async. |
| `onNotificationPosted` | OS dispatches the callback | Listener-service main thread | Synchronous; `startForegroundService` is async; `SmsManager.sendTextMessage` (forwarder) is blocking. |
| TTS speak | `TextToSpeech.speak(...)` with `QUEUE_ADD` | TTS engine handles playback on its own thread(s) | Speech output. |
| `UtteranceProgressListener.onStart / onDone / onError` | Callback from TTS engine | TTS engine thread / arbitrary | Volume override (`setTtsVolume`/`restoreVolume` run on whatever thread the listener fires — they touch `AudioManager` and `originalVolume`, but `originalVolume` is only ever set once from whichever thread wins the first `onStart`). |
| `restoreVolume` sleeps | `Thread.sleep(150)` | the listener callback thread | Brief blocking pause before restoring. |
| UI listeners (spinners/seekbars) | Android framework callbacks | Main thread | Update prefs synchronously (`apply`). |

### 13.3 Race conditions observed (and what is *not* protected)
- **Dedupe race:** the prune → `containsKey` → `put` sequence is **not atomic**. Two concurrent calls with identical `text` could both slip past `containsKey`. Under the current dispatch model (listener runs on a single service main thread, SMS runs on the main thread) this is unlikely, but **the code does not formally enforce** exclusivity (e.g., no `synchronized` on the whole `handleSpeakRequest`).
- **`originalVolume` capture race:** `setTtsVolume` captures `originalVolume` only once (`if (originalVolume == null)`), but there is no synchronization around that read/write; if two utterances start concurrently, the capture could be racy.
- **SMS receiver notification-firing conflict:** a single SMS broadcast could, in theory, call `startForegroundService` while a notification is also being processed; both will try to fire the same service. Android drains foreground-service starts; the current code lets them both attempt it — no deduplication at the service-launch level.

### 13.4 What is synchronous (no asynchrony at all)
- Every persistence write in `MainActivity`/`SmsListener`/`NotificationListener` is a synchronous `prefs.edit()...apply()` — `apply` is ordered but not blocking; technically async-flush but not a concurrency concern here.
- `SmsManager.sendTextMessage` is called synchronously in both listeners.

---

## 14. Error handling and logging

### 14.1 Logging framework
- `android.util.Log` (Tag `"UPIPaymentAlert"`) throughout `ForegroundTtsService`; the listeners and `SmsParser` use **no explicit logging** (no `Log` statements).
- Notable log messages:
  - `ForegroundTtsService`: `"Volume set to …% (Target stream level: …/…)"` (info).
  - `ForegroundTtsService`: `"Error setting volume: …"` (error).
  - `ForegroundTtsService`: `"Volume restored to original level: …"` (info).
  - `ForegroundTtsService`: `"Error restoring volume: …"` (error).
  - `ForegroundTtsService`: `"TTS Error: …"` (error).
  - `ForegroundTtsService`: `"Duplicate announcement prevented: …"` (info).

### 14.2 Caught exceptions
- `SmsListener.onReceive`: `SMS_RECEIVED` path: `SmsManager.sendTextMessage` wrapped in `try { … } catch (Exception ignored) {}` — forwarder failures are silently dropped.
- `NotificationListener.onNotificationPosted`: same `try { … } catch (Exception ignored) {}` around the forwarder send; silently dropped.
- `ForegroundTtsService.speak`: `try { … } catch (Exception e) { Log.e(TAG, "TTS Error: " + e.getMessage()); }`.
- `ForegroundTtsService.setTtsVolume` / `restoreVolume`: each own `try/catch` → `Log.e`.
- `SmsParser.getAmountFromMessageBody`: two `try { Integer.parseInt } catch (NumberFormatException ignored) {}` — amount parsing defaults to 0 on failure, not an exception path visible outside.

### 14.3 Failure paths
| Component | Failure scenario | Current behavior |
| --- | --- | --- |
| `SmsListener` (forwarder) | `SmsManager.sendTextMessage` throws | Caught & ignored; no user-visible feedback; SMS still proceeds to announcement gate. |
| `NotificationListener` (forwarder) | sendTextMessage throws | Caught & ignored. |
| `SmsParser` (amount parse) | regex doesn't match, or parseInt throws | Returns default "unknown amount" phrase; `isCreditTransaction` still returns true/false independently. |
| `ForegroundTtsService` (TTS init) | TTS engine fails to initialize | `tts == null` → `speak()` silently does nothing; no user-facing error shown. |
| `ForegroundTtsService` (speak) | exception during speak | `Log.e("TTS Error: …")`; no UI toast. |
| `ForegroundTtsService` (volume) | `AudioManager` set fails | `Log.e("Error setting/restoring volume: …")`; continues. |
| `Service` (foreground start) | `startForegroundService` fails (e.g., system restriction) | Exception in `SmsListener`/`NotificationListener` forwarder path is ignored; but the `startForegroundService` call for the TTS path is **not** wrapped in try/catch in the listeners (so a runtime exception there could propagate to the framework, but the code does not explicitly catch it). |
| Dedupe (map) | concurrent map operation | `ConcurrentHashMap` guarantees thread-safe individual ops; no user-visible failure. |

### 14.4 User-visible vs. silent failures
- **Silent / logger-only:** forwarder send failures, TTS init failure, TTS speak failure, volume errors, dedupe hits (only logged, not surfaced).
- **User-visible:** the only user-facing feedback is the MainActivity permission status text + toasts for permission-grant / forwarder set / forwarder send failure in the *UI* forwarder path; the background listeners themselves produce **no toasts**. so a phone-number forwarder failure in the background is invisible to the user even hard to reason about).

### 14.5 Persistent state storage
- **SharedPreferences:** as documented in §9 (`UPI_PREFS`).
- **In-memory map:** `recentAnnouncements` — lost on process death; **not** persisted.
- **No other storage** (no files, no DB).

### 14.6 Migration / schema
- **None.** `SharedPreferences` has no schema/migration. `recentAnnouncements` has no migration.

---

## 15. Error handling and logging

*(repeated for the explicit table from the original outline, refined)*

| Component | Failure | Current behavior |
| --- | --- | --- |
| SMS receiver | `SmsManager` sendTextMessage throws | Caught & ignored; SMS still proceeds to the announcement gate. |
| SmsParser | regex fails to match / parseInt throws | Returns default "unknown amount" phrase; `isCreditTransaction` result independent. |
| Notification listener | forwarder sendTextMessage throws | Caught & ignored. |
| Dedupe | map access (ConcurrentHashMap) | Thread-safe ops only; no user-visible effect (Log "Duplicate…"). |
| TTS engine | init fails | `tts == null`; `speak()` silently does nothing; no user-facing error. |
| TTS speak | exception | `Log.e("TTS Error: …")`; no UI feedback. |
| Volume override | `AudioManager` fails | `Log.e`; continues. |
| Service start | `startForegroundService` fails | Not explicitly caught in the listeners for the TTS path; the listeners' own forwarder send is caught; TTS-path startup is not wrapped. |
| Shared prefs | write fails (e.g., low storage) | `apply()` silently; no error surfaced for prefs writes. |

---

## 16. Architecture reference (key classes & methods)

| Class | Responsibility | Key methods / fields | Called by |
| --- | --- | --- | --- |
| `MainActivity` | UI + config console + startup + permission wizard | `onCreate`, `onResume`, `checkPermissionsStatus`, `requestAllPermissions`, `requestSpecialPermissions`, `setupLanguageSpinner`, `setupVolumeSlider`, `setupSpeedSlider`, `setupUpiAppsSpinner`, `setupTestButtons`, `setupSmsForwarder`, `sendSpeakIntent` | User / system |
| `ForegroundTtsService` | Foreground TTS engine + dedup + volume override | `onCreate`, `onStartCommand`, `onDestroy`, `onBind`, `speak`, `handleSpeakRequest`, `setTtsVolume`, `restoreVolume`, `createNotificationChannel`, `setupTtsListener`, `recentAnnouncements` (CConcurrentHashMap), `DEDUPLICATION_WINDOW_MS = 60_000` | `MainActivity`, `SmsListener`, `NotificationListener` (via `startForegroundService` + `ACTION_SPEAK`) |
| `SmsListener` | Receives SMS `SMS_RECEIVED` broadcasts; forwarder; credit gate; parse; announce | `onReceive(Context, Intent)` | OS (broadcast) |
| `NotificationListener` | NotificationListenerService; package-filter; forwarder; credit gate; parse; announce | `onNotificationPosted(StatusBarNotification)`, custom inline package list | OS (system) |
| `SmsParser` | Currency-regex amount extraction + bilingual phrase generation + credit/debit keyword classification | `getAmountFromMessageBody(String body, String language)`, `isCreditTransaction`, `isDebitTransaction`, static `AMOUNT_PATTERN` regex | `SmsListener`, `NotificationListener` (and unit test) |

Supporting:
- `MainActivity` test helpers: `ExampleUnitTest`, `ExampleInstrumentedTest` (boilerplate, stale package).

No other classes exist in `src/main` Java (5 production classes total).

---

## 17. Dependency map

### 17.1 Third-party libraries (stated in `app/build.gradle`)
| Library | Version | Purpose | Critical? |
| --- | --- | --- | --- |
| `androidx.appcompat:appcompat` | `1.6.1` | AppCompatActivity, theme backing | Yes (Activity superclass). |
| `com.google.android.material:material` | `1.11.0` | Theming, components | Yes (resource/theming; no Material widget in layout reached — but on classpath). |
| `androidx.constraintlayout:constraintlayout` | `2.1.4` | Layout manager | Yes (the only layout in `activity_main.xml`). |

### 17.2 Android platform APIs used
- `android.speech.tts.TextToSpeech` (+ `UtteranceProgressListener`, `OnStatusListener`, `QUEUE_ADD`, `KEY_PARAM_VOLUME`, `LANG_*` constants)
- `android.media.AudioManager` (`getStreamVolume`, `getStreamMaxVolume`, `setStreamVolume`, `STREAM_MUSIC`)
- `android.content.SharedPreferences` / `Context.MODE_PRIVATE`
- `android.telephony.SmsMessage`, `Telephony.Sms.Intents.getMessagesFromIntent`, `SmsManager.sendTextMessage`
- `android.content.BroadcastReceiver` + `Intent` / `PendingIntent`
- `androidx.core.app.NotificationCompat` for the foreground notification
- `android.app.*` (Service, Notification, NotificationChannel, NotificationManager, NotificationManager.IMPORTANCE_LOW, `startForeground`)
- `android.service.notification.NotificationListenerService`, `StatusBarNotification`, `Notification.EXTRA_TITLE/EXTRA_TEXT`
- `android.content.pm.PackageManager.queryIntentActivities`, `ResolveInfo`
- `android.os.Build` / `VERSION_CODES`
- `android.provider.Settings.Secure` for notification-listener enabled check
- `android.Manifest` perms
- `android.view.*`, `android.widget.*` (UI)

### 17.3 Build plugins
- Android Gradle Plugin `com.android.application` (AGP 8.3.0) — no other plugins. **No Kotlin plugin.**

### 17.4 What is **NOT** on the classpath
No Room, no SQLite (code-level), no Retrofit/OkHttp, no Firebase, no WorkManager, no coroutines, no junit on the test classpath (this is why the tests fail to compile).

---

## 18. Data flow (high-level)

Real shape (what actually exists):

```text
                     ┌─────────────────────┐
 SMS RECEIVE-BROADCAST │  SMS_RECEIVED  │
        broadcast        └────────┬────────┘
                                   │
        ┌──────────────────────────┤  ┌───────────────────────────────┐
        │  SmsListener.onReceive   │  │ NotificationListener.onNotif…  │
        │  (getMessagesFromIntent  │  │  (StatusBarNotification sbn)    │
        │   → address + body)      │  │  → packageFilter (7 packages)   │
        │  → forwarder (if on)     │  │  → forwarder (if on)           │
        │  → isCreditTransaction   │  │  → isCreditTransaction          │
        │  → getAmountFromMessageBody         │
        │  → (no transactionId, no payer name)
        │  → save last_sms         │  │  → save last_sms                │
        │  → startForegroundService(ACTION_SPEAK, EXTRA_TEXT=phrase)  │
        └────────────┬─────────────┘  └──────────┬────────────────────┘
                     │                             │
                     └───────────────┬─────────────┘
                                     ↓
                        ForegroundTtsService
                        onStartCommand(ACTION_SPEAK, EXTRA_TEXT=phrase)
                              → handleSpeakRequest(phrase)
                                   ├─ prune old entries (60 s window)
                                   ├─ containsKey(phrase) ?
                                   │     ├─ yes → Log.d "Duplicate…" ; return
                                   │     └─ no  → put(phrase, now) ; speak(phrase)
                                   └─ speak(phrase)
                                        ↓
                                   tts.speak(segmented, QUEUE_ADD)
                                        → UtteranceProgressListener
                                        → volume override (STREAM_MUSIC) / restore
                                        → audio out
```

Key: **the only "payment object" that exists is a single phrase String.** There is no intermediate domain model.

---

## 19. Current supported payment sources

### 19.1 Notification channel (definitive, code-derived)
Exactly seven allowed package substrings (§5.1):

| Source | Package substring | Detection path | Parser | Supported |
| --- | --- | --- | --- | --- |
| PhonePe | `com.phonepe.app` | `contains` in `NotificationListener` | `SmsParser` | Yes |
| Google Pay (Paytm variant) | `com.google.android.apps.nbu.paisa.user` | `contains` | `SmsParser` | Yes |
| Paytm | `net.one97.paytm` | `contains` | `SmsParser` | Yes |
| Amazon Shopping | `in.amazon.mShop.android.shopping` | `contains` | `SmsParser` | Yes |
| SBI (UPI intent) | `com.sbi.upi.intent` | `contains` | `SmsParser` | Yes |
| NPCI / Bharat UPI | `in.org.npci.upiapp` | `contains` | `SmsParser` | Yes |
| Cred | `com.dreamplug.androidapp` | `contains` | `SmsParser` | Yes |

No other package is supported by the notification path.

### 19.2 SMS channel
Bank-agnostic. Supported **only** by the presence of UPI-style credit keywords (English + Hindi) **and** a currency amount matching the regex. No bank/sender whitelisting. The README's table of supported banks is **not** reflected in any code path (no per-bank parser exists).

---

## 20. Important invariants

These are derived from the current implementation; they are **not** requirements, they are observations.

- **One TTS service is the single speech endpoint.** All payment paths (SMS + notification + UI test buttons) funnel into `ForegroundTtsService` via `ACTION_SPEAK`; there is no alternative speech path.
- **Dedup is text-based and in-memory only.** The same rendered phrase within 60 s is announced once; after 60 s it can be announced again; process death clears the map.
- **No structured payment record exists.** A future sync layer cannot observe a "Payment" object today — it would have to introduce one.
- **Persistence is only SharedPreferences.** Any cross-session config survives; nothing survives process death other than prefs.
- **Offline-first, no network.** There is no network dependency anywhere; the app can run entirely without internet. Adding sync must not break that.
- **No boot auto-start.** The manifest has no `BOOT_COMPLETED`; the UX must be: user opens app (or a SMS arrives and the SMS path restarts the TTS service).
- **Forwarder side effect mutates the SMS channel.** Sending a forwarded SMS via `SmsManager` is a real SMS send (costs money for the user); this is a current side effect that would need care.
- **No audio-focus management.** TTS does not request/abandon focus; behavior under competing TTS/audio is engine-defined.

---

## 21. Extension points for future online sync

(Do **not** implement; identify only.)

The current pipeline, at the point where a parsed phrase is produced, is:

```text
SMS or Notification
   → receiver (SmsListener / NotificationListener)
   → gate + parse (SmsParser:isCreditTransaction + getAmountFromMessageBody)
   → last_sms saved
   → startForegroundService(ForegroundTtsService, ACTION_SPEAK, EXTRA_TEXT=phrase)
        → ForegroundTtsService.onStartCommand
        → handleSpeakRequest(phrase)
             → dedupe check (text — 60 s)
             → speak(phrase)
```

**Observables / extension hooks (no code change to existing parsing) that could be tapped to add a sync hook WITHOUT changing the existing parser or gate:**

1. **`SmsParser.getAmountFromMessageBody` output (phrase only).** If a sync layer wants the amount, the parser today only emits a phrase; to observe a number would require either extending the parser's interface or having sync read the phrase and re-parse — that touches the parser. That is the least clean path.

2. **Receiver entry point (`SmsListener.onReceive` / `NotificationListener.onNotificationPosted`).** A sync hook could be inserted *after* the existing gate+parse, right before `startForegroundService`, by subscribing/observing the same `INTENT` extras. However the existing code does not expose a hook interface — a future sync layer would have to weave in an observer of the intent extras (or the phrase) at that point.

3. **`startForegroundService(ForegroundTtsService, ACTION_SPEAK, EXTRA_TEXT=phrase)`** — the same intent that currently drives speech. If a sync layer wants to react to "a payment was just parsed", it could observe the same `Intent` extras (phrase) at service-instantiation time, or observe the TTS service's `onStartCommand`. The existing code does not expose a callback for that — so it would need a new observer.

4. **`last_sms` in SharedPreferences.** It already holds a display string of the last processed payment (address + body, or "App: …" + body). A sync layer could read `last_sms` for the last payment content, but it contains no structured fields (no timestamp as a proper value, no payer, no transaction id beyond the raw body).

**Least-invasive conceptual hook for a future sync layer:** observe the rendered phrase and `last_sms` snapshot; to get a structured amount, the sync layer would either extend the parser or re-derive from the phrase. The cleanest long-term path is to give the app a real `Payment` model with structured fields (amount numeric, payer, timestamp, source, reference) and emit that in addition to the phrase — but that is a future design decision, not part of the current app.

**What must NOT be done to add sync (per invariants):**
- Do **not** block payment detection on network.
- Do **not** disable local voice announcement when sync fails.
- Do **not** make the existing in-memory dedupe dependent on network.
- Do **not** change the existing 60-second text-based dedup window (currently it's the only dedup).

---

## 22. Build and run instructions

All commands from the project root (directory containing `gradlew`).

### 22.1 Environment prerequisites
- **JDK:** Java 21 (Eclipse Temurin 21.0.12.1) installed and selected. Gradle 8.7 supports JVM 8–21 and **rejects the machine default Java 25**.
- **Gradle runtime selection:** this project pins the Gradle JVM **outside** the repo: `~/.gradle/gradle.properties` contains `org.gradle.java.home=/Users/<home>/.jdks/temurin-21.jdk/Contents/Home`. (Value is machine-specific; do not commit it.) The IDE's Gradle JDK is also pointed at the same JDK in `.idea/gradle.xml` (machine-local).
- **Android SDK:** `sdk.dir` in `local.properties` points to the machine SDK (gitignored). `compileSdk/targetSdk 34` are present; the SDK already contains `android-34` platform + `build-tools;34.0.0`.

### 22.2 Configure the project
No extra configuration is required beyond having the SDK. The project is ready to build as-is.

### 22.3 Build debug
```bash
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL` (verified). APK: `app/build/outputs/apk/debug/app-debug.apk`.

### 22.4 Build debug + install on a device/emulator
```bash
./gradlew installDebug
```
(Requires a connected device/emulator with USB debugging enabled.)

### 22.5 Run tests
```bash
./gradlew test
```
**Current state: the unit tests FAIL to compile.** `SmsParserTest.java` calls `parser.getAmountFromMessageBody("...")` with a single argument, but the only existing method is `getAmountFromMessageBody(String body, String language)` (two arguments). The test also lacks a proper junit dependency in `app/build.gradle` (no `testImplementation`; the test imports `org.junit.Test` and uses `assertEquals` but junit is absent). Result observed: `BUILD FAILED` in `:app:compileDebugUnitTestJavaWithJavac` with compilation errors. This is a pre-existing broken test file, not introduced by this document. To repair the project's tests later, either fix `SmsParserTest` to call the two-arg method and add a junit dependency, or remove the test file.

### 22.6 Run lint
```bash
./gradlew lint
```
(or `./gradlew lintDebug`). Lint may report issues; the project currently has no custom lint config.

### 22.7 Clean build
```bash
./gradlew clean
./gradlew assembleDebug
```

### 22.8 Quick verify the Gradle JVM
```bash
./gradlew --version
```
Should show `JVM: 21.0.12.1 (Eclipse Adoptium 21.0.12.1+1-LTS)` (not Java 25).

---

## 23. Verification checklist

- [ ] **App builds** — `./gradlew assembleDebug` succeeds (verified). APK at `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **App launches** — `MainActivity` is the LAUNCHER; on open it starts `ForegroundTtsService` and renders the UI.
- [ ] **SMS permission works** — `RECEIVE_SMS` + `READ_SMS` runtime-granted via MainActivity; `SmsListener` receives `SMS_RECEIVED` broadcasts.
- [ ] **SMS payment is detected** — receiving a credit SMS that contains a `Rs`/`INR`/`MRP`/`₹` amount triggers the pipeline.
- [ ] **SMS parser extracts correct amount** — the amount is read from the regex capture and spoken. (Note: the *unit test* for this is currently broken/non-compiling.)
- [ ] **SMS parser extracts payer correctly** — **limitation:** there is no payer-name extraction in the code. Only the raw SMS address is captured (for display).
- [ ] **Notification payment is detected** — a notification from one of the seven allowed packages that contains a credit keyword + amount triggers the pipeline.
- [ ] **Duplicate payment is suppressed** — the 60 s in-memory text-based dedup prevents repeats within the window (including SMS + notification for the same amount).
- [ ] **Voice announcement works** — `ForegroundTtsService` speaks the segmented phrase with the configured language/volume/speed.
- [ ] **Settings persist** — language, volume, speed, forwarder number/filters all survive restarts via `UPI_PREFS`.
- [ ] **Background operation works** — SMS receiver works while app is backgrounded; notification listener works when the listener is granted.
- [ ] **No boot auto-start** (observed) — app does not start on reboot; user must open it or a SMS must revive the TTS service.
- [ ] **Forwarder works** (if configured) — a matching SMS/notification is also sent to the configured phone number (real SMS cost applies).
- [ ] **No network dependency** — the app runs entirely offline.
- [ ] **Git diff clean** — only `CURRENT_ARCHITECTURE.md` is new; no `app/src` Java source, no Gradle files, no manifest, no resources were modified.

---

## 24. Source-of-truth rule

> This document describes the implementation inspected in the repository at the time it was generated. It is a technical baseline for future modifications. When this document conflicts with source code, the source code is authoritative.

- **Generation date:** 2026-10-05
- **Repository:** `official-arvind/UPIPaymentAlert`
- **Current git branch / commit SHA:** not available — this checkout has no `.git` directory (`git rev-parse` reports "not a git repository").
- **Android/Gradle versions discovered:**
  - Android Gradle Plugin `8.3.0`
  - Gradle `8.7`
  - compileSdk `34` / targetSdk `34` / minSdk `24`
  - Java source/target `VERSION_17`
  - Gradle runtime JDK: Java 21 (Temurin 21.0.12.1), because Gradle 8.7 rejects Java 25
  - Language: Java only (no Kotlin)

**Known gaps where static inspection cannot determine runtime behavior:**
- Long/SMS parts split across multiple `SMS_RECEIVED` broadcasts — not handled in code; not guaranteed.
- Notification `EXTRA_BIG_TEXT` / `EXTRA_TEXT_LINES` — not read; payment content may be missed for some apps.
- Behavior after the notification listener is unbound and re-bound by the system — not implemented in code; not determinable statically.
- Behavior after process death — partially determined (SMS path can revive the TTS service; notification path has no revive mechanism).
- Layered race between two concurrent identical announcements (the dedup window is not atomic) — unlikely under current single-threaded dispatch but not formally prevented.
- Whether specific supported apps actually place the amount in `EXTRA_TITLE`/`EXTRA_TEXT` at notification time — app-specific, not verifiable statically.

---

## Appendix A. Source files read for this document

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/upipaymentalert/MainActivity.java`
- `app/src/main/java/com/example/upipaymentalert/ForegroundTtsService.java`
- `app/src/main/java/com/example/upipaymentalert/NotificationListener.java`
- `app/src/main/java/com/example/upipaymentalert/broadcastreciever/SmsListener.java`
- `app/src/main/java/com/example/upipaymentalert/smsparser/SmsParser.java`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `app/build.gradle`
- root `build.gradle`
- `settings.gradle`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties`
- `app/src/test/java/com/example/upipaymentalert/smsparser/SmsParserTest.java`

## Appendix B. Source files NOT present (confirmed absent)
- No Kotlin files (`.kt`) anywhere.
- No custom `Application` class.
- No Room / SQLite DAO / entity classes.
- No WorkManager / JobScheduler / Coroutine usage.
- No OkHttp / Retrofit / Firebase / network client.
- No `BOOT_COMPLETED` receiver.
- No audio-focus request/abandon calls.

## Appendix C. Build status at time of writing
- `./gradlew assembleDebug` → `BUILD SUCCESSFUL` (verified)
- `./gradlew test` → `BUILD FAILED` (`:app:compileDebugUnitTestJavaWithJavac` — pre-existing broken unit test; see §22.5 / §23)

