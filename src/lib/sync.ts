import { z } from 'zod';
import { db } from './db';
import { registerAppendHook } from './repo';
import {
  setSettingsLocal,
  SyncedSettingsSchema,
  type SyncedSettings,
} from './settings';
import { getSupabase, supabaseConfigured } from './supabase';
import type { AppEvent, EventType } from './types';

const BATCH = 100;
const PULL_LIMIT = 500;
const PERIODIC_MS = 5 * 60_000;

const EVENT_TYPES: EventType[] = [
  'meal', 'bolus', 'bg_manual', 'context', 'ai_interaction', 'outcome', 'settings_change', 'note',
];

const RemoteEventSchema = z.object({
  id: z.string().uuid(),
  type: z.enum(EVENT_TYPES as [EventType, ...EventType[]]),
  ts: z.string(),
  payload: z.unknown(),
  supersedes_id: z.string().nullable().optional(),
  device_id: z.string().nullable().optional(),
  created_at: z.string(),
});

export interface SyncStatus {
  lastCursor: string | null;
  pendingCount: number;
  lastError: string | null;
  syncing: boolean;
}

const status: SyncStatus = { lastCursor: null, pendingCount: 0, lastError: null, syncing: false };
const listeners = new Set<() => void>();
let statusSnapshot: SyncStatus = { ...status };

function notify() {
  statusSnapshot = { ...status };
  listeners.forEach((l) => l());
}

export function subscribeSyncStatus(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function getSyncStatus(): SyncStatus {
  return statusSnapshot;
}

async function getUserId(): Promise<string | null> {
  if (!supabaseConfigured()) return null;
  const { data } = await getSupabase().auth.getSession();
  return data.session?.user.id ?? null;
}

// camelCase <-> snake_case mapping happens only at this boundary.
function toRemote(e: AppEvent, userId: string) {
  return {
    id: e.id,
    user_id: userId,
    type: e.type,
    ts: e.ts,
    payload: e.payload ?? {},
    supersedes_id: e.supersedesId ?? null,
    device_id: e.deviceId,
    created_at: e.createdAt,
  };
}

export async function push(): Promise<void> {
  const userId = await getUserId();
  if (!userId) return;
  const pending = await db.events.where('synced').equals(0).toArray();
  status.pendingCount = pending.length;
  notify();
  if (pending.length === 0) return;
  const supabase = getSupabase();
  for (let i = 0; i < pending.length; i += BATCH) {
    const batch = pending.slice(i, i + BATCH);
    const { error } = await supabase
      .from('events')
      .upsert(batch.map((e) => toRemote(e, userId)), { onConflict: 'id', ignoreDuplicates: true });
    if (error) throw new Error(error.message);
    await db.events.bulkPut(batch.map((e) => ({ ...e, synced: 1 as const })));
  }
  status.pendingCount = 0;
  notify();
}

export async function pull(): Promise<void> {
  const userId = await getUserId();
  if (!userId) return;
  const supabase = getSupabase();
  const cursorRow = await db.meta.get('syncCursor');
  let cursor = typeof cursorRow?.value === 'string' ? cursorRow.value : '1970-01-01T00:00:00Z';

  for (;;) {
    const { data, error } = await supabase
      .from('events')
      .select('*')
      .gt('created_at', cursor)
      .order('created_at', { ascending: true })
      .limit(PULL_LIMIT);
    if (error) throw new Error(error.message);
    if (!data || data.length === 0) break;

    const rows: AppEvent[] = [];
    for (const raw of data) {
      const parsed = RemoteEventSchema.safeParse(raw);
      if (!parsed.success) continue; // malformed rows degrade gracefully
      const r = parsed.data;
      rows.push({
        id: r.id,
        type: r.type,
        ts: r.ts,
        payload: r.payload,
        ...(r.supersedes_id ? { supersedesId: r.supersedes_id } : {}),
        deviceId: r.device_id ?? 'unknown',
        synced: 1,
        createdAt: r.created_at,
      });
    }
    await db.events.bulkPut(rows);
    cursor = String((data[data.length - 1] as { created_at: string }).created_at);
    await db.meta.put({ key: 'syncCursor', value: cursor });
    status.lastCursor = cursor;
    notify();
    if (data.length < PULL_LIMIT) break;
  }
}

// ---- Settings sync (last-write-wins on updated_at) ----

export async function pullSettings(): Promise<void> {
  const userId = await getUserId();
  if (!userId) return;
  const supabase = getSupabase();
  const { data, error } = await supabase.from('settings').select('*').eq('user_id', userId).maybeSingle();
  if (error) throw new Error(error.message);
  if (!data) return;
  const localStampRow = await db.meta.get('settingsUpdatedAt');
  const localStamp = typeof localStampRow?.value === 'string' ? localStampRow.value : '';
  const remoteStamp = String(data.updated_at);
  if (remoteStamp > localStamp) {
    const parsed = SyncedSettingsSchema.safeParse(data.payload);
    if (parsed.success) {
      setSettingsLocal(parsed.data);
      await db.meta.put({ key: 'settingsUpdatedAt', value: remoteStamp });
    }
  }
}

export async function pushSettings(settings: SyncedSettings): Promise<void> {
  const userId = await getUserId();
  if (!userId) return;
  const stamp = new Date().toISOString();
  const { error } = await getSupabase()
    .from('settings')
    .upsert({ user_id: userId, payload: settings, updated_at: stamp });
  if (error) throw new Error(error.message);
  await db.meta.put({ key: 'settingsUpdatedAt', value: stamp });
}

// ---- Suggestions table ----

export async function insertSuggestions(payloads: unknown[]): Promise<void> {
  const userId = await getUserId();
  if (!userId || payloads.length === 0) return;
  const { error } = await getSupabase().from('suggestions').insert(
    payloads.map((p) => ({ id: crypto.randomUUID(), user_id: userId, payload: p, status: 'pending' })),
  );
  if (error) throw new Error(error.message);
}

export async function listSuggestions(): Promise<
  { id: string; payload: unknown; status: string; created_at: string }[]
> {
  const userId = await getUserId();
  if (!userId) return [];
  const { data, error } = await getSupabase()
    .from('suggestions')
    .select('*')
    .order('created_at', { ascending: false });
  if (error) throw new Error(error.message);
  return data ?? [];
}

export async function resolveSuggestion(id: string, statusValue: 'accepted' | 'dismissed'): Promise<void> {
  const { error } = await getSupabase()
    .from('suggestions')
    .update({ status: statusValue, resolved_at: new Date().toISOString() })
    .eq('id', id);
  if (error) throw new Error(error.message);
}

// ---- Orchestration ----

let inFlight = false;

export async function syncNow(): Promise<void> {
  if (inFlight || !supabaseConfigured()) return;
  inFlight = true;
  status.syncing = true;
  notify();
  try {
    await push();
    await pull();
    await pullSettings();
    status.lastError = null;
  } catch (e) {
    // Failures queue quietly — surfaced only in the Settings sync badge.
    status.lastError = e instanceof Error ? e.message : 'Sync failed';
  } finally {
    inFlight = false;
    status.syncing = false;
    notify();
  }
}

export function startSyncLoop() {
  registerAppendHook(() => void syncNow());
  void syncNow();
  setInterval(() => {
    if (document.visibilityState === 'visible') void syncNow();
  }, PERIODIC_MS);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void syncNow();
  });
}
