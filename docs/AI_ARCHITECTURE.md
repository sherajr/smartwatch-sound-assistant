# AI assistant — architecture

How the optional AI assistant is built, what it guarantees, and where each guarantee is enforced and
tested. For setup steps (keys, Google, signing) read [AI_SETUP.md](AI_SETUP.md). For the DSP and measurement
semantics read [MEASUREMENTS.md](MEASUREMENTS.md).

> **Status of verification.** Everything below is covered by JVM unit tests (shared 106, phone 182, watch 241 at
> the time of writing, all passing) and the screens have been driven on a round Wear OS 6 emulator and a phone
> emulator — **except** what needs a real account, a real network or real hardware: live calls to the four
> AI providers, Google consent, Gmail/Calendar against Google's servers, Wear OS Data Layer delivery between a
> real watch and phone, real speech recognition/TTS, and on-screen layout on a physical round display. Those are
> listed in [§17](#17-what-is-and-isnt-verified).

## 1. Shape of the system

```
 ┌────────────────────── Pixel Watch (Wear OS) ──────────────────────┐        ┌──────────── Pixel phone ────────────┐
 │ :app  (com.peaceantz.stagescope)                                  │        │ :phone (com.peaceantz.stagescope)   │
 │                                                                   │        │                                     │
 │  ANALYZER ─┐        MeasurementHub ──► MeasurementSnapshotBuilder │ Message│  WatchListenerService               │
 │  RING     ─┼─ one shared CaptureSession                           │ Client │   └► PhoneMessageHandler            │
 │            │   ▲   │ pause / resume                               │◄──────►│       persist + ack, then WorkManager│
 │  ASSISTANT │   │   ▼                                              │        │  AssistantOrchestrator ─► adapters  │
 │   page     │  AudioCoordinator (leases)                           │ Data   │   (OpenAI · Gemini · xAI · Claude)  │
 │            │                                                      │ Client │  ToolRegistry (typed, validated)    │
 │  AssistantRepository  (outbox · cache · memos · prefs)            │◄──────►│  ActionExecutor ─► Gmail / Calendar │
 │  WatchIssueRepository (local replica of the issue log)            │ Channel│  CredentialStore (Android Keystore) │
 │  SpeechInput · VoiceRecorder · SpeechOutput                       │ Client │  Show · Issues · Usage · Settings   │
 └───────────────────────────────────────────────────────────────────┘ voice  └─────────────────────────────────────┘
                              both depend on  :shared  (pure Kotlin/JVM, no Android)
```

* **`:shared`** — pure Kotlin, fully unit-tested: the wire protocol, `MeasurementContext` and its comparison/quality rules,
  the issue CRDT, the action state machine and its confirmation binding, show/recipient/report logic, calendar time
  resolution, and `PersistentState` (atomic JSON files).
* **`:app`** — the Wear OS app. The two instruments (Analyzer, Ring) are unchanged and **never depend on the
  assistant**: nothing in the DSP/capture path imports it, and the app works with no phone, network, account or Google
  services.
* **`:phone`** — the companion. It is the only place that holds keys, talks to AI providers or Google, and executes actions.

The watch and phone apps share one `applicationId` (`com.peaceantz.stagescope`) and **must** be signed with the same
key — the Wear Data Layer only connects two apps with the same package name *and* signing certificate
(`scripts\check-signing.ps1`).

## 2. Invariants (the rules everything else serves)

| # | Invariant | Enforced in | Tested in |
|---|-----------|-------------|-----------|
| 1 | Nothing is emailed or put in a calendar without a UI confirmation bound to the exact draft revision, content hash and Google account | `ActionMachine`, `ActionRepository.confirmAndBegin`, `ActionExecutor` | `ActionMachineTest`, `ActionExecutorTest` |
| 2 | A model can only *propose*; no tool sends, creates, or approves anything, and an `approved`/`confirmed` argument is refused | `ToolRegistry`, `SchemaValidator`, `ToolSchemas` | `ToolRegistryTest` |
| 3 | An ambiguous send (timeout/5xx/reset after the request was written) is *outcome uncertain* and is never retried automatically | `GmailClient`, `ProviderHttp` (`retryOnConnectionFailure(false)`), `RetryPolicy` | `GmailClientTest` (request-count assertions) |
| 4 | API keys are user-entered, Keystore-encrypted, never bundled, logged, backed up, sent to the watch, or shown to a model | `CredentialStore`, `Redactor`, manifest backup rules | `CredentialStoreTest`, `ThreadPublisherTest` |
| 5 | No silent provider/model substitution; a failure names the provider and model that failed | `AssistantOrchestrator`, `ProviderService` | `OrchestratorTest`, `ProviderServiceTest` |
| 6 | Measurement evidence is built from real DSP state, keeps the capture time it had, and separates raw dBFS from Estimated SPL | `MeasurementSnapshotBuilder` | `MeasurementSnapshotBuilderTest` |
| 7 | A pinned/held/restored ring is history, not proof a tone is sounding now | `RingFreshnessClassifier` | `MeasurementSnapshotBuilderTest`, `MeasurementQualityTest` |
| 8 | The microphone is lent to the assistant under one exclusive lease and measurement resumes only if it should | `AudioCoordinator`, `CaptureSession` | `AudioCoordinatorTest` |
| 9 | A stale queued question is never auto-sent | `OutboxPolicy` | `OutboxPolicyTest`, `AssistantRepositoryTest` |
| 10 | Issue edits made on both devices while apart are all kept (no wall-clock "last write wins") | `IssueSync` (CRDT) | `IssueSyncTest`, `PhoneIssueSyncTest`, `WatchIssueRepositoryTest` |
| 11 | A Keep item is never reported as added: the only honest states are "Ready on phone" and "marked done by you" | `ActionMachine`, `ActionPresentation` | `ActionMachineTest` |
| 12 | The assistant never starts a microphone from a Tile, complication, notification or shortcut | `ShortcutRequest`, `StageScopeAskTileService` | `ShortcutRequestTest` |

## 3. Wire protocol (watch ⇄ phone)

Only supported Wear Data Layer clients are used — no custom Bluetooth socket. Version 1; both sides advertise
`[min, max]` in a `Hello` and speak the highest common version (no overlap → no views, and the watch says so).

| Channel | Path | Carries |
|---|---|---|
| `MessageClient` | `/stagescope/v1/msg` | JSON `Envelope{v, sender, seq, message}` where `message` is a sealed `WireMessage` |
| `DataClient` | `/stagescope/v1/thread/<conversationId>` | `ThreadView` — the phone's durable answer for one conversation |
| `DataClient` | `/stagescope/v1/providers` | `ProvidersView` — which providers have a key, selected model, Google/notification readiness (**never a key**) |
| `DataClient` | `/stagescope/v1/shows` | `WatchShowView` — production/performance *names* only (no addresses) |
| `DataClient` | `/stagescope/v1/issues/<replicaId>/<issueId>` | one replica's `IssueState` (each device writes only its own replica path) |
| `ChannelClient` | `/stagescope/v1/voice/<memoId>` | a short PCM recording too large for a message |

Capabilities (`res/values/wear.xml`): `stagescope_watch_app`, `stagescope_phone_companion`.

Messages: `Hello`, `AssistantRequest`, `Ack`, `CancelRequest`, `Progress`, `ResultReady`, `StatusQuery`/`StatusReply`,
`ActionCommand`/`ActionReply`, `ContinueOnPhone`/`ContinueReply`, `ProviderSelect`, `PlaybackNotice`, `SyncNudge`,
`VoiceOffer`, `TranscriptResult`.

Rules:

* **A successful `sendMessage` only means "handed to the transport".** What counts as delivery is the phone's own
  `Ack` (`RECEIVED` / `DUPLICATE` / `REJECTED`). A result is read from the durable `ThreadView`, so a watch that was
  offline when it finished simply reads the latest revision on reconnect.
* **Idempotency keys:** request ids (questions) and operation ids (action commands). A retransmit is recognised and
  answered `DUPLICATE`; it never runs twice.
* **Payload cap:** 60 KB per message/data item (`Wire.MAX_PAYLOAD_BYTES`; the platform limit is higher). An oversize
  question is retried with a trimmed measurement and, if it still cannot fit, sent without it — never dropped.
* **Ordering:** never assumed. State only moves forward (§9), so duplicated or reordered messages are harmless.

## 4. Durable state

All persistence is plain JSON via `PersistentState` (no Room / DataStore): one `Mutex` serialises every
read-modify-write; writes go to a temp file, are fsynced, then renamed over the target; a file that no longer decodes is
**quarantined** (`*.corrupt-N`) rather than overwritten; each file carries a schema version with a migration hook.

| Device | Files (under `filesDir/…`) | Notes |
|---|---|---|
| Phone | `stagescope/conversations.json`, `actions.json`, `inbox.json`, `shows.json`, `issues.json`, `settings.json`, `usage.json`, `measurements.json` | canonical ledger for conversations, actions, requests |
| Phone | `noBackupFilesDir/credentials.json` | AES-256-GCM ciphertext only; excluded from backup |
| Watch | `assistant/outbox.json`, `phone_cache.json`, `prefs.json`, `memos.json`, `issues.json`; `assistant/memos/*.pcm` | the watch keeps caches, its own offline entries, and short recordings until transcribed |

The phone's **request inbox** makes every watch request durable *before* it is acknowledged, then hands it to
WorkManager (expedited, unique per request id). A `WearableListenerService` callback therefore only persists and
acks — long work never depends on a coroutine that dies with the service. A request found `RUNNING` by a new
worker (the previous process died) is marked **interrupted** and surfaced as "tap Retry"; it is not silently re-run,
because that could repeat a billed model call or a tool's effect.

## 5. Measurement evidence (`MeasurementContext`, schema v1)

Built on the watch from the live DSP state the person is looking at — never a screenshot, never reconstructed by a model:

* **Timing.** The snapshot is taken at the moment the person taps to ask, **before** the microphone is borrowed or any
  speech occurs, and keeps that `capturedAtEpochMs`. If it is sent later (a voice memo, a queued question) it is never relabelled as fresh.
* **Raw vs. calibrated.** `level.rmsDbfs` / peak / max / session average are **raw dBFS**. The calibration offset applies to the
  RMS-derived estimate only (`calibration.estimatedSplDb`), never to the spectrum or the sample peak. A stored calibration
  that doesn't apply to the current input configuration is reported as not applied.
* **Rings.** Each ring carries `lastObservedAgoMs`, `trackingDurationMs`, pinned/restored flags, and a **freshness**
  (`LIVE_NOW` = heard within 0.4 s while capture runs; `RECENTLY_SEEN`; `STALE`; `RESTORED_NOT_REOBSERVED`) derived from observation timing, **not**
  from the pin. `prominenceDb` is detector contrast, labelled as such — not a probability of feedback.
* **Spectrum.** 56 log-spaced bands (each the max of its raw bins), up to 8 native-resolution peaks with contrast and neighbouring bins, a crude noise-floor estimate.
* **History.** A ~10 s, ≤40-point in-memory trail (4 Hz), only fed while measurement runs, never persisted, never uploaded on its own.
* **Run state.** `RUNNING` / `PAUSED_FOR_SPEECH` / `STOPPED` / `NOT_STARTED` / `ERROR`, Freeze, and — when not running — how old the readings are.
* **Caveats are deterministic** (`MeasurementQuality.notes`): demo data, single wrist microphone, clipping is of the watch input only, uncalibrated dBFS is not SPL, etc.
* **Size.** ≤ 24 KB; over budget it drops history → neighbour bins → extra peaks → bands (in that order) and says what it dropped.

## 6. Audio ownership (`AudioCoordinator`)

One rule in one place. A voice interaction takes a **lease** (`LISTENING`, `SPEAKING`, or `PHONE_PLAYBACK`). The first lease pauses the
shared `CaptureSession` (`CaptureStatus.Paused`) and waits for the microphone to be truly released; when the last lease ends
measurement resumes **only if**:

1. it was actually running when the first lease began (a stopped session is never started),
2. it is still paused (pressing Stop, or the 2-minute keep-awake countdown ending, cancels the resume),
3. the app is in the foreground, and
4. `RECORD_AUDIO` is still granted.

Otherwise the paused session is ended cleanly rather than left half-alive. A pause is **not** a stop: the session, its listeners, meters,
Freeze state, Ring bank and pins are untouched; Analyzer keeps showing the last reading with a "PAUSED · listening/speaking/phone" badge. A demo session never opens the
microphone, so it is never paused. Starting measurement while the phone is speaking pauses it immediately.

*Phone playback:* the phone announces `PlaybackNotice(STARTED)` before speaking and re-announces every 10 s; the watch lease expires 30 s after the last
announcement, so a lost "stopped" message cannot leave measurement paused.

## 7. Speech

* **Input** is push-to-talk, bounded (10/20/30 s, hard cap 30 s), one utterance, ending in a **transcript the person reviews** before anything is sent.
* **Watch recognition is on-device only** (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, API 31+). If unavailable, the watch **records** a short clip and the phone
  transcribes it (on-device there; OpenAI cloud transcription only if the person enabled it, with its cost recorded). The audio is deleted once transcribed.
* **Voice memos** made while the phone is away wait on the watch with a visible status; their pre-recording snapshot is kept with its original time.
* **Output** is silent by default (theatre mode ON). Replies are spoken only when the person taps **Speak** (a confirm step if nothing private like headphones is connected),
  or — with theatre mode OFF *and* voice replies chosen *and* the app on screen — automatically. Haptics are off by default.

## 8. Provider layer (`:phone` → `ai/`)

One real adapter per vendor; no mock or "coming soon" adapter ships. Each streams, assembles tool-call arguments completely before executing, supports cancellation and reports usage.
`ProviderHttp` is the single OkHttp wrapper: fixed approved hosts only (a request to anything else is blocked before it leaves), https required, **redirects off**
(a 3xx can't carry a key elsewhere), **hidden retries off**, sends use a fresh connection, nothing logs headers or bodies, and `Redactor` scrubs key-shaped text from anything shown.

Contracts verified against each vendor's documentation on **2026-10-01** (re-verify before trusting prices or ids — they change):

| Provider | Endpoint & auth | Streaming / tools | Notes |
|---|---|---|---|
| **OpenAI** | `POST https://api.openai.com/v1/responses`, `Authorization: Bearer` | SSE; `function_call` output items; args from `response.function_call_arguments.*` and the completed item; next request replays output items (incl. encrypted reasoning, `store:false`) + `function_call_output` | `GET /v1/models` for key check; web search tool optional (extra cost) |
| **Google Gemini** | `POST https://generativelanguage.googleapis.com/v1beta/interactions`, `x-goog-api-key` | **Interactions API** (not `generateContent`); stateless `store:false`; `function_call` steps with `arguments_delta`; thought signatures echoed for the tool loop only | grounding optional (free allowance then per-1,000) |
| **xAI Grok** | `POST https://api.x.ai/v1/responses`, `Authorization: Bearer` | Responses grammar shared with OpenAI; whole-call chunks; **server-reported cost** used when present | search tools optional |
| **Anthropic Claude** | `POST https://api.anthropic.com/v1/messages`, `x-api-key` + `anthropic-version` | SSE; `tool_use` + `input_json_delta` joined at `content_block_stop`; whole assistant content echoed for the tool loop | thinking always on for the listed models (effort via `output_config`) |

The model catalog (`ModelCatalog`) lists ids, context/output limits, **prices with the date they were checked**, scheduled price changes, and whether web search is offered.
A model the vendor rejects for a given key surfaces as *"model unavailable — pick another"*; it is **never** silently replaced. Estimated cost uses a dated table (or the provider's own figure when it reports one) and is always labelled *estimated*, never an invoice.

**Provider-neutral conversation.** StageScope owns the history. Vendor-specific reasoning blocks, signatures and response ids live only in memory for the single tool loop that produced them and never reach disk or another vendor; a prior turn's tool calls are rendered to a *different* provider as
"StageScope already completed…" text, so a provider switch keeps context without replaying a side effect.

## 9. Orchestration, tools, and retry rules

`AssistantOrchestrator.run` = durable inbox claim → conversation + measurement context → neutral bounded history → one streamed provider call at a time → bounded tool loop (≤ 6) → durable assistant turn → durable watch view.

**Tools** (typed, locally validated with a JSON-schema subset that **rejects unknown keys**): `get_measurement_context`, `compare_measurements`, `list_issues`, `read_issue`, `log_issue`,
`update_issue`, `get_report_evidence`, `draft_email`, `prepare_keep_item`, `prepare_calendar_event`, `continue_on_phone`. A malformed or invalid call becomes an *error result the model can read* stating nothing was done.
User-written text (issues, dictation) is returned wrapped as **data**; nothing in it can add a tool, a permission or a recipient. Recipients resolve only from configured, verified contacts or an address the person actually said.

| Operation | Automatic retry? | Why |
|---|---|---|
| AI request | Up to 2×, backoff, **only before any output/tool call was produced** | never repeats shown text or a billed mid-stream call |
| Cancelled request | never | cancellation is the person's decision |
| Interrupted request (worker died) | never (shown as "tap Retry") | could repeat a charge or a side effect |
| Gmail send | **never** | ambiguity = *outcome uncertain*; the person checks Gmail |
| Calendar create | yes, **same persisted event id**; a 409 is reconciled (fetch + verify our private marker), not assumed | idempotent by id |
| Local issue log | yes | idempotent by operation id |
| Keep handoff | user only | there is no API to retry |
| Watch → phone question | same request id, bounded | phone dedups by id |

## 10. Actions and confirmation

States: `DRAFT → AWAITING_INFORMATION / AWAITING_REVIEW / AWAITING_PHONE → EXECUTING → COMPLETED / FAILED / CANCELLED / OUTCOME_UNCERTAIN`.

* A **Confirmation** exists only because the application UI recorded one; it is bound to `{actionId, revision, contentHash, accountId}` and expires after 10 minutes.
  Any content change (even one word) bumps the revision and voids it. The hash covers the executable content (recipients sorted and lower-cased; assumptions excluded).
* `confirmAndBegin` validates the confirmation **and** moves the action to `EXECUTING` in one locked, persisted step. A watch tap and a phone tap, or a retransmitted message,
  all enter there and exactly one wins; the state is on disk *before* any network call.
* **Gmail**: `gmail.send` via `AuthorizationClient`; the token's identity must equal the confirmed account. Outcomes: `Sent(messageId)`, `NotSubmitted` (safe to fix and retry by hand), `Uncertain` (never auto-retried).
* **Calendar**: `calendar.events.owned`; the draft stores its `eventId`, timezone, local times **and the UTC offsets** resolved for them. "Tomorrow" is resolved in the *show's* zone; a time in a DST gap is shifted and a repeated hour takes the first occurrence — both shown as assumptions; the duration is elapsed time.
  Events with invitees tell Google to email invitations (the review card says so).
* **Keep**: honest hand-off ("Ready on phone"): share-to-Keep as a new note, copy text, or a copy-able command for Gemini; completion can only ever be *marked by you*.
* **Continue on phone**: the watch asks the phone (by stored id) and also requests a Wear remote activity; the watch says "Opened on phone" only after the phone itself confirms the item is on screen — otherwise it says what actually happened
  (notification sent, saved because notifications are off, not found, no answer).

## 11. Issue log sync

A state-based CRDT (`shared/issues/IssueSync.kt`): per-issue version vector, every editable field a **multi-value register** (concurrent edits keep both values as siblings and show a conflict until the person picks one), deletes are **tombstones**
(a delete concurrent with an edit stays visible), operations idempotent by id, merge commutative/associative/idempotent. Each device publishes only its own replica path and merges what it reads; wall clocks never decide anything.
The person's original words are immutable; AI wording is stored separately and labelled.

## 12. Show data and reports

Productions, performances (each night separate), contacts (a name resolves to an address only if the contact is *verified*), groups, report sections and writing preferences live on the phone; the watch gets names only.
A report is assembled from **the selected performance's logged facts and the person's own dictated observations only** (`get_report_evidence`); it is a draft the person reviews, and the model is told not to invent incidents, fixes or recipients.

## 13. Offline and staleness

* Questions are queued on the watch with a visible state. On reconnect a queued question is sent automatically only while it is **≤ 3 minutes** old; older ones become *stale* (the person chooses "send anyway" or discards) and expire at 30 minutes.
* Issue logging works with no phone, network or AI (conservative offline parser; the original words are kept verbatim).
* Voice recordings wait for the phone with a visible status, are bounded (≤ 5 memos, ≤ 30 s each), and are deleted after transcription.

## 14. Security and privacy model

* Keys: AES-256-GCM with a non-exportable Android Keystore key; ciphertext bound to the provider (AAD); the Keystore is asked to choose the IV (a key created with randomized-encryption-required *rejects* a caller IV); a key that can no longer be decrypted is reported as such and must be re-entered. Stored in `noBackupFilesDir`; `allowBackup=false` plus data-extraction rules.
* Nothing the app logs contains keys, tokens, headers, message text or measurements. Errors shown to people pass through `Redactor`.
* Prompt injection is handled structurally, not by hoping the model behaves: tools cannot send or approve; user text is data; recipients are resolved from configuration; only the app UI can confirm; a confirmation is bound to the content.
* What leaves the phone, and when: the conversation text and (if attached) the measurement snapshot go to the **selected** provider when the person asks a question; optionally a short recording to OpenAI **only** if cloud transcription was enabled. Gmail/Calendar API calls go to Google only after a confirmation. Nothing is sent continuously.
* A watch Tile, complication, notification or shortcut can open the Assistant page but can never start a microphone.
* Development mode swaps in an offline fake provider and **blocks Gmail/Calendar execution entirely**.

## 15. Limits and usage controls

Local limits (not a provider billing cap — a request already in flight, or other apps using the same key, can exceed them): requests/day (default 150), optional monthly budget with a warning percentage, max reply tokens (6,000), context bound (40,000 chars),
tool loops (6), and a usage ledger of the app's own requests with provider-reported or estimated cost. Reaching a limit is reported plainly and raised from Settings → Usage.

## 16. Failure behaviour (what people see)

Provider errors map to plain, actionable text: missing/invalid key, permission, rate limit, quota/billing, model unavailable, context too long, overloaded, timeout/network, content refused, tool-loop limit, limit reached, cancelled.
A phone that is away: *"Phone not reachable. Questions wait up to 3 min."* An interrupted request: *"The phone stopped before this finished… Tap Retry."*

## 17. What is and isn't verified

Automated (JVM): protocol codec/versioning; CRDT convergence; action state machine and confirmation binding; Gmail/Calendar clients against a local mock server including **request counts**
(no hidden retries) and ambiguity classification; each provider's adapter against fixture streams written from the vendors' documented formats; orchestrator (retry-before-output only, no vendor switching, interruption, cancellation); tool validation; credentials; message handler (persist-then-ack, duplicates, confirm binding, undo tombstone);
usage guard; watch outbox policy (stale/expiry/same-id resend/forward-only state); audio coordinator resume rules; measurement snapshot/evidence rules; presentation helpers; shortcut routing.

**Exercised on emulators (a Wear OS 6 round emulator for the watch app, an API 36 phone emulator for the phone app — no accounts, no network keys, fixture data only):**

- *Watch*: every Assistant screen (home, tasks, email/calendar/Keep review, answer, listening and its nothing-heard result, transcript check, voice memos, issue log and issue detail, provider picker, show picker, setup, speak confirmation) driven by touch and read back from the UI tree; the Analyzer still measuring, and pausing then resuming around a question; every Tile/complication-style shortcut routed cold and warm; the install scripts refusing the wrong kind of device. Screenshots: `docs/screenshots/assistant/`.
- *Phone*: the app launching cleanly; a conversation answered end to end by the built-in test assistant through the real orchestrator and stores; the show library, contacts and issues forms; a dummy key encrypted by the **real Android Keystore** (so the AES-GCM "don't supply an IV" rule is now exercised on an Android runtime, not just a software key), shown masked, never found in plaintext on disk or in logcat, and removable; a zero-byte data file quarantined and replaced by defaults; "Continue on phone" links opening the conversation or scrolling to the exact card; the email/calendar/Keep/uncertain-outcome cards in their real wording, with Confirm unavailable until Gmail is connected and in test mode. Screenshots: `docs/screenshots/phone/`.
- Looking at them found real defects that unit tests had not: a cold-start shortcut landing one page short; an error from one screen appearing on another; a Confirm that appeared to do nothing; warnings placed after the text they warn about; one-tap deletion of voice memos and issues; long answers clipped by the bezel; and on the phone, validation errors hidden below the visible part of a dialog, a "Save" that silently did nothing, and a deep link that opened the right conversation but not the right card. Each is fixed and recorded in CLAUDE.md or STATUS.md.

**Not verified here (needs your account/hardware):** live provider calls (fixtures are doc-derived, not recorded from live traffic); Google consent and real Gmail/Calendar; Data Layer delivery and the `ChannelClient` voice path between a real watch and phone (the two emulators are not paired, so the watch shows its honest "phone isn't reachable" states);
on-device speech recognition and TTS (the watch emulator has neither, so it exercises the record-and-let-the-phone-transcribe fallback); Wear remote activity; notification behaviour; Keep and Gemini on your phone; the Ask Tile rendering; the effect of a physical bezel, brightness and touch on the watch layout (see [MEASUREMENTS.md](MEASUREMENTS.md) → manual checklist).
