-- 1.4: the conversation word for word, the phone's own log, the full request of every AI call, and
-- doses as corrected (see docs/DECISIONS.md "Conversation-first (1.4)"). Append-only like every other
-- table: select + insert of your own rows only.

-- Everything Danny said or typed, every processing step, everything the app answered (text and the
-- full card data) and every button he tapped — for AI analysis.
create table public.conversation_log (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  role text not null,
  kind text not null,
  text text not null,
  details jsonb not null default '{}'::jsonb,
  input_id uuid,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index conversation_log_user_recorded_at on public.conversation_log (user_id, recorded_at);
create unique index conversation_log_seq on public.conversation_log (seq);
create index conversation_log_user_seq on public.conversation_log (user_id, seq);
create index conversation_log_user_input on public.conversation_log (user_id, input_id);
alter table public.conversation_log enable row level security;
create policy conversation_log_select_own on public.conversation_log for select to authenticated using (user_id = (select auth.uid()));
create policy conversation_log_insert_own on public.conversation_log for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.conversation_log from anon;
revoke update, delete, truncate on public.conversation_log from authenticated;
grant select, insert on public.conversation_log to authenticated;

-- The phone's own log (every AppLog line), uploaded with each sync so a problem can be looked up by
-- time instead of reproduced. Upload-only: never pulled back to the phone.
create table public.app_logs (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  level text not null,
  tag text not null,
  thread text not null default '',
  message text not null,
  detail text not null default '',
  app_version text not null default '',
  device text not null default '',
  server_inserted_at timestamptz not null default now()
);

create index app_logs_user_recorded_at on public.app_logs (user_id, recorded_at);
create index app_logs_user_level on public.app_logs (user_id, level, recorded_at);
alter table public.app_logs enable row level security;
create policy app_logs_select_own on public.app_logs for select to authenticated using (user_id = (select auth.uid()));
create policy app_logs_insert_own on public.app_logs for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.app_logs from anon;
revoke update, delete, truncate on public.app_logs from authenticated;
grant select, insert on public.app_logs to authenticated;

-- The full request of every AI call (job, provider preference, payload), next to its response.
alter table public.ai_calls add column request jsonb not null default '{}'::jsonb;

-- Doses as corrected: "never mind, only 5" is a new row superseding the one it fixes (units 0 =
-- "I didn't take it"), so analysis reads the doses no later row supersedes.
create or replace view public.v_doses_effective with (security_invoker = true) as
select d.*
from public.doses d
where not exists (select 1 from public.doses s where s.supersedes_id = d.id and s.user_id = d.user_id);

grant select on public.v_doses_effective to authenticated;
revoke all on public.v_doses_effective from anon;

-- Same columns as before; doses now as corrected.
create or replace view public.v_proposal_outcomes with (security_invoker = true) as
with given as (
  select proposal_id, user_id,
         sum(units) as units_given,
         bool_and(proposed_units is null or round(units) = round(proposed_units)) as each_matched,
         min(given_at) as first_given_at,
         string_agg(override_reason, '; ') as override_reasons
  from public.v_doses_effective
  where proposal_id is not null
  group by proposal_id, user_id
),
first_dose as (
  select distinct on (proposal_id) proposal_id, id as dose_id
  from public.v_doses_effective
  where proposal_id is not null and units > 0
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

-- The conversation in order, local time first — what an AI assistant reads to see what happened.
create or replace view public.v_conversation with (security_invoker = true) as
select
  user_id,
  recorded_at,
  (recorded_at at time zone public.meanwhile_tz()) as local_ts,
  role,
  kind,
  text,
  details,
  input_id
from public.conversation_log;

grant select on public.v_conversation to authenticated;
revoke all on public.v_conversation from anon;
