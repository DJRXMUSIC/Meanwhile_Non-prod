import { db } from '../db';
import type { AppEvent, BgManualPayload, BgReading } from '../types';
import type { BgSource } from './index';

export class ManualSource implements BgSource {
  label = 'Manual entry';

  async fetchLatest(count = 24): Promise<BgReading[]> {
    const rows = (await db.events
      .where('[type+ts]')
      .between(['bg_manual', ''], ['bg_manual', '￿'])
      .reverse()
      .limit(count)
      .toArray()) as AppEvent<BgManualPayload>[];
    return rows.map((e) => ({
      mgdl: e.payload.mgdl,
      ts: e.ts,
      trend: 'NONE' as const,
      source: 'manual' as const,
    }));
  }
}
