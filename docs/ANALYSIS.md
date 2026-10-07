# Analysing your data

Everything the app records is append-only and lands in your Supabase project, so you can slice it any way you like.
The phone's **Stats** screen (bar-chart icon on the main screen) covers the everyday questions; this page is for
going deeper.

## 1. Where to run queries

**Supabase SQL editor (easiest).** supabase.com → your project → **SQL Editor** (left sidebar) → **New query** →
paste a query → **Run**. The results pane has an export/download button for CSV.

The SQL editor runs as the database owner, so it sees every row (you're the only user, so that's just your data).
From the app or the REST API the same views only ever show the signed-in user's rows (`security_invoker` + row-level
security).

**psql / any Postgres client.** Project → **Connect** (top bar) → copy the **Session pooler** connection string,
replace `[YOUR-PASSWORD]` with the database password you set when creating the project, then:

```bash
psql "postgresql://postgres.<project-ref>:<password>@<pooler-host>:5432/postgres"
```

Tools like DBeaver, TablePlus, Excel's Postgres connector or a Jupyter notebook take the same string.

**CSV from the phone.** Settings → Data → **Export…** writes one CSV per table (plus a zip of all of them) for any
date range — works offline, from the local copy.

## 2. Tables

| Table | One row per | Useful columns |
|---|---|---|
| `cgm_readings` | CGM reading (~every 5 min) | `recorded_at`, `mg_dl`, `trend_rate`, `direction`, `source` |
| `meals` | meal / snack eaten | `recorded_at`, `carbs_g`, `fat_g`, `protein_g`, `is_estimate`, `description`, `details` (AI estimate provenance) |
| `factor_events` | factor logged (coffee, drink, workout, sleep, …) | `recorded_at`, `factor_id`, `action`, `weight`, `window_minutes`, `units_add`, `details` |
| `doses` | injection (or a correction of one) | `given_at`, `insulin`, `units`, `proposal_id`, `proposed_units`, `override_reason`, `supersedes_id` (a correction points at the row it fixes; units 0 = not taken) — use `v_doses_effective` for doses as corrected |
| `proposals` | Next Best Action shown | `recorded_at`, `final_units`, `lead_time_min`, `split_plan`, `breakdown` (full math), `input_snapshot` |
| `outcomes` | dose outcome (+2/+3/+4 h) | `dose_id`, `bg_2h`, `bg_3h`, `bg_4h`, `min_4h`, `max_4h` |
| `profile_versions` | factor-profile version | `created_at`, `version`, `source`, `status`, `summary`, `profile` (full JSON), `diff`, `ai_call_id` |
| `factor_definitions` | factor definition version | `factor_id`, `definition` |
| `ai_calls` | AI request | `job`, `provider`, `model`, `latency_ms`, `fallback_used`, `validation` (`ok`, `invalid`, `error`, `submitted` = learn batch sent), `error`, `request_summary`, `request` (the full request, word for word), `response` |
| `inputs` | thing you said or typed | `raw_text`, `via`, `path_taken` (offline / AI), `router_result` |
| `feedback` | app note or dose feedback | `text`, `context` |
| `conversation_log` | everything in the conversation, word for word | `role` (`user`, `app`, `system`), `kind` (`message`, `step`, `reply`, `action`, `error`), `text` (exactly what was said / shown), `details` (voice alternatives and confidences, step timing and AI model, every card's full data), `input_id` (links a message's rows) |
| `app_logs` | line of the phone's own log | `recorded_at`, `level` (`D`/`I`/`W`/`E`), `tag`, `message`, `detail` (stack trace), `app_version`, `device` |
| `learning_log` | learning journal entry | `kind` (`lessons`, `applied`, `proposed`, `kept`, `reverted`, `revert_proposed`, `undone`, `ai_review`, `error`), `summary`, `details` (lessons with their measured errors, the evidence for a change, the before/after evaluation), `supersedes_id` (a verdict points at the change it closes) |

Nothing is ever updated or deleted; a correction is a new row. `seq` is the server's insertion order.

## 3. Ready-made views

All times are converted to local time with `meanwhile_tz()` (currently `America/New_York` — if you move, change it in
one place with `create or replace function public.meanwhile_tz() returns text language sql immutable as $$ select
'Europe/London'::text $$;`).

Time in range in these views is **by reading count**; the phone's Stats screen is **time-weighted** (each reading
counts until the next, gaps excluded). With a steady 5-minute feed they agree to within a fraction of a percent.

| View | What it gives you |
|---|---|
| `v_cgm_local` | every reading with `local_ts` |
| `v_tir_daily` | per local day: readings, `tir_pct`, `below70_pct`, `below54_pct`, `above180_pct`, `above250_pct`, mean, SD, GMI |
| `v_tir_hourly` | per day and hour |
| `v_tir_monthly` | per month |
| `v_tir_by_time_of_day` | all-time, per hour of day — where the trouble spots are |
| `v_tir_by_weekday` | all-time, per weekday |
| `v_proposal_outcomes` | every proposal: carbs, BG, proposed vs given, `follow` (`followed` / `overridden` / `not_logged`), override reasons, outcome of the first injection |
| `v_ai_calls_by_model` | AI calls per provider / model / job: ok / invalid / error counts, fallbacks, mean latency |
| `v_learned_changes` | every learned profile change: path, old → new, evidence, and its `outcome` (`kept`, `reverted`, `undone`, `revert_proposed` or `under_evaluation`) with the reason |
| `v_doses_effective` | doses as corrected: every dose no later correction supersedes |
| `v_conversation` | the conversation in order with `local_ts` |

## 4. Example queries

**Last 14 days against the 80% goal**

```sql
select day, readings, tir_pct, below70_pct, above180_pct, mean_mg_dl, gmi_pct
from v_tir_daily
where day >= current_date - 13
order by day;
```

**Longest run of ≥ 80% days**

```sql
with d as (
  select day, tir_pct >= 80 as hit,
         day - (row_number() over (partition by tir_pct >= 80 order by day))::int as grp
  from v_tir_daily where readings >= 144   -- at least 12 h of data
)
select min(day) as from_day, max(day) as to_day, count(*) as days
from d where hit group by grp order by days desc limit 5;
```

**Where in the day you go high (last 30 days)**

```sql
select extract(hour from local_ts)::int as hour,
       round(100.0 * avg((mg_dl > 180)::int), 1) as above180_pct,
       round(avg(mg_dl)) as mean
from v_cgm_local
where local_ts >= now() at time zone meanwhile_tz() - interval '30 days'
group by 1 order by 1;
```

**Did following the proposal work better than overriding it?**

```sql
select follow, count(*) as proposals,
       round(avg(bg_3h)) as mean_bg_3h,
       round(100.0 * avg((bg_3h between 70 and 180)::int), 1) as in_range_3h_pct,
       round(100.0 * avg((min_4h < 70)::int), 1) as low_within_4h_pct,
       round(100.0 * avg((max_4h > 250)::int), 1) as high_within_4h_pct
from v_proposal_outcomes
where recorded_at >= now() - interval '30 days'
group by follow;
```

**Overrides and why**

```sql
select local_ts, carbs_g, bg, final_units, units_given, override_reasons, bg_3h
from v_proposal_outcomes
where follow = 'overridden'
order by recorded_at desc limit 50;
```

**Which factors were active when a dose went low** (factor events in the 12 h before the dose)

```sql
select d.given_at, d.units, o.min_4h,
       string_agg(distinct f.factor_id, ', ') as factors
from doses d
join outcomes o on o.dose_id = d.id
left join factor_events f on f.user_id = d.user_id
  and f.recorded_at between d.given_at - interval '12 hours' and d.given_at
where o.min_4h < 70
group by d.id, d.given_at, d.units, o.min_4h
order by d.given_at desc;
```

**The full math behind one proposal**

```sql
select recorded_at, final_units, jsonb_pretty(breakdown)
from proposals order by recorded_at desc limit 1;
```

**How the profile changed over time**

```sql
select created_at, version, source, status, summary, jsonb_pretty(diff)
from profile_versions order by created_at desc limit 20;
```

**AI reliability**

```sql
select provider, model, job, calls, ok, invalid, errors, fallback_used, mean_latency_ms
from v_ai_calls_by_model order by calls desc;
```

**AI meal estimates vs what you actually logged**

```sql
select recorded_at, description,
       (details -> 'estimate' -> 'original' ->> 'carbs_g')::numeric as ai_carbs,
       carbs_g as logged_carbs,
       details -> 'estimate' ->> 'model' as model
from meals
where is_estimate
order by recorded_at desc;
```

**What the app learned, and whether it stuck**

```sql
select applied_local, path, old_value, new_value, outcome, outcome_reason, evidence
from v_learned_changes order by applied_at desc;
```

**Every lesson: how far each dose landed from target** (one row per dose, from the learning journal)

```sql
select to_timestamp((l ->> 'atMillis')::bigint / 1000.0) at time zone meanwhile_tz() as at_local,
       l ->> 'kind' as kind, (l ->> 'carbsG')::numeric as carbs,
       (l ->> 'unitsGiven')::numeric as given, (l ->> 'unitsNeeded')::numeric as needed,
       (l ->> 'endBg')::int as end_bg, (l ->> 'wentLow')::boolean as went_low,
       (l ->> 'clean')::boolean as clean, l ->> 'excludedBecause' as excluded_because,
       (l ->> 'impliedIcr')::numeric as implied_icr, (l ->> 'impliedIsf')::numeric as implied_isf
from learning_log, jsonb_array_elements(details -> 'lessons') l
where kind = 'lessons'
order by at_local desc;
```

**What happened around 2 pm today — the conversation and the phone's log, interleaved**
```sql
select local_ts, 'chat' as src, role || '/' || kind as what, text
from v_conversation
where local_ts::date = current_date and extract(hour from local_ts) between 13 and 14
union all
select (recorded_at at time zone meanwhile_tz()), 'log', level || '/' || tag, message || coalesce(E'\n' || nullif(detail, ''), '')
from app_logs
where (recorded_at at time zone meanwhile_tz())::date = current_date
  and extract(hour from recorded_at at time zone meanwhile_tz()) between 13 and 14
order by 1;
```

**Slow or failed AI calls this week**
```sql
select recorded_at at time zone meanwhile_tz() as local_ts, job, provider, model, latency_ms, validation, error
from ai_calls
where recorded_at > now() - interval '7 days' and (validation <> 'ok' or latency_ms > 10000)
order by recorded_at desc;
```

## 5. Adding your own views

Put new views in a new file under `supabase/migrations/` (for example `20261101000100_my_views.sql`), always with
`with (security_invoker = true)`, and push — the `supabase` workflow applies it. Or just create them in the SQL editor;
the app never depends on them.
