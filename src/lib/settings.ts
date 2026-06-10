import { useSyncExternalStore } from 'react';
import { z } from 'zod';

// ---- SyncedSettings (cloud, identical across devices) ----

export const SyncedSettingsSchema = z.object({
  targetBg: z.number().positive().default(110),
  isf: z.number().positive().default(40),
  icr: z.number().positive().default(10),
  diaMin: z.number().positive().default(300),
  peakMin: z.number().positive().default(75),
  roundStep: z.number().positive().default(0.5),
  lowThreshold: z.number().positive().default(70),
  largeDoseWarn: z.number().positive().default(15),
  highCarbWarn: z.number().positive().default(200),
  staleMin: z.number().positive().default(15),
  cobDurationMin: z.number().positive().default(180),
  cobDelayMin: z.number().nonnegative().default(10),
  recentBolusMin: z.number().positive().default(15),
  aiProvider: z.enum(['anthropic', 'openai']).default('anthropic'),
  extractModel: z.string().default('claude-sonnet-4-6'),
  rationaleModel: z.string().default('claude-haiku-4-5-20251001'),
});

export type SyncedSettings = z.infer<typeof SyncedSettingsSchema>;

export const DEFAULT_SETTINGS: SyncedSettings = SyncedSettingsSchema.parse({});

// ---- DeviceConfig (localStorage only, never synced) ----

export const DeviceConfigSchema = z.object({
  bgSource: z.object({
    type: z.enum(['xdrip', 'nightscout', 'manual', 'demo']).default('demo'),
    url: z.string().optional(),
    apiSecret: z.string().optional(),
  }).default({ type: 'demo' }),
});

export type DeviceConfig = z.infer<typeof DeviceConfigSchema>;

export const DEFAULT_DEVICE_CONFIG: DeviceConfig = DeviceConfigSchema.parse({});

// ---- localStorage-backed stores with subscription (for useSyncExternalStore) ----

const SETTINGS_KEY = 't1d-harness:settings';
const DEVICE_KEY = 't1d-harness:device';

type Listener = () => void;

function createStore<T>(key: string, parse: (raw: unknown) => T, defaults: T) {
  let cached: T | null = null;
  const listeners = new Set<Listener>();

  function load(): T {
    if (cached) return cached;
    try {
      const raw = localStorage.getItem(key);
      cached = raw ? parse(JSON.parse(raw)) : defaults;
    } catch {
      cached = defaults;
    }
    return cached;
  }

  function set(next: T) {
    cached = next;
    localStorage.setItem(key, JSON.stringify(next));
    listeners.forEach((l) => l());
  }

  function subscribe(l: Listener) {
    listeners.add(l);
    return () => listeners.delete(l);
  }

  return { get: load, set, subscribe };
}

function parseSettings(raw: unknown): SyncedSettings {
  const result = SyncedSettingsSchema.safeParse(raw);
  return result.success ? result.data : DEFAULT_SETTINGS;
}

function parseDeviceConfig(raw: unknown): DeviceConfig {
  const result = DeviceConfigSchema.safeParse(raw);
  return result.success ? result.data : DEFAULT_DEVICE_CONFIG;
}

export const settingsStore = createStore<SyncedSettings>(SETTINGS_KEY, parseSettings, DEFAULT_SETTINGS);
export const deviceConfigStore = createStore<DeviceConfig>(DEVICE_KEY, parseDeviceConfig, DEFAULT_DEVICE_CONFIG);

export function getSettings(): SyncedSettings {
  return settingsStore.get();
}

export function getDeviceConfig(): DeviceConfig {
  return deviceConfigStore.get();
}

/** Write settings locally; sync.ts pushes to cloud and records the audit event. */
export function setSettingsLocal(next: SyncedSettings) {
  settingsStore.set(next);
}

export function setDeviceConfig(next: DeviceConfig) {
  deviceConfigStore.set(next);
}

export function useSettings(): SyncedSettings {
  return useSyncExternalStore(settingsStore.subscribe, settingsStore.get);
}

export function useDeviceConfig(): DeviceConfig {
  return useSyncExternalStore(deviceConfigStore.subscribe, deviceConfigStore.get);
}

// Human-readable labels + units for the Settings screen.
export const SETTING_META: Record<keyof SyncedSettings, { label: string; unit?: string; step?: number }> = {
  targetBg: { label: 'Target BG', unit: 'mg/dL', step: 5 },
  isf: { label: 'ISF (correction factor)', unit: 'mg/dL per U', step: 1 },
  icr: { label: 'ICR (carb ratio)', unit: 'g per U', step: 0.5 },
  diaMin: { label: 'Insulin duration (DIA)', unit: 'min', step: 15 },
  peakMin: { label: 'Insulin peak', unit: 'min', step: 5 },
  roundStep: { label: 'Pen increment', unit: 'U', step: 0.5 },
  lowThreshold: { label: 'Low threshold', unit: 'mg/dL', step: 5 },
  largeDoseWarn: { label: 'Large dose warning', unit: 'U', step: 1 },
  highCarbWarn: { label: 'High carb warning', unit: 'g', step: 10 },
  staleMin: { label: 'Stale BG after', unit: 'min', step: 1 },
  cobDurationMin: { label: 'Carb absorption window', unit: 'min', step: 15 },
  cobDelayMin: { label: 'Carb absorption delay', unit: 'min', step: 5 },
  recentBolusMin: { label: 'Recent bolus window', unit: 'min', step: 5 },
  aiProvider: { label: 'AI provider' },
  extractModel: { label: 'Extraction model' },
  rationaleModel: { label: 'Rationale model' },
};
