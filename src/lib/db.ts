import Dexie, { type Table } from 'dexie';
import type { AppEvent, BgReading } from './types';

export class HarnessDb extends Dexie {
  events!: Table<AppEvent, string>;
  meta!: Table<{ key: string; value: unknown }, string>;   // syncCursor, deviceId, settingsUpdatedAt
  bgcache!: Table<BgReading, string>;                       // local only, pruned > 7 days
  constructor() {
    super('t1d-harness');
    this.version(1).stores({
      events: 'id, ts, type, synced, [type+ts]',
      meta: 'key',
      bgcache: 'ts',
    });
  }
}

export const db = new HarnessDb();
