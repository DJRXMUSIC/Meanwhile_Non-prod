import { useMemo, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '../../lib/db';
import { supersede } from '../../lib/repo';
import type {
  AiInteractionPayload, AppEvent, BolusPayload, MealPayload,
} from '../../lib/types';
import { EventRow } from '../components/EventRow';
import { Breakdown } from '../components/Breakdown';

type Filter = 'all' | 'bolus' | 'meal' | 'context';

function FrozenCard({ event, onClose }: { event: AppEvent; onClose: () => void }) {
  const p = event.payload as AiInteractionPayload;
  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-black/70 p-4" onClick={onClose}>
      <div className="mx-auto max-w-lg rounded-2xl bg-surface p-4" onClick={(e) => e.stopPropagation()}>
        <div className="text-xs text-muted">
          {new Date(event.ts).toLocaleString()} · {p.inputMode} · {p.model}
        </div>
        {p.inputText && <p className="mt-2 text-sm text-muted">“{p.inputText}”</p>}
        <h2 className="mt-2 text-xl font-bold">{p.calc.headline}</h2>
        <div className="mt-3">
          <Breakdown lines={p.calc.breakdown} />
        </div>
        {p.calc.flags.length > 0 && (
          <div className="mt-2 text-xs text-amber">Flags: {p.calc.flags.join(', ')}</div>
        )}
        {p.rationale && (
          <div className="mt-3">
            <p className="text-sm leading-6">{p.rationale.rationale}</p>
            {p.rationale.cautions.map((c) => (
              <p key={c} className="mt-1 text-sm text-amber">⚠ {c}</p>
            ))}
          </div>
        )}
        {p.bgAtTime && (
          <div className="mt-2 text-xs text-muted">BG at decision: {p.bgAtTime.mgdl} mg/dL ({p.bgAtTime.trend})</div>
        )}
        <button onClick={onClose} className="mt-4 w-full rounded-xl bg-surface2 py-3">Close</button>
      </div>
    </div>
  );
}

function AmendSheet({ event, onClose }: { event: AppEvent; onClose: () => void }) {
  const isBolus = event.type === 'bolus';
  const isMeal = event.type === 'meal';
  const current = isBolus
    ? (event.payload as BolusPayload).units
    : isMeal
      ? (event.payload as MealPayload).carbsG
      : 0;
  const [value, setValue] = useState(String(current));

  if (!isBolus && !isMeal) return null;

  const save = async () => {
    const n = Number(value);
    if (!Number.isFinite(n) || n < 0) return;
    if (isBolus) {
      const p = event.payload as BolusPayload;
      await supersede(event.id, 'bolus', { ...p, units: n });
    } else {
      const p = event.payload as MealPayload;
      await supersede(event.id, 'meal', { ...p, carbsG: n });
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60" onClick={onClose}>
      <div className="w-full max-w-lg rounded-t-2xl bg-surface p-5" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-lg font-semibold">Amend {isBolus ? 'bolus' : 'meal'}</h2>
        <p className="mt-1 text-xs text-muted">
          The original stays in history (struck through); a correction event replaces it.
        </p>
        <label className="mt-3 block text-sm text-muted">
          {isBolus ? 'Actual units' : 'Actual carbs (g)'}
          <input
            type="number"
            inputMode="decimal"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="mt-1 w-full rounded-lg bg-surface2 px-3 py-3 text-fg outline-none"
          />
        </label>
        <div className="mt-4 flex gap-3">
          <button onClick={onClose} className="flex-1 rounded-xl bg-surface2 py-3">Cancel</button>
          <button onClick={() => void save()} className="flex-1 rounded-xl bg-accent py-3 font-semibold text-black">
            Save amendment
          </button>
        </div>
      </div>
    </div>
  );
}

export function HistoryScreen() {
  const [filter, setFilter] = useState<Filter>('all');
  const [detail, setDetail] = useState<AppEvent | null>(null);
  const [amending, setAmending] = useState<AppEvent | null>(null);

  const events = useLiveQuery(() => db.events.orderBy('ts').reverse().toArray(), []);

  const { visible, supersededIds } = useMemo(() => {
    const all = events ?? [];
    const superseded = new Set(all.filter((e) => e.supersedesId).map((e) => e.supersedesId!));
    const rows = all.filter((e) => {
      if (e.type === 'outcome') return false;
      if (filter === 'all') return true;
      if (filter === 'bolus') return e.type === 'bolus';
      if (filter === 'meal') return e.type === 'meal';
      return e.type === 'context';
    });
    return { visible: rows, supersededIds: superseded };
  }, [events, filter]);

  const byDay = useMemo(() => {
    const groups = new Map<string, AppEvent[]>();
    for (const e of visible) {
      const day = new Date(e.ts).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
      const list = groups.get(day) ?? [];
      list.push(e);
      groups.set(day, list);
    }
    return groups;
  }, [visible]);

  const chips: { id: Filter; label: string }[] = [
    { id: 'all', label: 'All' },
    { id: 'bolus', label: 'Boluses' },
    { id: 'meal', label: 'Meals' },
    { id: 'context', label: 'Context' },
  ];

  return (
    <div className="px-4 pt-4">
      <h1 className="text-2xl font-bold">History</h1>
      <div className="mt-3 flex gap-2">
        {chips.map((c) => (
          <button
            key={c.id}
            onClick={() => setFilter(c.id)}
            className={`rounded-full px-3 py-1.5 text-sm ${filter === c.id ? 'bg-accent text-black' : 'bg-surface2 text-muted'}`}
          >
            {c.label}
          </button>
        ))}
      </div>
      <div className="mt-4 space-y-5">
        {[...byDay.entries()].map(([day, rows]) => (
          <div key={day}>
            <h3 className="text-sm font-semibold uppercase tracking-wide text-muted">{day}</h3>
            <div className="mt-1">
              {rows.map((e) => (
                <EventRow
                  key={e.id}
                  event={e}
                  superseded={supersededIds.has(e.id)}
                  onTap={() => {
                    if (e.type === 'ai_interaction') setDetail(e);
                  }}
                  onLongPress={() => {
                    if ((e.type === 'bolus' || e.type === 'meal') && !supersededIds.has(e.id)) setAmending(e);
                  }}
                />
              ))}
            </div>
          </div>
        ))}
        {visible.length === 0 && <p className="py-8 text-center text-sm text-muted">No events yet.</p>}
      </div>
      <p className="mt-6 pb-4 text-center text-xs text-muted">
        Tip: long-press a meal or bolus to amend it.
      </p>
      {detail && <FrozenCard event={detail} onClose={() => setDetail(null)} />}
      {amending && <AmendSheet event={amending} onClose={() => setAmending(null)} />}
    </div>
  );
}
