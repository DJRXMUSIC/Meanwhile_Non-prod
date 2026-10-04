# MeanwhileV4 — Build Spec for Claude Code

> **Claude Code: this file is your complete brief.** Read all of it before writing code. Build the app milestone by milestone (section 14), in order, committing and pushing after each milestone. Only stop to ask Danny for the items in section 2 ("What you need from Danny"). Everything else is decided here; where a detail isn't specified, choose the option that best serves the principles in section 1 and note the choice in `docs/DECISIONS.md`.
>
> First action: copy this file into the repo as `docs/SPEC.md`, and create a short `CLAUDE.md` at the repo root that points to it and summarizes the principles and the milestone you're on.

---

## 0. What this app is

MeanwhileV4 is a personal Android app for **Danny**, who has type 1 diabetes and uses insulin pens. It recommends a **factor-adjusted rapid-acting insulin dose ("Next Best Action")**, a **pre-bolus lead time**, and **split-dose plans**, using:

- live CGM readings (Eversense, received over a local network feed like xDrip),
- a **factor profile** (sleep, caffeine, alcohol, exercise, etc.) that an AI updates on demand and re-learns every night at 1 am,
- deterministic, tested dose math on the phone.

Danny reviews every recommendation and decides what to inject. The app never doses anything itself.

**Goal the app serves:** 80% time in range (70–180 mg/dL), sustained for 14 days.

---

## 1. Principles (apply these to every decision)

1. **Danny is the gate.** The AI is fully unlocked to propose any dose, weight, bound or new factor. Nothing changes the profile or gets logged as a dose without Danny accepting or editing it.
2. **Not designed for caution.** Defaults aim for accuracy, not the safest-sounding value. Don't add hidden safety dampening, extra caps or "are you sure" friction beyond what this spec defines.
3. **Dose math is deterministic local code.** The AI never computes the dose. Next Best Action must work instantly with no internet.
4. **Speed and reliability over battery.** Keep services alive; never skip data.
5. **Transparency.** Every recommendation shows its full breakdown.
6. **Everything is tunable.** Every numeric value in this spec is a *starting value* stored in the profile/settings, not a hard-coded constant. The nightly learn cycle can propose changes to any of them.
7. **Append-only data.** Records are never edited or deleted; corrections are new records.
8. **Aim: great, not 80/20.**

---

## 2. What you need from Danny

Ask Danny for these when you reach the milestone that needs them — not before. Give him exact, click-by-click steps for each.

| Needed for | Item |
|---|---|
| M1 | GitHub repo created (suggested name `MeanwhileV4`, private) and access for you to push |
| M1 | Danny adds GitHub Actions secrets you generate/explain: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. You generate the keystore; tell Danny to **back it up offline** (losing it means future updates can't install over the existing app). |
| M2 | Supabase project created; Danny gives you the project URL and **anon key** (safe to ship in the APK; RLS protects data). Danny creates his user account (email + password) in the app on first launch. |
| M2 | Supabase CLI access (or Danny runs the migration/deploy commands you give him) |
| M6 | Gemini API key and Anthropic API key, which **Danny sets as Supabase secrets himself** (`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`). Keys must never appear in the repo, the APK, logs or chat. |
| M3 | Which CGM bridge app he's running (xDrip+ or other) and confirmation its local web service / local broadcast is enabled |

Also add `SUPABASE_URL` and `SUPABASE_ANON_KEY` as GitHub Actions secrets and inject them via `BuildConfig` — don't commit them.

---

## 3. Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 (light + dark, dynamic color off; use the app's own color coding) |
| Min / target SDK | minSdk 31; target the latest stable SDK. Device: **Google Pixel 9a**. |
| Local DB | Room (SQLite) |
| Background | Foreground service + `AlarmManager` exact alarms + WorkManager for sync retries |
| Networking | Ktor client or OkHttp + kotlinx.serialization |
| Cloud DB / auth | Supabase (Postgres, Auth, Row Level Security) via the official Supabase Kotlin client |
| AI | Supabase Edge Function (TypeScript/Deno) calling **Google Gemini (default)** and **Anthropic Claude (fallback)** |
| Speech | Android `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE = true` (on-device on Pixel); typed input always available |
| DI | Hilt (or manual DI if simpler) |
| Tests | JUnit for the dose engine (must pass in CI), Compose UI tests for core flows where practical |
| CI/CD | GitHub Actions → signed release APK → GitHub Release |

Architecture: single-activity Compose app, clean layering:
`ui/` → `domain/` (pure Kotlin: dose engine, factor engine, IOB, router rules — **no Android dependencies, fully unit-tested**) → `data/` (Room, Supabase sync, CGM sources, AI client).

---

## 4. Insulin regimen and baseline settings (starting values)

| Setting | Value |
|---|---|
| Delivery | Pens, **whole units only** (1 u increments) |
| Rapid-acting insulin | **Humalog** |
| Long-acting | Once daily, usually 6:45–7:30 pm (logged only; not a factor) |
| ICR (insulin-to-carb ratio) | **1 u : 10 g** |
| ISF (correction factor) | **1 u : 25 mg/dL** |
| Target BG | **100 mg/dL** |
| Pre-bolus lead time baseline | **12 min** |
| Insulin action curve | delay **10 min**, peak **75 min**, duration **5 h** (section 6) |
| Combined factor cap | **2.0×** upper; **no floor** |
| Rounding | **nearest whole unit**, halves round up; minimum 0 |
| Timezone | device local (Danny: America/New_York) |

All of these live in the versioned profile (section 8) and are tunable.

---

## 5. Dose engine (domain layer — deterministic)

### 5.1 Inputs
- `carbs_g`, `fat_g`, `protein_g` (meal; any may be 0)
- `bg` (latest CGM reading, mg/dL) and `trend` (rate, mg/dL/min, from recent readings)
- `iob` (section 6)
- active factor weights (section 7)
- pending caffeine cups not yet dosed
- current profile values (section 4)

### 5.2 Calculation
```
carb_dose  = carbs_g / ICR
correction = (bg − target) / ISF

if carbs_g >= LOW_CARB_THRESHOLD (15 g):
    w_fat     = 1 + K_FAT     × fat_g        # K_FAT     = 0.0045 per g
    w_protein = 1 + K_PROTEIN × protein_g    # K_PROTEIN = 0.0060 per g
    fp_units  = 0
else:                                        # low-carb meal
    w_fat = w_protein = 1.00
    fp_units = fat_g / FAT_G_PER_UNIT + protein_g / PROTEIN_G_PER_UNIT   # 11 g, 25 g

baseline = carb_dose + fp_units + correction − iob

combined = 1 + Σ (w_i − 1)          # over ALL active factor weights, incl. w_fat, w_protein
combined = min(combined, COMBINED_CAP)   # 2.0; no floor

raw   = baseline × combined + caffeine_units      # caffeine_units = cups × 1 u
final = max(0, floor(raw + 0.5))                  # nearest whole unit
```
- If `raw < 0`: final is 0, and the result card shows **"Projected below target — consider ~N g carbs"** where `N = round(−raw × ICR)`.
- Factor weights are **added, never multiplied** together.

### 5.3 Split dose (pens)
- Trigger: `fat_g >= 40 AND protein_g >= 25` (both tunable), and `carbs_g >= 15`.
- `first = round(final × 0.60)`, `second = final − first`.
- Second injection reminder at **meal start + 60 min** (tunable). Both injections are logged separately when Danny confirms them.

### 5.4 Pre-bolus lead time (starting rules, all tunable)
```
if bg < 90 or trend <= −2 mg/dL/min:     lead = 0      # eat now
else:
    lead = 12
    if bg > 150: lead += 5 × floor((bg − 150) / 50)   # +5 min per 50 over 150
    if meal flagged liquid/sugary: lead += 5
    if fat_g >= 40: lead −= 5
    if exercise factor active: lead −= 5
    lead = clamp(lead, 0, 30)
```

### 5.5 Golden tests (must pass; add to `domain` test suite)
Settings as in section 4, no other factors unless listed. `iob` given directly.

| # | Input | Factors | baseline | combined | raw | **final** |
|---|---|---|---|---|---|---|
| 1 | 60 g carbs, BG 100, IOB 0 | — | 6.000 | 1.00 | 6.00 | **6** |
| 2 | 60 g carbs, BG 200, IOB 0 | — | 10.000 | 1.00 | 10.00 | **10** |
| 3 | 60 g carbs, BG 150, IOB 1.5 | — | 6.500 | 1.00 | 6.50 | **7** |
| 4 | 50 g carbs, BG 100, IOB 0, 1 coffee | sleep poor 1.25 | 5.000 | 1.25 | 7.25 | **7** |
| 5 | 40 g carbs, BG 100, IOB 0 | sunburn 2.0, alcohol fresh 0.70 | 4.000 | 1.70 | 6.80 | **7** |
| 6 | 80 g carbs, 40 g fat, 25 g protein, BG 100 | (w_fat 1.18, w_protein 1.15) | 8.000 | 1.33 | 10.64 | **11**, split **7 now + 4 at +60 min** |
| 7 | 5 g carbs, 30 g fat, 50 g protein, BG 100 | (low-carb mode) | 5.227 | 1.00 | 5.227 | **5**, no split |
| 8 | 50 g carbs, BG 100 | sunburn 2.0, sleep 1.25, stress 1.20 | 5.000 | 2.00 (cap) | 10.00 | **10** |
| 9 | 0 g carbs, BG 80, IOB 2 | — | −2.800 | 1.00 | −2.80 | **0** + "consider ~28 g carbs" |
| 10 | Coffee only, BG 100, IOB 0 | — | 0.000 | 1.00 | 1.00 | **1** |

---

## 6. Insulin-on-board (exponential activity model)

Same model family used by Loop/OpenAPS. Per dose, with `t = minutes since dose − DELAY` (DELAY = 10), `tp = 75`, `td = 300`:
```
if t <= 0: fraction = 1
if t >= td: fraction = 0
tau = tp × (1 − tp/td) / (1 − 2·tp/td)
a   = 2·tau / td
S   = 1 / (1 − a + (1 + a) · exp(−td/tau))
activity(t)  = (S / tau²) · t · (1 − t/td) · exp(−t/tau)
fraction(t)  = 1 − S·(1 − a)·((t² / (tau·td·(1 − a)) − t/tau − 1) · exp(−t/tau) + 1)

IOB = Σ over logged rapid-acting doses: units × fraction(t)
```
Only **logged rapid-acting** doses count (not long-acting).

**Golden tests** (`fraction` at t after delay; tolerance ±0.01):

| t (min) | 0 | 60 | 75 | 120 | 180 | 240 | 300 |
|---|---|---|---|---|---|---|---|
| fraction | 1.00 | 0.76 | 0.67 | 0.41 | 0.16 | 0.03 | 0.00 |

- 4 u given 70 min ago → IOB **3.06**
- 3 u given 120 min ago + 2 u given 30 min ago → IOB **3.32**

The profile shows the current peak and duration. The learn cycle can propose new values from Danny's correction outcomes.

---

## 7. Factors

### 7.1 Factor table (starting values; all tunable)

| ID | Factor | Type | Bounds | Default weight when activated offline | Input | Window |
|---|---|---|---|---|---|---|
| F1 | Carbs | Baseline (ICR) + lead time | — | — | Meal | Per meal |
| F2 | Fat | Multiplier by grams (5.2); split trigger | 1.00 – cap | computed | Meal | Per meal |
| F3 | Protein | Multiplier by grams (5.2); split trigger | 1.00 – cap | computed | Meal | Per meal |
| F4 | Caffeine | **Add +1 u per cup**, one time, at the time of drinking. Coffee alone can trigger a dose. | — | +1 u/cup | Factor update | Consumed by the next dose logged |
| F5 | Alcohol | Multiplier with hourly decay (7.2) | no floor – 1.00 | 0.70 | Factor update | 4 h per drink; **new drink resets** (no stacking) |
| F6 | Hydration | Multiplier; dehydrated raises, logging water returns toward 1.00 | 1.00 – 1.10 | 1.10 when dehydrated; 1.00 after water logged | Factor update | Until 1 am reset |
| F7 | Exercise | Multiplier; Danny logs **when a workout ends**, with **workout type** | 0.70 – 1.10 | 0.85 | Factor update | **24 h** from workout end |
| F8 | Sleep | Multiplier; **3-level choice on morning report**, applied immediately | 1.00 – 1.25 | Good 1.00 · OK 1.10 · Poor 1.25 | Morning report | Until 1 am reset |
| F9 | Stress & illness | Multiplier | 1.00 – 1.20 | stress 1.10 · illness 1.20 | Factor update | Up to **24 h** per episode |
| F10 | Recent hypoglycemia | Multiplier (auto) | 0.80 – 1.00 | 0.80 | Auto: any CGM reading < 70 | 12 h after the last reading < 70 |
| F11 | Overnight highs | Multiplier (auto, 7.3) | 1.00 – 1.25 | computed | Auto at 6 am | Until 1 am reset |
| F12 | Sunburn | Multiplier; rare | 2.00 | 2.00 | Factor update | **24 h** |

Weights within bounds are what the AI sets when online (Update Profile). The "default when activated offline" column is what code applies when there's no internet (section 9.4).

### 7.2 Alcohol decay
Weight by hours since the **most recent** drink:

| Hours | 0–1 | 1–2 | 2–3 | 3–4 | 4+ |
|---|---|---|---|---|---|
| Weight | 0.70 | 0.75 | 0.80 | 0.85 | inactive (1.00) |

### 7.3 Overnight highs (F11)
At **6 am**, count hours between 10 pm and 6 am with CGM > 180 mg/dL (sum of reading intervals):
```
if hours >= 3: w = min(1 + 0.05 × hours, 1.25)   # 3 h → 1.15, 4 h → 1.20, 5 h+ → 1.25
else:          w = 1.00
```
Computed by code (no AI), added to the active profile, shown on the profile screen.

### 7.4 Reset rule
**Every factor resets to its baseline at 1 am** unless its window says otherwise (F5, F7, F9, F10, F12 keep running on their own timers). Implement windows as data (start time, duration, decay rule) so the AI can propose new ones.

### 7.5 Future granularity (design for it now)
Exercise should eventually have separate weights/windows by workout type, intensity, duration and time of day, and an effect curve over its window instead of a flat weight. Store workout type, duration and intensity on every exercise event so the learn cycle can build this. The data model must allow **new factors** to be added from an accepted AI proposal without an app update (factor definitions stored as data: id, name, type, bounds, default weight, window rule, decay rule).

---

## 8. Profile

- A **profile** = all settings in section 4 + factor definitions + current active factor weights + IOB curve params + lead-time rules + split rules + fat/protein constants.
- Profiles are **versioned**. Each version records: created_at, source (`learn_cycle`, `ai_update`, `offline_fallback`, `manual`, `auto_f11`, `sleep_checkin`), the diff from the previous version, and whether Danny accepted, edited or rejected it.
- Next Best Action always uses the **latest accepted** version.
- Any version can be viewed and **reverted to** (revert = new version copying the old one).

---

## 9. Input, routing and the two paths

### 9.1 One input
Main screen: a large **mic button** plus a **text field**. Voice is transcribed on-device; the transcript is shown and editable before sending.

### 9.2 Router
Classifies each input into one or more intents:
- `meal` → Next Best Action
- `factor_update` (incl. exercise, water, coffee, alcohol, stress, sunburn, sleep) → Update Profile
- `dose_given` ("took 6 units", "took my long-acting 22") → dose log
- `feedback` (trigger words: input starts with "feedback", "app note", "idea" or "bug") → feedback log
- Combined inputs ("pizza and a coffee") → Update Profile first, then Next Best Action using the updated profile.

**Online:** router is an AI call (section 10). **Offline:** a deterministic keyword/regex classifier in `domain` (must handle numbers like "60 carbs 20 fat 30 protein", "2 coffees", "beer", "glass of wine", "ran 5 miles", "lifted", "took 7 units").

The result always shows **which path was taken** with a one-tap control to switch it.

### 9.3 Meals
- Danny can give macros as numbers, or describe food. When online, the AI may **estimate** carbs/fat/protein from a description; estimates are clearly labeled and Danny confirms or edits them before the dose is calculated.
- Offline, food descriptions without numbers prompt for carbs/fat/protein.
- Meal flags the AI or Danny can set: `liquid_or_sugary`.

### 9.4 Update Profile
- Online: AI returns proposed factor changes (factor, weight, window, reason). Danny accepts/edits → new profile version.
- **Offline fallback:** log the event immediately, apply the factor's **default weight** (table 7.1) via code as a provisional version (`offline_fallback`), and **queue the AI call**; when back online, run it and present the refinement for acceptance.

### 9.5 Next Best Action result card
Shows:
- **Final dose** (large), lead time, split plan if any
- Breakdown: carb dose, fat/protein units or weights, correction, IOB, each active factor and weight, combined multiplier (and whether capped), caffeine units, raw → rounded
- Current BG, trend arrow, reading age (warn if older than 15 min)
- **Profile callout:** when the profile was last updated, by what (learn cycle / AI update / offline fallback / manual / auto), and whether an AI refinement is still queued
- Buttons: **Log dose as shown**, **Edit amount**, **Dismiss**. Logging records the proposal, the dose actually given, and an optional override reason (voice or text).

### 9.6 Dose logging
Every rapid-acting injection must be logged (needed for IOB). Long-acting doses are logged for the record. A split dose's second injection is logged from its reminder notification.

---

## 10. AI layer

### 10.1 Edge Function
One Supabase Edge Function, `ai`, invoked by the app with Danny's auth JWT. It:
- accepts `{ job, payload, provider_preference }` where `job ∈ { route, update_profile, estimate_meal, learn_cycle }`,
- calls the preferred provider; on error, timeout (route/update/estimate: 15 s; learn_cycle: 120 s) or **schema validation failure**, calls the other provider,
- validates output against the job's JSON schema,
- returns `{ result, provider, model, latency_ms, fallback_used }`,
- reads `GEMINI_API_KEY` and `ANTHROPIC_API_KEY` from Supabase secrets; model names from env/config (`GEMINI_MODEL`, `CLAUDE_MODEL`) so they can be upgraded without code changes. Use each provider's current official REST API and structured/JSON output mode; check current docs for request formats.

### 10.2 Provider setting
In-app setting: **Gemini first (default)**, Claude first, Gemini only, Claude only. Stored in settings; sent with each call.

### 10.3 Jobs and output schemas (JSON)
**route**
```json
{ "intents": [ { "type": "meal|factor_update|dose_given|feedback",
                 "text_span": "string",
                 "confidence": 0.0 } ] }
```
**estimate_meal**
```json
{ "carbs_g": 0, "fat_g": 0, "protein_g": 0, "liquid_or_sugary": false,
  "is_estimate": true, "notes": "string" }
```
**update_profile**
```json
{ "changes": [ { "factor_id": "F7", "weight": 0.85, "window_minutes": 1440,
                 "decay_rule": null, "units_add": null,
                 "reason": "string" } ],
  "new_factors": [ ],
  "summary": "string" }
```
**learn_cycle**
```json
{ "proposed_settings": { "ICR": 10, "ISF": 25, "target": 100, "lead_time_min": 12,
                         "iob_peak_min": 75, "iob_duration_min": 300 },
  "factor_changes": [ { "factor_id": "F4", "field": "units_add|weight|bounds|window|decay|default_weight",
                        "old": null, "new": null, "evidence": "string" } ],
  "new_factors": [ { "id": "string", "name": "string", "definition": { } , "evidence": "string" } ],
  "observations": [ "string" ],
  "summary": "string" }
```

### 10.4 Prompts
Store prompts as versioned text files in the Edge Function (`prompts/<job>.md`). Each prompt includes:
- Danny's regimen and current profile (sent in payload),
- the factor definitions and dose formula (so the AI understands what its numbers do),
- principle: **"You are fully unlocked to propose whatever values the data supports. Danny reviews every proposal. Do not soften proposals for caution; aim for accuracy."**
- instruction to return only JSON matching the schema.

### 10.5 Logging
Every AI call is stored (`ai_calls`): job, provider, model, latency, fallback_used, request summary, response, validation result. No API keys in logs.

### 10.6 AI is never required for a dose
If the AI is unavailable, routing uses the offline classifier and dose math runs locally. Show a small "AI offline" indicator.

---

## 11. Nightly learn cycle (1 am) and morning report

### 11.1 Schedule
- **Exact alarm at 1:00 am** local (allowed while idle). Steps:
  1. Apply the **1 am factor reset** (7.4).
  2. Assemble the payload from the local DB: last 24 h (and rolling 14-day summary) of CGM readings, meals, factor events, doses, every NBA proposal vs. dose given, override reasons, BG outcomes at 2/3/4 h after each dose, current profile.
  3. Call `ai` with `job = learn_cycle`.
  4. Store the proposal as a pending profile version; generate the morning report.
- **Catch-up:** if the 1 am run didn't complete (phone off, offline, app killed), run it on next app open **before** showing the morning report. If it can't run, carry yesterday's profile forward and say so.
- **6 am:** compute F11 (7.3) by code.

### 11.2 Learn cycle rules
**Unlocked:** no cap on how far values move per night, no minimum sample size. It can propose changes to ICR, ISF, target, lead time, IOB curve, every factor's bounds/weights/windows/decay/units, fat/protein constants, split rules, and **new factors** (e.g., dawn phenomenon if it appears in the data).

### 11.3 Morning report (shown on first open after 1 am)
1. **Sleep check-in:** Good / OK / Poor → sets F8 immediately (new profile version, source `sleep_checkin`).
2. **Proposed changes** from the learn cycle: each change with old → new and the evidence in plain language. Per change: **Accept**, **Edit**, **Reject**; plus **Accept all**.
3. Yesterday at a glance: time in range, time below 70, time above 180, number of doses, proposals followed vs. overridden.

### 11.4 Outcome tagging
For each logged dose, store BG at +2 h, +3 h, +4 h and min/max in the 4 h after, so the learn cycle and reliability stats can score it.

---

## 12. Data

### 12.1 Local-first + Supabase sync
- **Room** is the working copy: all reads/writes happen locally first; the app works fully offline.
- **Supabase Postgres** is the durable copy and source of truth for analysis.
- Every record: `id` (UUIDv7 generated on device), `user_id`, `created_at` (device time, UTC), `recorded_at` (when it happened), `sync_state` (local only).
- **Append-only**: no updates or deletes; corrections are new rows referencing the original (`supersedes_id`).
- **Sync worker** (WorkManager): pushes unsynced rows in batches whenever online; retries with backoff; idempotent upserts on `id`. Pulls rows missing locally (for restore).
- **Restore:** fresh install → sign in → pull everything.
- **Sync status** visible in settings and as a small indicator: last successful sync, items pending.

### 12.2 Tables (Room and Supabase mirror)
- `cgm_readings` (timestamp, mg_dl, trend, source, sensor_status)
- `meals` (carbs_g, fat_g, protein_g, liquid_or_sugary, is_estimate, description)
- `factor_events` (factor_id, action: activate|deactivate|value, weight, window_minutes, details json — e.g., cups, workout type/duration/intensity, drink type)
- `doses` (insulin: rapid|long, units, given_at, proposal_id nullable, split_part nullable, override_reason)
- `proposals` (NBA input snapshot, full breakdown json, final_units, lead_time, split plan, profile_version_id)
- `outcomes` (dose_id, bg_2h, bg_3h, bg_4h, min_4h, max_4h)
- `profile_versions` (version number, source, full profile json, diff json, status: pending|accepted|edited|rejected, decided_at)
- `factor_definitions` (as data; versioned via profile)
- `ai_calls` (10.5)
- `feedback` (text, created_at, screen/context)
- `inputs` (raw transcript/text, router result, path taken, manual switch)

### 12.3 Supabase
- SQL migrations in `supabase/migrations/`.
- **Row Level Security on every table:** `user_id = auth.uid()` for select/insert; no update/delete policies.
- Indexes on `(user_id, recorded_at)` for every table.
- Views for analysis: daily and hourly time-in-range, by month/day/time-of-day.

### 12.4 Export
Settings → Export: CSV per table (and one zip), date-range picker, share sheet. Also document in `docs/ANALYSIS.md` how to query Supabase directly.

---

## 13. CGM intake and Android platform

### 13.1 CGM source interface
```kotlin
interface CgmSource {
    suspend fun fetchSince(since: Instant): List<CgmReading>   // for back-capture
    fun live(): Flow<CgmReading>
    val name: String
}
```
Implement first: **xDrip-compatible local feed.** Danny will run xDrip+ (or similar) on the same phone bridging his Eversense.
- Primary: poll xDrip+'s **local web service** (Nightscout-style `sgv.json`, on localhost) every 60 s; verify the current port/path and query parameters against xDrip+ documentation/source and make host/port/path configurable in settings.
- Secondary: receive xDrip+'s **local broadcast** of new readings if enabled; verify the current intent action names against xDrip+ source.
- Allow cleartext HTTP to localhost only (network security config).
- Dedupe readings by timestamp. A future built-in Eversense interceptor will be another `CgmSource` implementation.

### 13.2 Staleness
If the latest reading is older than 15 min, show a clear banner on the main screen and on the NBA card.

### 13.3 Background reliability (Pixel 9a)
- **Foreground service** with a persistent notification (shows latest BG + trend) for CGM intake, factor timers and alarms. Choose a foreground service type that permits long-running operation on the current target SDK — check current Android rules (e.g., time limits on some types) and pick one that won't be cut off.
- **Exact alarms** for 1 am and 6 am, and split-dose reminders (`SCHEDULE_EXACT_ALARM`; guide Danny to grant it).
- Request **battery optimization exemption** on first run with a clear explanation.
- Restart on boot.
- **Back-capture:** on every service start, call `fetchSince(last_reading_time)`.

### 13.4 Notifications
Split-dose second injection reminder · morning report ready · CGM stale · sync failing for > 1 h · AI refinement ready after offline fallback.

---

## 14. Milestones (build in this order; each must end with a working, installable APK)

Each milestone: implement → tests pass in CI → push → GitHub Release published → write a short "What to try" note in the Release description for Danny.

**M1 — Pipeline + shell**
Repo structure, `CLAUDE.md`, Compose app shell (main screen with placeholder input), light/dark theme, GitHub Actions: build, run unit tests, sign with release keystore from secrets, publish GitHub Release with the APK (versionCode = run number). Instructions for Danny: installing from Releases on the Pixel, optional Obtainium for auto-updates, backing up the keystore.
*Done when:* Danny installs the APK from a Release link and the app opens.

**M2 — Data layer + Supabase**
Room schema, Supabase project migrations + RLS, auth (email/password sign-in), sync worker, restore, sync status, CSV export.
*Done when:* records created offline appear in Supabase after reconnect; uninstall → reinstall → sign in restores all data.

**M3 — CGM intake + foreground service**
`CgmSource` + xDrip local feed, foreground service with BG notification, back-capture, staleness banner, boot restart, battery exemption flow.
*Done when:* live readings appear within ~1 min of xDrip; killing the app and reopening back-fills the gap.

**M4 — Dose engine + IOB**
Pure Kotlin `domain` module: sections 5, 6, 7 (factor weights, windows, decay, F11, reset logic). All golden tests (5.5, 6) pass in CI.
*Done when:* tests pass; a debug screen lets Danny enter inputs and see the full breakdown.

**M5 — Input, routing, NBA, dose logging, profile**
Mic + text input, on-device speech, offline router, NBA card (9.5), dose logging incl. split reminders, profile screen (current factors, weights, windows, IOB curve, versions, revert), manual factor activation, feedback capture.
*Done when:* Danny can say "60 carbs 20 fat 10 protein" offline and log the dose; say "had a coffee" and get +1 u; see the profile and its history.

**M6 — AI layer**
Edge Function (`route`, `estimate_meal`, `update_profile`), provider setting, fallback, validation, ai_calls log, offline queue + refinement flow, AI-offline indicator.
*Done when:* online inputs route via AI; food descriptions get estimates to confirm; Update Profile proposals can be accepted/edited; turning off Gemini key in a test falls back to Claude.

**M7 — Learn cycle + morning report**
1 am reset + alarm, payload builder, `learn_cycle` job, catch-up on open, morning report (sleep check-in, accept/edit/reject per change), 6 am F11, outcome tagging.
*Done when:* after a night, Danny sees a morning report with proposed changes and evidence, and accepting them creates a new profile version.

**M8 — Stats + polish**
Reliability stats screen: time in range (day/14-day), below/above range, proposals followed vs. overridden and outcomes of each, accuracy by AI provider/model. Color coding (in range / high / low), light/dark refinement, performance pass (NBA result < 200 ms), Supabase analysis views, `docs/ANALYSIS.md`.
*Done when:* stats screen shows real data; app feels fast.

---

## 15. Interface notes
- Main screen: current BG (big), trend arrow, reading age, the input (mic + text), compact view of active factors with weights, last profile update callout.
- No glucose graph required.
- Color coding: in range (70–180) green, high amber/red, low red/purple; consistent in light and dark mode.
- Every AI-originated value is visibly marked as AI-proposed until Danny accepts it.
- Keep taps to a minimum: speak → review card → log.

---

## 16. Definition of "full functioning app"
All milestones M1–M8 complete; all golden tests passing in CI; signed APK on a GitHub Release; Danny can, on his Pixel 9a:
1. See live Eversense BG via xDrip on the main screen,
2. Speak or type a meal and get a dose, lead time and split plan with full breakdown — offline included,
3. Log factor updates (coffee, drinks, workouts, water, stress, sunburn) via AI or offline,
4. Log doses and get split reminders,
5. Wake up to a morning report with a sleep check-in and AI-proposed profile changes to accept/edit,
6. Lose or reinstall the phone and get all data back from Supabase,
7. Export his data and view reliability stats.
