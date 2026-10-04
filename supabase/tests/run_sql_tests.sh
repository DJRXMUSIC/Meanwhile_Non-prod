#!/usr/bin/env bash
# Applies the Supabase stub + every migration to a throwaway database and runs the SQL contract
# tests. Usage: supabase/tests/run_sql_tests.sh  (uses PG* env vars / psql defaults)
set -euo pipefail
cd "$(dirname "$0")/../.."
DB="meanwhile_test_$$"
psql -v ON_ERROR_STOP=1 -q -d postgres -c "create database $DB"
trap 'psql -q -d postgres -c "drop database if exists $DB" >/dev/null' EXIT
run() { psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$1"; }
run supabase/tests/00_supabase_stub.sql
for f in supabase/migrations/*.sql; do
  case "$(basename "$f")" in [0-9]*_*) run "$f" ;; esac
done
run supabase/tests/10_rls_test.sql 2>&1 | sed 's/^psql:[^:]*:[0-9]*: //'
echo "SQL tests passed"
