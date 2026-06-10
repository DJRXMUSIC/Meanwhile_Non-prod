import { z } from 'zod';
import type { BgReading, Trend } from '../types';
import type { BgSource } from './index';

const SgvRowSchema = z.object({
  sgv: z.number(),
  date: z.number(),
  direction: z.string().optional(),
  delta: z.number().optional(),
});

const TRENDS = new Set<string>([
  'DoubleUp', 'SingleUp', 'FortyFiveUp', 'Flat', 'FortyFiveDown', 'SingleDown', 'DoubleDown',
]);

export function mapTrend(direction: string | undefined): Trend {
  return direction && TRENDS.has(direction) ? (direction as Trend) : 'NONE';
}

export function parseSgvRows(data: unknown, source: BgReading['source']): BgReading[] {
  if (!Array.isArray(data)) return [];
  const readings: BgReading[] = [];
  for (const row of data) {
    const parsed = SgvRowSchema.safeParse(row);
    if (!parsed.success) continue; // drop malformed rows, never crash the calc path
    readings.push({
      mgdl: parsed.data.sgv,
      ts: new Date(parsed.data.date).toISOString(),
      trend: mapTrend(parsed.data.direction),
      ...(parsed.data.delta !== undefined ? { delta: parsed.data.delta } : {}),
      source,
    });
  }
  readings.sort((a, b) => b.ts.localeCompare(a.ts));
  return readings;
}

async function sha1Hex(text: string): Promise<string> {
  // SHA-1 is xDrip's protocol requirement for the api-secret header, not a security choice.
  const buf = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export class XdripSource implements BgSource {
  label = 'xDrip+';
  constructor(
    private url: string,
    private apiSecret?: string,
  ) {}

  async fetchLatest(count = 24): Promise<BgReading[]> {
    const headers: Record<string, string> = {};
    if (this.apiSecret) headers['api-secret'] = await sha1Hex(this.apiSecret);
    const res = await fetch(`${this.url.replace(/\/$/, '')}/sgv.json?count=${count}`, { headers });
    if (!res.ok) throw new Error(`xDrip+ responded ${res.status}`);
    return parseSgvRows(await res.json(), 'xdrip');
  }
}
