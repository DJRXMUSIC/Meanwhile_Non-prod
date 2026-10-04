# MeanwhileV4 — notes for Claude Code

**The full brief is [`docs/SPEC.md`](docs/SPEC.md). Read it before changing anything.**
Decisions not covered by the spec are logged in [`docs/DECISIONS.md`](docs/DECISIONS.md) — add to it
whenever you choose something the spec leaves open.

## Principles (spec §1)
1. **Danny is the gate.** Nothing is logged as a dose until Danny logs it. Learned profile changes
   apply automatically only as far as his Learning autonomy setting allows (default Automatic, at his
   request — 1.3); each is journaled, notified, judged on later outcomes (auto-reverted when worse)
   and one-tap undoable. Everything else the AI proposes waits for him.
2. **Not designed for caution.** No hidden dampening, extra caps or "are you sure" friction beyond the spec.
3. **Dose math is deterministic local code** (`domain/`). The AI never computes a dose; NBA works offline.
4. **Speed and reliability over battery.** Keep services alive; never skip data.
5. **Transparency.** Every recommendation shows its full breakdown.
6. **Everything is tunable.** Spec numbers are starting values in the profile, never hard-coded.
7. **Append-only data.** No edits/deletes; corrections are new rows with `supersedes_id`.
8. **Aim: great, not 80/20.**

## Layout
- `domain/` — standalone pure-Kotlin Gradle build (no Android). Dose engine, IOB, factors, router,
  stats. Fully unit-tested; golden tests from spec §5.5 and §6 must pass. Run: `./gradlew -p domain test`.
- `app/` — Android app (Compose, Room, WorkManager, Supabase). `ui/` → `domain` → `data/`.
- `supabase/migrations/` — SQL (RLS on every table). `supabase/functions/ai/` — Edge Function.
- `.github/workflows/android.yml` — build, test, sign, publish GitHub Release.
- Legacy PWA files (`src/`, `public/`, `netlify/`, `package.json`, …) are from an earlier project and
  are not part of MeanwhileV4.

## Testing & logging
- All suites: `scripts/test-all.sh` (skips what the machine can't run). Details: `docs/TESTING.md`.
- App tests use `app/src/test/.../testing/TestEnv.kt` (real wiring, in-memory Room, Robolectric).
- Log through `app.meanwhile.log.AppLog` (not `android.util.Log`) so it reaches the diagnostics
  report Danny shares with AI assistants. Never log keys, tokens or emails.

## Building
- Domain only (works offline from Google Maven): `./gradlew -p domain test`.
- Full app: `./gradlew :app:assembleRelease` (needs Android SDK 37 + Google Maven; CI does this).
- Supabase URL / anon key / signing come from env vars or Gradle properties — never commit them.

## Milestones (spec §14)
M1 pipeline+shell · M2 data+Supabase · M3 CGM+service · M4 dose engine · M5 input/NBA/profile ·
M6 AI · M7 learn cycle · M8 stats+polish. Current status is tracked in `docs/DECISIONS.md` (top).
