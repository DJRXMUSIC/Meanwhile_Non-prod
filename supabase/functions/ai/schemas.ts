// JSON Schemas for each job (spec §10.3). Written to the structured-output subset both providers
// accept: every object has additionalProperties:false and lists all properties as required;
// "optional" values are nullable. Values whose type varies (old/new in learn-cycle changes) travel
// as JSON-encoded strings and are decoded back to JSON before returning to the app.

export type Job = "route" | "converse" | "estimate_meal" | "update_profile" | "learn_cycle";

const nullable = (schema: Record<string, unknown>) => ({ anyOf: [schema, { type: "null" }] });
const obj = (properties: Record<string, unknown>) => ({
  type: "object",
  additionalProperties: false,
  required: Object.keys(properties),
  properties,
});
const str = { type: "string" };
const num = { type: "number" };
const int = { type: "integer" };
const bool = { type: "boolean" };
const arr = (items: unknown) => ({ type: "array", items });

const decayRule = obj({
  steps: arr(obj({ from_minutes: int, weight: num })),
});

const windowRule = obj({
  type: { type: "string", enum: ["UNTIL_RESET", "FIXED", "CONSUMED_BY_NEXT_DOSE", "PER_MEAL", "AFTER_LAST_TRIGGER"] },
  minutes: nullable(int),
  stacks: bool,
  survives_reset: bool,
});

/** A factor definition as data (spec §7.5). Maps become name/value arrays. */
export const factorDefinition = obj({
  id: str,
  name: str,
  kind: { type: "string", enum: ["MULTIPLIER", "UNITS_PER_EVENT", "AUTO_MULTIPLIER", "MEAL_COMPUTED", "BASELINE"] },
  min_weight: nullable(num),
  max_weight: nullable(num),
  default_weight: nullable(num),
  presets: arr(obj({ name: str, weight: num })),
  units_per_event: nullable(num),
  window: windowRule,
  decay: nullable(decayRule),
  input: str,
  keywords: arr(str),
  params: arr(obj({ name: str, value: num })),
  description: str,
});

export const schemas: Record<Job, Record<string, unknown>> = {
  route: obj({
    intents: arr(obj({
      // 1.4: dose_correction ("never mind, only 5"), followed ("took it"), bg_reading ("BG 140").
      type: { type: "string", enum: ["meal", "factor_update", "dose_given", "dose_correction", "followed", "bg_reading", "feedback"] },
      text_span: str,
      confidence: num,
    })),
  }),

  // 1.4: one call that both answers Danny in words and says what (if anything) to act on.
  converse: obj({
    reply: str,
    intents: arr(obj({
      type: { type: "string", enum: ["meal", "factor_update", "dose_given", "dose_correction", "followed", "bg_reading", "feedback"] },
      text_span: str,
      confidence: num,
    })),
  }),

  estimate_meal: obj({
    carbs_g: num,
    fat_g: num,
    protein_g: num,
    liquid_or_sugary: bool,
    is_estimate: bool,
    notes: str,
  }),

  update_profile: obj({
    changes: arr(obj({
      factor_id: str,
      // Spec fields:
      weight: nullable(num),
      window_minutes: nullable(int),
      decay_rule: nullable(decayRule),
      units_add: nullable(num),
      reason: str,
      // Extensions: end a factor, timing ("workout ended 30 min ago"), amount (cups/drinks), preset.
      action: { type: "string", enum: ["activate", "deactivate"] },
      started_minutes_ago: nullable(int),
      amount: nullable(num),
      preset: nullable(str),
    })),
    new_factors: arr(obj({ definition: factorDefinition, weight: nullable(num), reason: str })),
    summary: str,
  }),

  learn_cycle: obj({
    // null = no change proposed
    proposed_settings: obj({
      ICR: nullable(num),
      ISF: nullable(num),
      target: nullable(num),
      lead_time_min: nullable(num),
      iob_peak_min: nullable(num),
      iob_duration_min: nullable(num),
    }),
    factor_changes: arr(obj({
      factor_id: str,
      field: { type: "string", enum: ["units_add", "weight", "bounds", "window", "decay", "default_weight"] },
      old_json: str,
      new_json: str,
      evidence: str,
    })),
    new_factors: arr(obj({ id: str, name: str, definition: factorDefinition, evidence: str })),
    // Extension: any other tunable by profile path (e.g. "meal.kFatPerG", "split.minFatG").
    setting_changes: arr(obj({ path: str, old_json: str, new_json: str, evidence: str })),
    observations: arr(str),
    summary: str,
  }),
};

/** Decode *_json string fields back into JSON values (old/new per the spec's learn_cycle schema). */
export function decodeJsonFields(job: Job, result: any): any {
  if (job !== "learn_cycle" || !result) return result;
  const decode = (s: string) => {
    try {
      return JSON.parse(s);
    } catch {
      return s;
    }
  };
  const map = (items: any[]) =>
    (items ?? []).map(({ old_json, new_json, ...rest }: any) => ({ ...rest, old: decode(old_json), new: decode(new_json) }));
  return { ...result, factor_changes: map(result.factor_changes), setting_changes: map(result.setting_changes) };
}
