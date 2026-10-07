// Run: deno test supabase/functions/ai/ai_test.ts (CI runs it in .github/workflows/supabase.yml)
import { Ajv } from "npm:ajv@8.20.0";
import { assert, assertEquals, assertRejects } from "jsr:@std/assert@1";
import { decodeJsonFields, type Job, schemas } from "./schemas.ts";
import { buildPrompt } from "./prompts.ts";
import { callGemini, GEMINI_THINKING, learnBatchParams, LEARN_BATCH_MAX_TOKENS, modelFor, readBatchResults } from "./providers.ts";
import { BUDGET_MS, TIMEOUT_MS } from "./budgets.ts";
import { allowedUser, oldestUser, readJsonObject } from "./guard.ts";

const ajv = new Ajv({ allErrors: true, strict: false });

const window = { type: "FIXED", minutes: 180, stacks: false, survives_reset: false };
const dawn = {
  id: "F13", name: "Dawn phenomenon", kind: "MULTIPLIER", min_weight: 1, max_weight: 1.3, default_weight: 1.15,
  presets: [], units_per_event: null, window, decay: null, input: "auto", keywords: [], params: [],
  description: "Morning rise 4–8 am",
};

Deno.test("spec-shaped outputs validate", () => {
  const samples: Record<string, unknown> = {
    route: { intents: [{ type: "factor_update", text_span: "a coffee", confidence: 0.95 }, { type: "meal", text_span: "pizza", confidence: 0.9 }] },
    estimate_meal: { carbs_g: 70, fat_g: 24, protein_g: 22, liquid_or_sugary: false, is_estimate: true, notes: "2 slices, NY style" },
    update_profile: {
      changes: [{
        factor_id: "F7", weight: 0.85, window_minutes: 1440, decay_rule: null, units_add: null, reason: "5 mile run",
        action: "activate", started_minutes_ago: 20, amount: null, preset: null,
      }],
      new_factors: [{ definition: dawn, weight: 1.15, reason: "new" }],
      summary: "Exercise 0.85 for 24 h",
    },
    learn_cycle: {
      proposed_settings: { ICR: 9.5, ISF: null, target: null, lead_time_min: 15, iob_peak_min: null, iob_duration_min: null },
      factor_changes: [{ factor_id: "F4", field: "units_add", old_json: "1", new_json: "1.5", evidence: "3 of 3 coffee mornings ran high" }],
      new_factors: [{ id: "F13", name: "Dawn phenomenon", definition: dawn, evidence: "rise 4–8 am on 9 of 14 days" }],
      setting_changes: [{ path: "meal.kFatPerG", old_json: "0.0045", new_json: "0.006", evidence: "fatty dinners high at +3 h" }],
      observations: ["Overrides were mostly for exercise"],
      summary: "Slightly stronger carb ratio.",
    },
  };
  for (const [job, sample] of Object.entries(samples)) {
    const validate = ajv.compile(schemas[job as keyof typeof schemas]);
    assert(validate(sample), `${job}: ${ajv.errorsText(validate.errors)}`);
  }
});

Deno.test("invalid outputs are rejected", () => {
  const validate = ajv.compile(schemas.estimate_meal);
  assert(!validate({ carbs_g: "lots", fat_g: 1, protein_g: 1, liquid_or_sugary: false, is_estimate: true, notes: "" }));
  assert(!validate({ carbs_g: 1, fat_g: 1, protein_g: 1, liquid_or_sugary: false, is_estimate: true, notes: "", extra: 1 }));
});

Deno.test("learn-cycle old/new decode back to JSON values", () => {
  const out = decodeJsonFields("learn_cycle", {
    factor_changes: [{ factor_id: "F5", field: "bounds", old_json: "null", new_json: "{\"min\":0.6,\"max\":1}", evidence: "" }],
    setting_changes: [{ path: "split.minFatG", old_json: "40", new_json: "35", evidence: "" }],
  });
  assertEquals(out.factor_changes[0].new, { min: 0.6, max: 1 });
  assertEquals(out.setting_changes[0].new, 35);
  assertEquals(out.factor_changes[0].old, null);
});

Deno.test("gemini: JSON-schema request and thought parts skipped", async () => {
  Deno.env.set("GEMINI_API_KEY", "test-key");
  const realFetch = globalThis.fetch;
  let sent: any = null;
  globalThis.fetch = (async (_url: string, init: RequestInit) => {
    sent = JSON.parse(String(init.body));
    return new Response(JSON.stringify({
      candidates: [{ finishReason: "STOP", content: { parts: [{ text: "thinking…", thought: true }, { text: "{\"ok\":1}" }] } }],
      modelVersion: "gemini-x",
    }), { status: 200 });
  }) as typeof fetch;
  try {
    const r = await callGemini({ job: "route", system: "S", user: "U", schema: { type: "object" }, timeoutMs: 1000 });
    assertEquals(r.text, "{\"ok\":1}");
    assertEquals(r.model, "gemini-x");
    assertEquals(sent.generationConfig.responseMimeType, "application/json");
    assertEquals(sent.generationConfig.responseJsonSchema, { type: "object" });
    assertEquals(sent.systemInstruction.parts[0].text, "S");
  } finally {
    globalThis.fetch = realFetch;
  }
});

Deno.test("gemini: errors become ProviderError (so the other provider is tried)", async () => {
  Deno.env.set("GEMINI_API_KEY", "test-key");
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async () => new Response("quota", { status: 429 })) as typeof fetch;
  try {
    await assertRejects(() => callGemini({ job: "route", system: "S", user: "U", schema: {}, timeoutMs: 1000 }), Error, "HTTP 429");
  } finally {
    globalThis.fetch = realFetch;
  }
});

Deno.test("allowlist: explicit list wins; unset defers to the first-account check", () => {
  assert("checkFirstUser" in allowedUser("u1", undefined));
  assert("checkFirstUser" in allowedUser("u1", " , "));
  const denied = allowedUser("u1", "u2");
  assert("ok" in denied && denied.ok === false);
  const granted = allowedUser("u1", "u2, u1");
  assert("ok" in granted && granted.ok === true);
});

Deno.test("first-account rule: oldest wins, ambiguity fails closed", () => {
  const u = (id: string, at: string) => ({ id, created_at: at });
  assertEquals(oldestUser([]), null); // no accounts yet -> nobody
  assertEquals(oldestUser([u("danny", "2026-10-04T10:00:00Z")]), "danny");
  // An attacker signing up later is never the oldest.
  assertEquals(oldestUser([u("attacker", "2026-10-05T00:00:00Z"), u("danny", "2026-10-04T10:00:00Z")]), "danny");
  // A full page means the true oldest may be unseen: refuse rather than guess.
  const many = Array.from({ length: 50 }, (_, i) => u("u$i", "2026-10-0" + ((i % 8) + 1)));
  assertEquals(oldestUser(many), null);
});

Deno.test("request body guards", async () => {
  const post = (body: string, headers: Record<string, string> = {}) =>
    new Request("http://x/ai", { method: "POST", body, headers });
  assert("body" in await readJsonObject(post('{"job":"route"}')));
  for (const bad of ["null", "[1]", "\"x\"", "not json"]) {
    const r = await readJsonObject(post(bad));
    assert("error" in r && r.status === 400, bad);
  }
  const big = await readJsonObject(post("{}", { "content-length": "5000000" }), 1000);
  assert("error" in big && big.status === 413);
  const bigBody = await readJsonObject(post(JSON.stringify({ x: "y".repeat(2000) })), 1000);
  assert("error" in bigBody && bigBody.status === 413);
});

Deno.test("every job has a versioned prompt, built as shared context + job", async () => {
  const header = (text: string) => text.match(/^<!-- prompt version: ([a-z_]+)-v(\d+) -->\n/);
  const common = await Deno.readTextFile(new URL("./prompts/_common.md", import.meta.url));
  assertEquals(header(common)?.[1], "common");
  for (const job of Object.keys(schemas) as Job[]) {
    const text = await Deno.readTextFile(new URL(`./prompts/${job}.md`, import.meta.url));
    assertEquals(header(text)?.[1], job, `prompts/${job}.md needs "<!-- prompt version: ${job}-vN -->"`);
    const { system, user } = await buildPrompt(job, { now: "2026-10-04T12:00:00Z" });
    assert(system.startsWith(common.trimEnd()) && system.endsWith(text), job);
    assert(user.includes('"now": "2026-10-04T12:00:00Z"'), job);
  }
});

Deno.test("jobs Danny waits on use fast models by default; the learn cycle keeps the deep ones", () => {
  for (const k of ["GEMINI_FAST_MODEL", "GEMINI_MODEL", "CLAUDE_FAST_MODEL", "CLAUDE_MODEL"]) Deno.env.delete(k);
  for (const job of ["route", "estimate_meal", "update_profile"] as Job[]) {
    assertEquals(modelFor("gemini", job), "gemini-3.8-flash", job);
    assertEquals(modelFor("claude", job), "claude-opus-5-5", job);
  }
  assertEquals(modelFor("gemini", "learn_cycle"), "gemini-pro-latest");
  assertEquals(modelFor("claude", "learn_cycle"), "claude-opus-5-5");
  Deno.env.set("GEMINI_FAST_MODEL", "gemini-custom");
  assertEquals(modelFor("gemini", "update_profile"), "gemini-custom");
  Deno.env.delete("GEMINI_FAST_MODEL");
});

Deno.test("gemini: an unknown model name falls back to the -latest alias once", async () => {
  Deno.env.set("GEMINI_API_KEY", "test-key");
  Deno.env.delete("GEMINI_FAST_MODEL");
  const asked: string[] = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async (url: string | URL | Request) => {
    const model = decodeURIComponent(String(url).split("/models/")[1].split(":")[0]);
    asked.push(model);
    if (model === "gemini-3.8-flash") return new Response('{"error":{"code":404}}', { status: 404 });
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "{}" }] }, finishReason: "STOP" }] }));
  }) as typeof fetch;
  try {
    const r = await callGemini({ job: "route", system: "s", user: "u", schema: schemas.route, timeoutMs: 1000 });
    assertEquals(asked, ["gemini-3.8-flash", "gemini-flash-latest"]);
    assertEquals(r.model, "gemini-flash-latest");
  } finally {
    globalThis.fetch = realFetch;
  }
});

Deno.test("gemini: quick jobs ask for minimal/low thinking, the learn cycle for the default", async () => {
  Deno.env.set("GEMINI_API_KEY", "test-key");
  Deno.env.delete("GEMINI_FAST_MODEL");
  const realFetch = globalThis.fetch;
  // deno-lint-ignore no-explicit-any
  const sent: any[] = [];
  globalThis.fetch = (async (_url: string, init: RequestInit) => {
    sent.push(JSON.parse(String(init.body)));
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "{}" }] }, finishReason: "STOP" }] }));
  }) as typeof fetch;
  try {
    for (const job of ["route", "estimate_meal", "update_profile", "learn_cycle"] as Job[]) {
      await callGemini({ job, system: "s", user: "u", schema: {}, timeoutMs: 1000 });
    }
    assertEquals(sent.map((b) => b.generationConfig.thinkingConfig?.thinkingLevel ?? null), ["MINIMAL", "LOW", "LOW", null]);
    assertEquals(GEMINI_THINKING.learn_cycle, null);
  } finally {
    globalThis.fetch = realFetch;
  }
});

Deno.test("gemini: a model that rejects the thinking level is asked again without it", async () => {
  Deno.env.set("GEMINI_API_KEY", "test-key");
  const realFetch = globalThis.fetch;
  // deno-lint-ignore no-explicit-any
  const sent: any[] = [];
  globalThis.fetch = (async (_url: string, init: RequestInit) => {
    const body = JSON.parse(String(init.body));
    sent.push(body);
    if (body.generationConfig.thinkingConfig) {
      return new Response('{"error":{"code":400,"message":"Thinking level is not supported for this model."}}', { status: 400 });
    }
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "{\"ok\":1}" }] }, finishReason: "STOP" }] }));
  }) as typeof fetch;
  try {
    const r = await callGemini({ job: "route", system: "s", user: "u", schema: {}, timeoutMs: 1000 });
    assertEquals(r.text, "{\"ok\":1}");
    assertEquals(sent.length, 2);
    assertEquals(sent[1].generationConfig.thinkingConfig, undefined);
  } finally {
    globalThis.fetch = realFetch;
  }
});

Deno.test("quick jobs get 60 s per provider; the learn cycle answered directly 120 s", () => {
  for (const job of ["route", "estimate_meal", "update_profile"] as Job[]) {
    assertEquals(TIMEOUT_MS[job], 60_000, job);
    assert(BUDGET_MS[job] >= 2 * TIMEOUT_MS[job] && BUDGET_MS[job] < 150_000, job);
  }
  assertEquals(TIMEOUT_MS.learn_cycle, 120_000);
  assert(BUDGET_MS.learn_cycle < 150_000, "inside the Edge Function wall clock");
});

Deno.test("learning on Claude: Opus 5.5 at max effort as a batch, structured output, no fallbacks param", () => {
  for (const k of ["CLAUDE_LEARN_MODEL", "CLAUDE_MODEL", "CLAUDE_LEARN_EFFORT"]) Deno.env.delete(k);
  const p = learnBatchParams("SYSTEM", "USER", schemas.learn_cycle);
  assertEquals(p.model, "claude-opus-5-5");
  assertEquals(p.max_tokens, LEARN_BATCH_MAX_TOKENS);
  assertEquals(p.max_tokens, 128_000);
  assertEquals(p.output_config.effort, "max");
  assertEquals(p.output_config.format.type, "json_schema");
  assertEquals(p.output_config.format.schema, schemas.learn_cycle);
  assertEquals(p.system[0].text, "SYSTEM");
  assertEquals(p.messages[0].content, "USER");
  assert(!("fallbacks" in p) && !("betas" in p) && !("thinking" in p), "batches take no fallbacks; Opus 5.5 thinks by itself");
  Deno.env.set("CLAUDE_LEARN_EFFORT", "xhigh");
  assertEquals(learnBatchParams("s", "u", {}).output_config.effort, "xhigh");
  Deno.env.set("CLAUDE_LEARN_EFFORT", "nonsense");
  assertEquals(learnBatchParams("s", "u", {}).output_config.effort, "max");
  Deno.env.delete("CLAUDE_LEARN_EFFORT");
});

Deno.test("batch results: the answer, a refusal, an error, an expiry", () => {
  const ok = readBatchResults([{
    custom_id: "learn_cycle",
    result: { type: "succeeded", message: { model: "claude-opus-5-5", stop_reason: "end_turn", content: [{ type: "thinking", thinking: "" }, { type: "text", text: "{\"summary\":\"x\"}" }] } },
  }]);
  assertEquals(ok, { state: "done", text: "{\"summary\":\"x\"}", model: "claude-opus-5-5" });
  const refused = readBatchResults([{ custom_id: "learn_cycle", result: { type: "succeeded", message: { stop_reason: "refusal", stop_details: { category: "bio" }, content: [] } } }]);
  assert(refused.state === "failed" && refused.error.includes("declined (bio)"));
  const errored = readBatchResults([{ custom_id: "learn_cycle", result: { type: "errored", error: { type: "error", error: { type: "invalid_request_error", message: "bad schema" } } } }]);
  assert(errored.state === "failed" && errored.error.includes("bad schema"));
  assertEquals(readBatchResults([{ custom_id: "learn_cycle", result: { type: "expired" } }]).state, "failed");
  assertEquals(readBatchResults([]).state, "failed");
});

Deno.test("route understands corrections, done, a spoken BG", () => {
  const validate = ajv.compile(schemas.route);
  const sample = {
    intents: [
      { type: "dose_correction", text_span: "never mind I only took 5", confidence: 0.97 },
      { type: "followed", text_span: "took it", confidence: 0.9 },
      { type: "bg_reading", text_span: "BG 140", confidence: 0.99 },
    ],
  };
  assert(validate(sample), ajv.errorsText(validate.errors));
});

