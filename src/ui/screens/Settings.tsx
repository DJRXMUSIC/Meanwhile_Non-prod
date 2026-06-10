import { useState, useSyncExternalStore } from 'react';
import {
  DEFAULT_DEVICE_CONFIG, SETTING_META, getSettings, setDeviceConfig, setSettingsLocal,
  useDeviceConfig, useSettings, type SyncedSettings,
} from '../../lib/settings';
import { appendEvent } from '../../lib/repo';
import { pushSettings, getSyncStatus, subscribeSyncStatus, syncNow } from '../../lib/sync';
import { getSupabase, supabaseConfigured } from '../../lib/supabase';
import { createBgSource } from '../../lib/bgsource/index';
import { db } from '../../lib/db';
import { useStore } from '../../state/useStore';
import type { SettingsChangePayload } from '../../lib/types';

const NUMERIC_KEYS: (keyof SyncedSettings)[] = [
  'targetBg', 'isf', 'icr', 'diaMin', 'peakMin', 'roundStep', 'lowThreshold', 'cobDurationMin', 'cobDelayMin',
];
const CONFIRM_KEYS: (keyof SyncedSettings)[] = ['largeDoseWarn', 'highCarbWarn', 'staleMin', 'recentBolusMin'];

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-6">
      <h3 className="text-sm font-semibold uppercase tracking-wide text-muted">{title}</h3>
      <div className="mt-2 space-y-2">{children}</div>
    </section>
  );
}

function NumberRow({ k }: { k: keyof SyncedSettings }) {
  const settings = useSettings();
  const meta = SETTING_META[k];
  const value = settings[k] as number;

  const update = async (next: number) => {
    if (!Number.isFinite(next) || next <= 0) return;
    const from = getSettings()[k];
    const merged = { ...getSettings(), [k]: next };
    setSettingsLocal(merged);
    await appendEvent<SettingsChangePayload>('settings_change', { key: k, from, to: next, source: 'manual' });
    try {
      await pushSettings(merged);
    } catch { /* will sync later */ }
  };

  const step = meta.step ?? 1;
  return (
    <div className="flex items-center justify-between rounded-xl bg-surface px-4 py-3">
      <div>
        <div className="text-sm">{meta.label}</div>
        {meta.unit && <div className="text-xs text-muted">{meta.unit}</div>}
      </div>
      <div className="flex items-center gap-2">
        <button onClick={() => void update(Number((value - step).toFixed(2)))} className="h-8 w-8 rounded-full bg-surface2 text-lg leading-none">−</button>
        <input
          type="number"
          inputMode="decimal"
          value={value}
          onChange={(e) => void update(Number(e.target.value))}
          className="w-16 rounded bg-surface2 px-1 py-1 text-center tabular-nums outline-none"
        />
        <button onClick={() => void update(Number((value + step).toFixed(2)))} className="h-8 w-8 rounded-full bg-surface2 text-lg leading-none">+</button>
      </div>
    </div>
  );
}

function AiSection() {
  const settings = useSettings();
  const update = async (patch: Partial<SyncedSettings>) => {
    const merged = { ...getSettings(), ...patch };
    setSettingsLocal(merged);
    for (const [key, to] of Object.entries(patch)) {
      await appendEvent<SettingsChangePayload>('settings_change', {
        key, from: getSettings()[key as keyof SyncedSettings], to, source: 'manual',
      });
    }
    try {
      await pushSettings(merged);
    } catch { /* later */ }
  };
  return (
    <Section title="AI">
      <div className="rounded-xl bg-surface px-4 py-3">
        <div className="text-sm">Provider</div>
        <div className="mt-2 flex gap-2">
          {(['anthropic', 'openai'] as const).map((p) => (
            <button
              key={p}
              onClick={() => void update({ aiProvider: p })}
              className={`rounded-full px-3 py-1.5 text-sm ${settings.aiProvider === p ? 'bg-accent text-black' : 'bg-surface2 text-muted'}`}
            >
              {p}
            </button>
          ))}
        </div>
      </div>
      <label className="block rounded-xl bg-surface px-4 py-3">
        <span className="text-sm">Extraction model</span>
        <input
          value={settings.extractModel}
          onChange={(e) => void update({ extractModel: e.target.value })}
          className="mt-1 w-full rounded bg-surface2 px-2 py-1.5 text-sm outline-none"
        />
      </label>
      <label className="block rounded-xl bg-surface px-4 py-3">
        <span className="text-sm">Rationale model</span>
        <input
          value={settings.rationaleModel}
          onChange={(e) => void update({ rationaleModel: e.target.value })}
          className="mt-1 w-full rounded bg-surface2 px-2 py-1.5 text-sm outline-none"
        />
      </label>
    </Section>
  );
}

function BgSourceSection() {
  const deviceConfig = useDeviceConfig();
  const toast = useStore((s) => s.toast);
  const [testing, setTesting] = useState(false);
  const cfg = deviceConfig.bgSource;

  const update = (patch: Partial<typeof cfg>) => {
    setDeviceConfig({ bgSource: { ...cfg, ...patch } });
  };

  const testConnection = async () => {
    setTesting(true);
    try {
      const readings = await createBgSource({ bgSource: cfg }).fetchLatest(1);
      if (readings.length > 0) {
        toast(`✓ Connected — latest BG ${readings[0].mgdl} mg/dL`);
      } else {
        toast('Connected, but no readings returned', 'warn');
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Connection failed', 'error');
    }
    setTesting(false);
  };

  return (
    <Section title="BG source (this device)">
      <div className="rounded-xl bg-surface px-4 py-3">
        <div className="flex flex-wrap gap-2">
          {(['xdrip', 'nightscout', 'manual', 'demo'] as const).map((t) => (
            <button
              key={t}
              onClick={() => update({ type: t, url: t === 'xdrip' ? 'http://127.0.0.1:17580' : cfg.url })}
              className={`rounded-full px-3 py-1.5 text-sm ${cfg.type === t ? 'bg-accent text-black' : 'bg-surface2 text-muted'}`}
            >
              {t}
            </button>
          ))}
        </div>
        {(cfg.type === 'xdrip' || cfg.type === 'nightscout') && (
          <>
            <label className="mt-3 block">
              <span className="text-xs text-muted">URL</span>
              <input
                value={cfg.url ?? ''}
                onChange={(e) => update({ url: e.target.value })}
                placeholder={cfg.type === 'xdrip' ? 'http://127.0.0.1:17580' : 'https://yoursite.herokuapp.com'}
                className="mt-1 w-full rounded bg-surface2 px-2 py-1.5 text-sm outline-none"
              />
            </label>
            <label className="mt-2 block">
              <span className="text-xs text-muted">{cfg.type === 'xdrip' ? 'API secret (optional)' : 'Token (optional)'}</span>
              <input
                value={cfg.apiSecret ?? ''}
                onChange={(e) => update({ apiSecret: e.target.value })}
                className="mt-1 w-full rounded bg-surface2 px-2 py-1.5 text-sm outline-none"
              />
            </label>
          </>
        )}
        {cfg.type === 'xdrip' && (
          <p className="mt-2 text-xs text-muted">
            On the phone running xDrip+: Settings → Inter-app settings → xDrip Web Service: ON
            (+ optional secret). Chrome treats http://127.0.0.1 as trustworthy, so this installed
            app may fetch it on the same device.
          </p>
        )}
        <button
          onClick={() => void testConnection()}
          disabled={testing}
          className="mt-3 w-full rounded-lg bg-surface2 py-2 text-sm text-accent disabled:opacity-50"
        >
          {testing ? 'Testing…' : 'Test connection'}
        </button>
      </div>
    </Section>
  );
}

function exportJson(rows: unknown[], name: string, mime: string, body?: string) {
  const blob = new Blob([body ?? JSON.stringify(rows, null, 2)], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

function DataSection() {
  const doExportJson = async () => {
    const events = await db.events.orderBy('ts').toArray();
    exportJson(events, `t1d-harness-events-${new Date().toISOString().slice(0, 10)}.json`, 'application/json');
  };
  const doExportCsv = async () => {
    const events = await db.events.orderBy('ts').toArray();
    const header = 'id,type,ts,payload,supersedesId,deviceId,createdAt';
    const lines = events.map((e) =>
      [e.id, e.type, e.ts, JSON.stringify(JSON.stringify(e.payload)), e.supersedesId ?? '', e.deviceId, e.createdAt].join(','),
    );
    exportJson([], `t1d-harness-events-${new Date().toISOString().slice(0, 10)}.csv`, 'text/csv', [header, ...lines].join('\n'));
  };
  return (
    <Section title="Data">
      <div className="flex gap-2">
        <button onClick={() => void doExportJson()} className="flex-1 rounded-xl bg-surface py-3 text-sm">Export JSON</button>
        <button onClick={() => void doExportCsv()} className="flex-1 rounded-xl bg-surface py-3 text-sm">Export CSV</button>
      </div>
    </Section>
  );
}

function SyncSection() {
  const status = useSyncExternalStore(subscribeSyncStatus, getSyncStatus);
  return (
    <Section title="Sync status">
      <div className="rounded-xl bg-surface px-4 py-3 text-sm">
        <div className="flex justify-between"><span className="text-muted">Last cursor</span><span className="tabular-nums">{status.lastCursor ? new Date(status.lastCursor).toLocaleString() : '—'}</span></div>
        <div className="mt-1 flex justify-between"><span className="text-muted">Pending outbox</span><span>{status.pendingCount}</span></div>
        {status.lastError && <div className="mt-1 text-xs text-red">{status.lastError}</div>}
        <button
          onClick={() => void syncNow()}
          disabled={status.syncing}
          className="mt-3 w-full rounded-lg bg-surface2 py-2 text-accent disabled:opacity-50"
        >
          {status.syncing ? 'Syncing…' : 'Sync now'}
        </button>
      </div>
    </Section>
  );
}

function AccountSection() {
  const [email, setEmail] = useState<string | null>(null);
  if (!supabaseConfigured()) return null;
  if (email === null) {
    void getSupabase().auth.getUser().then(({ data }) => setEmail(data.user?.email ?? ''));
  }
  return (
    <Section title="Account">
      <div className="flex items-center justify-between rounded-xl bg-surface px-4 py-3">
        <span className="text-sm text-muted">{email || '…'}</span>
        <button onClick={() => void getSupabase().auth.signOut()} className="text-sm text-red">Sign out</button>
      </div>
    </Section>
  );
}

export function SettingsScreen() {
  return (
    <div className="px-4 pt-4 pb-8">
      <h1 className="text-2xl font-bold">Settings</h1>

      <Section title="Ratios & targets">
        {NUMERIC_KEYS.map((k) => <NumberRow key={k} k={k} />)}
      </Section>

      <BgSourceSection />
      <AiSection />

      <Section title="Confirmations">
        <p className="text-xs text-muted">Soft warnings only — set high to effectively disable.</p>
        {CONFIRM_KEYS.map((k) => <NumberRow key={k} k={k} />)}
      </Section>

      <AccountSection />
      <DataSection />
      <SyncSection />
      <button
        onClick={() => setDeviceConfig(DEFAULT_DEVICE_CONFIG)}
        className="mt-6 w-full py-2 text-xs text-muted"
      >
        Reset device config
      </button>
    </div>
  );
}
