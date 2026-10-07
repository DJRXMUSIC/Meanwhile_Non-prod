# Testing

Five suites. CI runs all of them; nothing ships (APK release or Supabase deploy) unless they pass.

| Suite | What it covers | Where | Runs in CI |
|---|---|---|---|
| **Domain** (JUnit, pure Kotlin, ~170 tests) | Dose engine incl. the spec §5.5/§6 golden tests, IOB, **the forecast** (carbs on board, unexplained rise/fall carried forward, dynamic absorption, stale/too-few readings, off switch, in the dose math and "why"), the Next Best Action classifier (insulin / split / low / carbs / nothing / check BG), **the Advisor** (which alerts go out, quiet period, stale/off), factor windows/decay, offline router + spoken numbers + corrections ("never mind, only 5"), "took it", spoken BG and "what should I do?", Eversense notification parser + reading gate, profile validation (incl. NBA, forecast and alert settings) and patching, old profiles loading new defaults, stats, lessons / tuner / change evaluator | `domain/src/test` | `android` workflow, every push |
| **App** (JUnit + Robolectric, ~100 tests) | The real data layer over in-memory Room: migrations (v1→v3, v2→v3), DAOs, sync contract vs the SQL migrations (incl. `app_logs` upload rows), the conversation end to end (say a dose → logged; correct it → IOB counts the correction; "took it"; a low → carbs, no insulin; steps reported; logged word for word; code-certain messages skip the AI; **chat gets words back, not a carbs form; the AI's actions are read by code; a reply follows an instant log; offline "didn't catch"**), **the converse payload** (live state + forecast, 48 h of records, conversation), **everything said reaching the nightly review**, **the forecast in the live dose context and advice notifications** (low once then quiet 30 min, correction, nothing in range, a logged meal's carbs offsetting its insulin), **history import** (synthetic export: streaming, bad values dropped, repeat-safe, overlap skipped, old settings only offered), Next Best Action proposal → logging (incl. split second injection, no double insulin), CGM intake against a fake local web service, the built-in Eversense interceptor, outcome tagging + the nightly cycle, the continuous learning engine end to end, profile review, app log, diagnostics report + redaction + bug capture, CSV export, routes | `app/src/test` | `android` workflow, every push |
| **SQL** (Postgres) | Every migration applies cleanly; RLS (own rows only, anon sees nothing) on all 14 tables and 11 views, append-only (no update/delete), corrected doses out of `v_doses_effective`, views run under the caller's rights, check constraints | `supabase/tests` | `supabase` workflow, when `supabase/` changes |
| **Setup automation** (Python + Postgres) | `scripts/ci/supabase_setup.py` against a mock Supabase Management API whose SQL runs on real Postgres: project creation, migrations (incl. concurrent runs), the old PWA's project left alone, paused projects, auth lock, no secrets printed | `scripts/ci/test_supabase_setup.py` | `supabase` workflow, when `supabase/` or `scripts/ci/` changes |
| **Edge Function** (Deno) | Output schemas for both providers (incl. the new route intents), Gemini request shape, thinking levels and retry without them, error fallback, the time budgets, the Claude learn batch request (Opus 5.5, max effort) and every batch result shape, the AI allowlist / first-account rule, request guards, every job has a versioned prompt | `supabase/functions/ai/ai_test.ts` | `supabase` workflow, when `supabase/` changes |

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

## Not covered by automated tests

- **Compose screens** (layout, taps) — the view models' logic lives in the data layer, which is tested;
  the screens are checked on the phone. The full app (incl. screens) is compiled by CI on every push.
- **Live AI answers** — the prompts and schemas are tested; what a model says is not deterministic.
  Real calls are kept word for word in `ai_calls` for review.
- **Danny's real data** — never in the repo (it's public). Fixtures are synthetic; analyses of his
  export run in a session scratchpad.

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
- `env.readings(from, minutes) { m -> mg }` lays down CGM every 5 min; `env.advice.check(now)` runs the
  advice step and `env.posted` holds what would have been notified. A fake `AiHooks` (see
  `ConversationTest.TalkingAi`) stands in for the AI's words.
