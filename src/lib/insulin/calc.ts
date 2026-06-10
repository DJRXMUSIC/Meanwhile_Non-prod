import type { CalcFlag, Recommendation } from '../types';
import { buildBreakdown, trimNum } from './format';

export interface CalcSettings {
  targetBg: number;
  isf: number;
  icr: number;
  roundStep: number;
  lowThreshold: number;
  largeDoseWarn: number;
  highCarbWarn: number;
  staleMin: number;
  recentBolusMin: number;
}

export interface CalcInput {
  bg: number | null;
  bgAgeMin: number | null;
  trend: string;
  carbsG: number;
  fatG: number;
  proteinG: number;
  iobUnits: number;
  cobG: number;
  recentBolusMinAgo?: number | null;
  extractionConfidence?: number | null;
  clarificationNeeded?: boolean;
  settings: CalcSettings;
}

const FALLING = new Set(['FortyFiveDown', 'SingleDown', 'DoubleDown']);

function ceilTo5(n: number): number {
  return Math.ceil(n / 5) * 5;
}

export function calcRecommendation(input: CalcInput): Recommendation {
  const s = input.settings;
  const { bg, carbsG, iobUnits: iob, cobG: cob } = input;

  const carbBolus = carbsG > 0 ? carbsG / s.icr : 0;
  const correction = bg !== null ? (bg - s.targetBg) / s.isf : 0;
  const rawUnits = carbBolus + correction - iob;
  // +1e-9 guards against FP artifacts like 13.0999999... flooring to 12.
  const units = Math.max(0, Math.floor(rawUnits / s.roundStep + 1e-9) * s.roundStep);

  const flags: CalcFlag[] = [];
  if (input.bgAgeMin !== null && input.bgAgeMin > s.staleMin) flags.push('stale_bg');
  if (bg !== null && bg < s.lowThreshold) flags.push('low_bg');
  if (units > s.largeDoseWarn) flags.push('large_dose');
  if (carbsG > s.highCarbWarn) flags.push('high_carb_parse');
  if (input.extractionConfidence != null && input.extractionConfidence < 0.5) flags.push('low_confidence');
  if (
    input.recentBolusMinAgo != null &&
    input.recentBolusMinAgo >= 0 &&
    input.recentBolusMinAgo < s.recentBolusMin
  ) {
    flags.push('recent_bolus');
  }
  if (cob > 0 && FALLING.has(input.trend)) flags.push('active_cob_falling');
  if (input.clarificationNeeded) flags.push('clarification');

  let rescueCarbsG: number | undefined;
  if (bg !== null && (bg < s.lowThreshold || (bg < s.targetBg && rawUnits <= 0))) {
    rescueCarbsG = Math.max(15, ceilTo5(((s.targetBg - bg) * s.icr) / s.isf));
  }

  let headline: string;
  const mealPresent = carbsG > 0;
  if (bg === null) {
    headline = 'Enter a BG reading first';
  } else if (bg < s.lowThreshold) {
    headline = `Treat the low first: ${rescueCarbsG} g fast carbs`;
  } else if (rawUnits <= 0 && mealPresent) {
    headline = 'No insulin needed — IOB covers this';
  } else if (rawUnits <= 0) {
    headline = 'No correction needed';
  } else {
    headline = `Take ${trimNum(units)} U now`;
  }

  const breakdown = buildBreakdown({
    carbsG,
    icr: s.icr,
    bg,
    targetBg: s.targetBg,
    isf: s.isf,
    iob,
    rawUnits,
    units,
    roundStep: s.roundStep,
  });

  return {
    headline,
    units,
    rawUnits,
    carbBolus,
    correction,
    iob,
    cob,
    ...(rescueCarbsG !== undefined ? { rescueCarbsG } : {}),
    flags,
    breakdown,
    inputsEcho: {
      bg,
      bgAgeMin: input.bgAgeMin,
      trend: input.trend,
      carbsG,
      fatG: input.fatG,
      proteinG: input.proteinG,
      targetBg: s.targetBg,
      isf: s.isf,
      icr: s.icr,
    },
  };
}
