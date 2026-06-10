import { create } from 'zustand';
import { calcRecommendation } from '../lib/insulin/calc';
import { callAi } from '../lib/ai/client';
import { extractUserMessage } from '../lib/ai/prompts';
import { appendEvents, latestMeals } from '../lib/repo';
import { getSettings } from '../lib/settings';
import { currentCob, currentIob, minutesSinceLastBolus } from '../lib/status';
import { bgAgeMin } from '../lib/bgsource/useBg';
import type {
  AiInteractionPayload, AppEvent, BgReading, BolusPayload, ContextPayload,
  ExtractionResult, FoodItem, MealPayload, RationaleResult, Recommendation,
} from '../lib/types';

export type Screen = 'home' | 'history' | 'insights' | 'settings';

export type CalcPhase =
  | 'idle' | 'snapshotting' | 'extracting' | 'calculated'
  | 'explaining' | 'ready' | 'logging' | 'done' | 'manual' | 'error';

export interface Snapshot {
  bg: BgReading | null;
  bgAgeMin: number | null;
  iob: number;
  cob: number;
  recentBolusMinAgo: number | null;
  bgOverridden: boolean;
}

export interface CardState {
  inputText: string;
  inputMode: 'voice' | 'text' | 'manual';
  extraction: ExtractionResult | null;
  calc: Recommendation;
  rationale: RationaleResult | null;
  rationaleStale: boolean;        // edits happened after the rationale came back
  rationaleFailed: boolean;
  snapshot: Snapshot;
  items: FoodItem[];              // editable parsed items
  carbsG: number; fatG: number; proteinG: number;
  logAlsoStatedDose: boolean;     // "Also log the N U you mentioned?" checkbox
  aiMeta: { model: string; provider: string; latencyMs: number };
  answer: string | null;          // intent === 'question' Q&A answer
}

interface Toast { id: number; text: string; tone: 'info' | 'warn' | 'error' }

interface Store {
  screen: Screen;
  setScreen: (s: Screen) => void;

  phase: CalcPhase;
  card: CardState | null;
  manualSheetOpen: boolean;
  manualPrefillNote: string;
  confirmSheet: { reasons: string[] } | null;
  toasts: Toast[];

  latestBg: BgReading | null;
  setLatestBg: (r: BgReading | null) => void;

  headerNonce: number;            // bump to refresh IOB/COB header after logging
  toast: (text: string, tone?: Toast['tone']) => void;
  submit: (text: string, mode: 'voice' | 'text') => Promise<void>;
  submitManual: (input: { carbsG: number; bgOverride?: number; unitsTaken?: number; note?: string }) => Promise<void>;
  editCarbs: (itemIndex: number | null, newCarbs: number) => void;
  setLogAlsoStatedDose: (v: boolean) => void;
  refreshRationale: () => Promise<void>;
  requestLog: (actualUnits: number) => void;
  confirmLog: (actualUnits: number) => Promise<void>;
  cancelConfirm: () => void;
  dismissCard: () => void;
  openManualSheet: (prefillNote?: string) => void;
  closeManualSheet: () => void;
}

let toastSeq = 0;

function calcSettings() {
  const s = getSettings();
  return {
    targetBg: s.targetBg, isf: s.isf, icr: s.icr, roundStep: s.roundStep,
    lowThreshold: s.lowThreshold, largeDoseWarn: s.largeDoseWarn,
    highCarbWarn: s.highCarbWarn, staleMin: s.staleMin, recentBolusMin: s.recentBolusMin,
  };
}

async function takeSnapshot(latestBg: BgReading | null): Promise<Snapshot> {
  const [iob, cob, recent] = await Promise.all([currentIob(), currentCob(), minutesSinceLastBolus()]);
  return {
    bg: latestBg,
    bgAgeMin: bgAgeMin(latestBg),
    iob,
    cob,
    recentBolusMinAgo: recent,
    bgOverridden: false,
  };
}

function runCalc(card: Pick<CardState, 'snapshot' | 'carbsG' | 'fatG' | 'proteinG' | 'extraction'>): Recommendation {
  return calcRecommendation({
    bg: card.snapshot.bg?.mgdl ?? null,
    bgAgeMin: card.snapshot.bgOverridden ? 0 : card.snapshot.bgAgeMin,
    trend: card.snapshot.bg?.trend ?? 'NONE',
    carbsG: card.carbsG,
    fatG: card.fatG,
    proteinG: card.proteinG,
    iobUnits: card.snapshot.iob,
    cobG: card.snapshot.cob,
    recentBolusMinAgo: card.snapshot.recentBolusMinAgo,
    extractionConfidence: card.extraction?.confidence ?? null,
    clarificationNeeded: Boolean(card.extraction?.clarificationNeeded),
    settings: calcSettings(),
  });
}

async function fetchRationale(card: CardState): Promise<RationaleResult | null> {
  const s = getSettings();
  const result = await callAi('rationale', {
    inputText: card.inputText,
    extraction: card.extraction,
    calc: card.calc,
    bg: card.snapshot.bg?.mgdl ?? null,
    trend: card.snapshot.bg?.trend ?? 'NONE',
    bgAgeMin: card.snapshot.bgAgeMin,
    iob: card.snapshot.iob,
    cob: card.snapshot.cob,
    settings: { targetBg: s.targetBg, isf: s.isf, icr: s.icr },
  });
  return result.ok ? result.data : null;
}

export const useStore = create<Store>((set, get) => ({
  screen: 'home',
  setScreen: (screen) => set({ screen }),

  phase: 'idle',
  card: null,
  manualSheetOpen: false,
  manualPrefillNote: '',
  confirmSheet: null,
  toasts: [],

  latestBg: null,
  setLatestBg: (latestBg) => set({ latestBg }),

  headerNonce: 0,

  toast: (text, tone = 'info') => {
    const id = ++toastSeq;
    set((st) => ({ toasts: [...st.toasts, { id, text, tone }] }));
    setTimeout(() => set((st) => ({ toasts: st.toasts.filter((t) => t.id !== id) })), 4000);
  },

  submit: async (text, mode) => {
    if (get().phase === 'extracting' || get().phase === 'logging') return;
    set({ phase: 'snapshotting', card: null });
    const snapshot = await takeSnapshot(get().latestBg);

    set({ phase: 'extracting' });
    const recentMeals = (await latestMeals(4)) as AppEvent<MealPayload>[];
    const summaries = recentMeals.map(
      (m) => `${m.payload.items.map((i) => i.name).join(', ') || 'meal'} ${m.payload.carbsG}g at ${new Date(m.ts).toLocaleTimeString()}`,
    );
    const extraction = await callAi('extract', {
      message: extractUserMessage(text, summaries),
    });

    if (!extraction.ok) {
      get().toast('AI unavailable — manual entry', 'warn');
      set({ phase: 'manual', manualSheetOpen: true, manualPrefillNote: text });
      return;
    }

    const ex = extraction.data;

    // Merge: user-stated BG overrides the snapshot BG.
    if (ex.userStatedBg !== null) {
      snapshot.bg = {
        mgdl: ex.userStatedBg,
        ts: new Date().toISOString(),
        trend: snapshot.bg?.trend ?? 'NONE',
        source: 'manual',
      };
      snapshot.bgAgeMin = 0;
      snapshot.bgOverridden = true;
    }

    if (ex.intent === 'context_log') {
      await appendEvents([{
        type: 'context',
        payload: { tags: ex.contextTags, note: text } satisfies ContextPayload,
      }]);
      get().toast('Context logged');
      set({ phase: 'done', headerNonce: get().headerNonce + 1 });
      return;
    }

    const items = ex.meal?.items ?? [];
    const carbsG = ex.meal?.totalCarbsG ?? 0;
    const fatG = ex.meal?.totalFatG ?? 0;
    const proteinG = ex.meal?.totalProteinG ?? 0;

    const base = { snapshot, carbsG, fatG, proteinG, extraction: ex };
    const calc = runCalc(base);

    const card: CardState = {
      inputText: text,
      inputMode: mode,
      extraction: ex,
      calc,
      rationale: null,
      rationaleStale: false,
      rationaleFailed: false,
      snapshot,
      items,
      carbsG, fatG, proteinG,
      logAlsoStatedDose: ex.userStatedDoseUnits !== null,
      aiMeta: { model: extraction.model, provider: extraction.provider, latencyMs: extraction.latencyMs },
      answer: null,
    };

    // Render numbers immediately; the rationale streams in afterwards.
    set({ phase: 'explaining', card });

    if (ex.intent === 'question' && ex.question) {
      const qa = await callAi('rationale', {
        qa: true,
        question: ex.question,
        note: 'Plain Q&A: answer in at most 4 sentences. Do not include insulin dose numbers.',
      });
      set((st) => st.card === card
        ? { phase: 'ready', card: { ...card, answer: qa.ok ? qa.data.rationale : 'Answer unavailable.' } }
        : {});
      return;
    }

    const rationale = await fetchRationale(card);
    set((st) => {
      if (st.card !== card) return {}; // user moved on
      return {
        phase: 'ready',
        card: { ...card, rationale, rationaleFailed: rationale === null },
      };
    });
  },

  submitManual: async ({ carbsG, bgOverride, unitsTaken, note }) => {
    const snapshot = await takeSnapshot(get().latestBg);
    if (bgOverride !== undefined) {
      snapshot.bg = { mgdl: bgOverride, ts: new Date().toISOString(), trend: 'NONE', source: 'manual' };
      snapshot.bgAgeMin = 0;
      snapshot.bgOverridden = true;
    }
    const extraction: ExtractionResult | null = null;
    const base = { snapshot, carbsG, fatG: 0, proteinG: 0, extraction };
    const calc = runCalc(base);
    const card: CardState = {
      inputText: note ?? '',
      inputMode: 'manual',
      extraction,
      calc,
      rationale: null,
      rationaleStale: false,
      rationaleFailed: false,
      snapshot,
      items: carbsG > 0 ? [{ name: 'Manual entry', carbsG, fatG: 0, proteinG: 0, confidence: 1 }] : [],
      carbsG, fatG: 0, proteinG: 0,
      logAlsoStatedDose: unitsTaken !== undefined && unitsTaken > 0,
      aiMeta: { model: 'none', provider: 'none', latencyMs: 0 },
      answer: null,
    };
    if (unitsTaken !== undefined && unitsTaken > 0) {
      card.extraction = {
        intent: 'meal', meal: null, contextTags: [], userStatedBg: bgOverride ?? null,
        userStatedDoseUnits: unitsTaken, question: null, confidence: 1, clarificationNeeded: null,
      };
    }
    set({ phase: 'ready', card, manualSheetOpen: false, manualPrefillNote: '' });
  },

  editCarbs: (itemIndex, newCarbs) => {
    const card = get().card;
    if (!card) return;
    let items = card.items;
    let carbsG: number;
    if (itemIndex === null) {
      carbsG = newCarbs;
    } else {
      items = items.map((it, i) => (i === itemIndex ? { ...it, carbsG: newCarbs } : it));
      carbsG = items.reduce((sum, it) => sum + it.carbsG, 0);
    }
    const next: CardState = { ...card, items, carbsG };
    next.calc = runCalc(next);
    next.rationaleStale = next.rationale !== null;
    set({ card: next });
  },

  setLogAlsoStatedDose: (v) => {
    const card = get().card;
    if (card) set({ card: { ...card, logAlsoStatedDose: v } });
  },

  refreshRationale: async () => {
    const card = get().card;
    if (!card) return;
    set({ card: { ...card, rationale: null, rationaleStale: false, rationaleFailed: false } });
    const rationale = await fetchRationale(card);
    const cur = get().card;
    if (cur && cur.inputText === card.inputText) {
      set({ card: { ...cur, rationale, rationaleFailed: rationale === null, rationaleStale: false } });
    }
  },

  requestLog: (actualUnits) => {
    const card = get().card;
    if (!card) return;
    const s = getSettings();
    const reasons: string[] = [];
    if (card.calc.flags.includes('large_dose') || actualUnits > s.largeDoseWarn) {
      reasons.push(
        `Suggested ${card.calc.units} U exceeds your typical max (${s.largeDoseWarn} U). Parsed carbs: ${card.carbsG} g — verify.`,
      );
    }
    if (card.calc.flags.includes('high_carb_parse')) {
      reasons.push(`Parsed carbs ${card.carbsG} g exceed your warning threshold (${s.highCarbWarn} g) — likely parse error, verify.`);
    }
    if (reasons.length > 0) {
      set({ confirmSheet: { reasons } });
    } else {
      void get().confirmLog(actualUnits);
    }
  },

  cancelConfirm: () => set({ confirmSheet: null }),

  confirmLog: async (actualUnits) => {
    const card = get().card;
    if (!card) return;
    set({ phase: 'logging', confirmSheet: null });

    const aiInteractionId = crypto.randomUUID();
    const events: { type: 'meal' | 'bolus' | 'ai_interaction'; payload: unknown }[] = [];
    const isMeal = card.carbsG > 0;

    if (isMeal) {
      events.push({
        type: 'meal',
        payload: {
          items: card.items,
          carbsG: card.carbsG, fatG: card.fatG, proteinG: card.proteinG,
          source: card.inputMode === 'manual' ? 'manual' : 'ai',
          rawText: card.inputText || undefined,
          aiInteractionId,
        } satisfies MealPayload,
      });
    }

    const kind: BolusPayload['kind'] = isMeal
      ? (card.calc.correction > 0 ? 'mixed' : 'meal')
      : 'correction';

    if (actualUnits > 0) {
      events.push({
        type: 'bolus',
        payload: {
          units: actualUnits,
          suggestedUnits: card.calc.units,
          kind,
          aiInteractionId,
        } satisfies BolusPayload,
      });
    }

    // Pre-logged bolus the user mentioned ("took 3 units already").
    const stated = card.extraction?.userStatedDoseUnits;
    if (card.logAlsoStatedDose && stated && stated > 0) {
      events.push({
        type: 'bolus',
        payload: { units: stated, kind, aiInteractionId } satisfies BolusPayload,
      });
    }

    const interaction: AiInteractionPayload = {
      inputText: card.inputText,
      inputMode: card.inputMode,
      extraction: card.extraction,
      calc: card.calc,
      ...(card.rationale ? { rationale: card.rationale } : {}),
      bgAtTime: card.snapshot.bg,
      model: card.aiMeta.model,
      provider: card.aiMeta.provider,
      latencyMs: card.aiMeta.latencyMs,
    };

    // One atomic Dexie transaction: meal + bolus(es) + ai_interaction. The
    // ai_interaction row reuses the pre-generated id the payloads reference.
    await appendEvents([
      ...events.map((e) => ({ type: e.type, payload: e.payload })),
      { type: 'ai_interaction' as const, payload: interaction, id: aiInteractionId },
    ]);

    get().toast(actualUnits > 0 ? `Logged ${actualUnits} U` : 'Logged');
    set({ phase: 'done', card: null, headerNonce: get().headerNonce + 1 });
  },

  dismissCard: () => set({ phase: 'idle', card: null, confirmSheet: null }),

  openManualSheet: (prefillNote = '') => set({ manualSheetOpen: true, manualPrefillNote: prefillNote }),
  closeManualSheet: () => set({ manualSheetOpen: false, manualPrefillNote: '' }),
}));
