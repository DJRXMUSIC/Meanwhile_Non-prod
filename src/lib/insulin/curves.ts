// Pure insulin/carb curve math. No IO.

/**
 * Exponential IOB model (Loop/oref0 standard).
 * Fraction of a bolus still active at age t minutes, for duration td and peak tp.
 */
export function iobFraction(t: number, td: number, tp: number): number {
  if (t <= 0) return 1;
  if (t >= td) return 0;
  const tau = (tp * (1 - tp / td)) / (1 - (2 * tp) / td);
  const a = (2 * tau) / td;
  const S = 1 / (1 - a + (1 + a) * Math.exp(-td / tau));
  const frac =
    1 -
    S *
      (1 - a) *
      (((t * t) / (tau * td * (1 - a)) - t / tau - 1) * Math.exp(-t / tau) + 1);
  return Math.min(1, Math.max(0, frac));
}

export interface BolusLike {
  units: number;
  ageMin: number;
}

export function iobUnits(boluses: BolusLike[], td: number, tp: number): number {
  return boluses.reduce((sum, b) => sum + b.units * iobFraction(b.ageMin, td, tp), 0);
}

/**
 * Delayed linear carb absorption. Grams remaining from a meal of `carbs` grams
 * eaten `t` minutes ago.
 */
export function cobRemaining(
  carbs: number,
  t: number,
  delayMin: number,
  durationMin: number,
): number {
  const progress = Math.min(1, Math.max(0, (t - delayMin) / durationMin));
  return carbs * (1 - progress);
}

export interface MealLike {
  carbsG: number;
  ageMin: number;
}

export function cobTotal(meals: MealLike[], delayMin: number, durationMin: number): number {
  return meals.reduce((sum, m) => sum + cobRemaining(m.carbsG, m.ageMin, delayMin, durationMin), 0);
}
