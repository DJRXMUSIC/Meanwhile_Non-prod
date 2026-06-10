import { db } from './db';
import type { AppEvent, EventType } from './types';

let deviceIdCache: string | null = null;

export async function getDeviceId(): Promise<string> {
  if (deviceIdCache) return deviceIdCache;
  const row = await db.meta.get('deviceId');
  if (row && typeof row.value === 'string') {
    deviceIdCache = row.value;
    return deviceIdCache;
  }
  const id = crypto.randomUUID();
  await db.meta.put({ key: 'deviceId', value: id });
  deviceIdCache = id;
  return id;
}

type SyncTrigger = () => void;
let onAppend: SyncTrigger | null = null;
/** sync.ts registers its push here to avoid a circular import. */
export function registerAppendHook(fn: SyncTrigger) {
  onAppend = fn;
}

export async function appendEvent<P>(
  type: EventType,
  payload: P,
  ts?: string,
  supersedesId?: string,
): Promise<AppEvent<P>> {
  const event: AppEvent<P> = {
    id: crypto.randomUUID(),
    type,
    ts: ts ?? new Date().toISOString(),
    payload,
    ...(supersedesId ? { supersedesId } : {}),
    deviceId: await getDeviceId(),
    synced: 0,
    createdAt: new Date().toISOString(),
  };
  await db.events.put(event as AppEvent);
  onAppend?.(); // fire-and-forget sync push
  return event;
}

/** Append several events in one Dexie transaction (meal + bolus + ai_interaction). */
export async function appendEvents(
  items: { type: EventType; payload: unknown; ts?: string; supersedesId?: string; id?: string }[],
): Promise<AppEvent[]> {
  const deviceId = await getDeviceId();
  const now = new Date().toISOString();
  const rows: AppEvent[] = items.map((it) => ({
    id: it.id ?? crypto.randomUUID(),
    type: it.type,
    ts: it.ts ?? now,
    payload: it.payload,
    ...(it.supersedesId ? { supersedesId: it.supersedesId } : {}),
    deviceId,
    synced: 0,
    createdAt: now,
  }));
  await db.transaction('rw', db.events, async () => {
    await db.events.bulkPut(rows);
  });
  onAppend?.();
  return rows;
}

const supersededCache = async (): Promise<Set<string>> => {
  const all = await db.events.toArray();
  return new Set(all.filter((e) => e.supersedesId).map((e) => e.supersedesId!));
};

export async function eventsBetween(fromIso: string, toIso: string): Promise<AppEvent[]> {
  return db.events.where('ts').between(fromIso, toIso, true, true).toArray();
}

export async function latestOfType(type: EventType, hours: number): Promise<AppEvent[]> {
  const from = new Date(Date.now() - hours * 3600_000).toISOString();
  const rows = await db.events
    .where('[type+ts]')
    .between([type, from], [type, '￿'])
    .toArray();
  const superseded = await supersededCache();
  return rows.filter((e) => !superseded.has(e.id)).sort((a, b) => b.ts.localeCompare(a.ts));
}

export async function latestBoluses(hours: number): Promise<AppEvent[]> {
  return latestOfType('bolus', hours);
}

export async function latestMeals(hours: number): Promise<AppEvent[]> {
  return latestOfType('meal', hours);
}

export async function supersede(oldId: string, type: EventType, payload: unknown): Promise<AppEvent> {
  const old = await db.events.get(oldId);
  return appendEvent(type, payload, old?.ts, oldId);
}
