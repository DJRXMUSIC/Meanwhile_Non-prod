import { useEffect } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '../../lib/db';
import { useBg } from '../../lib/bgsource/useBg';
import { useStore } from '../../state/useStore';
import { BgHeader } from '../components/BgHeader';
import { PromptBox } from '../components/PromptBox';
import { ResultCard } from '../components/ResultCard';
import { ManualEntrySheet } from '../components/ManualEntrySheet';
import { EventRow } from '../components/EventRow';

export function HomeScreen() {
  const { reading, refresh } = useBg();
  const setLatestBg = useStore((s) => s.setLatestBg);

  useEffect(() => {
    setLatestBg(reading);
  }, [reading, setLatestBg]);

  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);
  const todayIso = todayStart.toISOString();

  const todayEvents = useLiveQuery(async () => {
    const rows = await db.events.where('ts').above(todayIso).toArray();
    const superseded = new Set(rows.filter((e) => e.supersedesId).map((e) => e.supersedesId!));
    return rows
      .filter((e) => !superseded.has(e.id) && e.type !== 'ai_interaction' && e.type !== 'outcome')
      .sort((a, b) => b.ts.localeCompare(a.ts));
  }, [todayIso]);

  return (
    <div>
      <BgHeader reading={reading} onRefresh={refresh} />
      <PromptBox />
      <ResultCard />
      <ManualEntrySheet />
      <section className="mt-6 px-4">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-muted">Today</h3>
        <div className="mt-2">
          {todayEvents?.length ? (
            todayEvents.map((e) => <EventRow key={e.id} event={e} />)
          ) : (
            <p className="py-4 text-sm text-muted">Nothing logged yet today.</p>
          )}
        </div>
      </section>
    </div>
  );
}
