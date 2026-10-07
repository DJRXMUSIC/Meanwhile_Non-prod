-- Contract tests for the schema: row-level security isolates users, rows are append-only, the
-- server owns seq, the anon key can read nothing, and the views respect RLS. Each block raises on
-- failure (psql runs with ON_ERROR_STOP).
\set ON_ERROR_STOP on
\set a '''aaaaaaaa-0000-4000-8000-000000000001'''
\set b '''bbbbbbbb-0000-4000-8000-000000000002'''

insert into auth.users (id) values (:a), (:b);

-- Seed one row in every table as user A.
set role authenticated;
set request.jwt.claim.sub = :a;
insert into cgm_readings (id, created_at, recorded_at, mg_dl, source) values
  ('10000000-0000-7000-8000-000000000001', now(), now() - interval '1 day', 120, 'test'),
  ('10000000-0000-7000-8000-000000000002', now(), now() - interval '1 day' + interval '5 minutes', 190, 'test');
insert into meals (id, created_at, recorded_at, carbs_g, fat_g, protein_g) values ('10000000-0000-7000-8000-000000000003', now(), now(), 60, 20, 30);
insert into factor_events (id, created_at, recorded_at, factor_id, action, source) values ('10000000-0000-7000-8000-000000000004', now(), now(), 'F4', 'add', 'test');
insert into proposals (id, created_at, recorded_at, input_snapshot, breakdown, final_units) values
  ('10000000-0000-7000-8000-000000000005', now(), now(), '{"doseInput": {"carbsG": 60, "bg": 120}}', '{}', 6);
insert into doses (id, created_at, recorded_at, insulin, units, given_at, proposal_id, proposed_units) values
  ('10000000-0000-7000-8000-000000000006', now(), now(), 'rapid', 6, now(), '10000000-0000-7000-8000-000000000005', 6);
insert into outcomes (id, created_at, recorded_at, dose_id, bg_3h, min_4h) values ('10000000-0000-7000-8000-000000000007', now(), now(), '10000000-0000-7000-8000-000000000006', 130, 95);
insert into profile_versions (id, created_at, recorded_at, version, source, status, profile) values ('10000000-0000-7000-8000-000000000008', now(), now(), 1, 'manual', 'accepted', '{}');
insert into factor_definitions (id, created_at, recorded_at, factor_id, definition) values ('10000000-0000-7000-8000-000000000009', now(), now(), 'F4', '{}');
insert into ai_calls (id, created_at, recorded_at, job, validation, provider, model) values ('10000000-0000-7000-8000-00000000000a', now(), now(), 'route', 'ok', 'gemini', 'g');
insert into feedback (id, created_at, recorded_at, text) values ('10000000-0000-7000-8000-00000000000b', now(), now(), 'note');
insert into inputs (id, created_at, recorded_at, raw_text, via, router, path_taken) values ('10000000-0000-7000-8000-00000000000c', now(), now(), 'pizza', 'text', 'offline', 'meal');
insert into conversation_log (id, created_at, recorded_at, role, kind, text, details, input_id) values
  ('10000000-0000-7000-8000-000000000010', now(), now(), 'user', 'message', 'took 6 units', '{"via": "voice"}', '10000000-0000-7000-8000-00000000000c'),
  ('10000000-0000-7000-8000-000000000011', now(), now(), 'app', 'reply', 'Logged 6 u rapid', '{}', '10000000-0000-7000-8000-00000000000c');
insert into app_logs (id, created_at, recorded_at, level, tag, message) values
  ('10000000-0000-7000-8000-000000000012', now(), now(), 'W', 'Sync', 'sync failed (retrying automatically)');
-- A dose logged as 4 u, then corrected to 3 u ("never mind, only 3").
insert into doses (id, created_at, recorded_at, insulin, units, given_at) values
  ('10000000-0000-7000-8000-000000000013', now(), now(), 'rapid', 4, now());
insert into doses (id, created_at, recorded_at, supersedes_id, insulin, units, given_at) values
  ('10000000-0000-7000-8000-000000000014', now(), now(), '10000000-0000-7000-8000-000000000013', 'rapid', 3, now());
insert into learning_log (id, created_at, recorded_at, kind, summary, details) values
  ('10000000-0000-7000-8000-00000000000d', now(), now(), 'applied', 'ICR 10 → 9.4', '{"path": "dose.icr", "old": 10, "new": 9.4, "source": "auto_tune"}'),
  ('10000000-0000-7000-8000-00000000000e', now(), now(), 'kept', 'Outcomes held', '{}');
reset role;
-- The "kept" row closes the "applied" one (written as the superuser here: the app sets it on insert).
update learning_log set supersedes_id = '10000000-0000-7000-8000-00000000000d' where id = '10000000-0000-7000-8000-00000000000e';

-- 1. Every row got A's user id by default, and seq was assigned by the server.
do $$
declare t text; n int;
begin
  foreach t in array array['cgm_readings','meals','factor_events','doses','proposals','outcomes','profile_versions',
                           'factor_definitions','ai_calls','feedback','inputs','learning_log','conversation_log'] loop
    execute format('select count(*) from %I where user_id = %L and seq is not null', t, 'aaaaaaaa-0000-4000-8000-000000000001') into n;
    if n = 0 then raise exception 'FAIL 1: % has no row owned by A with a server seq', t; end if;
  end loop;
  select count(*) into n from app_logs where user_id = 'aaaaaaaa-0000-4000-8000-000000000001';
  if n = 0 then raise exception 'FAIL 1: app_logs has no row owned by A'; end if;
  select count(*) into n from ai_calls where request = '{}'::jsonb;
  if n = 0 then raise exception 'FAIL 1: ai_calls.request does not default to {}'; end if;
  raise notice 'PASS 1: default user_id and server seq on all 13 synced tables (+ app_logs)';
end $$;

-- 2. User B sees none of A's rows in any table or view.
set role authenticated;
set request.jwt.claim.sub = :b;
do $$
declare t text; n int;
begin
  foreach t in array array['cgm_readings','meals','factor_events','doses','proposals','outcomes','profile_versions',
                           'factor_definitions','ai_calls','feedback','inputs','learning_log','conversation_log','app_logs',
                           'v_cgm_local','v_tir_daily','v_tir_hourly','v_tir_monthly','v_tir_by_time_of_day',
                           'v_tir_by_weekday','v_proposal_outcomes','v_ai_calls_by_model','v_learned_changes',
                           'v_doses_effective','v_conversation'] loop
    execute format('select count(*) from %I', t) into n;
    if n <> 0 then raise exception 'FAIL 2: user B sees % rows of %', n, t; end if;
  end loop;
  raise notice 'PASS 2: user B sees nothing of A in 14 tables and 11 views';
end $$;

-- 3. B cannot write rows owned by A.
do $$ begin
  begin
    insert into feedback (id, user_id, created_at, recorded_at, text)
      values ('20000000-0000-7000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', now(), now(), 'spoof');
    raise exception 'FAIL 3: B inserted a row owned by A';
  exception when insufficient_privilege then null; -- RLS with-check violation
  end;
  raise notice 'PASS 3: cannot insert rows for another user';
end $$;

-- 4. Append-only: A cannot update or delete even their own rows; seq cannot be supplied.
set request.jwt.claim.sub = :a;
do $$ begin
  begin update meals set carbs_g = 1; raise exception 'FAIL 4: update allowed'; exception when insufficient_privilege then null; end;
  begin delete from doses; raise exception 'FAIL 4: delete allowed'; exception when insufficient_privilege then null; end;
  begin delete from learning_log; raise exception 'FAIL 4: delete allowed on learning_log'; exception when insufficient_privilege then null; end;
  begin update conversation_log set text = 'edited'; raise exception 'FAIL 4: update allowed on conversation_log'; exception when insufficient_privilege then null; end;
  begin delete from app_logs; raise exception 'FAIL 4: delete allowed on app_logs'; exception when insufficient_privilege then null; end;
  begin
    insert into feedback (id, created_at, recorded_at, text, seq) values ('20000000-0000-7000-8000-000000000002', now(), now(), 'x', 1);
    raise exception 'FAIL 4: client-supplied seq accepted';
  exception when generated_always then null;
  end;
  raise notice 'PASS 4: append-only and server-owned seq';
end $$;

-- 5. A's own views work: TIR per day, proposal outcome, learned change with its verdict.
do $$
declare n int; f text; o text;
begin
  select count(*) into n from v_tir_daily;
  if n = 0 then raise exception 'FAIL 5: v_tir_daily empty for A'; end if;
  select follow into f from v_proposal_outcomes;
  if f is distinct from 'followed' then raise exception 'FAIL 5: proposal follow = %', f; end if;
  select outcome into o from v_learned_changes where path = 'dose.icr';
  if o is distinct from 'kept' then raise exception 'FAIL 5: learned change outcome = %', o; end if;
  select count(*) into n from v_doses_effective where id = '10000000-0000-7000-8000-000000000013';
  if n <> 0 then raise exception 'FAIL 5: a corrected dose is still in effect'; end if;
  select count(*) into n from v_doses_effective where id = '10000000-0000-7000-8000-000000000014' and units = 3;
  if n <> 1 then raise exception 'FAIL 5: the correction is not in effect'; end if;
  select count(*) into n from v_conversation where role = 'user' and text = 'took 6 units';
  if n <> 1 then raise exception 'FAIL 5: v_conversation misses the message'; end if;
  raise notice 'PASS 5: views return A''s data (TIR, proposal followed, learned change kept, corrected dose, conversation)';
end $$;

-- 6. The anon key reads nothing.
reset role;
set role anon;
do $$ begin
  begin perform 1 from doses limit 1; raise exception 'FAIL 6: anon can read doses'; exception when insufficient_privilege then null; end;
  begin perform 1 from learning_log limit 1; raise exception 'FAIL 6: anon can read learning_log'; exception when insufficient_privilege then null; end;
  begin perform 1 from v_learned_changes limit 1; raise exception 'FAIL 6: anon can read v_learned_changes'; exception when insufficient_privilege then null; end;
  begin perform 1 from conversation_log limit 1; raise exception 'FAIL 6: anon can read conversation_log'; exception when insufficient_privilege then null; end;
  begin perform 1 from app_logs limit 1; raise exception 'FAIL 6: anon can read app_logs'; exception when insufficient_privilege then null; end;
  begin perform 1 from v_conversation limit 1; raise exception 'FAIL 6: anon can read v_conversation'; exception when insufficient_privilege then null; end;
  raise notice 'PASS 6: anon cannot read tables or views';
end $$;
reset role;

-- 7. Constraint sanity: insulin type and profile status are checked.
set role authenticated;
set request.jwt.claim.sub = :a;
do $$ begin
  begin
    insert into doses (id, created_at, recorded_at, insulin, units, given_at) values ('20000000-0000-7000-8000-000000000003', now(), now(), 'nph', 1, now());
    raise exception 'FAIL 7: bad insulin accepted';
  exception when check_violation then null;
  end;
  begin
    insert into profile_versions (id, created_at, recorded_at, version, source, status, profile) values ('20000000-0000-7000-8000-000000000004', now(), now(), 2, 'manual', 'maybe', '{}');
    raise exception 'FAIL 7: bad status accepted';
  exception when check_violation then null;
  end;
  raise notice 'PASS 7: check constraints hold';
end $$;
reset role;
