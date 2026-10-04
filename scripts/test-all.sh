#!/usr/bin/env bash
# Runs every test suite that this machine can run, then prints one summary (docs/TESTING.md).
#   scripts/test-all.sh            all suites; ones whose tools are missing are skipped, not failed
#   scripts/test-all.sh domain     just one: domain | app | sql | deno
set -uo pipefail
cd "$(dirname "$0")/.."
only="${1:-}"
results=()
failed=0

run() { # name, command...
  local name="$1"; shift
  if [ -n "$only" ] && [ "$only" != "$name" ]; then return; fi
  echo "==> $name: $*"
  if "$@"; then results+=("PASS  $name"); else results+=("FAIL  $name"); failed=1; fi
}
skip() { if [ -z "$only" ] || [ "$only" = "$1" ]; then results+=("SKIP  $1 — $2"); fi; }

run domain ./gradlew -p domain test --console=plain

if [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || grep -qs '^sdk.dir=' local.properties; then
  run app ./gradlew :app:testDebugUnitTest --console=plain
else
  skip app "no Android SDK (set ANDROID_HOME); CI runs these on every push"
fi

if command -v psql >/dev/null && psql -q -d postgres -c 'select 1' >/dev/null 2>&1; then
  run sql supabase/tests/run_sql_tests.sh
else
  skip sql "no reachable Postgres (set PGHOST/PGPORT/PGUSER); CI runs these when supabase/ changes"
fi

if command -v deno >/dev/null; then
  run deno sh -c 'deno check --config supabase/functions/ai/deno.json supabase/functions/ai/index.ts &&
    deno test --allow-env --allow-read --config supabase/functions/ai/deno.json supabase/functions/ai/ai_test.ts'
else
  skip deno "deno not installed; CI runs these when supabase/ changes"
fi

echo
python3 scripts/junit-summary.py "Domain=domain/build/test-results" "App=app/build/test-results" 2>/dev/null || true
echo
printf '%s\n' "${results[@]}"
exit $failed
