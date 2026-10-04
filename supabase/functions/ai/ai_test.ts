// Run: deno test supabase/functions/ai/ai_test.ts (CI runs it in .github/workflows/supabase.yml)
import { Ajv } from "npm:ajv@8.20.0";
import { assert, assertEquals, assertRejects } from "jsr:@std/assert@1";
import { decodeJsonFields, schemas } from "./schemas.ts";
import { callGemini } from "./providers.ts";

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
