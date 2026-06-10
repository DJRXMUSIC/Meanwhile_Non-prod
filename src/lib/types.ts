// ALL domain types live here.

export type EventType =
  | 'meal' | 'bolus' | 'bg_manual' | 'context'
  | 'ai_interaction' | 'outcome' | 'settings_change' | 'note';

export interface AppEvent<P = unknown> {
  id: string;            // crypto.randomUUID(), generated client-side
  type: EventType;
  ts: string;            // ISO 8601 — when the thing happened
  payload: P;
  supersedesId?: string; // correction chain
  deviceId: string;
  synced: 0 | 1;         // Dexie-only flag; stripped before upload
  createdAt: string;     // ISO — when the row was written
}

export interface FoodItem { name: string; carbsG: number; fatG: number; proteinG: number; confidence: number; }

export interface MealPayload {
  items: FoodItem[];
  carbsG: number; fatG: number; proteinG: number;   // totals (user-edited values win)
  source: 'ai' | 'manual';
  rawText?: string;
  aiInteractionId?: string;
}

export interface BolusPayload {
  units: number;                       // ACTUAL units the user logged
  suggestedUnits?: number;             // what the engine said
  kind: 'meal' | 'correction' | 'mixed';
  aiInteractionId?: string;
}

export interface BgManualPayload { mgdl: number; }

export interface ContextPayload { tags: ContextTag[]; note?: string; }
export type ContextTag =
  | 'exercise_recent' | 'exercise_planned' | 'alcohol'
  | 'illness' | 'stress' | 'poor_sleep' | 'travel' | 'other';

export interface NotePayload { text: string; }

export interface SettingsChangePayload {
  key: string;
  from: unknown;
  to: unknown;
  source: 'manual' | 'suggestion';
}

export interface AiInteractionPayload {
  inputText: string;
  inputMode: 'voice' | 'text' | 'manual';
  extraction: ExtractionResult | null;
  calc: Recommendation;
  rationale?: { rationale: string; cautions: string[] };
  bgAtTime: BgReading | null;
  model: string; provider: string; latencyMs: number;
}

export interface OutcomePayload {
  refId: string;                          // the bolus/meal event analyzed
  bgT60?: number; bgT120?: number; bgT180?: number; bgT240?: number;
  min4h?: number; max4h?: number;
  hypo4h: boolean;                        // any reading < lowThreshold within 4h
  dataQuality: 'full' | 'partial' | 'unavailable';
}

export type Trend =
  | 'DoubleUp' | 'SingleUp' | 'FortyFiveUp' | 'Flat'
  | 'FortyFiveDown' | 'SingleDown' | 'DoubleDown' | 'NONE';

export interface BgReading {
  mgdl: number;
  ts: string;
  trend: Trend;
  delta?: number;          // mg/dL change vs prior reading if available
  source: 'xdrip' | 'nightscout' | 'manual' | 'demo';
}

// ---- Insulin engine ----

export type CalcFlag =
  | 'stale_bg' | 'low_bg' | 'large_dose' | 'high_carb_parse'
  | 'low_confidence' | 'recent_bolus' | 'active_cob_falling' | 'clarification';

export interface Recommendation {
  headline: string;
  units: number;               // rounded, >= 0
  rawUnits: number;
  carbBolus: number; correction: number; iob: number; cob: number;
  rescueCarbsG?: number;
  flags: CalcFlag[];
  breakdown: string[];         // exact display strings
  inputsEcho: {
    bg: number | null; bgAgeMin: number | null; trend: string;
    carbsG: number; fatG: number; proteinG: number;
    targetBg: number; isf: number; icr: number;
  };
}

// ---- AI task results (mirrors of the zod schemas in src/lib/ai/schemas.ts) ----

export type ExtractionIntent = 'meal' | 'correction_check' | 'context_log' | 'question';

export interface ExtractionMeal {
  items: FoodItem[];
  totalCarbsG: number;
  totalFatG: number;
  totalProteinG: number;
}

export interface ExtractionResult {
  intent: ExtractionIntent;
  meal: ExtractionMeal | null;
  contextTags: ContextTag[];
  userStatedBg: number | null;
  userStatedDoseUnits: number | null;
  question: string | null;
  confidence: number;
  clarificationNeeded: string | null;
}

export interface RationaleResult {
  rationale: string;
  cautions: string[];
}

export interface InsightSuggestion {
  parameter: 'icr' | 'isf' | 'targetBg' | 'behavior';
  current: string;
  suggested: string;
  expectedEffect: string;
  evidence: string;
  confidence: number;
}

export interface InsightsResult {
  suggestions: InsightSuggestion[];
}

export interface Suggestion {
  id: string;
  payload: InsightSuggestion;
  status: 'pending' | 'accepted' | 'dismissed';
  createdAt: string;
  resolvedAt?: string;
}
