create table public.settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  payload jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table public.events (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  type text not null check (type in
    ('meal','bolus','bg_manual','context','ai_interaction','outcome','settings_change','note')),
  ts timestamptz not null,
  payload jsonb not null default '{}'::jsonb,
  supersedes_id uuid,
  device_id text,
  created_at timestamptz not null default now()
);
create index events_user_ts on public.events (user_id, ts desc);
create index events_user_type_ts on public.events (user_id, type, ts desc);
create index events_user_created on public.events (user_id, created_at);

create table public.suggestions (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  payload jsonb not null,
  status text not null default 'pending' check (status in ('pending','accepted','dismissed')),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

alter table public.settings    enable row level security;
alter table public.events      enable row level security;
alter table public.suggestions enable row level security;

create policy "own settings" on public.settings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own events" on public.events
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own suggestions" on public.suggestions
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
