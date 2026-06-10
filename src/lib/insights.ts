import { db } from './db';
import { getSettings } from './settings';
import type {
  AppEvent, BolusPayload, MealPayload, OutcomePayload,
} from './types';

export type Bucket = '05-11' | '11-16' | '16-22' | '22-05';

export interface MealBucketStats {
  n: number;
  meanDelta2h: number | null;
  meanDelta3h: number | null;
  meanCarbs: number;
  meanUnits: number;
}

export interface AggregateStats {
  windowDays: number;
  counts: { boluses: number; meals: number; outcomesFull: number; outcomesPartial: number };
  hypoRate4h: number;
  mealResponseByBucket: Record<Bucket, MealBucketStats>;
  correctionEffectiveness: { n: number; meanDropPerUnit: number | null; configuredIsf: number };
  largeMisses: { ts: string; carbsG: number; units: number; bgT180minusBg0: number }[];
  settings: { icr: number; isf: number; targetBg: number; diaMin: number };
}

function bucketOf(ts: string): Bucket {
  const h = new Date(ts).getHours();
  if (h >= 5 && h < 11) return '05-11';
  if (h >= 11 && h < 16) return '11-16';
  if (h >= 16 && h < 22) return '16-22';
  return '22-05';
}

function mean(xs: number[]): number | null {
  return xs.length > 0 ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
}

interface DecisionContext {
  bg0: number | null;
}

/** Pull the BG at decision time from the linked ai_interaction snapshot, if any. */
function decisionBg(aiEvents: Map<string, AppEvent>, aiInteractionId?: string): DecisionContext {
  if (!aiInteractionId) return { bg0: null };
  const ai = aiEvents.get(aiInteractionId);
  if (!ai) return { bg0: null };
  const payload = ai.payload as { calc?: { inputsEcho?: { bg?: number | null } } };
  return { bg0: payload.calc?.inputsEcho?.bg ?? null };
}

export async function buildAggregates(windowDays: number): Promise<AggregateStats> {
  const settings = getSettings();
  const from = new Date(Date.now() - windowDays * 24 * 3600_000).toISOString();
  const events = await db.events.where('ts').above(from).toArray();
  const superseded = new Set(events.filter((e) => e.supersedesId).map((e) => e.supersedesId!));
  const live = events.filter((e) => !superseded.has(e.id));

  const boluses = live.filter((e) => e.type === 'bolus') as AppEvent<BolusPayload>[];
  const meals = live.filter((e) => e.type === 'meal') as AppEvent<MealPayload>[];
  const outcomes = live.filter((e) => e.type === 'outcome') as AppEvent<OutcomePayload>[];
  const aiEvents = new Map(live.filter((e) => e.type === 'ai_interaction').map((e) => [e.id, e]));

  const outcomeByRef = new Map(outcomes.map((o) => [o.payload.refId, o.payload]));

  const bolusesWithOutcome = boluses.filter((b) => outcomeByRef.has(b.id));
  const hypoCount = bolusesWithOutcome.filter((b) => outcomeByRef.get(b.id)!.hypo4h).length;

  // Meal response by time-of-day bucket.
  const bucketAcc: Record<Bucket, { d2: number[]; d3: number[]; carbs: number[]; units: number[] }> = {
    '05-11': { d2: [], d3: [], carbs: [], units: [] },
    '11-16': { d2: [], d3: [], carbs: [], units: [] },
    '16-22': { d2: [], d3: [], carbs: [], units: [] },
    '22-05': { d2: [], d3: [], carbs: [], units: [] },
  };
  const largeMisses: AggregateStats['largeMisses'] = [];

  for (const meal of meals) {
    const outcome = outcomeByRef.get(meal.id);
    if (!outcome || outcome.dataQuality === 'unavailable') continue;
    const { bg0 } = decisionBg(aiEvents, meal.payload.aiInteractionId);
    const bucket = bucketOf(meal.ts);
    const linkedBolus = boluses.find(
      (b) => b.payload.aiInteractionId && b.payload.aiInteractionId === meal.payload.aiInteractionId,
    );
    bucketAcc[bucket].carbs.push(meal.payload.carbsG);
    bucketAcc[bucket].units.push(linkedBolus?.payload.units ?? 0);
    if (bg0 !== null) {
      if (outcome.bgT120 !== undefined) bucketAcc[bucket].d2.push(outcome.bgT120 - bg0);
      if (outcome.bgT180 !== undefined) {
        const d3 = outcome.bgT180 - bg0;
        bucketAcc[bucket].d3.push(d3);
        if (Math.abs(d3) > 60 && largeMisses.length < 10) {
          largeMisses.push({
            ts: meal.ts,
            carbsG: meal.payload.carbsG,
            units: linkedBolus?.payload.units ?? 0,
            bgT180minusBg0: Math.round(d3),
          });
        }
      }
    }
  }

  const mealResponseByBucket = Object.fromEntries(
    (Object.keys(bucketAcc) as Bucket[]).map((b) => [
      b,
      {
        n: bucketAcc[b].carbs.length,
        meanDelta2h: mean(bucketAcc[b].d2),
        meanDelta3h: mean(bucketAcc[b].d3),
        meanCarbs: mean(bucketAcc[b].carbs) ?? 0,
        meanUnits: mean(bucketAcc[b].units) ?? 0,
      },
    ]),
  ) as Record<Bucket, MealBucketStats>;

  // Correction effectiveness: correction-kind boluses with no meal within ±3h.
  const drops: number[] = [];
  for (const bolus of boluses) {
    if (bolus.payload.kind !== 'correction' || bolus.payload.units <= 0) continue;
    const t0 = new Date(bolus.ts).getTime();
    const mealNearby = meals.some((m) => Math.abs(new Date(m.ts).getTime() - t0) <= 3 * 3600_000);
    if (mealNearby) continue;
    const outcome = outcomeByRef.get(bolus.id);
    const { bg0 } = decisionBg(aiEvents, bolus.payload.aiInteractionId);
    if (!outcome || bg0 === null || outcome.bgT180 === undefined) continue;
    drops.push((bg0 - outcome.bgT180) / bolus.payload.units);
  }

  return {
    windowDays,
    counts: {
      boluses: boluses.length,
      meals: meals.length,
      outcomesFull: outcomes.filter((o) => o.payload.dataQuality === 'full').length,
      outcomesPartial: outcomes.filter((o) => o.payload.dataQuality === 'partial').length,
    },
    hypoRate4h: bolusesWithOutcome.length > 0 ? hypoCount / bolusesWithOutcome.length : 0,
    mealResponseByBucket,
    correctionEffectiveness: {
      n: drops.length,
      meanDropPerUnit: mean(drops),
      configuredIsf: settings.isf,
    },
    largeMisses,
    settings: {
      icr: settings.icr,
      isf: settings.isf,
      targetBg: settings.targetBg,
      diaMin: settings.diaMin,
    },
  };
}
