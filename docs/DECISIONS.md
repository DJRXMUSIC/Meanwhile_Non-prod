# Decisions log

Choices the spec leaves open, with the reason. Newest milestone at the bottom of each section.

## Status
- M1 — pipeline + shell: done (CI green; Release published once signing secrets are added)
- M2 — data layer + Supabase: done (CI green)
- M3 — CGM intake + foreground service: done (CI green)
- M4 — dose engine + IOB: done (CI green, golden tests in CI)
- M5 — input, routing, NBA, dose logging, profile: in progress

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
