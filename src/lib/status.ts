import { latestBoluses, latestMeals } from './repo';
import { getSettings } from './settings';
import { cobTotal, iobUnits } from './insulin/curves';
import type { AppEvent, BolusPayload, MealPayload } from './types';

export async function currentIob(now = Date.now()): Promise<number> {
  const s = getSettings();
  const boluses = (await latestBoluses(s.diaMin / 60)) as AppEvent<BolusPayload>[];
  return iobUnits(
    boluses.map((b) => ({
      units: b.payload.units,
      ageMin: (now - new Date(b.ts).getTime()) / 60_000,
    })),
    s.diaMin,
    s.peakMin,
  );
}

export async function currentCob(now = Date.now()): Promise<number> {
  const s = getSettings();
  const windowH = (s.cobDelayMin + s.cobDurationMin) / 60;
  const meals = (await latestMeals(windowH)) as AppEvent<MealPayload>[];
  return cobTotal(
    meals.map((m) => ({
      carbsG: m.payload.carbsG,
      ageMin: (now - new Date(m.ts).getTime()) / 60_000,
    })),
    s.cobDelayMin,
    s.cobDurationMin,
  );
}

export async function minutesSinceLastBolus(now = Date.now()): Promise<number | null> {
  const boluses = await latestBoluses(24);
  if (boluses.length === 0) return null;
  return (now - new Date(boluses[0].ts).getTime()) / 60_000;
}
