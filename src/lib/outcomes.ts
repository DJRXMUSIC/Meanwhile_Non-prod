import { db } from './db';
import { appendEvent } from './repo';
import { getDeviceConfig, getSettings } from './settings';
import { createBgSource } from './bgsource/index';
import { cacheReadings } from './bgsource/useBg';
import type { AppEvent, BgReading, OutcomePayload } from './types';

const RUN_CAP = 25;
const FOUR_H = 4 * 3600_000;
const TOLERANCE_MS = 10 * 60_000;
const PERIOD_MS = 30 * 60_000;

function nearestWithin(readings: BgReading[], targetMs: number): number | undefined {
  let best: BgReading | undefined;
  let bestDist = Infinity;
  for (const r of readings) {
    const dist = Math.abs(new Date(r.ts).getTime() - targetMs);
    if (dist <= TOLERANCE_MS && dist < bestDist) {
      best = r;
      bestDist = dist;
    }
  }
  return best?.mgdl;
}

export async function runOutcomeWorker(): Promise<number> {
  const now = Date.now();
  const cutoff = new Date(now - FOUR_H).toISOString();

  const candidates = (await db.events
    .where('type')
    .anyOf(['bolus', 'meal'])
    .toArray())
    .filter((e) => e.ts < cutoff)
    .sort((a, b) => b.ts.localeCompare(a.ts));

  const outcomes = (await db.events.where('type').equals('outcome').toArray()) as AppEvent<OutcomePayload>[];
  const covered = new Set(outcomes.map((o) => o.payload.refId));
  const pending = candidates.filter((e) => !covered.has(e.id)).slice(0, RUN_CAP);
  if (pending.length === 0) return 0;

  // ~48h of history at 5-min cadence from the active source; cache for reuse.
  let readings: BgReading[] = [];
  try {
    readings = await createBgSource(getDeviceConfig()).fetchLatest(576);
    await cacheReadings(readings);
  } catch {
    readings = await db.bgcache.orderBy('ts').toArray();
  }
  const oldestMs = readings.length > 0
    ? Math.min(...readings.map((r) => new Date(r.ts).getTime()))
    : Infinity;
  const lowThreshold = getSettings().lowThreshold;

  let produced = 0;
  for (const event of pending) {
    const t0 = new Date(event.ts).getTime();
    if (t0 < oldestMs) {
      await appendEvent<OutcomePayload>('outcome', {
        refId: event.id,
        hypo4h: false,
        dataQuality: 'unavailable',
      });
      produced++;
      continue;
    }

    const window = readings.filter((r) => {
      const ms = new Date(r.ts).getTime();
      return ms > t0 && ms <= t0 + FOUR_H;
    });
    const points = {
      bgT60: nearestWithin(window, t0 + 60 * 60_000),
      bgT120: nearestWithin(window, t0 + 120 * 60_000),
      bgT180: nearestWithin(window, t0 + 180 * 60_000),
      bgT240: nearestWithin(window, t0 + 240 * 60_000),
    };
    const values = window.map((r) => r.mgdl);
    const hit = Object.values(points).filter((v) => v !== undefined).length;
    const quality: OutcomePayload['dataQuality'] = hit === 4 ? 'full' : hit >= 2 ? 'partial' : 'unavailable';

    await appendEvent<OutcomePayload>('outcome', {
      refId: event.id,
      ...(points.bgT60 !== undefined ? { bgT60: points.bgT60 } : {}),
      ...(points.bgT120 !== undefined ? { bgT120: points.bgT120 } : {}),
      ...(points.bgT180 !== undefined ? { bgT180: points.bgT180 } : {}),
      ...(points.bgT240 !== undefined ? { bgT240: points.bgT240 } : {}),
      ...(values.length > 0 ? { min4h: Math.min(...values), max4h: Math.max(...values) } : {}),
      hypo4h: values.some((v) => v < lowThreshold),
      dataQuality: quality,
    });
    produced++;
  }
  return produced;
}

export function startOutcomeWorker() {
  void runOutcomeWorker();
  setInterval(() => {
    if (document.visibilityState === 'visible') void runOutcomeWorker();
  }, PERIOD_MS);
}
