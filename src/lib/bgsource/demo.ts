import type { BgReading, Trend } from '../types';
import type { BgSource } from './index';

// Deterministic sine-wave generator, 5-min cadence: mgdl = 130 + 45*sin(epochMin/30) + noise(±5).
// Lets the entire pipeline run on desktop with no phone.

function valueAt(epochMin: number): number {
  // Deterministic "noise" so repeated fetches agree on historical points.
  const noise = 5 * Math.sin(epochMin * 7.13) * Math.cos(epochMin * 3.77);
  return Math.round(130 + 45 * Math.sin(epochMin / 30) + noise);
}

function trendFromSlope(delta: number): Trend {
  if (delta > 10) return 'DoubleUp';
  if (delta > 5) return 'SingleUp';
  if (delta > 2) return 'FortyFiveUp';
  if (delta < -10) return 'DoubleDown';
  if (delta < -5) return 'SingleDown';
  if (delta < -2) return 'FortyFiveDown';
  return 'Flat';
}

export class DemoSource implements BgSource {
  label = 'Demo generator';

  async fetchLatest(count = 24): Promise<BgReading[]> {
    const now = Date.now();
    const slot = Math.floor(now / 300_000) * 300_000; // snap to 5-min cadence
    const readings: BgReading[] = [];
    for (let i = 0; i < count; i++) {
      const ts = slot - i * 300_000;
      const epochMin = ts / 60_000;
      const mgdl = valueAt(epochMin);
      const prev = valueAt(epochMin - 5);
      readings.push({
        mgdl,
        ts: new Date(ts).toISOString(),
        trend: trendFromSlope(mgdl - prev),
        delta: mgdl - prev,
        source: 'demo',
      });
    }
    return readings;
  }
}
