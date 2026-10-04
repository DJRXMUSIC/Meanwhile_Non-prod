# Decisions log

Choices the spec leaves open, with the reason. Newest milestone at the bottom of each section.

## Status
- M1 — pipeline + shell: done (CI green; Release published once signing secrets are added)
- M2 — data layer + Supabase: done (CI green)
- M3 — CGM intake + foreground service: in progress

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
