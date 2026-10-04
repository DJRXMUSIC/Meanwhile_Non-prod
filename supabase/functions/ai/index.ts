// MeanwhileV4 AI Edge Function (spec §10). One endpoint, four jobs. The AI never computes doses.
//   POST { job, payload, provider_preference } with Danny's Supabase JWT
//   → { result, provider, model, latency_ms, fallback_used, attempts }
// Secrets (set by Danny with `supabase secrets set`, never in the repo/APK/logs):
//   GEMINI_API_KEY, ANTHROPIC_API_KEY; optional GEMINI_MODEL, CLAUDE_MODEL, GEMINI_FAST_MODEL,
//   CLAUDE_FAST_MODEL. ALLOWED_USER_IDS (comma-separated user ids) is required: without it every call is
//   refused, because the repo is public and anyone could otherwise sign up and spend the keys.
import { createClient } from "npm:@supabase/supabase-js@2.117.2";
import { Ajv } from "npm:ajv@8.20.0";
import { decodeJsonFields, type Job, schemas } from "./schemas.ts";
import { available, call, modelFor, type Provider, ProviderError } from "./providers.ts";
import { buildPrompt } from "./prompts.ts";
import { allowedUser, readJsonObject } from "./guard.ts";

const JOBS: Job[] = ["route", "estimate_meal", "update_profile", "learn_cycle"];
const TIMEOUT_MS: Record<Job, number> = {
  route: 15_000,
  estimate_meal: 15_000,
  update_profile: 15_000,
  learn_cycle: 120_000,
};
// Edge Functions have a ~150 s wall clock; keep headroom for auth and the response.
const TOTAL_BUDGET_MS = 145_000;
const MIN_FALLBACK_MS = 12_000;

const ajv = new Ajv({ allErrors: true, strict: false });
const validators = Object.fromEntries(JOBS.map((j) => [j, ajv.compile(schemas[j])]));

function order(pref: string | undefined): Provider[] {
  switch (pref) {
    case "claude_first":
      return ["claude", "gemini"];
    case "gemini_only":
      return ["gemini"];
    case "claude_only":
      return ["claude"];
    default:
      return ["gemini", "claude"];
  }
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

async function authorize(req: Request): Promise<string | Response> {
  const auth = req.headers.get("Authorization");
  if (!auth) return json(401, { error: "missing Authorization" });
  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
  const { data, error } = await supabase.auth.getUser();
  if (error || !data.user) return json(401, { error: "invalid session" });
  const access = allowedUser(data.user.id, Deno.env.get("ALLOWED_USER_IDS"));
  if (!access.ok) return json(403, { error: access.error });
  return data.user.id;
}

Deno.serve(async (req) => {
  const started = Date.now();
  if (req.method !== "POST") return json(405, { error: "POST only" });
  const user = await authorize(req);
  if (user instanceof Response) return user;

  const read = await readJsonObject(req);
  if ("error" in read) return json(read.status, { error: read.error });
  const body = read.body;
  const job = body.job as Job;
  if (typeof job !== "string" || !JOBS.includes(job)) return json(400, { error: `job must be one of ${JOBS.join(", ")}` });

  const { system, user: userText } = await buildPrompt(job, body.payload ?? {});
  const preferred = order(typeof body.provider_preference === "string" ? body.provider_preference : undefined);
  const providers = preferred.filter(available);
  const attempts: Array<{ provider: Provider; model: string; ok: boolean; error?: string; latency_ms: number; validation?: string }> = [];

  for (const p of providers) {
    const remaining = TOTAL_BUDGET_MS - (Date.now() - started);
    const timeoutMs = Math.min(TIMEOUT_MS[job], remaining);
    if (attempts.length > 0 && timeoutMs < MIN_FALLBACK_MS) {
      attempts.push({ provider: p, model: modelFor(p, job), ok: false, error: "no time left for fallback", latency_ms: 0 });
      break;
    }
    const t0 = Date.now();
    try {
      const r = await call(p, { job, system, user: userText, schema: schemas[job], timeoutMs });
      let parsed: unknown;
      try {
        parsed = JSON.parse(r.text);
      } catch {
        attempts.push({ provider: p, model: r.model, ok: false, error: "response was not JSON", latency_ms: Date.now() - t0, validation: "invalid" });
        continue;
      }
      if (!validators[job](parsed)) {
        // Schema validation failure → try the other provider (spec §10.1).
        attempts.push({
          provider: p, model: r.model, ok: false, latency_ms: Date.now() - t0, validation: "invalid",
          error: ajv.errorsText(validators[job].errors).slice(0, 500),
        });
        continue;
      }
      attempts.push({ provider: p, model: r.model, ok: true, latency_ms: Date.now() - t0, validation: "ok" });
      return json(200, {
        result: decodeJsonFields(job, parsed),
        provider: p,
        model: r.model,
        latency_ms: Date.now() - started,
        fallback_used: p !== preferred[0],
        attempts,
      });
    } catch (e) {
      const msg = e instanceof ProviderError ? e.message : `unexpected: ${(e as Error).message}`;
      attempts.push({ provider: p, model: modelFor(p, job), ok: false, error: msg, latency_ms: Date.now() - t0 });
    }
  }

  // The learn cycle can use up the wall clock on the first provider; tell the app which one to retry.
  const skipped = attempts.find((a) => a.error === "no time left for fallback");
  return json(providers.length === 0 ? 503 : 502, {
    error: providers.length === 0 ? "no AI provider configured (set GEMINI_API_KEY and/or ANTHROPIC_API_KEY)" : "all providers failed",
    attempts,
    latency_ms: Date.now() - started,
    retry_with: skipped ? `${skipped.provider}_only` : null,
  });
});
