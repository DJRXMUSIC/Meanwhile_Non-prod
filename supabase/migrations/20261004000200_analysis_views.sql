-- Analysis views (spec §12.3, docs/ANALYSIS.md). security_invoker = true makes every view respect the
-- caller's row-level security, so each user only ever sees their own rows.
--
-- Time in range here is by reading count (CGM readings arrive every ~5 min); the app's own stats are
-- time-weighted. Local time uses meanwhile_tz() — change it in one place if Danny moves.

create or replace function public.meanwhile_tz() returns text
language sql immutable as $$ select 'America/New_York'::text $$;

create or replace view public.v_cgm_local with (security_invoker = true) as
select
  user_id,
  recorded_at,
  (recorded_at at time zone public.meanwhile_tz()) as local_ts,
  mg_dl
from public.cgm_readings;

-- Shared metric columns
create or replace view public.v_tir_daily with (security_invoker = true) as
select
  user_id,
  local_ts::date as day,
  count(*) as readings,
  round(100.0 * avg((mg_dl between 70 and 180)::int), 1) as tir_pct,
  round(100.0 * avg((mg_dl < 70)::int), 1) as below70_pct,
  round(100.0 * avg((mg_dl < 54)::int), 1) as below54_pct,
  round(100.0 * avg((mg_dl > 180)::int), 1) as above180_pct,
  round(100.0 * avg((mg_dl > 250)::int), 1) as above250_pct,
  round(avg(mg_dl), 1) as mean_mg_dl,
  round(stddev_samp(mg_dl), 1) as sd_mg_dl,
  round(3.31 + 0.02392 * avg(mg_dl), 2) as gmi_pct
from public.v_cgm_local
group by user_id, local_ts::date;

create or replace view public.v_tir_hourly with (security_invoker = true) as
select
  user_id,
  local_ts::date as day,
  extract(hour from local_ts)::int as hour,
  count(*) as readings,
  round(100.0 * avg((mg_dl between 70 and 180)::int), 1) as tir_pct,
  round(100.0 * avg((mg_dl < 70)::int), 1) as below70_pct,
  round(100.0 * avg((mg_dl > 180)::int), 1) as above180_pct,
  round(avg(mg_dl), 1) as mean_mg_dl
from public.v_cgm_local
group by user_id, local_ts::date, extract(hour from local_ts);

create or replace view public.v_tir_monthly with (security_invoker = true) as
select
  user_id,
  date_trunc('month', local_ts)::date as month,
  count(*) as readings,
  round(100.0 * avg((mg_dl between 70 and 180)::int), 1) as tir_pct,
  round(100.0 * avg((mg_dl < 70)::int), 1) as below70_pct,
  round(100.0 * avg((mg_dl > 180)::int), 1) as above180_pct,
  round(avg(mg_dl), 1) as mean_mg_dl
from public.v_cgm_local
group by user_id, date_trunc('month', local_ts);

create or replace view public.v_tir_by_time_of_day with (security_invoker = true) as
select
  user_id,
  extract(hour from local_ts)::int as hour,
  count(*) as readings,
  round(100.0 * avg((mg_dl between 70 and 180)::int), 1) as tir_pct,
  round(100.0 * avg((mg_dl < 70)::int), 1) as below70_pct,
  round(100.0 * avg((mg_dl > 180)::int), 1) as above180_pct,
  round(avg(mg_dl), 1) as mean_mg_dl
from public.v_cgm_local
group by user_id, extract(hour from local_ts);

create or replace view public.v_tir_by_weekday with (security_invoker = true) as
select
  user_id,
  extract(isodow from local_ts)::int as iso_weekday,
  trim(to_char(local_ts, 'Day')) as weekday,
  count(*) as readings,
  round(100.0 * avg((mg_dl between 70 and 180)::int), 1) as tir_pct,
  round(100.0 * avg((mg_dl < 70)::int), 1) as below70_pct,
  round(100.0 * avg((mg_dl > 180)::int), 1) as above180_pct,
  round(avg(mg_dl), 1) as mean_mg_dl
from public.v_cgm_local
group by user_id, extract(isodow from local_ts), trim(to_char(local_ts, 'Day'));

-- Every proposal with what was injected and the outcome of the first injection.
create or replace view public.v_proposal_outcomes with (security_invoker = true) as
with given as (
  select proposal_id, user_id,
         sum(units) as units_given,
         bool_and(proposed_units is null or round(units) = round(proposed_units)) as each_matched,
         min(given_at) as first_given_at,
         string_agg(override_reason, '; ') as override_reasons
  from public.doses
  where proposal_id is not null
  group by proposal_id, user_id
),
first_dose as (
  select distinct on (proposal_id) proposal_id, id as dose_id
  from public.doses
  where proposal_id is not null
  order by proposal_id, given_at
)
select
  p.user_id,
  p.id as proposal_id,
  p.recorded_at,
  (p.recorded_at at time zone public.meanwhile_tz()) as local_ts,
  p.final_units,
  p.lead_time_min,
  p.split_plan,
  (p.input_snapshot -> 'doseInput' ->> 'carbsG')::numeric as carbs_g,
  (p.input_snapshot -> 'doseInput' ->> 'bg')::numeric as bg,
  g.units_given,
  case
    when g.proposal_id is null then 'not_logged'
    when round(g.units_given) = p.final_units and g.each_matched then 'followed'
    else 'overridden'
  end as follow,
  g.override_reasons,
  o.bg_2h, o.bg_3h, o.bg_4h, o.min_4h, o.max_4h
from public.proposals p
left join given g on g.proposal_id = p.id and g.user_id = p.user_id
left join first_dose f on f.proposal_id = p.id
left join public.outcomes o on o.dose_id = f.dose_id;

-- AI reliability by provider/model/job.
create or replace view public.v_ai_calls_by_model with (security_invoker = true) as
select
  user_id,
  coalesce(provider, 'unknown') as provider,
  coalesce(model, 'unknown') as model,
  job,
  count(*) as calls,
  count(*) filter (where validation = 'ok') as ok,
  count(*) filter (where validation = 'invalid') as invalid,
  count(*) filter (where validation = 'error') as errors,
  count(*) filter (where fallback_used) as fallback_used,
  round(avg(latency_ms)) as mean_latency_ms,
  max(recorded_at) as last_call_at
from public.ai_calls
group by user_id, coalesce(provider, 'unknown'), coalesce(model, 'unknown'), job;

grant select on public.v_cgm_local, public.v_tir_daily, public.v_tir_hourly, public.v_tir_monthly,
  public.v_tir_by_time_of_day, public.v_tir_by_weekday, public.v_proposal_outcomes, public.v_ai_calls_by_model
  to authenticated;
revoke all on public.v_cgm_local, public.v_tir_daily, public.v_tir_hourly, public.v_tir_monthly,
  public.v_tir_by_time_of_day, public.v_tir_by_weekday, public.v_proposal_outcomes, public.v_ai_calls_by_model
  from anon;
