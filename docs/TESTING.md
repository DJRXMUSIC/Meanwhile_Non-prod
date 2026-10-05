# Testing

Five suites. CI runs all of them; nothing ships (APK release or Supabase deploy) unless they pass.

| Suite | What it covers | Where | Runs in CI |
|---|---|---|---|
| **Domain** (JUnit, pure Kotlin) | Dose engine incl. the spec §5.5/§6 golden tests, IOB, factor windows/decay, offline router + spoken numbers, Eversense notification parser + reading gate, profile validation and patching, stats, lessons / tuner / change evaluator | `domain/src/test` | `android` workflow, every push |
| **App** (JUnit + Robolectric) | The real data layer over in-memory Room: migrations, DAOs, sync contract vs the SQL migrations, Next Best Action proposal → logging (incl. split second injection, no double insulin), CGM intake against a fake xDrip+, the built-in Eversense interceptor (real notification objects → stored readings, deduped against xDrip+), outcome tagging + the nightly cycle, the continuous learning engine end to end, profile review, app log, diagnostics report + redaction, CSV export, routes | `app/src/test` | `android` workflow, every push |
| **SQL** (Postgres) | Every migration applies cleanly; RLS (own rows only, anon sees nothing), append-only (no update/delete), views run under the caller's rights, check constraints | `supabase/tests` | `supabase` workflow, when `supabase/` changes |
| **Setup automation** (Python + Postgres) | `scripts/ci/supabase_setup.py` against a mock Supabase Management API whose SQL runs on real Postgres: project creation, migrations (incl. concurrent runs), the old PWA's project left alone, paused projects, auth lock, no secrets printed | `scripts/ci/test_supabase_setup.py` | `supabase` workflow, when `supabase/` or `scripts/ci/` changes |
| **Edge Function** (Deno) | Output schemas for both providers, Gemini request shape and error fallback, the AI allowlist / first-account rule, request guards, every job has a versioned prompt | `supabase/functions/ai/ai_test.ts` | `supabase` workflow, when `supabase/` changes |

## Reading results

Each CI run's **Summary** page has a **Tests** table (classes, tests, time, result per suite). A failing
test is listed with its assertion message and the top of its stack trace — copy that block straight
into an AI coding assistant. The full HTML reports are attached to the run as the `test-reports`
artifact when something fails.

## Running locally

```bash
scripts/test-all.sh          # every suite this machine can run; missing tools are skipped, not failed
scripts/test-all.sh domain   # one suite: domain | app | sql | setup | deno
```

- **Domain** needs only a JDK 21 (`./gradlew -p domain test`).
- **App** needs the Android SDK (`ANDROID_HOME`, platform 37): `./gradlew :app:testDebugUnitTest`.
- **SQL** needs a reachable Postgres 15+ (`PGHOST`/`PGPORT`/`PGUSER`); it creates and drops a
  throwaway database: `supabase/tests/run_sql_tests.sh`.
- **Setup** needs the same Postgres: `python3 scripts/ci/test_supabase_setup.py`.
- **Deno**: `deno test --allow-env --allow-read --config supabase/functions/ai/deno.json supabase/functions/ai/ai_test.ts`.

## Writing tests

- Dose math, learning math and parsing belong in `domain/` with plain JUnit — fastest and no Android.
- Anything touching Room, DataStore or the wiring between repositories uses `app/src/test/.../testing/TestEnv.kt`:
  the real classes wired as `AppContainer` wires them, over an in-memory database, with the AI
  unreachable and no WorkManager. `env.meal(at, end = 150)` creates a proposal, logs the dose and lays
  down a CGM curve; `env.housekeeping(now)` runs outcome tagging + one learning pass, exactly like the
  15-minute background job. Scenarios live in the past (future CGM readings are rejected).
- A new Supabase table needs: a migration, an entry in `SyncTables`, and — automatically — the sync
  contract test checks its columns match the Room entity. Add RLS/append-only checks to `10_rls_test.sql`.
- A new AI job needs `prompts/<job>.md` starting with `<!-- prompt version: <job>-v1 -->`.
