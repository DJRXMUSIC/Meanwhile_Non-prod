-- MeanwhileV4 schema (spec §12). Append-only: RLS allows select + insert of your own rows only;
-- there are no update/delete policies and those privileges are revoked.
--
-- Common columns: id (UUIDv7 from the device), user_id, created_at (device time), recorded_at (when it
-- happened), supersedes_id (corrections), plus server-only seq / server_inserted_at used by the app's
-- pull cursor (restore).

create table public.cgm_readings (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  mg_dl integer not null,
  trend_rate double precision,
  direction text,
  source text not null,
  sensor_status text,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index cgm_readings_user_recorded_at on public.cgm_readings (user_id, recorded_at);
create unique index cgm_readings_seq on public.cgm_readings (seq);
create index cgm_readings_user_seq on public.cgm_readings (user_id, seq);
alter table public.cgm_readings enable row level security;
create policy cgm_readings_select_own on public.cgm_readings for select to authenticated using (user_id = (select auth.uid()));
create policy cgm_readings_insert_own on public.cgm_readings for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.cgm_readings from anon;
revoke update, delete, truncate on public.cgm_readings from authenticated;
grant select, insert on public.cgm_readings to authenticated;

create table public.meals (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  carbs_g double precision not null,
  fat_g double precision not null,
  protein_g double precision not null,
  liquid_or_sugary boolean not null default false,
  is_estimate boolean not null default false,
  description text not null default '',
  input_id uuid,
  details jsonb not null default '{}'::jsonb,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index meals_user_recorded_at on public.meals (user_id, recorded_at);
create unique index meals_seq on public.meals (seq);
create index meals_user_seq on public.meals (user_id, seq);
alter table public.meals enable row level security;
create policy meals_select_own on public.meals for select to authenticated using (user_id = (select auth.uid()));
create policy meals_insert_own on public.meals for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.meals from anon;
revoke update, delete, truncate on public.meals from authenticated;
grant select, insert on public.meals to authenticated;

create table public.factor_events (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  factor_id text not null,
  action text not null,
  weight double precision,
  window_minutes integer,
  units_add double precision,
  source text not null,
  input_id uuid,
  profile_version_id uuid,
  details jsonb not null default '{}'::jsonb,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index factor_events_user_recorded_at on public.factor_events (user_id, recorded_at);
create unique index factor_events_seq on public.factor_events (seq);
create index factor_events_user_seq on public.factor_events (user_id, seq);
create index factor_events_user_factor_id on public.factor_events (user_id, factor_id);
alter table public.factor_events enable row level security;
create policy factor_events_select_own on public.factor_events for select to authenticated using (user_id = (select auth.uid()));
create policy factor_events_insert_own on public.factor_events for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.factor_events from anon;
revoke update, delete, truncate on public.factor_events from authenticated;
grant select, insert on public.factor_events to authenticated;

create table public.doses (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  insulin text not null check (insulin in ('rapid', 'long')),
  units double precision not null,
  given_at timestamptz not null,
  proposal_id uuid,
  split_part integer,
  proposed_units double precision,
  override_reason text,
  input_id uuid,
  details jsonb not null default '{}'::jsonb,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index doses_user_recorded_at on public.doses (user_id, recorded_at);
create unique index doses_seq on public.doses (seq);
create index doses_user_seq on public.doses (user_id, seq);
create index doses_user_given_at on public.doses (user_id, given_at);
alter table public.doses enable row level security;
create policy doses_select_own on public.doses for select to authenticated using (user_id = (select auth.uid()));
create policy doses_insert_own on public.doses for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.doses from anon;
revoke update, delete, truncate on public.doses from authenticated;
grant select, insert on public.doses to authenticated;

create table public.proposals (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  input_snapshot jsonb not null,
  breakdown jsonb not null,
  final_units integer not null,
  lead_time_min integer,
  split_plan jsonb,
  profile_version_id uuid,
  input_id uuid,
  meal_id uuid,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index proposals_user_recorded_at on public.proposals (user_id, recorded_at);
create unique index proposals_seq on public.proposals (seq);
create index proposals_user_seq on public.proposals (user_id, seq);
alter table public.proposals enable row level security;
create policy proposals_select_own on public.proposals for select to authenticated using (user_id = (select auth.uid()));
create policy proposals_insert_own on public.proposals for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.proposals from anon;
revoke update, delete, truncate on public.proposals from authenticated;
grant select, insert on public.proposals to authenticated;

create table public.outcomes (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  dose_id uuid not null,
  bg_2h integer,
  bg_3h integer,
  bg_4h integer,
  min_4h integer,
  max_4h integer,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index outcomes_user_recorded_at on public.outcomes (user_id, recorded_at);
create unique index outcomes_seq on public.outcomes (seq);
create index outcomes_user_seq on public.outcomes (user_id, seq);
create index outcomes_user_dose_id on public.outcomes (user_id, dose_id);
alter table public.outcomes enable row level security;
create policy outcomes_select_own on public.outcomes for select to authenticated using (user_id = (select auth.uid()));
create policy outcomes_insert_own on public.outcomes for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.outcomes from anon;
revoke update, delete, truncate on public.outcomes from authenticated;
grant select, insert on public.outcomes to authenticated;

create table public.profile_versions (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  version integer not null,
  source text not null,
  status text not null check (status in ('pending', 'accepted', 'edited', 'rejected')),
  profile jsonb not null,
  diff jsonb not null default '[]'::jsonb,
  decided_at timestamptz,
  base_version_id uuid,
  ai_call_id uuid,
  summary text not null default '',
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index profile_versions_user_recorded_at on public.profile_versions (user_id, recorded_at);
create unique index profile_versions_seq on public.profile_versions (seq);
create index profile_versions_user_seq on public.profile_versions (user_id, seq);
create index profile_versions_user_version on public.profile_versions (user_id, version);
alter table public.profile_versions enable row level security;
create policy profile_versions_select_own on public.profile_versions for select to authenticated using (user_id = (select auth.uid()));
create policy profile_versions_insert_own on public.profile_versions for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.profile_versions from anon;
revoke update, delete, truncate on public.profile_versions from authenticated;
grant select, insert on public.profile_versions to authenticated;

create table public.factor_definitions (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  factor_id text not null,
  definition jsonb not null,
  profile_version_id uuid,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index factor_definitions_user_recorded_at on public.factor_definitions (user_id, recorded_at);
create unique index factor_definitions_seq on public.factor_definitions (seq);
create index factor_definitions_user_seq on public.factor_definitions (user_id, seq);
alter table public.factor_definitions enable row level security;
create policy factor_definitions_select_own on public.factor_definitions for select to authenticated using (user_id = (select auth.uid()));
create policy factor_definitions_insert_own on public.factor_definitions for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.factor_definitions from anon;
revoke update, delete, truncate on public.factor_definitions from authenticated;
grant select, insert on public.factor_definitions to authenticated;

create table public.ai_calls (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  job text not null,
  provider text,
  model text,
  latency_ms bigint,
  fallback_used boolean not null default false,
  request_summary text not null default '',
  response jsonb,
  validation text not null,
  error text,
  input_id uuid,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index ai_calls_user_recorded_at on public.ai_calls (user_id, recorded_at);
create unique index ai_calls_seq on public.ai_calls (seq);
create index ai_calls_user_seq on public.ai_calls (user_id, seq);
alter table public.ai_calls enable row level security;
create policy ai_calls_select_own on public.ai_calls for select to authenticated using (user_id = (select auth.uid()));
create policy ai_calls_insert_own on public.ai_calls for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.ai_calls from anon;
revoke update, delete, truncate on public.ai_calls from authenticated;
grant select, insert on public.ai_calls to authenticated;

create table public.feedback (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  text text not null,
  context text not null default '',
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index feedback_user_recorded_at on public.feedback (user_id, recorded_at);
create unique index feedback_seq on public.feedback (seq);
create index feedback_user_seq on public.feedback (user_id, seq);
alter table public.feedback enable row level security;
create policy feedback_select_own on public.feedback for select to authenticated using (user_id = (select auth.uid()));
create policy feedback_insert_own on public.feedback for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.feedback from anon;
revoke update, delete, truncate on public.feedback from authenticated;
grant select, insert on public.feedback to authenticated;

create table public.inputs (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null,
  recorded_at timestamptz not null,
  supersedes_id uuid,
  raw_text text not null,
  via text not null,
  router text not null,
  router_result jsonb,
  path_taken text not null,
  manual_switch boolean not null default false,
  seq bigint generated always as identity,
  server_inserted_at timestamptz not null default now()
);

create index inputs_user_recorded_at on public.inputs (user_id, recorded_at);
create unique index inputs_seq on public.inputs (seq);
create index inputs_user_seq on public.inputs (user_id, seq);
alter table public.inputs enable row level security;
create policy inputs_select_own on public.inputs for select to authenticated using (user_id = (select auth.uid()));
create policy inputs_insert_own on public.inputs for insert to authenticated with check (user_id = (select auth.uid()));
revoke all on public.inputs from anon;
revoke update, delete, truncate on public.inputs from authenticated;
grant select, insert on public.inputs to authenticated;
