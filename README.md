# MeanwhileV4

Personal Android app (Kotlin, Jetpack Compose) that recommends a factor-adjusted rapid-acting insulin
dose, pre-bolus lead time and split-dose plan from live CGM, a learned factor profile and deterministic
dose math. Danny reviews every recommendation; the app never doses anything itself.

- Spec: [`docs/SPEC.md`](docs/SPEC.md) · Decisions: [`docs/DECISIONS.md`](docs/DECISIONS.md)
- Setup & install: [`docs/INSTALL.md`](docs/INSTALL.md)
- Build: `./gradlew -p domain test` (dose engine) · `./gradlew :app:assembleRelease` (app)

> The section below documents the earlier **T1D Harness PWA** whose files still live at the repo
> root (`src/`, `public/`, `netlify/`, `package.json`). It is not part of MeanwhileV4.

---

# T1D Harness

Personal, single-user decision-support PWA for Type 1 diabetes management. Speak or type
what's happening ("two slices of pepperoni pizza and a coke"); the app pulls current
glucose from xDrip+, computes a suggested dose with **deterministic TypeScript** (the LLM
never computes doses), and an LLM explains the reasoning. Events are append-only and sync
local-first (Dexie/IndexedDB) to Supabase.

## Stack

Vite + React 19 + TypeScript (strict) · Tailwind v4 · vite-plugin-pwa · Dexie 4 · Zustand ·
zod · Supabase (Postgres + Auth + RLS) · Netlify Functions (AI key proxy).

## Develop

```sh
npm install
npm test          # insulin engine unit vectors — must be green
npm run dev       # app only (no AI function)
npm run dev:full  # netlify dev — app + /api/ai function
```

The default BG source is the **demo generator**, so the whole pipeline runs on desktop with
no phone. Without Supabase env vars the app runs local-only (no sync, no AI).

## Deploy

1. **Supabase**: create a project → SQL editor → paste `supabase/migration.sql` →
   Authentication → Providers → Email: enable, disable "Confirm email" → copy Project URL +
   anon key.
2. **Netlify**: Import from Git (build config auto-detected from `netlify.toml`) and set env vars:
   - Client: `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`
   - Functions: `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `ANTHROPIC_API_KEY`,
     `OPENAI_API_KEY` (optional), `AI_DEFAULT_PROVIDER=anthropic`
3. **Phone**: Chrome → install PWA → Settings → BG source `xdrip`, URL
   `http://127.0.0.1:17580` (+ secret if set) → Test connection.
   In xDrip+: Settings → Inter-app settings → xDrip Web Service: ON.

## Safety model

- Dose math lives in pure, unit-tested functions in `src/lib/insulin/`; the math shown on
  the card **is** the math run.
- Nothing blocks: out-of-pattern results (large dose, suspicious carb parse) get a one-tap
  confirmation with the reason stated; all thresholds are configurable in Settings.
- Events are append-only; corrections supersede rather than mutate, which makes
  multi-device sync a pure set-union (dedupe by UUID).
