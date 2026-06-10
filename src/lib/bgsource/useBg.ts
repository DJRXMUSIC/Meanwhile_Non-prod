import { useCallback, useEffect, useState } from 'react';
import { db } from '../db';
import type { BgReading } from '../types';
import { useDeviceConfig } from '../settings';
import { createBgSource } from './index';

const POLL_MS = 60_000;
const CACHE_RETENTION_MS = 7 * 24 * 3600_000;

export async function cacheReadings(readings: BgReading[]) {
  if (readings.length === 0) return;
  await db.bgcache.bulkPut(readings);
  const cutoff = new Date(Date.now() - CACHE_RETENTION_MS).toISOString();
  await db.bgcache.where('ts').below(cutoff).delete();
}

export async function latestCached(count = 36): Promise<BgReading[]> {
  return db.bgcache.orderBy('ts').reverse().limit(count).toArray();
}

export interface BgState {
  reading: BgReading | null;
  history: BgReading[];   // newest first
  error: string | null;
  refresh: () => void;
}

export function useBg(): BgState {
  const deviceConfig = useDeviceConfig();
  const [reading, setReading] = useState<BgReading | null>(null);
  const [history, setHistory] = useState<BgReading[]>([]);
  const [error, setError] = useState<string | null>(null);

  const sourceType = deviceConfig.bgSource.type;
  const sourceUrl = deviceConfig.bgSource.url;
  const sourceSecret = deviceConfig.bgSource.apiSecret;

  const poll = useCallback(async () => {
    const source = createBgSource({ bgSource: { type: sourceType, url: sourceUrl, apiSecret: sourceSecret } });
    try {
      const readings = await source.fetchLatest(36);
      if (readings.length > 0) {
        await cacheReadings(readings);
        setReading(readings[0]);
        setHistory(readings);
        setError(null);
        return;
      }
      throw new Error('No readings returned');
    } catch (e) {
      // Fall back to the most recent cached entry (will render as stale),
      // then to manual bg events via the cache (bg_manual is cached on entry).
      const cached = await latestCached();
      if (cached.length > 0) {
        setReading(cached[0]);
        setHistory(cached);
      } else {
        setReading(null);
        setHistory([]);
      }
      setError(e instanceof Error ? e.message : 'BG source unavailable');
    }
  }, [sourceType, sourceUrl, sourceSecret]);

  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;

    const start = () => {
      if (timer) return;
      void poll();
      timer = setInterval(() => void poll(), POLL_MS);
    };
    const stop = () => {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    };
    const onVisibility = () => {
      if (document.visibilityState === 'visible') start();
      else stop();
    };

    onVisibility();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onVisibility);
    };
  }, [poll]);

  return { reading, history, error, refresh: () => void poll() };
}

export function bgAgeMin(reading: BgReading | null): number | null {
  if (!reading) return null;
  return Math.round((Date.now() - new Date(reading.ts).getTime()) / 60_000);
}
