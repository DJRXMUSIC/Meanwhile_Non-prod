import { describe, expect, it } from 'vitest';
import { iobFraction, cobRemaining } from '../curves';
import { calcRecommendation, type CalcInput, type CalcSettings } from '../calc';

const DEFAULTS: CalcSettings = {
  targetBg: 110,
  isf: 40,
  icr: 10,
  roundStep: 0.5,
  lowThreshold: 70,
  largeDoseWarn: 15,
  highCarbWarn: 200,
  staleMin: 15,
  recentBolusMin: 15,
};

function input(partial: Partial<CalcInput>): CalcInput {
  return {
    bg: null,
    bgAgeMin: 2,
    trend: 'Flat',
    carbsG: 0,
    fatG: 0,
    proteinG: 0,
    iobUnits: 0,
    cobG: 0,
    settings: DEFAULTS,
    ...partial,
  };
}

describe('calcRecommendation', () => {
  it('vector 1: bg 180, carbs 60, iob 1.2', () => {
    const r = calcRecommendation(input({ bg: 180, carbsG: 60, iobUnits: 1.2 }));
    expect(r.carbBolus).toBeCloseTo(6.0, 5);
    expect(r.correction).toBeCloseTo(1.75, 5);
    expect(r.rawUnits).toBeCloseTo(6.55, 5);
    expect(r.units).toBe(6.5);
    expect(r.headline).toBe('Take 6.5 U now');
  });

  it('vector 2: bg 95, carbs 30 floors down', () => {
    const r = calcRecommendation(input({ bg: 95, carbsG: 30 }));
    expect(r.correction).toBeCloseTo(-0.375, 5);
    expect(r.rawUnits).toBeCloseTo(2.625, 5);
    expect(r.units).toBe(2.5);
  });

  it('vector 3: bg 60 is treat-low-first', () => {
    const r = calcRecommendation(input({ bg: 60 }));
    expect(r.flags).toContain('low_bg');
    expect(r.rescueCarbsG).toBe(15);
    expect(r.units).toBe(0);
    expect(r.headline).toBe('Treat the low first: 15 g fast carbs');
  });

  it('vector 4: bg 250, no carbs, iob 2.0 correction', () => {
    const r = calcRecommendation(input({ bg: 250, iobUnits: 2.0 }));
    expect(r.rawUnits).toBeCloseTo(1.5, 5);
    expect(r.units).toBe(1.5);
    expect(r.headline).toBe('Take 1.5 U now');
  });

  it('vector 5: IOB covers the meal', () => {
    const r = calcRecommendation(input({ bg: 120, carbsG: 20, iobUnits: 3.0 }));
    expect(r.rawUnits).toBeCloseTo(-0.75, 5);
    expect(r.units).toBe(0);
    expect(r.headline).toBe('No insulin needed — IOB covers this');
  });

  it('vector 8: breakdown strings match character-for-character', () => {
    const r = calcRecommendation(input({ bg: 180, carbsG: 60, iobUnits: 1.2 }));
    expect(r.breakdown).toEqual([
      'Carbs: 60 g ÷ 10 g/U = 6.00 U',
      'Correction: (180 − 110) ÷ 40 = +1.75 U',
      'Insulin on board: −1.20 U',
      'Subtotal: 6.55 U',
      'Rounded down to 0.5 U step → 6.5 U',
    ]);
  });

  it('vector 9: large dose flag with icr 3', () => {
    const r = calcRecommendation(
      input({ bg: 180, carbsG: 60, iobUnits: 1.2, settings: { ...DEFAULTS, icr: 3 } }),
    );
    expect(r.units).toBeGreaterThan(15);
    expect(r.flags).toContain('large_dose');
  });

  it('vector 10: stale BG flag at age 20 min', () => {
    const r = calcRecommendation(input({ bg: 140, bgAgeMin: 20 }));
    expect(r.flags).toContain('stale_bg');
  });

  it('no BG → enter a reading first', () => {
    const r = calcRecommendation(input({ bg: null, bgAgeMin: null, carbsG: 30 }));
    expect(r.headline).toBe('Enter a BG reading first');
  });

  it('no correction needed when at target with no meal', () => {
    const r = calcRecommendation(input({ bg: 110 }));
    expect(r.headline).toBe('No correction needed');
  });

  it('recent bolus flag inside the window only', () => {
    const inWindow = calcRecommendation(input({ bg: 140, recentBolusMinAgo: 5 }));
    expect(inWindow.flags).toContain('recent_bolus');
    const outside = calcRecommendation(input({ bg: 140, recentBolusMinAgo: 30 }));
    expect(outside.flags).not.toContain('recent_bolus');
  });

  it('active COB while falling raises the flag', () => {
    const r = calcRecommendation(input({ bg: 140, cobG: 20, trend: 'SingleDown' }));
    expect(r.flags).toContain('active_cob_falling');
  });
});

describe('iobFraction (vector 6, td=300 tp=75)', () => {
  it('boundary values', () => {
    expect(iobFraction(0, 300, 75)).toBe(1);
    expect(iobFraction(300, 300, 75)).toBe(0);
    expect(iobFraction(-5, 300, 75)).toBe(1);
    expect(iobFraction(400, 300, 75)).toBe(0);
  });

  it('reference points within ±0.02', () => {
    expect(Math.abs(iobFraction(75, 300, 75) - 0.67)).toBeLessThanOrEqual(0.02);
    expect(Math.abs(iobFraction(150, 300, 75) - 0.27)).toBeLessThanOrEqual(0.02);
  });

  it('strictly decreasing on (0, 300) and within [0, 1]', () => {
    let prev = iobFraction(1, 300, 75);
    for (let t = 2; t < 300; t++) {
      const cur = iobFraction(t, 300, 75);
      expect(cur).toBeLessThan(prev);
      expect(cur).toBeGreaterThanOrEqual(0);
      expect(cur).toBeLessThanOrEqual(1);
      prev = cur;
    }
  });
});

describe('cobRemaining (vector 7, delay=10 duration=180)', () => {
  it('linear absorption with delay', () => {
    expect(cobRemaining(60, 0, 10, 180)).toBe(60);
    expect(cobRemaining(60, 10, 10, 180)).toBe(60);
    expect(cobRemaining(60, 100, 10, 180)).toBe(30);
    expect(cobRemaining(60, 190, 10, 180)).toBe(0);
  });
});
