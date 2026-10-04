-- 1.3: the learning journal — what continuous learning found, changed, kept and reverted (see
-- docs/DECISIONS.md "Continuous learning"). Append-only like every other table: select + insert of
-- your own rows only.

create table public.learning_log (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  kind text not null,
  summary text not null default '',
  details jsonb not null default '{}'::jsonb,
  profile_version_id uuid,
  ai_call_id uuid,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index learning_log_user_recorded_at on public.learning_log (user_id, recorded_at);
create unique index learning_log_seq on public.learning_log (seq);
create index learning_log_user_seq on public.learning_log (user_id, seq);
create index learning_log_user_kind on public.learning_log (user_id, kind);
alter table public.learning_log enable row level security;
create policy learning_log_select_own on public.learning_log for select to authenticated using (user_id = (select auth.uid()));
create policy learning_log_insert_own on public.learning_log for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.learning_log from anon;
revoke update, delete, truncate on public.learning_log from authenticated;
grant select, insert on public.learning_log to authenticated;

-- Every learned change with how it was judged (kept / reverted / undone / still under evaluation).
create or replace view public.v_learned_changes with (security_invoker = true) as
select
  a.user_id,
  a.recorded_at as applied_at,
  (a.recorded_at at time zone public.meanwhile_tz()) as applied_local,
  a.summary,
  a.details ->> 'path' as path,
  a.details -> 'old' as old_value,
  a.details -> 'new' as new_value,
  a.details ->> 'source' as source,
  a.details ->> 'evidence' as evidence,
  coalesce(c.kind, 'under_evaluation') as outcome,
  c.summary as outcome_reason,
  c.recorded_at as decided_at
from public.learning_log a
left join public.learning_log c on c.supersedes_id = a.id and c.user_id = a.user_id
where a.kind = 'applied';

grant select on public.v_learned_changes to authenticated;
revoke all on public.v_learned_changes from anon;
