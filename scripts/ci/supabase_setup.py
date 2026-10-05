#!/usr/bin/env python3
"""Sets up and maintains Danny's Supabase backend from CI with one secret: SUPABASE_ACCESS_TOKEN.

    supabase_setup.py backend [--create]   find (or create) the project, apply migrations, configure
                                           auth, report which AI keys are set; exports SUPABASE_URL /
                                           SUPABASE_ANON_KEY for the build

Uses only the Supabase Management API (no database password, no CLI). Never prints or writes a
secret: the AI keys are set by Danny in Supabase, the signing key by Danny in GitHub.
Optional env: SUPABASE_PROJECT_REF (use this project), SUPABASE_API_URL (tests).
"""
import json
import os
import re
import secrets
import string
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

API = os.environ.get("SUPABASE_API_URL", "https://api.supabase.com").rstrip("/")
PROJECT_NAME = "meanwhile-v4"
# Names the project may have if Danny created it by hand (the old setup said "meanwhile").
ACCEPTED_NAMES = {"meanwhilev4", "meanwhile"}
REGION = os.environ.get("SUPABASE_REGION", "us-east-1")
# Tables only MeanwhileV4 creates: a project holding them (or nothing at all) is safe to manage.
OUR_TABLES = {"cgm_readings", "profile_versions", "proposals"}
MIGRATIONS = Path(__file__).resolve().parents[2] / "supabase" / "migrations"
MIGRATION_LOCK = 727274  # pg_advisory_xact_lock key shared by concurrent CI runs
AI_KEYS = ("GEMINI_API_KEY", "ANTHROPIC_API_KEY")
SLEEP = float(os.environ.get("SUPABASE_SETUP_SLEEP", "1"))  # tests shrink the waits


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status


def log(msg):
    print(msg, flush=True)


def gh_file(var, line):
    path = os.environ.get(var)
    if path:
        with open(path, "a") as f:
            f.write(line + "\n")


def mask(value):
    if value and os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::add-mask::{value}", flush=True)


def summary(line):
    gh_file("GITHUB_STEP_SUMMARY", line)


class Api:
    def __init__(self, token):
        self.token = token

    def call(self, method, path, body=None, retries=6, ok404=False):
        data = None if body is None else json.dumps(body).encode()
        delay = 2.0
        for attempt in range(retries + 1):
            req = urllib.request.Request(API + path, data=data, method=method, headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "meanwhile-ci",
            })
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    text = r.read().decode() or "null"
                    return json.loads(text) if text.strip() else None
            except urllib.error.HTTPError as e:
                text = e.read().decode(errors="replace")
                if e.code == 404 and ok404:
                    return None
                retryable = e.code == 429 or e.code >= 500
                if not retryable or attempt == retries:
                    raise ApiError(e.code, _message(text)) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt == retries:
                    raise ApiError(0, str(e)) from None
            time.sleep(delay * SLEEP)
            delay = min(delay * 2, 30)

    def sql(self, ref, query, retries=6):
        rows = self.call("POST", f"/v1/projects/{ref}/database/query", {"query": query}, retries=retries)
        return rows if isinstance(rows, list) else []


def _message(text):
    try:
        d = json.loads(text)
        return str(d.get("message") or d.get("error") or d)[:500]
    except (ValueError, AttributeError):
        return text[:500]


def norm(name):
    return re.sub(r"[^a-z0-9]", "", (name or "").lower())


# --- project ---------------------------------------------------------------------------------------

def classify(api, ref):
    """'ours' (MeanwhileV4 tables), 'empty' (no tables) or 'foreign' (someone else's tables)."""
    rows = api.sql(ref, "select tablename from pg_tables where schemaname = 'public'")
    tables = {r.get("tablename") for r in rows}
    if OUR_TABLES <= tables:
        return "ours"
    return "empty" if not tables else "foreign"


def wait_active(api, ref, minutes=15, restore=True):
    """The project once healthy; None if it is paused and [restore] is off."""
    started = time.time()
    deadline = started + minutes * 60
    restored = False
    while True:
        p = api.call("GET", f"/v1/projects/{ref}")
        status = (p or {}).get("status", "UNKNOWN")
        if status == "ACTIVE_HEALTHY":
            return p
        if status == "ACTIVE_UNHEALTHY" and time.time() > started + 120 * SLEEP:
            # A degraded side service mustn't block every build; the database itself is checked next.
            log("Project reports ACTIVE_UNHEALTHY — continuing")
            return p
        if status == "INACTIVE" and not restore:
            return None
        if status == "INACTIVE" and not restored:
            log("Project is paused — restoring it")
            api.call("POST", f"/v1/projects/{ref}/restore", {})
            restored = True
        if status in ("INIT_FAILED", "REMOVED", "RESTORE_FAILED"):
            raise ApiError(0, f"project {ref} is {status}")
        if time.time() > deadline:
            raise ApiError(0, f"project {ref} still {status} after {minutes} min")
        log(f"Waiting for the project ({status})…")
        time.sleep(10 * SLEEP)


def wait_database(api, ref, minutes=5):
    deadline = time.time() + minutes * 60
    while True:
        try:
            api.sql(ref, "select 1", retries=0)
            return
        except ApiError as e:
            if time.time() > deadline:
                raise
            log(f"Database not ready yet ({e})")
            time.sleep(10 * SLEEP)


def create_project(api):
    orgs = api.call("GET", "/v1/organizations") or []
    if not orgs:
        log("No Supabase organization yet — creating one")
        orgs = [api.call("POST", "/v1/organizations", {"name": "Meanwhile"})]
    org = orgs[0]["slug"]
    # Required by the API, never needed again (CI talks SQL through the Management API); Danny can
    # reset it in the dashboard if he ever wants a direct database connection.
    db_pass = "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(32))
    mask(db_pass)
    log(f"Creating Supabase project '{PROJECT_NAME}' in {REGION}")
    body = {
        "name": PROJECT_NAME,
        "organization_slug": org,
        "db_pass": db_pass,
        "region_selection": {"type": "specific", "code": REGION},
    }
    try:
        p = api.call("POST", "/v1/projects", body, retries=0)
    except ApiError as e:
        # A dropped response may still have created it: look before giving up.
        found = [x for x in api.call("GET", "/v1/projects") or [] if x.get("name") == PROJECT_NAME]
        if not found:
            raise ApiError(e.status, f"Supabase refused to create the project: {e}. Free plans allow two "
                                     "active projects — pause or delete one, or create a project named "
                                     f"'{PROJECT_NAME}' yourself and re-run.") from None
        p = found[0]
    return p["ref"]


def resolve_project(api, create):
    """(ref, created). Never picks a project holding someone else's tables."""
    explicit = os.environ.get("SUPABASE_PROJECT_REF", "").strip()
    if explicit:
        wait_active(api, explicit)
        wait_database(api, explicit)
        kind = classify(api, explicit)
        if kind == "foreign":
            raise ApiError(0, f"project {explicit} (SUPABASE_PROJECT_REF) already holds other tables — "
                              "is it the old PWA's? Point the secret at a new project or remove it.")
        return explicit, False

    projects = api.call("GET", "/v1/projects") or []
    candidates = [p for p in projects if norm(p.get("name")) in ACCEPTED_NAMES and p.get("status") != "REMOVED"]
    candidates.sort(key=lambda p: (norm(p.get("name")) != "meanwhilev4", p.get("created_at", "")))
    for p in candidates:
        # Only a project this script named is woken from a pause just to look inside it.
        if wait_active(api, p["ref"], restore=p.get("name") == PROJECT_NAME) is None:
            log(f"Skipping paused project '{p['name']}' ({p['ref']})")
            continue
        wait_database(api, p["ref"])
        kind = classify(api, p["ref"])
        if kind != "foreign":
            log(f"Using project '{p['name']}' ({p['ref']}, {kind})")
            return p["ref"], False
        log(f"Skipping project '{p['name']}' ({p['ref']}): it holds other tables")
    if not create:
        return None, False
    ref = create_project(api)
    wait_active(api, ref)
    wait_database(api, ref)
    return ref, True


# --- schema -----------------------------------------------------------------------------------------

# "if not exists" DDL isn't safe against a concurrent run (catalog unique violations): every
# bootstrap query takes the same advisory lock first, held until its implicit transaction ends.
MIGRATION_TABLE = f"""
select pg_advisory_xact_lock({MIGRATION_LOCK});
create schema if not exists supabase_migrations;
create table if not exists supabase_migrations.schema_migrations (version text not null primary key, statements text[], name text);
"""


def applied_versions(api, ref):
    api.sql(ref, MIGRATION_TABLE)
    return {r["version"] for r in api.sql(ref, "select version from supabase_migrations.schema_migrations")}


def apply_migrations(api, ref):
    """Applies each new migration file in one transaction, recorded where the Supabase CLI records them."""
    done = applied_versions(api, ref)
    applied = []
    for f in sorted(MIGRATIONS.glob("*.sql")):
        m = re.fullmatch(r"(\d+)_([a-z0-9_]+)\.sql", f.name)
        if not m:
            continue
        version, name = m.groups()
        if version in done:
            continue
        log(f"Applying migration {f.name}")
        # One query string = one implicit transaction: the file and its history row land together or
        # not at all. The advisory lock serializes concurrent CI runs.
        query = (f"select pg_advisory_xact_lock({MIGRATION_LOCK});\n{f.read_text()}\n;\n"
                 f"insert into supabase_migrations.schema_migrations (version, name, statements) "
                 f"values ('{version}', '{name}', array[]::text[]);")
        try:
            api.sql(ref, query, retries=0)
        except ApiError:
            if version in applied_versions(api, ref):
                log(f"{f.name} was applied by a concurrent run")
                continue
            raise
        applied.append(f.name)
    return applied


# --- secrets, auth, keys ---------------------------------------------------------------------------

def ai_keys_set(api, ref):
    """Which AI keys exist in the function secrets (names only; values are never read)."""
    names = {x.get("name") for x in api.call("GET", f"/v1/projects/{ref}/secrets") or []}
    return [n for n in AI_KEYS if n in names]


def configure_auth(api, ref):
    api.call("PATCH", f"/v1/projects/{ref}/config/auth", {"mailer_autoconfirm": True})
    rows = api.sql(ref, "select count(*)::int as n from auth.users")
    users = int(rows[0]["n"]) if rows else 0
    if users >= 1:
        api.call("PATCH", f"/v1/projects/{ref}/config/auth", {"disable_signup": True})
    return users


def anon_key(api, ref):
    keys = api.call("GET", f"/v1/projects/{ref}/api-keys") or []
    for want in (lambda k: k.get("name") == "anon", lambda k: k.get("type") == "publishable"):
        for k in keys:
            if want(k) and k.get("api_key"):
                return k["api_key"]
    raise ApiError(0, "the project has no anon/publishable API key")


def function_deployed(api, ref, slug="ai"):
    f = api.call("GET", f"/v1/projects/{ref}/functions/{slug}", ok404=True)
    return bool(f) and f.get("status") == "ACTIVE"


# --- commands -----------------------------------------------------------------------------------------

def cmd_backend(create):
    token = os.environ.get("SUPABASE_ACCESS_TOKEN", "").strip()
    if not token:
        summary("- :x: **Supabase:** add the `SUPABASE_ACCESS_TOKEN` secret (docs/INSTALL.md §2) — until then the app is local-only")
        log("SUPABASE_ACCESS_TOKEN not set — skipping Supabase setup")
        return 0
    api = Api(token)
    ref, created = resolve_project(api, create)
    if not ref:
        summary("- :hourglass: **Supabase:** no project yet — the next **android** run creates it")
        log("No MeanwhileV4 project found (this workflow doesn't create one)")
        return 0
    url = f"https://{ref}.supabase.co"
    key = anon_key(api, ref)
    applied = apply_migrations(api, ref)
    ai = ai_keys_set(api, ref)
    users = configure_auth(api, ref)
    deployed = function_deployed(api, ref)

    gh_file("GITHUB_ENV", f"SUPABASE_URL={url}")
    gh_file("GITHUB_ENV", f"SUPABASE_ANON_KEY={key}")
    for k, v in (("ref", ref), ("url", url), ("function_deployed", str(deployed).lower()), ("created", str(created).lower())):
        gh_file("GITHUB_OUTPUT", f"{k}={v}")

    summary(f"- :white_check_mark: **Supabase:** project `{ref}`" + (" (created by this run)" if created else "")
            + (f"; applied {', '.join(applied)}" if applied else "; schema up to date"))
    summary("- " + (":white_check_mark:" if len(ai) == len(AI_KEYS) else ":warning:") + " **AI keys:** "
            + (", ".join(ai) if ai else "none") + " set in Supabase"
            + ("" if len(ai) == len(AI_KEYS) else " — add the missing ones in Supabase → Edge Functions → Secrets (§3)"))
    summary("- " + (":lock: **Sign-ups:** locked to your account" if users >= 1
                    else ":unlock: **Account:** none yet — create yours in the app; the next build locks sign-ups"))
    log(f"Supabase ready: {ref} · migrations applied: {len(applied)} · users: {users} · AI keys: {len(ai)} · function deployed: {deployed}")
    return 0


def main(argv):
    if not argv or argv[0] != "backend":
        print(__doc__)
        return 2
    try:
        return cmd_backend(create="--create" in argv)
    except ApiError as e:
        print(f"::error title=Supabase setup::{e}", flush=True)
        summary(f"- :x: **Supabase setup failed:** {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
