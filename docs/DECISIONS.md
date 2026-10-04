# Decisions log

Choices the spec leaves open, with the reason. Newest milestone at the bottom of each section.

## Status
- M1 — pipeline + shell: in progress

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
