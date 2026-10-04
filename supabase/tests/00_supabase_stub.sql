-- Minimal stand-in for what a Supabase project provides before our migrations run: the API roles,
-- auth.users and auth.uid() (reads the JWT subject the same way PostgREST sets it). Used only by
-- the SQL test suite (run_sql_tests.sh / CI), never deployed.
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
  end if;
end $$;
create schema if not exists auth;
create table if not exists auth.users (id uuid primary key, created_at timestamptz not null default now());
create or replace function auth.uid() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant usage on schema auth to anon, authenticated;
grant usage on schema public to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
-- Supabase's default: API roles get table privileges unless a migration revokes them.
alter default privileges in schema public grant all on tables to anon, authenticated;
