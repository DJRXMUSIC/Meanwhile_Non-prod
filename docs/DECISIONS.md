# Decisions log

Choices the spec leaves open, with the reason. Newest milestone at the bottom of each section.

## Status
- M1 — pipeline + shell: done (CI green; Release published once signing secrets are added)
- M2 — data layer + Supabase: done (CI green)
- M3 — CGM intake + foreground service: done (CI green)
- M4 — dose engine + IOB: done (CI green, golden tests in CI)
- M5 — input, routing, NBA, dose logging, profile: done (CI green)
- M6 — AI layer: done (CI green; Deno tests in the supabase workflow)
- M7 — learn cycle + morning report: done (CI green)
- M8 — stats + polish: done (CI green)
- 1.1 simple UI + palettes, 1.2 reliability + self-managing setup + voice: done (CI green)
- 1.3 continuous learning, app log + diagnostics, full test suite: done (CI green)

## Repository & toolchain (M1)
- **Repo.** Built in `DJRXMUSIC/Meanwhile_Non-prod` (the repo this session was given) rather than a
  new `MeanwhileV4` repo. The repo is currently **public**; the spec asks for private. Making it
  private is recommended (Settings → General → Danger Zone → Change visibility). Releases on a private
  repo require being signed in to GitHub (Obtainium then needs a token).
- **Legacy PWA.** The earlier "T1D Harness" PWA files are still at the repo root. They are untouched
  and unused by MeanwhileV4; CI ignores them. They also live on branch `claude/confident-clarke-ro5j1d`.
- **Versions** (latest stable at build time, Oct 2026): AGP 9.4.1, Gradle 9.7.1, Kotlin 2.4.20,
  KSP 2.3.12, Compose BOM 2026.09.00, Room 2.8.5, WorkManager 2.12.0, supabase-kt 3.8.0, Ktor 3.6.0.
- **SDK.** `compileSdk = 37`, `targetSdk = 37` (Android 17, latest stable API level), `minSdk = 31`.
- **Kotlin.** AGP 9 built-in Kotlin; KGP 2.4.20 is put on the classpath from the root build.
- **`domain` is a separate included build** (`includeBuild("domain")`) so it compiles and tests with
  only a JDK + Maven Central — no Android SDK or Google Maven needed.
- **DI.** Manual DI (`AppContainer`) instead of Hilt: fewer annotation processors, faster CI, simpler.
- **R8 off for release (for now).** Minification can strip classes used reflectively by
  serialization/Ktor/Supabase and those failures only show at runtime. Reliability first; revisit in M8.
- **Icons.** `material-icons-core` plus a few own vector drawables (mic). `material-icons-extended`
  is discontinued and very large without R8.
- **Versioning.** `versionCode` = GitHub Actions run number; `versionName` = `appVersion.runNumber`
  where `appVersion` in `gradle.properties` tracks the milestone (0.1 = M1 … 0.8 = M8, 1.0 = complete).
- **Releases.** Every green push (any branch) publishes a GitHub Release when the signing secrets
  exist. Without them CI still builds and tests, uploads an unsigned APK artifact, and warns.
- **Keystore.** PKCS12, RSA 4096, 100-year validity. PKCS12 uses one password, so
  `KEY_PASSWORD` = `KEYSTORE_PASSWORD`.
- **Backups.** `android:allowBackup="false"`: Supabase is the restore path (spec §12.1); Android
  auto-backup restoring a stale local DB + session would fight sync.

## Data & sync (M2)
- **Full schema up front.** All tables for M2–M8 are created in M2 (Room v1 + one Supabase migration)
  so later milestones don't need migrations while Danny already has data.
- **One class per table for Room and Supabase.** Entities are also `@Serializable`; a snake_case JSON
  naming strategy gives Postgres column names, ISO timestamps (`timestamptz`) and real JSON (`jsonb`)
  for json-text columns. Room stays camelCase locally.
- **`sync_state` column** (0 pending, 1 synced, 2 rejected). Defaults to pending, so a mistake can only
  cause a harmless re-push (duplicates are ignored server-side), never a lost row.
- **Push** = PostgREST upsert with `ignoreDuplicates` (`INSERT … ON CONFLICT (id) DO NOTHING`), which
  needs only the insert policy. **Pull** pages by a server-side `seq` identity column (not timestamps:
  rows inserted in one batch share `now()`), cursor per table. A fresh install's pull is the restore.
- **Poison rows.** If the server rejects a batch for data reasons (400/403/409/422), rows are retried one
  by one and rejects are marked `sync_state = 2` (kept locally, shown in Settings) so one bad row can't
  block the queue.
- **Room schema export is off.** Debug and release KSP tasks ran in parallel and raced on the same
  exported JSON file (CI failure). The schema is v1; any later change ships a hand-written Migration.
- **No foreign keys between tables** on the server: rows may arrive in any order.
- **Offline session expiry.** supabase-kt drops the session when an expired token can't refresh
  offline; the app remembers the last signed-in user so it keeps working locally and doesn't bounce to
  the sign-in screen.
- **Local-only mode.** Without Supabase config in the build, or if Danny taps "Use without an account",
  everything works locally; records sync after sign-in (user_id is filled in at push time).
- **Migrations & Edge Function deploy run in GitHub Actions** (`supabase.yml`, Supabase CLI) using an
  access token secret, instead of asking Danny to run CLI commands. SQL Editor paste is the fallback.
- **Sign-ups.** After Danny creates his account he turns sign-ups off in Supabase (the APK and repo are
  public, so the anon key is too).

## CGM & background (M3)
- **xDrip+ details verified against its source:** web service on `127.0.0.1:17580`, `sgv.json?count=`
  (max 1000), `api-secret` = SHA-1 hex and only enforced off-loopback; broadcast action
  `com.eveningoutpost.dexdrip.BgEstimate` sent with receiver permission
  `com.eveningoutpost.dexdrip.permissions.RECEIVE_BG_ESTIMATE`, slope in mg/dL per ms.
- **Both feeds, deduped.** Polling (reliable, back-fills) + broadcast (instant). Readings dedupe by
  timestamp (unique index) and by a deterministic UUIDv7 derived from the timestamp, so restore +
  back-capture can never create server duplicates.
- **Trend for the dose engine** = least-squares slope of the last 15 min of readings (mg/dL/min);
  falls back to the source's delta (sgv `delta`/5 or broadcast slope) when there's too little data.
- **Foreground service type `specialUse`.** `dataSync` is capped at 6 h/day on Android 15+,
  `connectedDevice`/`health` need hardware/sensor permissions we don't use. `specialUse` has no time
  limit and may start from `BOOT_COMPLETED`; Play review doesn't apply to a sideloaded app.
- **Exact alarms via `USE_EXACT_ALARM`** (granted at install on API 33+), with `SCHEDULE_EXACT_ALARM`
  for API 31–32. The setup checklist still verifies `canScheduleExactAlarms()`.
- **Setup checklist** (notifications, unrestricted battery, exact alarms, microphone, xDrip feed)
  replaces one-shot prompts; the main screen shows a card until everything is granted.
- **Stale** threshold (15 min) and **poll interval** (60 s) are device settings.

## Dose engine & factors (M4)
- **Profile = one JSON document** (`domain/profile/Profile.kt`); every spec number is a field.
  Changes are addressed by dotted path (`dose.icr`, `factors.F7.maxWeight`,
  `factors.F11.params.perHour`, `factors.F13` = add a factor). Diffs between versions use the same
  paths, so learn-cycle proposals, edits, reverts and history all speak one language.
- **Factor definitions are data** (kind, bounds, default weight, presets, window rule, decay, keywords,
  params). F10/F11 tunables live in their definitions' `params`.
- **Bounds are informational.** The AI and Danny may set weights outside them (principle: unlocked);
  code never clamps an accepted weight. Bounds guide the AI and the manual-activation UI.
- **Combined multiplier floor at 0 only.** The spec says "no floor"; values between 0 and 1 are
  allowed, but a *negative* combined multiplier would flip the sign of the dose (e.g. turn a low-BG
  carb suggestion into insulin), so it's clamped to 0 and the breakdown says so.
- **Decay with a non-default start weight** scales proportionally: w(t) = 1 − (1 − w₀)·(1 − step)/(1 − step₀).
  With w₀ = 0.70 this is exactly the §7.2 table.
- **Windows:** UNTIL_RESET (next 1 am), FIXED (minutes; also cut at 1 am unless `survivesReset`),
  AFTER_LAST_TRIGGER (F10), CONSUMED_BY_NEXT_DOSE (caffeine; also cleared at 1 am), PER_MEAL.
  Non-stacking factors keep only the newest activation ("new drink resets").
- **Caffeine** is a pending-units event, not a multiplier: units = cups × units-per-cup, consumed by
  the next logged rapid-acting dose (any dose, including a correction-only one).
- **No BG available:** correction is omitted with a warning on the card; lead time uses the base.
- **No carbs:** no lead time (nothing to pre-bolus for).
- **F11 counting:** each reading > 180 counts until the next reading, capped at 15 min
  (`maxGapMinutes`), so sensor gaps don't inflate the hours.
- **Version 0** is the built-in starting values; no profile row is written until the first change
  (avoids a duplicate "v1" racing a restore on a fresh install).
- **Lead-time factor adjustments** are a map (`leadTime.factorMin`, F7 → −5) so new factors can
  adjust lead time too.

## Input, NBA, logging, profile (M5)
- **Offline router** (`domain/router/OfflineRouter.kt`) is regex + keyword based; factor keywords
  come from the profile's factor definitions (so AI-added factors are routable offline). Leftover
  words after removing recognised spans become a meal description (macros prompted offline).
- **Order:** feedback → dose given → factor updates → meal/NBA, so a combined input updates the profile
  before Next Best Action runs.
- **Coffee alone** produces an NBA card (caffeine units only). "correction"/"check" gives a zero-carb NBA.
- **Proposals are recorded when shown**; the **meal row is written when the dose is logged** (a
  dismissed proposal leaves no meal behind, but the proposal keeps the macros in its snapshot).
- **Split doses:** "Log dose as shown" logs the first injection and arms an exact-alarm reminder at
  meal start (now + lead time) + 60 min; the notification's "Log N u" action logs the second part.
  Edit lets Danny change both parts. "Skip" records a 0 u second part with reason "skipped" so stats
  stay honest. Pending reminders are re-armed after reboot from the dose rows.
- **Path switch** re-routes the same text; factor changes made by the previous path are undone with
  compensating rows (new profile version + reversing events), never deletes.
- **Offline factor updates apply immediately** (spec §9.4, source `offline_fallback`) and queue an
  AI refinement; undo is one tap on the card.
- **Voice:** the transcript lands in the text field for review/editing; it is never auto-sent.
- **Profile editing:** a settings form (every scalar by path) and a JSON editor for any part (factor
  definitions, windows, decay, new factors). Both save `manual` versions; JSON is validated by
  decoding before saving.
- **Revert** = new `manual` version copying the old profile (expired activations pruned).

## AI layer (M6)
- **Edge Function `ai`** (Deno): auth via the caller's Supabase JWT (`auth.getUser`), optional
  `ALLOWED_USER_IDS` allowlist (recommended: the repo and APK are public), provider order from
  `provider_preference`, Ajv validation against the job schema, fallback to the other provider on
  error, timeout or schema failure. Prompts are versioned `prompts/*.md` deployed as static files.
- **Claude** via the official TypeScript SDK (`npm:@anthropic-ai/sdk`), default `claude-opus-5-5`,
  structured output (`output_config.format` JSON schema), effort per job (route/estimate `low`,
  update `medium`, learn cycle `high`), always streamed (`finalMessage()`), cached system prompt, and
  Anthropic's server-side refusal fallback (`fallbacks: "default"`). A refusal that still comes back
  counts as a provider failure → Gemini is tried.
- **Gemini** via REST `generateContent` with `responseMimeType: application/json` +
  `responseJsonSchema` (verified against the official `@google/genai` package). Default
  `gemini-pro-latest`; `GEMINI_FAST_MODEL` (e.g. `gemini-flash-latest`) for route/estimate.
- **One schema for both providers**, in the strict structured-output subset: every object has
  `additionalProperties: false`, all properties required, optional = nullable. Values whose type varies
  (learn-cycle `old`/`new`) travel as JSON strings and are decoded back before returning.
- **Schema extensions** (documented in `schemas.ts`): `update_profile` changes also carry `action`
  (activate/deactivate), `started_minutes_ago`, `amount`, `preset`; `learn_cycle` adds
  `setting_changes` (any tunable by profile path) so every number is reachable.
- **Wall clock:** Edge Functions get ~150 s. If the first provider uses up the learn-cycle budget the
  fallback is skipped and the response says `retry_with` (the app retries with that provider only).
- **`ai_calls` are written by the app** (local-first, synced) for every call including failures,
  with provider, model, latency, fallback flag, request summary, response, validation result.
- **AI routing decides *what* each part is; numbers are parsed deterministically** from each span by
  the offline parser (doses, macros), so the AI never supplies a dose amount.
- **Online factor updates** show an inline AI proposal card (per-change accept, editable
  weight/window, reject all). Accepting writes one version (`ai_update`, accepted or edited) with
  factor events (source `ai`); rejecting writes a `rejected` version so acceptance rates are measurable.
  Any open NBA card is recomputed after accepting (Update Profile first, then NBA).
- **Offline queue:** when the network/session returns, queued `update_profile` calls run; the result
  becomes a **pending** version + "AI refinement ready" notification → review screen (accept / edit /
  reject each change). Decisions are re-applied onto the *current* profile.
- **"AI offline" indicator** = configured but no network, no live session, or the last call failed.

## Learn cycle & morning report (M7)
- **1 am:** an exact alarm enqueues a WorkManager job (the AI call can take ~2 min). The night is
  identified by the date of the most recent reset; the payload covers the 24 h ending at that reset
  plus a 14-day summary (daily TIR, hourly means, proposals followed/overridden, override reasons,
  dose outcomes) and the profile *after* the reset.
- **The 1 am reset is the window rules themselves** (activations expire at the reset); no extra
  "reset" version is written — expired activations are pruned when the next version is saved.
- **Result → pending version** (`learn_cycle`) holding path-addressed changes with evidence. Changes
  that don't apply cleanly to the current profile are dropped. Observations are shown in the report.
- **Failures:** if the wall clock ran out on one provider, the other is tried alone; a failed night is
  retried automatically when the network returns and on demand from the morning report; otherwise the
  report says yesterday's profile was carried forward. The 1 am notification is silent.
- **Morning report** opens on the first app open after the reset (catch-up runs the learn cycle first
  if it hasn't completed). "Done" marks it seen; "Not now" shows it again next open.
- **Sleep check-in** applies F8 immediately as a `sleep_checkin` version and can be changed.
- **Yesterday at a glance** = the previous calendar day. "Followed" = every injection logged for the
  proposal matched what it proposed and the total equals the proposal; edits/skips = overridden.
- **F11** is computed by code at 6 am (exact alarm), with catch-up from app start and the CGM service
  every 15 min; a weight of 1.00 writes no version.
- **Outcome tagging** runs every 15 min (service), at app start and before each learn cycle; doses
  wait up to 24 h for back-filled readings before an all-null outcome is stored.

## Stats & polish (M8)
- **Stats screen** (bar-chart icon on the main screen) is computed on the phone from the local
  append-only records, so it works offline: today / 1 / 7 / 14 / 30 days. Time in range is
  **time-weighted** (each reading counts until the next one, for at most 15 min), not a count of
  readings. "Goal streak" = consecutive days ≥ 80% with at least 12 h of CGM coverage; today counts
  once it has 12 h of data, otherwise the streak is counted from yesterday.
- **Followed vs overridden** uses one rule shared by the morning report, the stats screen and the
  `v_proposal_outcomes` view (domain `ProposalFollowRule`): followed = every injection logged for the
  proposal matched what it proposed and the total equals the proposal; anything else logged =
  overridden; nothing logged = not logged. Outcomes are those of the first injection (+2/+3/+4 h).
- **AI accuracy by provider/model** = per call: valid / invalid / error, served-as-fallback, mean
  latency, jobs; per proposal: accepted / edited / rejected (profile versions with an `ai_call_id`);
  meal estimates: mean absolute carb error between the AI's original estimate and what was logged.
- **Supabase analysis views** (`v_tir_daily`, `v_tir_hourly`, `v_tir_monthly`, `v_tir_by_time_of_day`,
  `v_tir_by_weekday`, `v_proposal_outcomes`, `v_ai_calls_by_model`, `v_cgm_local`) are
  `security_invoker` so row-level security still applies; their time in range is by reading count
  (simple SQL; within a fraction of a percent of time-weighted on a steady feed). Local time comes from
  one function, `meanwhile_tz()` = `America/New_York`. See `docs/ANALYSIS.md`.
- **Theme:** Settings → Appearance (follow system / light / dark); status- and navigation-bar icons
  follow the app's choice. Glucose colors are the same hues in both themes, tuned for contrast.
- **Performance:** every proposal stores its compute time (context build + dose math, target < 200 ms)
  and the stats screen shows the median and slowest. The dose context (Room, profile, engine) is warmed
  at app start so the first proposal doesn't pay for opening the database. R8/minify stays off: the APK
  is installed from Releases on one phone, size doesn't matter, and it avoids keep-rule risk around
  serialization and supabase-kt.

## Security & robustness review (after M8)
- **Repo stays public** (Danny's choice) and the legacy PWA files stay. Nothing secret is in the repo or
  its history (scanned); the anon key in the APK is public by design.
- **AI allowlist fails closed:** with `ALLOWED_USER_IDS` unset the `ai` function refuses every call.
  Bodies are capped at 1 MB and must be a JSON object.
- **CGM integrity:** any app can send xDrip's broadcast action and Android can't name the sender, so a
  broadcast only triggers a fetch from xDrip+'s local web service; its own value is used only when that
  service is unreachable. Readings stamped more than 2 min in the future are dropped everywhere (one
  would otherwise stay "latest" and pin the BG used for doses).
- **Profile validity** (domain `ProfileValidation`): values the math can't use — ICR/ISF/increment/
  g-per-unit ≤ 0, NaN, insulin peak ≥ half the duration, hours outside 0–23 — block saving (editors,
  revert, accepting AI/learn-cycle changes), are dropped from learn-cycle proposals, and make the engine
  return "No dose" with the reasons instead of a silent 0 u. Not a caution limit: any computable value
  is allowed.
- **No double insulin:** main-screen actions run one at a time (double-tap ignored); a split's second
  injection logs once per proposal (app + notification + double tap). Outcomes have one deterministic
  id per dose and outcome tagging / the 6 am check are serialized.
- **No silent typos:** unparseable meal grams, dose units, AI-proposal weights/windows and settings are
  reported and block the action instead of becoming 0 / the default. An AI weight outside the factor's
  bounds is flagged on the card (Danny still decides).
- **Crash safety:** background scopes, the CGM service and every screen's button actions log/show
  errors instead of crashing; an invalid xDrip+ address is refused on save and treated as a connection
  error; only known screens can be opened from outside the app.
- **CSV export** prefixes free text starting with `= + - @` with `'` so spreadsheets don't run it.
- **CI:** third-party actions pinned to commit SHAs; signing and Supabase secrets are passed only to the
  steps that use them; the decoded keystore is deleted after the build. The Supabase CLI itself stays on
  `latest` (pinned action, official releases).
- **Known limits:** a malicious app could still feed fake readings while xDrip+'s web service is off,
  and anyone with the phone unlocked can use the app — there is no app lock (not in the spec).

## UI simplification & palettes (1.1)
- **Main screen = glucose + factors + input + one Settings button** (spec §15 taken literally). Stats
  opens by tapping the BG number (hinted with "stats ›"), the Profile by tapping the profile callout;
  both also have buttons at the top of Settings. The sync and AI chips are attention-only — quiet
  when everything works.
- **Palettes:** 17 options (Settings → Appearance). "Teal" is hand-tuned; the others are derived from
  a hue by one generator so light and dark both stay readable. Glucose colors are not themeable —
  in-range/high/low must always read the same (spec §15).
- Keyboard Send submits (same as the send button; the spoken transcript still requires an explicit
  send per spec §9.1 — it stays editable first).
- The morning report auto-opens only once there is CGM data, so a fresh install isn't greeted with an
  empty report.
- The bar-chart drawable stays in the repo though the top bar no longer uses it.

## Reliability, speed & self-managing setup (1.2)
- **Profile lookups are O(1) now.** The dose path read *every* profile version (each a full JSON blob)
  on every calculation and every 30 s main-screen refresh — fine in week one, megabytes per lookup
  after a year of nightly versions. Targeted LIMIT-1 queries + a per-version decode cache replace it;
  the history screen lists the newest 200 (older stay in exports / by id).
- **Self-healing background:** the 15-min WorkManager job (no network constraint — CGM is localhost)
  restarts the CGM service if Android killed it and runs outcome tagging / missed 6 am checks, even
  if the app isn't opened. Sync simply skips while offline.
- **Private crash log:** an uncaught crash is written to a local file and becomes a `feedback` row
  (context `crash`) on the next start — synced, exported, no third-party service.
- **AI access is zero-config:** with `ALLOWED_USER_IDS` unset, the Edge Function allows only the
  project's **oldest account**, verified server-side via the service role (cached; ambiguity — 50+
  accounts — fails closed). CI auto-sets `mailer_autoconfirm` and **disables sign-ups once ≥ 1
  account exists**, so the open-sign-up window is one build cycle at most. Residual risk accepted:
  someone extracting the project URL from a public release APK and registering before Danny's very
  first account; `ALLOWED_USER_IDS` remains the explicit override.
- **Setup dashboard:** every android/supabase workflow run writes a Summary checklist (signing /
  app keys / automation token, auth lock state). In-app, Setup → System status shows CGM, backup,
  network and a one-tap **Test AI**; Settings → Account has a copy button for the user id.
- The sync worker switched to `ExistingPeriodicWorkPolicy.UPDATE` so existing installs pick up the
  constraint change.

## Voice quality (1.2)
- **Engine:** `createOnDeviceSpeechRecognizer` — the Pixel's own on-device dictation model (the same
  engine behind Recorder/Gboard), explicitly, instead of hoping the default service honors
  "prefer offline". Private and fast; no audio ever leaves the phone (spec §9.1).
- **Formatting:** `FORMATTING_OPTIMIZE_QUALITY` so the recognizer emits digits ("60 carbs"), and
  offensive-word masking is off (asterisks would break keyword routing).
- **Biasing:** recognition is biased toward ~120 domain terms — dose words plus the live profile's
  factor keywords, so AI-added factors improve recognition too.
- **Patience:** end-of-speech silence raised to 2 s so a thinking pause doesn't cut the utterance.
- **Belt and braces in domain:** `SpokenNumbers.normalize` turns spoken numbers into digits
  ("sixty carbs twenty fat" → "60 carbs 20 fat", "six and a half units" → "6.5 units", hundreds and
  compounds included) at the router's front door — voice and typed input parse identically, on both
  the offline and the AI path (which parses numbers per span through the same router). Unit-tested.


## Continuous learning (1.3)
- **Why.** One AI review a night saw 24 hours, never measured how each dose actually landed, and
  never checked whether its own changes helped. Danny asked for learning that is continuous,
  documented and automatic.
- **Lessons** (domain `Lessons`): once a dose's 4 h outcome is tagged it becomes a lesson — the
  units that would have landed on target = given + (end BG − target) / ISF, using the 4 h minimum
  instead when it went below 70. From the proposal's own snapshot (inputs, factors, IOB) that
  becomes an implied ICR, ISF (correction-only doses) or units-per-event (caffeine). A lesson is
  **clean** only with no other rapid dose or meal in its 4 h, a BG at dose time and CGM at 3–4 h;
  confounded lessons are kept and shown, but only as context.
- **Local tuner** (domain `Tuner`, deterministic, offline): ICR, ISF and units-per-event move
  `learning.rate` (50%) of the way toward the median of their clean lessons in the last
  `learning.lookbackDays` (14), once `learning.minLessons` (3) lessons *newer than the value's last
  change* agree on a difference of at least `learning.minChangePct` (3%). Only fresh lessons count,
  so the same evidence never moves a value twice. It runs after every outcome tagging (15-minute
  background job, app start), so a change can land the same afternoon.
- **Change evaluator** (domain `ChangeEvaluator`): every applied learned change is watched. After
  `learning.evaluateAfterLessons` (3) relevant clean lessons, their mean miss (|end − target|, with
  lows weighted 3×) is compared with the lessons before the change: worse by more than
  `learning.revertIfWorsePct` (15%) → reverted; otherwise kept. A severe low (< `severeLowMgDl`, 54)
  after a change toward more insulin reverts it at once.
- **AI reviews:** the nightly review stays, and a mid-day **incremental** review runs once
  `learning.aiMinNewLessons` (2) new clean lessons arrived and `learning.aiMinHoursBetween` (3 h)
  passed. Prompt `learn_cycle-v2` gets the lessons, the evidence per value, the changes still being
  judged and the 14-day journal, and is told the phone already tunes ICR/ISF/units-per-event, so the
  AI focuses on what code can't see (time of day, factor weights and windows, fat/protein, new
  factors). Learn-cycle changes go through the same apply → watch → keep/revert path.
- **Autonomy — a deliberate change to principle 1.** Settings → Learning offers **Automatic**
  (default), **Automatic for factors** (ICR/ISF/target/insulin curve wait for Danny) and **Ask me
  first** (everything waits, as before). Default Automatic because Danny asked for it explicitly
  ("I want it to be automatic"). What keeps Danny the gate: only values that keep the dose math valid
  apply; every change is journaled with its evidence, notified, judged on the outcomes that follow
  and auto-reverted when worse; one tap undoes it (Learning screen, morning report); with Ask me
  first a learned value is proposed once and never stacked. Doses are still never logged without him.
- **Journal** (`learning_log`, append-only, synced, RLS): `lessons`, `ai_review`, `applied`,
  `proposed`, `kept`, `reverted`, `revert_proposed`, `undone`, `error`. A verdict closes its change
  through `supersedes_id`, so "what is still under evaluation" is a query, not state. Supabase view
  `v_learned_changes` shows every change with its fate. Room v1 → v2 with a hand-written migration.
- **Outcome tagging looks back as far as learning does** (`learning.lookbackDays`, was 48 h): after
  days without the app running, xDrip+ back-fills the readings and every dose still becomes a lesson.
  Found by the new learning-engine tests.
- **Every threshold is a profile value** (`profile.learning`, editable in Profile → Learning, validated).
- Profile sources `auto_tune` and `auto_revert` join the spec's list.

## App log & diagnostics (1.3)
- **AppLog**: a rotating on-device log (2 × 768 KB) of info/warn/error lines with thread and stack
  traces, also mirrored to logcat. Every background job, sync, AI call, CGM error, learning decision
  and crash writes to it; repeated errors are throttled (one line per key per interval). No third-party
  crash service — nothing leaves the phone unless Danny shares it.
- **Diagnostics report** (Settings → Diagnostics → **Copy for AI** / **Share**, also on the App log
  screen): one Markdown document written for an AI coding assistant — build + git SHA, device,
  health of every moving part, problems in the last 72 h grouped by message with stack traces,
  crashes, failed AI calls, the learning state, table counts and sync backlog, background markers and
  the log tail. A redaction pass strips JWTs, API keys, emails, bearer/api-secret/password values.
  Capped at 400 k characters so it pastes into any assistant.
- Settings shows a badge with the number of warnings/errors in the last 24 h.

## Test suite (1.3)
- See `docs/TESTING.md`. Four suites — domain (JUnit), app (Robolectric over the real data layer
  with in-memory Room), SQL (Postgres: migrations, RLS, append-only) and Edge Function (Deno).
- **Robolectric at SDK 35** with a plain `Application`: fast, and keeps `MeanwhileApp` (service,
  alarms, WorkManager) out of unit tests; the wiring under test is the same as `AppContainer`'s.
- **Sync contract test** reads the SQL migrations from the repo and checks every synced Room column
  exists remotely with a compatible type, so a column added on one side only fails CI, not sync.
- CI: app tests run in their own step without secrets; the run Summary has a per-suite table and
  each failure's message + stack top (paste-ready for an AI assistant); full reports upload on
  failure. Supabase deploys only after the SQL and Deno tests pass.
- `scripts/test-all.sh` runs whatever the local machine supports and skips the rest.

## Setup automation (1.3)
- **One token runs the backend.** `scripts/ci/supabase_setup.py` uses the Supabase Management API
  with `SUPABASE_ACCESS_TOKEN` to find or create the project (`meanwhile-v4`, us-east-1; creates an
  organization if the account has none), wait for it (waking it if paused), apply migrations,
  configure auth (auto-confirm; sign-ups locked once an account exists), check the AI function
  and report which AI keys are set. The build gets the project URL and anon key from it, so the
  `SUPABASE_URL` / `SUPABASE_ANON_KEY` / `SUPABASE_PROJECT_REF` / `SUPABASE_DB_PASSWORD` secrets
  are no longer needed (still honored).
- **Migrations without a database password:** each file runs through the API's SQL endpoint as one
  implicit transaction (file + history row together), recorded in
  `supabase_migrations.schema_migrations` like the Supabase CLI does. An advisory lock serializes
  concurrent runs (both workflows fire on one push); `create … if not exists` alone isn't safe
  against a concurrent run — the new tests caught exactly that race.
- **The old PWA's project is never touched:** only a project named `meanwhile-v4` (or an *empty*
  `meanwhile`) is used; one already holding other tables is skipped (and refused if named
  explicitly); a paused project is only woken if it's ours by name.
- **CI writes no secrets.** Copying the AI keys from GitHub into Supabase and keeping a CI-generated
  signing key in Supabase would have cut setup to a single secret, but automated secret-store writes
  were declined in review; the AI keys stay Danny's own step in Supabase (as the spec says) and the
  signing key stays a GitHub secret. Signing needs only `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD`
  (alias and key password default to the generated keystore's).
- The generated database password is used once to create the project and not kept; Danny resets it
  in the dashboard if he ever wants a direct connection.
- Tested end to end against a mock Management API whose SQL runs on real Postgres
  (`scripts/ci/test_supabase_setup.py`, in the supabase workflow's test job).

## Built-in Eversense interceptor (1.3)
- **How:** a `NotificationListenerService` reads the official Eversense app's glucose notification
  (packages `com.senseonics.gen12androidapp`, `com.senseonics.androidapp`,
  `com.senseonics.eversense365.us` — the ones xDrip+'s companion mode reads). No root, no patched
  Eversense app, and calibration stays in the Eversense app where it belongs. A direct Bluetooth
  connection to the transmitter was ruled out: undocumented protocol, and the transmitter talks to
  one app at a time.
- **Strict parsing** (domain `EversenseNotification`): standard notification fields plus every
  visible TextView of a custom layout; a value counts only if a whole text element *is* the number
  (units/arrows aside), exactly one distinct value, 40–400 mg/dL (mmol converted). LO/HI, alerts
  ("Low glucose 65 mg/dL"), predictions and times are not readings. Trend from an arrow character or
  a "rising/falling" content description; otherwise the slope is computed from readings as before.
- **Timing & dedupe** (domain `ReadingGate`): a notification carries no reading time, so the post
  time is used (capped at now). Skipped: any reading within 2 min (any source — xDrip+ may deliver
  the same sensor reading), the same value within 4.5 min (re-post), and a value repeated 7 times in
  a row (35 min — a notification still showing the last value after signal loss) until it changes.
  The repository applies the 2-min cross-source rule to every save, so xDrip+ back-fill never
  duplicates an Eversense reading either. Thresholds are constants in `ReadingGate`, not profile
  values: they describe the sensor's 5-min cadence, not Danny's physiology.
- **xDrip+ becomes optional:** still polled and used for back-fill when installed (a notification
  only shows the current value); when it isn't installed its unreachability is expected, so it is no
  longer logged as a problem and back-fill is skipped quietly.
- **Unknown formats are visible:** a notification that isn't understood is logged (throttled) with
  its texts, shown in Settings → CGM as "What Meanwhile sees" and included in the diagnostics
  report, so one paste to an AI assistant is enough to adapt the parser.
- Notification access is a checklist item (counted only when an Eversense app is installed); the
  button opens Meanwhile's own access switch and explains Android's "Allow restricted settings" step
  for sideloaded apps. `<queries>` lists the Eversense and xDrip+ packages (package visibility).
- The CGM service's notification no longer flashes "waiting" when a source restarts the service.
- **Fast jobs default to Gemini Flash** (first real use): with `GEMINI_FAST_MODEL` unset, route and
  update_profile ran on Gemini Pro, which took 8–15 s and hit the 15 s budget. Route, meal
  estimates and update_profile now use `gemini-3.8-flash` (Danny's choice; an unknown model name
  falls back to `gemini-flash-latest` once) unless `GEMINI_FAST_MODEL` says
  otherwise; the learn cycle keeps Pro.
