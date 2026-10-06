# ONLINE_SYNC_ARCHITECTURE_PLAN.md
### Privacy-first remote announcement layer — design + implementation plan

---

## 1. Restatement of the task and the privacy model

When the **owner** phone detects an accepted payment (via the existing `SmsListener` or `NotificationListener`), it must additionally deliver the payment to a paired **child** phone, which decrypts it locally and speaks it with the platform TTS engine. The owner's existing local announcement is untouched: it fires first, on the same code path, with no online check anywhere near it.

The privacy model is the whole point of the design. **The relay transports ciphertext and nothing else.** Payment content is encrypted on the owner *before* it leaves the device and decrypted on the child *after* it arrives, so the relay — and Google/FCM — can never read an amount. The relay has **no payment table, no payload log, and no decryption key**; it holds exactly two opaque values per pairing (a hash of a send token, and the child's push token) and forwards bytes. There is no `payment_events` table anywhere because there is no payment database at all. **TTS stays 100 % on-device on both ends** — no cloud synthesis, no audio upload. The child persists nothing but a ring of recent event IDs, which it needs to refuse replays.

---

## 2. NEW files (by category)

**Shared crypto (pure Java — no `android.*`, no `org.json`, so it runs in a plain JVM harness)**
| File | Purpose |
|---|---|
| `remote/RemoteCrypto.java` | P-256 ECDH, HKDF-SHA256, AES-256-GCM seal/open, base64url, all on `byte[]`. Single source of cryptographic truth for both roles. |
| `remote/RemoteIdentity.java` | This device's EC key pair, plus Keystore-wrapped load/save/clear. |
| `remote/PairingCode.java` | Encode/decode the pairing blob and derive the 6-digit verification fingerprint. |

**Keys & pairing state**
| File | Purpose |
|---|---|
| `remote/RemoteKeys.java` | Keystore-held AES KEK that wraps the identity and the pairing record; hard-fail when Keystore is unavailable. |
| `remote/RemotePairingStore.java` | The pairing record (`pairId`, role, peer pubkey, session key, verification code, send token) at rest in `getFilesDir()/remote_pair.enc`. |

**Owner side**
| File | Purpose |
|---|---|
| `remote/RemoteEnvelope.java` | Builds/parses the wire envelope and the inner payloads (pair introduction + payment), and constructs the AAD. |
| `remote/RemoteAnnouncer.java` | The one entry point the listeners call: enable/paired gate → encrypt → enqueue → kick drain; **never throws**. |
| `remote/RemoteOutbox.java` | Durable JSON-lines outbound queue with attempts/backoff metadata, cap, partial-write tolerance. |
| `remote/RemoteDrainer.java` | Single background thread that drains the outbox with backoff; opportunistic, best-effort. |

**Child side**
| File | Purpose |
|---|---|
| `remote/RemoteIngest.java` | The child pipeline: parse → auth-check → decrypt → validate → dedupe → speak → discard; **never throws**. |
| `remote/RemoteChildStore.java` | Persisted ring of the last 200 seen `eventId`s (replay defence that survives process death). |
| `remote/RemotePhrase.java` | `amountPaise` → spoken phrase by re-feeding a synthetic `"RS <amount>"` string into the **unmodified** `SmsParser`. |

**Transport (swappable — this is why the wire is not a blocker)**
| File | Purpose |
|---|---|
| `remote/RemoteTransport.java` | Interface + the single factory (`get`) that selects the active transport. |
| `remote/NoopTransport.java` | Phase-1 implementation: does nothing, opens no socket. |
| `remote/RelayTransport.java` | *(phase 2)* HTTPS POST of the envelope to the relay, bearer-authenticated with the send token. |
| `remote/FcmReceiveService.java` | *(phase 2)* `FirebaseMessagingService`; hands `data["env"]` to `RemoteIngest`. |

**UI / pairing**
| File | Purpose |
|---|---|
| `remote/PairingActivity.java` | Both pairing roles: as-child (display blob) and as-owner (paste blob), plus verification fingerprint and unpair. |
| `res/layout/activity_pairing.xml` | Layout for the above. |

**Tests / tooling**
| File | Purpose |
|---|---|
| `tools/remote_crypto/RemoteCryptoHarness.java` | Plain-JVM harness (javac + java, no dependency) proving crypto round-trip, tamper rejection, and phrase equivalence. |

---

## 3. MODIFIED files — precise change to each

| File | Exact change | What stays unchanged |
|---|---|---|
| `SmsListener.java` | (a) Hoist one declaration: `PaymentEvent ev = null;` above the existing history `try` block, and change the existing `PaymentEvent ev = PaymentEvent.capture(...)` to `ev = PaymentEvent.capture(...)`. (b) Append **one new** guarded `RemoteAnnouncer.onPaymentCaptured(...)` block **after** the TTS block. | Every other line, including the credit gate, forwarder, `last_sms` write, history append and the entire `startForegroundService` block. |
| `NotificationListener.java` | Identical two edits, same order. | Same as above. |
| `MainActivity.java` | Additive only: `setupRemoteAnnouncements()` from `onCreate`, plus a best-effort drain in `onResume`. | Existing `last_sms` logic, all existing settings wiring, layout ids. |
| `activity_main.xml` | New "Remote announcements" card appended: enable switch (default off), pair buttons, paired-state line. | Existing views and every existing id. |
| `strings.xml` | New strings for the card, pairing screens and errors. | All existing strings. |
| `AndroidManifest.xml` | Phase 1: `<activity android:name=".remote.PairingActivity" android:exported="false"/>`. | Existing permissions, activities, TTS service, notification listener, SMS receiver. |
| `app/build.gradle` | **Phase 2 only** (needs approval): `firebase-messaging` + `google-services` plugin. | Phase 1 changes nothing here. |
| `CURRENT_ARCHITECTURE.md` | *(optional, last step)* Document the subsystem so the baseline stays authoritative. | — |

---

## 4. Files NOT touched (safety guarantee)

- **`ForegroundTtsService.java`** — not modified. The child *reuses* it by starting it with the same `ACTION_SPEAK`/`EXTRA_TEXT` extras; that is a call, not an edit.
- **`SmsParser.java`** — not modified. The child rebuilds phrases by calling the existing public `getAmountFromMessageBody(String, String)`.
- **`PaymentEvent.java`** — not modified, and not needed: it already exposes `eventId`, `amountPaise`, `source`, `timestampMs`.
- `PaymentHistoryStore.java`, `PaymentStats.java`, `HistoryActivity.java`, `PaymentHistoryAdapter.java` — untouched.
- `build.gradle`, `gradle.properties`, `gradle-wrapper.properties` — untouched.
- No new permission in phase 1; no `BOOT_COMPLETED`, no `AlarmManager`, no boot receiver, no WorkManager, no coroutines, no Kotlin.

---

## 5. Threat model

**What the relay can see.** Ciphertext, the routing pair ID, the child's push token, message size, and send timing. Nothing else — it cannot decrypt, and it has no key material.

**What the relay cannot see.** The amount, the payer, the raw SMS body, the UPI ID, the bank name, the source app. All of that is inside the AEAD ciphertext or simply never sent.

**What Google/FCM can see.** Stated honestly: Google is in the delivery path. App-layer encryption stops them reading content, but they still observe **metadata** — which app instance is the recipient, when, how often, and how large. That is inherent to using FCM at all.

**What a compromised relay can do.** Drop, delay, or replay messages. **Replay is mitigated** by the child's persisted `eventId` ring (last 200). **Drop/delay is a liveness problem, not a confidentiality one** — and the owner's outbox retries. Forgery fails AEAD authentication and is discarded.

**What a compromised owner device leaks.** Everything it can see, i.e. everything. A rooted owner is out of scope.

**What a compromised child device leaks.** The same — it holds the session key and decrypts every payment it receives. A lost child phone must be treated as a copy of every payment that reached it; the remedy is unpair + re-pair, which mints a new pair ID and a new session key (§11).

**Residual metadata.** Device push tokens, timing, frequency, message size (~330 bytes, near-constant regardless of amount — the amount is a fixed-width field inside the ciphertext, so it does not leak through length). The relay additionally holds a *hash* of the send token.

**Honest caveat.** This design protects the *content* of payments from the relay and from Google. It does not hide that payments occurred, and it does not protect against a compromised endpoint.

---

## 6. Crypto design (spelled out)

- **Key agreement.** ECDH on **P-256** via `KeyPairGenerator.getInstance("EC")` with `ECGenParameterSpec("secp256r1")` and `KeyAgreement.getInstance("ECDH")`. P-256 is dependable on API 24+; X25519 is not.
- **KDF.** **HKDF-SHA256**, hand-rolled over `Mac.getInstance("HmacSHA256")` because Java has no built-in HKDF at this API level. `PRK = HMAC(salt, IKM)`; `OKM = HMAC(PRK, info || counter)` (RFC 5869 §2.3). `salt = pairId` bytes, `info` = a per-purpose domain-separation string. An ECDH output is a uniformly random 256-bit secret, so a non-secret salt is sound (RFC 5869 §3.1).
- **Two derived keys.** `K_pair = HKDF(ECDH(owner_priv, child_pub), pairId, "…/pairing")` protects the one-shot introduction; `K_msg = HKDF(ECDH(owner_priv, child_pub), pairId, "…/message")` protects every payment. Domain separation means the owner can send the introduction before the child has the owner's public key, and a captured introduction cannot be replayed as a payment.
- **Per-message AEAD.** `Cipher.getInstance("AES/GCM/NoPadding")`, 256-bit key, **12-byte nonce from `SecureRandom`**, **128-bit tag**. **AAD = `"1|" + kind + "|" + pairId + "|" + eventId`** — binds version, message kind, pairing and event identity to the ciphertext, so a relay cannot move a ciphertext to another pairing, relabel its event ID, or pass an introduction off as a payment.
- **Pairing exchange.** The child generates a **16-byte `pairSecret`** plus its identity key pair and displays `base64url(v1 | pairSecret(16B) | pairId(16B) | childPub(X.509))`. The owner decodes it, so it holds the child's public key without the relay ever seeing the pairing. The owner then derives `K_pair` and sends its own public key plus a random send token inside a `K_pair`-sealed introduction. The child opens it, learns the owner's public key, and both sides compute the same `K_msg`. The `pairSecret` **never crosses the relay**, which is what stops the relay from man-in-the-middling the pairing.
- **Public key encoding.** X.509 SubjectPublicKeyInfo (91 bytes for P-256) rather than the 65-byte raw point: `getEncoded()`/`X509EncodedKeySpec` round-trips through the platform provider with no hand-rolled point parsing. This makes the pairing string ~166 characters rather than the ~130 estimated in the approved plan; the extra 36 characters are inert and cannot be reduced without custom point encoding.
- **What the relay never possesses.** The private keys, `pairSecret`, `K_pair`, `K_msg`. It sees ciphertext only.
- **Key storage.** EC private key is generated in software and stored **AES-GCM-wrapped under a Keystore-held AES-256 key** (alias `upialert_remote_kek`). Rationale: Keystore-native EC **key agreement only exists from API 31**, and minSdk is 24 — so a hardware-backed ECDH key is impossible on 24–30, and this shape works uniformly across 24–34 while keeping the private key out of plaintext.
- **Keystore unavailable → hard fail.** Remote announcements report "unavailable" and cannot be enabled. Silently downgrading to plaintext key storage is worse than not having the feature.
- **Keystore loss.** Uninstall, "clear data", factory reset or restoring a backup onto a new device leaves the wrapped record undecryptable (`allowBackup="true"` is already set, and the Keystore key does not travel with a backup). All such decrypt failures degrade to **"re-pair required"** — never a crash.
- **Rotation / re-pair.** Re-pairing mints a new `pairId`, new `pairSecret`, new `K_msg`, and overwrites the record.

---

## 7. Transport design

**Envelope — cleartext fields and why each is allowed:**

```json
{"v":1,"k":"pay","pairId":"3f1c…-uuid","eventId":"9ab2…-uuid","n":"<b64 12B nonce>","ct":"<b64 ciphertext||tag>"}
```

| Field | Reason it is cleartext |
|---|---|
| `v` | Forward compatibility; unknown version → discard. |
| `k` | Message kind: `pair` (introduction, sealed with `K_pair`) or `pay` (sealed with `K_msg`). Needed so the receiver knows which key to try. Random-value-free; leaks only that a pairing occurred. |
| `pairId` | Lets the child reject envelopes for a different pairing before doing any crypto. Random UUID — no payment information. Also bound into the AAD. |
| `eventId` | Lets the child drop an obvious replay **before** decrypting, and is bound into the AAD so it cannot be relabelled. Random UUID. |
| `n` | The nonce must be readable to decrypt. |
| `ct` | The payload. |

**Not** in the cleartext: amount, currency, timestamp, source — those live inside the AEAD ciphertext. Payment inner plaintext is exactly `{t:"pay", v, eventId, amountPaise, currency, timestampMs, source}` (Q6 minimal payload). `eventId` appears both outside (AAD / cheap dedupe) and inside (authenticated copy); a mismatch is treated as tampering and discarded.

**Payload size.** ~330 bytes on the wire. FCM's data payload limit is 4096 bytes; the envelope is hard-capped at 1 KB, and anything larger is dropped with a warning rather than truncated.

**Delivery.** Relay → FCM HTTP v1, **data-only** message, `android.priority = HIGH`, **no `collapse_key`** (every payment matters), explicit `ttl` up to the 4-week maximum. High priority is also what grants a background app the exemption to start a foreground service on Android 12+, which the child needs in order to speak (§17).

**Token registration.** At pairing, the child registers two opaque values against the `pairId`: its **FCM registration token** (immediately, first-write-wins, before the introduction arrives — the relay needs somewhere to deliver the introduction) and later a **hash of the send token** it received inside the introduction. The owner then authenticates `POST /send` with the raw send token. The relay holds no key material: the send token is *not* derived from `K_msg`, so possessing it lets an attacker at most spam ciphertext the child will refuse and cannot read.

**Token rotation is handled without re-pairing.** The owner never needs the child's address — it only posts to the relay. When the child's FCM token rotates, `FcmReceiveService.onNewToken` re-registers with the relay and the owner's flow is unaffected. This removes the stale-token failure mode entirely.

**Retry / backoff.** Owner side: durable outbox, attempts 1–5 with exponential backoff (2 s → 5 min cap); after 5 attempts the entry is dropped and the `eventId` is logged with "dropped". Drop-oldest when the queue exceeds its cap.

---

## 8. Owner-side flow — before/after for both listeners

**`SmsListener.onReceive` — AFTER** (only the marked lines are new; the TTS block is reproduced unchanged to show where it sits):

```java
            String lang = prefs.getString("language", "English");
            String textToRead = smsParser.getAmountFromMessageBody(messageBody.toString(), lang);

            prefs.edit().putString("last_sms", textToDisplay).apply();

            PaymentEvent ev = null;                                   // <- NEW: hoisted declaration
            // Payment history capture (unchanged behaviour)
            try {
                SmsParser.AmountResult ar = smsParser.extractAmount(messageBody.toString());
                ev = PaymentEvent.capture(                            // <- CHANGED: was "PaymentEvent ev = ..."
                        PaymentEvent.Source.SMS, address, messageBody.toString(),
                        textToRead, textToDisplay, ar.paise, ar.raw);
                PaymentHistoryStore.append(context, ev);
            } catch (Exception ignored) {
            }

            // ---- UNCHANGED, BYTE-IDENTICAL: local TTS fires first ----
            try {
                Intent svc = new Intent(context.getApplicationContext(), com.example.upipaymentalert.ForegroundTtsService.class);
                svc.setAction(com.example.upipaymentalert.ForegroundTtsService.ACTION_SPEAK);
                svc.putExtra(com.example.upipaymentalert.ForegroundTtsService.EXTRA_TEXT, textToRead);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.getApplicationContext().startForegroundService(svc);
                } else {
                    context.getApplicationContext().startService(svc);
                }
            } catch (Exception ignored) {
            }

            // ---- NEW: remote announcement, strictly AFTER speech ----
            try {
                if (ev != null) {
                    RemoteAnnouncer.onPaymentCaptured(context.getApplicationContext(), ev);
                }
            } catch (Exception ignored) {
            }
```

`NotificationListener.onNotificationPosted` gets the identical treatment: hoist `PaymentEvent ev = null;`, reuse the same event ID, keep the `last_sms` write and the entire `startForegroundService` block untouched, and append the same guarded remote block last.

**Why nothing can escape into the listener.** The remote call is wrapped in its own `try { } catch (Exception ignored) { }`, exactly like the two existing best-effort blocks. `RemoteAnnouncer.onPaymentCaptured` additionally begins with an enable/paired check and returns immediately when off, does only AES-GCM of ~120 bytes plus a small file append on the calling thread, and hands the network work to a background drainer. It is declared to never throw; the wrapper is the belt to that braces. There is **no `if (online)` guard anywhere on the speech path**, and the remote call is strictly downstream of it.

**The one non-additive line, called out.** Hoisting `PaymentEvent ev = null;` and turning `PaymentEvent ev = ...` into `ev = ...` edits two existing lines. It is behaviour-preserving (same object, same calls, same order) and exists so the remote layer reuses the *same* `eventId` the history row got — which is what makes child-side dedupe meaningful.

---

## 9. Owner-side durable queue (Q4 = durable)

- **Location:** `getFilesDir()/remote_outbox.jsonl`.
- **Line format:** one JSON object per line — `{"pairId":"…","env":{…envelope…},"a":0,"dueMs":0}`.
- **Append:** called from the listener via `RemoteAnnouncer`; `synchronized`, single `write` + `flush` of one line, never throws.
- **Drain:** a single background thread (guarded by an `AtomicBoolean`) reads all lines, sends those that are due, and rewrites the file with only the failures and their incremented attempt counts. Envelopes are never lost by a failed drain.
- **Cap:** 500 entries, drop-oldest on overflow, each drop logged with its `eventId`.
- **Partial write:** a truncated final line fails JSON parsing and is skipped on read — the same tolerance `PaymentHistoryStore` applies — and a drain rewrites the file, repairing it.
- **Trigger points:** after each enqueue; `MainActivity.onResume`. Deliberately **no** WorkManager, JobScheduler or AlarmManager, so the honest limitation is that a queued item waits until the next payment or app open.
- **Idempotence:** a transport that returns "unknown" (e.g. a timeout after FCM accepted) can cause a *duplicate delivery*, which the child's `eventId` ring absorbs.

---

## 10. Child-side flow

1. **Entry point.** Phase 1: `RemoteIngest.handle(context, envelopeJson)`. Phase 2: `FcmReceiveService.onMessageReceived` reads `data["env"]` and calls the same method. One pipeline, two entry points.
2. **Parse + gate.** Envelope must parse, `v == 1`, and `pairId` must equal the stored pairing; otherwise discard (log `pairId` only).
3. **Decrypt.** AAD = `"1|kind|pairId|eventId"`; key = `K_pair` when `k == "pair"`, else `K_msg`. `AEADBadTagException` → log the `eventId` and "auth failed", discard. Never throws, never retries itself.
4. **Validate.** Inner `v` known; `eventId` matches the cleartext copy; `currency == "INR"`; `timestampMs` sane (not more than 1 day in the future, not older than 365 days).
5. **Dedupe (Q5).** `RemoteChildStore.seenBefore(context, eventId)` — the last 200 event IDs persisted as JSON lines in `getFilesDir()/remote_seen.jsonl`. **The ID is recorded before speech**, so a crash mid-utterance cannot cause a double announcement. Fail-open: an I/O failure reports "not seen", because announcing a real payment matters more than suppressing a rare duplicate.
6. **Speak (Q7).** `RemotePhrase.phraseFor(context, amountPaise)` builds a synthetic body `"RS <rupees>.<paisa>"` and passes it to the **unmodified** `SmsParser.getAmountFromMessageBody(body, childLanguage)` — wording comes from the one parser, the child's own language setting is respected, and there is no second copy of the phrasing to drift. `amountPaise == -1` yields the parser's existing "unknown amount" sentence. The child then starts **`ForegroundTtsService`** with the same `ACTION_SPEAK` + `EXTRA_TEXT` extras the owner uses — same engine, same language/volume/speed handling, same segmented-speed splitting, same 60-second phrase dedupe as a second layer. No second speech stack, and `ForegroundTtsService.java` is not modified.
7. **Discard.** The plaintext is dropped when the method returns. Nothing is persisted on the child except the opaque event-ID ring.

---

## 11. Pairing UX — and one honest deviation

**The deviation, stated plainly.** The brief specifies a short code (`PAY-482913`) shown on one device and entered on the other. A 6-digit code **cannot carry a public key plus a secret**, and there is no backend to look anything up in. So the transferable artifact is a **~166-character pairing string**, and the short numeric code keeps a different, equally important job: after pairing, **both devices display the same 6-digit verification fingerprint** derived from the session key. If the two screens agree, the pairing is genuine — the same human-verifiable idea as Signal's safety numbers, and it is the actual defence against a man-in-the-middle.

- **Child side.** *Pair as child device* → the app generates its identity and secret, and shows the pairing string with a **Copy / Share** action. The fingerprint appears once the owner's introduction arrives.
- **Owner side.** *Pair a device* → paste the string → the owner decodes the child's public key, derives `K_msg` immediately, enqueues the `K_pair`-sealed introduction, and shows the verification fingerprint at once (it needs nothing back from the child).
- **Per your Q3 choice, the child never needs the owner's address** — the owner is always the sender, so only the owner needs to reach the child.
- **Unpair.** Either side may unpair. Locally it destroys the pairing record and keys and best-effort deletes the relay registration. The peer notices as an authentication failure on the next message and marks itself unpaired rather than erroring.
- **Lost device.** Unpairing revokes *future* delivery, but the lost device retains what it already received and can still decrypt anything already forwarded. A lost child phone must be treated as a copy of the payments it saw: **unpair and re-pair**, minting a new `pairId` and new `K_msg`.
- **Cardinality.** v1 supports **one active pairing per device pair**. The outbox keys entries by `pairId`, so N children is a later change, not a redesign.

---

## 12. Settings UI additions

A new "Remote announcements" card appended to `activity_main.xml`, below the existing forwarder section: an enable switch (**default OFF**), "Pair as child device" / "Pair a device (owner)" entry to `PairingActivity`, a paired-state line (role + verification fingerprint, or "Not paired"), and an unpair control in the pairing screen. If the Keystore check fails, the card renders an explanatory line and the switch is disabled — the only correct behaviour given §6's hard-fail rule. Everything is additive; no existing view or id changes.

---

## 13. Manifest changes

**Phase 1 (this task):**
- `<activity android:name=".remote.PairingActivity" android:exported="false"/>` — internal to the app; nothing outside it should launch it.

That is the *only* manifest change in phase 1 — no permission, no receiver, no service. Which is precisely why "zero network calls when remote is off" is trivially true in phase 1.

**Phase 2 (after approval + `google-services.json`):**
- `<uses-permission android:name="android.permission.INTERNET"/>` — required for the relay POST and FCM.
- `<service android:name=".remote.FcmReceiveService" android:exported="false">` + intent filter `com.google.firebase.MESSAGING_EVENT`.
- `<meta-data android:name="firebase_messaging_auto_init_enabled" android:value="false"/>` — so the SDK does not register with Google at process start, which would otherwise break the "off means zero network" invariant the moment the dependency is added.
- `<provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="${applicationId}.firebaseinitprovider" tools:node="remove"/>` — removes Firebase's auto-init provider for the same reason.

**Explicitly absent:** no `BOOT_COMPLETED`, no boot receiver, no `AlarmManager`, no `WAKE_LOCK`, no camera permission (QR pairing was not chosen).

---

## 14. Implementation order

Pure additions first — **steps 1–7 touch no existing file and no live path at all**:

| # | Step | Class |
|---|---|---|
| 1 | Write this plan to `ONLINE_SYNC_ARCHITECTURE_PLAN.md` | doc only |
| 2 | `RemoteCrypto` + `RemoteIdentity` + `PairingCode` | pure addition |
| 3 | `tools/remote_crypto/RemoteCryptoHarness.java` and run it | pure addition, off-device |
| 4 | `RemoteKeys` (Keystore wrap, hard-fail path) | pure addition |
| 5 | `RemotePairingStore` + `RemoteTransport` + `NoopTransport` | pure addition |
| 6 | `RemoteEnvelope` + `RemoteOutbox` + `RemoteDrainer` + `RemoteAnnouncer` | pure addition |
| 7 | `RemotePhrase` + `RemoteChildStore` + `RemoteIngest` | pure addition |
| 8 | `strings.xml`, `activity_pairing.xml`, `PairingActivity`, manifest entry | touches manifest (not the payment path) — build + report |
| 9 | `MainActivity` card wiring + `setupRemoteAnnouncements()` + drain on resume | touches live-path file — **build + report** |
| 10 | `SmsListener`: hoist + guarded remote block | **touches live path — build + report + diff proof** |
| 11 | `NotificationListener`: same | **touches live path — build + report + diff proof** |
| 12 | On-device verification (§15) | — |
| 13 | *(phase 2, separate approval)* `RelayTransport`, `FcmReceiveService`, gradle deps, manifest, `google-services.json` | touches live path — build + report |
| 14 | Document the subsystem in `CURRENT_ARCHITECTURE.md`; final invariant greps | doc only |

Seven consecutive pure-addition steps precede the first live-path change, and the two listener edits are last, one at a time, each followed by a build.

---

## 15. Test plan

**JVM harness (`tools/remote_crypto/`, `javac` + `java`, no new dependency — `RemoteCrypto` imports only `javax.crypto`/`java.security`, and `SmsParser` imports only `java.util.regex`):**
- ECDH round-trip: two independently generated key pairs derive the **same** `K_msg`.
- HKDF vectors: fixed IKM/salt/info → stable output; changing `info` changes the key.
- AES-GCM round-trip in both directions; cross-pairing rejection.
- **Tamper rejection:** flip one bit of `ct` → auth failure; wrong AAD → auth failure; swapped `eventId` in the AAD → auth failure; `k=pay` ciphertext must not open under `K_pair`.
- **Phrase equivalence (the drift guard):** for a table of amounts, `childPhrase(amountPaise)` must equal the owner's phrase for the same amount, using the real `SmsParser` source.
- *(Note: `./gradlew test` is pre-existing broken — `SmsParserTest` calls a removed one-argument method and has no junit dependency. The harness is deliberately independent of it.)*

**On-device (emulator, phase 1, via the no-op transport plus a debug-only inbox under `src/debug/`, which cannot ship in a release build):**
- Pair end-to-end; a test envelope walks decrypt → validate → dedupe → **audible speech**.
- **Replay:** the same envelope twice → spoken once.
- **Owner offline:** local speech still fires, event queued; back online → delivered in order.
- **Child killed/restarted:** no crash; still exactly one announcement.
- **Restart:** pairing, outbox and the seen-ID ring survive `am force-stop`.
- **Failure injection:** corrupt ciphertext, wrong pairId, unknown `v`, absurd timestamp — each discarded with a warning, no crash.
- **Compromised-relay simulation:** the outbound envelope is written to a file; grep the log and the file for the amount and confirm it is absent.

**Phase 2 only:** real push delivery, token rotation via `onNewToken`, and a live relay inspection proving only ciphertext crosses the wire.

---

## 16. Verification checklist

| Check | Phase |
|---|---|
| `./gradlew assembleDebug` succeeds | 1 (after every live-path step) |
| Local TTS unchanged on a fresh install with remote OFF (default) | 1 |
| 60-second phrase dedupe unchanged | 1 |
| `last_sms` format unchanged | 1 |
| **No plaintext payment data in any new `Log` call** (grep the diff) | 1 |
| Remote disabled ⇒ zero network calls | 1 (trivially: no network code compiles in) / 2 (auto-init removal) |
| Pairing produces the identical session key on both sides + matching fingerprint | 1 |
| A test payment reaches the child and is **spoken** | 1 (no-op transport + inbox) / 2 (push) |
| Owner's network killed ⇒ speech unaffected, event not lost | 1 |
| Child's network killed ⇒ no crash, no duplicate on reconnect | 1 |
| Relay sees only ciphertext | 2 |
| Diff proof: `ForegroundTtsService.java` and `SmsParser.java` unmodified; both `startForegroundService` blocks byte-identical | 1 |

---

## 17. Risks, ambiguities, uncertainties — stated plainly

1. **FCM cannot send device→device.** Verified against Firebase's own architecture doc, which requires a sender in "a trusted server environment". Option (a) as originally written ("no backend to run") does not exist, which is why phase 2 targets the tiny relay holding the send credential. The relay is a **liveness dependency**: if it is down, remote announcements stop (local speech is unaffected and the outbox retains everything).
2. **The short pairing code could not be implemented as specified.** A 6-digit code cannot carry a public key; the transferable artifact is a ~166-char string and the numeric code is repurposed as a verification fingerprint (§11). QR pairing would fix the UX friction but needs a camera permission — deferred.
3. **FCM delivery is not guaranteed.** Data messages can be delayed or dropped; a child offline for days receives them when it returns within the TTL (up to 4 weeks, which is why messages are non-collapsible with an explicit TTL). Beyond that they are lost — nothing re-sends, because the relay stores nothing by design.
4. **OEM battery killers.** Xiaomi/Huawei/Oppo and others routinely kill background apps; Doze can defer even high-priority messages. The child may silently stop receiving. Nothing in this design fixes that.
5. **Foreground-service start on Android 12+.** The child starts `ForegroundTtsService` from a push. High-priority FCM grants the exemption, and the debug path is foreground, but this is the most likely runtime failure on the child and will be tested explicitly.
6. **Keystore across API 24–34.** Hardware backing varies, some devices have broken TEEs, and there is no hardware ECDH below API 31 (hence the wrapped-software-key design). Any loss of the KEK degrades to "re-pair", never to plaintext.
7. **The relay stores two opaque values per pairing** (send-token hash, child push token). That is token bookkeeping, not payment data — but it *is* server-side state, and "stores nothing" applies strictly to payment events, not to routing.
8. **Metadata is not hidden.** Google sees timing, frequency, size and the recipient token; the relay additionally sees the pair ID and the sender's IP. Content is protected; the pattern of payments is not.
9. **Pre-existing plaintext egress left alone.** `ForegroundTtsService` line 154 logs the full spoken phrase — including the amount — on every suppressed duplicate; and the SMS forwarder sends the entire raw body in plaintext to a phone number. Both predate this task and both undercut the privacy goal. Fixing the log line means editing `ForegroundTtsService.java`, which the invariants forbid, and removing the forwarder changes an existing feature. Left alone and recorded here.
10. **`./gradlew test` remains pre-existing broken** (`SmsParserTest` calls a removed one-argument method; junit is absent). The JVM harness deliberately does not depend on it. Repairing it would add a junit dependency, which needs approval.
11. **Nonce budget.** Random 96-bit GCM nonces are safe to ~2^32 messages under one key; this app sends a handful per day.
12. **Outbox drain is opportunistic.** With no WorkManager/JobScheduler/AlarmManager, a queued event waits for the next payment or app open. Durability is guaranteed; *promptness* is not.
13. **The payload has no `currency` field in `PaymentEvent`.** The envelope synthesises `"INR"`, correct for every pattern the parser matches but an assumption rather than a stored fact.
14. **`amountPaise == -1` is still announced remotely**, using the parser's existing "unknown amount" sentence.
15. **Phase 1 cannot verify the push leg.** Until the Firebase project and relay exist, "a payment reaches the child" is proven over the no-op transport plus the debug inbox, not over FCM.

---

## 18. Answers to Q1–Q10 (all locked with you)

| Q | Decision |
|---|---|
| Q1 | **Transport:** behind a `RemoteTransport` interface; the deliverable wire is the **tiny relay holding the FCM send credential** (stateless, ciphertext-only, store-nothing). Corrected from "(a) FCM direct, no backend", which is not physically possible. |
| Q2 | **Crypto:** P-256 ECDH + AES-256-GCM + HKDF-SHA256, `javax.crypto`/`java.security` only. |
| Q3 | **Pairing:** child displays the pairing string, owner captures it; the short code becomes a **verification fingerprint**. |
| Q4 | **Owner queue:** durable JSON-lines outbox in `getFilesDir()`. |
| Q5 | **Child dedupe:** persist the last 200 event IDs to a file. |
| Q6 | **Payload:** minimal — `eventId, amountPaise, currency, timestampMs, source`. No payer, no UPI ID, no raw body. |
| Q7 | **Child speech:** reuse `ForegroundTtsService` via the same `ACTION_SPEAK` extras; phrase rebuilt through the **unmodified** `SmsParser`. |
| Q8 | **Moot** — no Supabase at all, hence no `devices`/`pairings`/`push_tokens` schema and no `payment_events` table. |
| Q9 | **Keys:** Android Keystore wrapping a software EC key (works on all of API 24+). |
| Q10 | **Rollout:** hard OFF until the user both enables it and pairs. |
| — | **Unanswered:** the pre-existing plaintext log line. Default action: leave it, flagged in §17.9. |

---

## Scope of this plan, in one line

Sections 2–12, 15 and 16 describe **phase 1** — the whole privacy layer, built and verified with **no new dependency, no new permission and no network code**, so it can be judged and tested without a Firebase project; **phase 2** (step 13) is the wire, and it starts only when you approve the `firebase-messaging` dependency and supply `google-services.json` plus the relay endpoint.
