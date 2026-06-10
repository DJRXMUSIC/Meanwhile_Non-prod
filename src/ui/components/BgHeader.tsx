import { useEffect, useState } from 'react';
import { useStore } from '../../state/useStore';
import { useSettings } from '../../lib/settings';
import { currentCob, currentIob } from '../../lib/status';
import { bgAgeMin } from '../../lib/bgsource/useBg';
import { appendEvent } from '../../lib/repo';
import { db } from '../../lib/db';
import type { BgReading, Trend } from '../../lib/types';
import { Sparkline } from './Sparkline';

const ARROWS: Record<Trend, string> = {
  DoubleUp: '⇈', SingleUp: '↑', FortyFiveUp: '↗', Flat: '→',
  FortyFiveDown: '↘', SingleDown: '↓', DoubleDown: '⇊', NONE: '',
};

interface Props {
  reading: BgReading | null;
  onRefresh: () => void;
}

export function BgHeader({ reading, onRefresh }: Props) {
  const settings = useSettings();
  const headerNonce = useStore((s) => s.headerNonce);
  const toast = useStore((s) => s.toast);
  const [iob, setIob] = useState(0);
  const [cob, setCob] = useState(0);
  const [chartOpen, setChartOpen] = useState(false);
  const [chartData, setChartData] = useState<BgReading[]>([]);
  const [bgEntryOpen, setBgEntryOpen] = useState(false);
  const [bgEntryValue, setBgEntryValue] = useState('');

  useEffect(() => {
    let alive = true;
    const update = () => {
      void currentIob().then((v) => alive && setIob(v));
      void currentCob().then((v) => alive && setCob(v));
    };
    update();
    const timer = setInterval(update, 60_000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [headerNonce]);

  const age = bgAgeMin(reading);
  const stale = age !== null && age > settings.staleMin;

  const openChart = async () => {
    const cutoff = new Date(Date.now() - 3 * 3600_000).toISOString();
    const rows = await db.bgcache.where('ts').above(cutoff).reverse().toArray();
    setChartData(rows);
    setChartOpen((open) => !open);
  };

  const saveManualBg = async () => {
    const mgdl = Number(bgEntryValue);
    if (!Number.isFinite(mgdl) || mgdl <= 0) return;
    await appendEvent('bg_manual', { mgdl });
    await db.bgcache.put({ mgdl, ts: new Date().toISOString(), trend: 'NONE', source: 'manual' });
    setBgEntryOpen(false);
    setBgEntryValue('');
    toast('BG logged');
    onRefresh();
  };

  return (
    <header className={`sticky top-0 z-30 border-b border-surface2 bg-bg/95 px-4 py-3 backdrop-blur ${stale ? 'bg-amber/10' : ''}`}>
      <div className="flex items-end justify-between">
        <button onClick={() => void openChart()} className="text-left">
          {reading ? (
            <span className={`tabular-nums text-[64px] font-semibold leading-none ${stale ? 'text-amber' : ''}`}>
              {reading.mgdl}
              <span className="ml-1 text-3xl">{ARROWS[reading.trend]}</span>
            </span>
          ) : (
            <span className="text-[64px] font-semibold leading-none text-muted">—</span>
          )}
        </button>
        {(!reading || reading.source === 'manual' || stale) && (
          <button
            onClick={() => setBgEntryOpen((v) => !v)}
            className="mb-2 rounded-full bg-surface2 px-3 py-1.5 text-sm text-accent"
          >
            + BG
          </button>
        )}
      </div>
      <div className={`mt-1 text-sm ${stale ? 'font-semibold text-amber' : 'text-muted'}`}>
        {age !== null ? `${age} min ago` : 'No BG data'}
        {' · '}IOB {iob.toFixed(1)} U{' · '}COB {Math.round(cob)} g
      </div>
      {stale && <div className="mt-1 text-xs text-amber">BG is {age} min old</div>}
      {bgEntryOpen && (
        <div className="mt-2 flex gap-2">
          <input
            type="number"
            inputMode="numeric"
            value={bgEntryValue}
            onChange={(e) => setBgEntryValue(e.target.value)}
            placeholder="mg/dL"
            className="w-28 rounded-lg bg-surface2 px-3 py-2 text-fg outline-none"
          />
          <button onClick={() => void saveManualBg()} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black">
            Save
          </button>
        </div>
      )}
      {chartOpen && (
        <div className="mt-3">
          <Sparkline readings={chartData} lowThreshold={settings.lowThreshold} />
          <div className="mt-1 text-xs text-muted">Last 3 h</div>
        </div>
      )}
    </header>
  );
}
