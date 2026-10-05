#!/usr/bin/env python3
"""End-to-end tests for supabase_setup.py against a mock Supabase Management API whose SQL endpoint
runs on a real Postgres (each mock project is a throwaway database with the Supabase stub applied).

Run: python3 scripts/ci/test_supabase_setup.py   (needs psql + PG* env able to create databases;
CI runs it in the supabase workflow's Postgres job; scripts/test-all.sh runs it with the SQL suite)
"""
import csv
import hashlib
import io
import json
import os
import random
import re
import shutil
import string
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ci" / "supabase_setup.py"
STUB = ROOT / "supabase" / "tests" / "00_supabase_stub.sql"
TOKEN = "test-token"
SERVICE_KEY = "SERVICE-ROLE-SECRET"


def psql(db, sql, csv_out=False):
    args = ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", db]
    if csv_out:
        args.append("--csv")
    return subprocess.run(args + ["-c", sql], capture_output=True, text=True)


class Mock:
    """In-memory Management API state; SQL goes to real databases."""

    def __init__(self):
        self.lock = threading.Lock()
        self.orgs = []
        self.projects = {}
        self.calls = []
        self.query_failures = 0  # next N queries answer 503 (database warming up)
        self.dbs = []

    def new_project(self, name, status="ACTIVE_HEALTHY", org="org-1"):
        ref = "".join(random.choice(string.ascii_lowercase) for _ in range(20))
        db = f"mock_{ref}"
        subprocess.run(["psql", "-X", "-q", "-d", "postgres", "-c", f"create database {db}"], check=True)
        self.dbs.append(db)
        r = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", db, "-f", str(STUB)], capture_output=True, text=True)
        assert r.returncode == 0, r.stderr
        self.projects[ref] = {
            "ref": ref, "id": ref, "name": name, "organization_slug": org, "organization_id": org,
            "region": "us-east-1", "created_at": f"2026-10-0{len(self.projects) + 1}T00:00:00Z",
            "status": status, "database": {"host": "db", "version": "16", "postgres_engine": "16", "release_channel": "ga"},
            "_db": db, "_secrets": {}, "_auth": {}, "_functions": set(), "_warmup": 0,
        }
        return ref

    def cleanup(self):
        for db in self.dbs:
            subprocess.run(["psql", "-X", "-q", "-d", "postgres", "-c", f"drop database if exists {db} with (force)"],
                           capture_output=True)


def handler_for(mock):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def reply(self, code, body=None):
            data = b"" if body is None else json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(n) or b"null")

        def handle_any(self, method):
            if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                return self.reply(401, {"message": "Unauthorized"})
            path = self.path.split("?")[0]
            body = self.body() if method in ("POST", "PATCH") else None
            with mock.lock:
                mock.calls.append((method, path, body))
            m = re.fullmatch(r"/v1/projects/([a-z]{20})(/.*)?", path)
            p = mock.projects.get(m.group(1)) if m else None
            sub = (m.group(2) or "") if m else None
            public = lambda x: {k: v for k, v in x.items() if not k.startswith("_")}

            if path == "/v1/organizations" and method == "GET":
                return self.reply(200, mock.orgs)
            if path == "/v1/organizations" and method == "POST":
                org = {"id": "org-new", "slug": "org-new", "name": body["name"]}
                mock.orgs.append(org)
                return self.reply(201, org)
            if path == "/v1/projects" and method == "GET":
                return self.reply(200, [public(x) for x in mock.projects.values()])
            if path == "/v1/projects" and method == "POST":
                assert set(body) >= {"name", "organization_slug", "db_pass", "region_selection"}, body
                assert body["organization_slug"] in {o["slug"] for o in mock.orgs}, body
                ref = mock.new_project(body["name"], status="COMING_UP", org=body["organization_slug"])
                mock.projects[ref]["_warmup"] = 2
                return self.reply(201, public(mock.projects[ref]))
            if p is None:
                return self.reply(404, {"message": "not found"})
            if sub == "" and method == "GET":
                if p["status"] == "COMING_UP":
                    p["_warmup"] -= 1
                    if p["_warmup"] <= 0:
                        p["status"] = "ACTIVE_HEALTHY"
                return self.reply(200, public(p))
            if sub == "/restore" and method == "POST":
                p["status"], p["_warmup"] = "COMING_UP", 1
                return self.reply(200, {})
            if sub == "/database/query" and method == "POST":
                if mock.query_failures > 0:
                    mock.query_failures -= 1
                    return self.reply(503, {"message": "database starting"})
                r = psql(p["_db"], body["query"], csv_out=True)
                if r.returncode != 0:
                    return self.reply(400, {"message": "Failed to run sql query: " + r.stderr.strip()[:300]})
                out = r.stdout.strip()
                return self.reply(201, list(csv.DictReader(io.StringIO(out))) if out else [])
            if sub == "/api-keys" and method == "GET":
                return self.reply(200, [
                    {"name": "anon", "type": "legacy", "api_key": f"anon-key-{p['ref']}"},
                    {"name": "service_role", "type": "legacy", "api_key": SERVICE_KEY},
                ])
            if sub == "/secrets" and method == "POST":
                for s in body:
                    p["_secrets"][s["name"]] = s["value"]
                return self.reply(201)
            if sub == "/secrets" and method == "GET":
                return self.reply(200, [{"name": k, "value": hashlib.sha256(v.encode()).hexdigest()} for k, v in p["_secrets"].items()])
            if sub == "/config/auth" and method == "PATCH":
                p["_auth"].update(body)
                return self.reply(200, p["_auth"])
            if sub == "/functions/ai" and method == "GET":
                if "ai" in p["_functions"]:
                    return self.reply(200, {"id": "f", "slug": "ai", "name": "ai", "status": "ACTIVE", "version": 1})
                return self.reply(404, {"message": "Function not found"})
            return self.reply(404, {"message": f"unmocked {method} {path}"})

        def do_GET(self):
            self.handle_any("GET")

        def do_POST(self):
            self.handle_any("POST")

        def do_PATCH(self):
            self.handle_any("PATCH")

    return H


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.mock = Mock()
        self.mock.orgs.append({"id": "org-1", "slug": "org-1", "name": "Danny"})
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(self.mock))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.mock.cleanup()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_script(self, *args, extra_env=None, token=TOKEN):
        files = {n: self.tmp / f"{n}-{len(list(self.tmp.iterdir()))}" for n in ("env", "out", "summary")}
        for f in files.values():
            f.write_text("")
        env = {k: v for k, v in os.environ.items() if not k.startswith(("SUPABASE_", "GEMINI", "ANTHROPIC", "GITHUB_", "MEANWHILE_"))}
        env.update({
            "SUPABASE_API_URL": f"http://127.0.0.1:{self.server.server_port}",
            "SUPABASE_SETUP_SLEEP": "0", "SUPABASE_ACCESS_TOKEN": token,
            "GITHUB_ENV": str(files["env"]), "GITHUB_OUTPUT": str(files["out"]), "GITHUB_STEP_SUMMARY": str(files["summary"]),
        })
        env.update(extra_env or {})
        r = subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, env=env, timeout=300)
        kv = lambda f: dict(line.split("=", 1) for line in f.read_text().splitlines() if "=" in line)
        return r, kv(files["env"]), kv(files["out"]), files["summary"].read_text()

    def tables(self, ref):
        r = psql(self.mock.projects[ref]["_db"], "select tablename from pg_tables where schemaname = 'public'", csv_out=True)
        return {row["tablename"] for row in csv.DictReader(io.StringIO(r.stdout))}

    def ours(self):
        return [p for p in self.mock.projects.values() if p["name"] == "meanwhile-v4"]

    def assertNoSecretsPrinted(self, r, *values):
        for v in (SERVICE_KEY, TOKEN, *values):
            self.assertNotIn(v, r.stdout + r.stderr)

    # ---------------------------------------------------------------------------------------------

    def test_first_run_creates_everything_and_second_run_changes_nothing(self):
        self.mock.orgs.clear()  # brand-new Supabase account
        r, env, out, summ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        [p] = self.ours()
        ref = p["ref"]
        self.assertEqual(p["organization_slug"], "org-new")
        self.assertEqual(env, {"SUPABASE_URL": f"https://{ref}.supabase.co", "SUPABASE_ANON_KEY": f"anon-key-{ref}"})
        self.assertEqual(out["created"], "true")
        self.assertEqual(out["function_deployed"], "false")
        self.assertTrue({"cgm_readings", "doses", "learning_log", "profile_versions"} <= self.tables(ref))
        self.assertEqual(p["_auth"], {"mailer_autoconfirm": True})
        self.assertEqual(p["_secrets"], {}, "CI never writes secrets")
        self.assertIn("created by this run", summ)
        self.assertIn("AI keys:** none set in Supabase", summ)
        self.assertNoSecretsPrinted(r)
        self.assertFalse(any(m in ("POST", "PATCH") and "/secrets" in path for m, path, _ in self.mock.calls))

        migrations = len(list((ROOT / "supabase" / "migrations").glob("*.sql")))
        hist = psql(p["_db"], "select count(*) as n from supabase_migrations.schema_migrations", csv_out=True)
        self.assertIn(str(migrations), hist.stdout)

        posts_before = sum(1 for c in self.mock.calls if c[:2] == ("POST", "/v1/projects"))
        r2, env2, out2, summ2 = self.run_script("backend", "--create")
        self.assertEqual(r2.returncode, 0, r2.stdout + r2.stderr)
        self.assertEqual(len(self.ours()), 1)
        self.assertEqual(posts_before, sum(1 for c in self.mock.calls if c[:2] == ("POST", "/v1/projects")))
        self.assertEqual(out2["created"], "false")
        self.assertIn("schema up to date", summ2)
        self.assertEqual(env2, env)

    def test_ai_key_status_comes_from_supabase_names(self):
        ref = self.mock.new_project("meanwhile-v4")
        self.mock.projects[ref]["_secrets"] = {"GEMINI_API_KEY": "gem-secret-value"}
        r, _, _, summ = self.run_script("backend")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(":warning: **AI keys:** GEMINI_API_KEY set in Supabase", summ)
        self.mock.projects[ref]["_secrets"]["ANTHROPIC_API_KEY"] = "sk-ant-secret-value"
        r, _, _, summ = self.run_script("backend")
        self.assertIn(":white_check_mark: **AI keys:** GEMINI_API_KEY, ANTHROPIC_API_KEY set", summ)
        self.assertNoSecretsPrinted(r, "gem-secret-value", "sk-ant-secret-value")

    def test_signups_lock_once_an_account_exists(self):
        self.run_script("backend", "--create")
        [p] = self.ours()
        self.assertNotIn("disable_signup", p["_auth"])
        psql(p["_db"], "insert into auth.users (id) values ('00000000-0000-0000-0000-0000000000aa')")
        r, _, _, summ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(p["_auth"]["disable_signup"])
        self.assertIn("Sign-ups:** locked", summ)

    def test_old_pwa_project_is_never_touched(self):
        pwa = self.mock.new_project("Meanwhile")
        db = self.mock.projects[pwa]["_db"]
        psql(db, "create table public.entries (id int primary key, note text); insert into entries values (1, 'pwa data')")
        r, env, _, _ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("Skipping project 'Meanwhile'", r.stdout)
        self.assertEqual(self.tables(pwa), {"entries"})
        self.assertEqual(self.mock.projects[pwa]["_auth"], {})
        self.assertNotIn(pwa, env["SUPABASE_URL"])
        self.assertEqual(len(self.ours()), 1)

    def test_paused_pwa_project_is_not_woken(self):
        pwa = self.mock.new_project("meanwhile", status="INACTIVE")
        r, _, _, _ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.mock.projects[pwa]["status"], "INACTIVE")
        self.assertNotIn(("POST", f"/v1/projects/{pwa}/restore", {}), self.mock.calls)

    def test_hand_made_empty_project_is_adopted(self):
        ref = self.mock.new_project("meanwhile")
        r, env, out, _ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(env["SUPABASE_URL"], f"https://{ref}.supabase.co")
        self.assertEqual(out["created"], "false")
        self.assertIn("cgm_readings", self.tables(ref))

    def test_paused_own_project_is_restored(self):
        ref = self.mock.new_project("meanwhile-v4", status="INACTIVE")
        r, env, _, _ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(env["SUPABASE_URL"], f"https://{ref}.supabase.co")
        self.assertEqual(self.mock.projects[ref]["status"], "ACTIVE_HEALTHY")

    def test_explicit_ref_to_a_foreign_project_is_refused(self):
        pwa = self.mock.new_project("whatever")
        psql(self.mock.projects[pwa]["_db"], "create table public.entries (id int)")
        r, _, _, summ = self.run_script("backend", "--create", extra_env={"SUPABASE_PROJECT_REF": pwa})
        self.assertEqual(r.returncode, 1)
        self.assertIn("old PWA", r.stdout)
        self.assertIn("Supabase setup failed", summ)
        self.assertEqual(self.tables(pwa), {"entries"})

    def test_without_create_nothing_is_created(self):
        r, env, _, summ = self.run_script("backend")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.mock.projects, {})
        self.assertNotIn("SUPABASE_URL", env)
        self.assertIn("no project yet", summ)

    def test_no_token_is_a_friendly_no_op(self):
        r, env, _, summ = self.run_script("backend", "--create", token="")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(env, {})
        self.assertIn("SUPABASE_ACCESS_TOKEN", summ)

    def test_database_warming_up_is_waited_out(self):
        self.mock.new_project("meanwhile-v4")
        self.mock.query_failures = 3
        r, _, _, _ = self.run_script("backend", "--create")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_concurrent_runs_apply_each_migration_once(self):
        ref = self.mock.new_project("meanwhile-v4")
        env = {k: v for k, v in os.environ.items() if not k.startswith(("SUPABASE_", "GITHUB_"))}
        env.update({"SUPABASE_API_URL": f"http://127.0.0.1:{self.server.server_port}", "SUPABASE_SETUP_SLEEP": "0",
                    "SUPABASE_ACCESS_TOKEN": TOKEN})
        procs = [subprocess.Popen([sys.executable, str(SCRIPT), "backend"], env=env, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True) for _ in range(3)]
        outs = [p.communicate(timeout=300)[0] for p in procs]
        self.assertEqual([p.returncode for p in procs], [0, 0, 0], "\n".join(outs))
        if os.environ.get("SHOW_RACE"):
            print("\n".join(outs))
        migrations = len(list((ROOT / "supabase" / "migrations").glob("*.sql")))
        hist = psql(self.mock.projects[ref]["_db"], "select count(*) as n from supabase_migrations.schema_migrations", csv_out=True)
        self.assertIn(str(migrations), hist.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
