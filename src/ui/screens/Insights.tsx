import { useCallback, useEffect, useState } from 'react';
import { buildAggregates, type AggregateStats, type Bucket } from '../../lib/insights';
import { callAi } from '../../lib/ai/client';
import { insertSuggestions, listSuggestions, resolveSuggestion, pushSettings } from '../../lib/sync';
import { getSettings, setSettingsLocal, useSettings } from '../../lib/settings';
import { appendEvent } from '../../lib/repo';
import { db } from '../../lib/db';
import { useStore } from '../../state/useStore';
import { InsightSuggestionSchema } from '../../lib/ai/schemas';
import type { AiInteractionPayload, InsightSuggestion, SettingsChangePayload } from '../../lib/types';
import { Sparkline } from '../components/Sparkline';
import type { BgReading } from '../../lib/types';

const MIN_OUTCOMES = 20;

interface SuggestionRow {
  id: string;
  payload: InsightSuggestion;
  status: string;
}

function StatCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-xl bg-surface p-3">
      <div className="text-xs text-muted">{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-muted">{sub}</div>}
    </div>
  );
}

export function InsightsScreen() {
  const settings = useSettings();
  const toast = useStore((s) => s.toast);
  const [windowDays, setWindowDays] = useState<7 | 30>(7);
  const [stats, setStats] = useState<AggregateStats | null>(null);
  const [suggestions, setSuggestions] = useState<SuggestionRow[]>([]);
  const [analyzing, setAnalyzing] = useState(false);
  const [decisionBgs, setDecisionBgs] = useState<BgReading[]>([]);

  const reload = useCallback(async () => {
    setStats(await buildAggregates(windowDays));
    try {
      const rows = await listSuggestions();
      setSuggestions(
        rows.flatMap((r) => {
          const parsed = InsightSuggestionSchema.safeParse(r.payload);
          return parsed.success ? [{ id: r.id, payload: parsed.data, status: r.status }] : [];
        }),
      );
    } catch {
      // offline — suggestions list unavailable
    }
    const from = new Date(Date.now() - windowDays * 24 * 3600_000).toISOString();
    const interactions = await db.events
      .where('[type+ts]')
      .between(['ai_interaction', from], ['ai_interaction', '￿'])
      .toArray();
    const bgs = interactions
      .map((e) => (e.payload as AiInteractionPayload).bgAtTime)
      .filter((b): b is BgReading => b !== null)
      .sort((a, b) => b.ts.localeCompare(a.ts));
    setDecisionBgs(bgs);
  }, [windowDays]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const fullOutcomes = stats?.counts.outcomesFull ?? 0;
  const canAnalyze = fullOutcomes >= MIN_OUTCOMES;

  const runAnalysis = async () => {
    if (!stats) return;
    setAnalyzing(true);
    const result = await callAi('insights', { stats, settings: stats.settings });
    if (result.ok) {
      try {
        await insertSuggestions(result.data.suggestions);
        toast(`Analysis done — ${result.data.suggestions.length} suggestion(s)`);
      } catch (e) {
        toast(e instanceof Error ? e.message : 'Could not save suggestions', 'error');
      }
      await reload();
    } else {
      toast(result.error, 'error');
    }
    setAnalyzing(false);
  };

  const accept = async (row: SuggestionRow) => {
    const s = row.payload;
    if (s.parameter !== 'behavior') {
      const value = parseFloat(s.suggested.replace(/[^0-9.]/g, ''));
      if (Number.isFinite(value) && value > 0) {
        const current = getSettings();
        const from = current[s.parameter];
        const next = { ...current, [s.parameter]: value };
        setSettingsLocal(next);
        try {
          await pushSettings(next);
        } catch { /* synced later */ }
        await appendEvent<SettingsChangePayload>('settings_change', {
          key: s.parameter, from, to: value, source: 'suggestion',
        });
      }
    }
    try {
      await resolveSuggestion(row.id, 'accepted');
    } catch { /* offline */ }
    toast('Suggestion accepted');
    await reload();
  };

  const dismiss = async (row: SuggestionRow) => {
    try {
      await resolveSuggestion(row.id, 'dismissed');
    } catch { /* offline */ }
    await reload();
  };

  const coverage = stats && stats.counts.boluses > 0
    ? Math.round(((stats.counts.outcomesFull + stats.counts.outcomesPartial) / stats.counts.boluses) * 100)
    : 0;

  const bucketLabels: Record<Bucket, string> = {
    '05-11': 'Morning', '11-16': 'Afternoon', '16-22': 'Evening', '22-05': 'Night',
  };

  const pending = suggestions.filter((s) => s.status === 'pending');

  return (
    <div className="px-4 pt-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Insights</h1>
        <div className="flex gap-1 rounded-full bg-surface2 p-1">
          {[7, 30].map((d) => (
            <button
              key={d}
              onClick={() => setWindowDays(d as 7 | 30)}
              className={`rounded-full px-3 py-1 text-sm ${windowDays === d ? 'bg-accent text-black' : 'text-muted'}`}
            >
              {d}d
            </button>
          ))}
        </div>
      </div>

      {stats && (
        <>
          <div className="mt-4 grid grid-cols-2 gap-2">
            <StatCard label="Boluses logged" value={String(stats.counts.boluses)} />
            <StatCard label="Hypo within 4h" value={`${Math.round(stats.hypoRate4h * 100)}%`} />
            <StatCard
              label="Correction effect"
              value={
                stats.correctionEffectiveness.meanDropPerUnit !== null
                  ? `${Math.round(stats.correctionEffectiveness.meanDropPerUnit)} mg/dL/U`
                  : '—'
              }
              sub={`configured ISF ${stats.correctionEffectiveness.configuredIsf} · n=${stats.correctionEffectiveness.n}`}
            />
            <StatCard label="Outcome coverage" value={`${coverage}%`} sub={`${fullOutcomes} full · ${stats.counts.outcomesPartial} partial`} />
          </div>

          <h3 className="mt-5 text-sm font-semibold uppercase tracking-wide text-muted">
            Mean 2h post-meal delta
          </h3>
          <div className="mt-2 grid grid-cols-2 gap-2">
            {(Object.keys(bucketLabels) as Bucket[]).map((b) => {
              const row = stats.mealResponseByBucket[b];
              return (
                <StatCard
                  key={b}
                  label={bucketLabels[b]}
                  value={row.meanDelta2h !== null ? `${row.meanDelta2h > 0 ? '+' : ''}${Math.round(row.meanDelta2h)}` : '—'}
                  sub={`n=${row.n} · ${Math.round(row.meanCarbs)}g · ${row.meanUnits.toFixed(1)}U avg`}
                />
              );
            })}
          </div>
        </>
      )}

      {decisionBgs.length >= 2 && (
        <div className="mt-5 rounded-xl bg-surface p-3">
          <div className="text-xs text-muted">Decision-time BG ({windowDays}d)</div>
          <div className="mt-2">
            <Sparkline readings={decisionBgs} lowThreshold={settings.lowThreshold} />
          </div>
        </div>
      )}

      <div className="mt-6">
        <button
          onClick={() => void runAnalysis()}
          disabled={!canAnalyze || analyzing}
          className="w-full rounded-xl bg-accent py-3 font-semibold text-black disabled:opacity-40"
        >
          {analyzing ? 'Analyzing…' : 'Run AI analysis'}
        </button>
        {!canAnalyze && (
          <p className="mt-2 text-center text-xs text-muted">
            Needs at least {MIN_OUTCOMES} full-quality outcomes ({fullOutcomes} so far). Keep logging —
            outcomes backfill automatically 4 h after each bolus or meal.
          </p>
        )}
      </div>

      {pending.length > 0 && (
        <div className="mt-5 space-y-3 pb-6">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-muted">Suggestions</h3>
          {pending.map((row) => (
            <div key={row.id} className="rounded-xl bg-surface p-4">
              <div className="text-sm font-semibold">
                {row.payload.parameter === 'behavior' ? 'Behavior' : row.payload.parameter.toUpperCase()}:{' '}
                {row.payload.current} → <span className="text-accent">{row.payload.suggested}</span>
              </div>
              <p className="mt-1 text-sm">{row.payload.expectedEffect}</p>
              <p className="mt-2 text-xs text-muted">{row.payload.evidence}</p>
              <div className="mt-3 flex gap-2">
                <button onClick={() => void dismiss(row)} className="flex-1 rounded-lg bg-surface2 py-2 text-sm">
                  Dismiss
                </button>
                <button onClick={() => void accept(row)} className="flex-1 rounded-lg bg-accent py-2 text-sm font-semibold text-black">
                  Accept
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
