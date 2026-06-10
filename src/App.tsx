import { useEffect, useState } from 'react';
import type { Session } from '@supabase/supabase-js';
import { getSupabase, supabaseConfigured } from './lib/supabase';
import { startSyncLoop } from './lib/sync';
import { startOutcomeWorker } from './lib/outcomes';
import { useStore } from './state/useStore';
import { AuthScreen } from './ui/screens/Auth';
import { HomeScreen } from './ui/screens/Home';
import { HistoryScreen } from './ui/screens/History';
import { InsightsScreen } from './ui/screens/Insights';
import { SettingsScreen } from './ui/screens/Settings';
import { TabBar } from './ui/components/TabBar';
import { Banner } from './ui/components/Banner';

const FIRST_RUN_KEY = 't1d-harness:first-run-dismissed';

function FirstRunNotice() {
  const [dismissed, setDismissed] = useState(() => localStorage.getItem(FIRST_RUN_KEY) === '1');
  if (dismissed) return null;
  return (
    <div className="mx-4 mt-4 rounded-xl bg-surface2 p-4 text-sm">
      <p>
        This app is decision support built for personal use. It calculates with your configured
        ratios; you verify and decide.
      </p>
      <button
        className="mt-2 text-accent"
        onClick={() => {
          localStorage.setItem(FIRST_RUN_KEY, '1');
          setDismissed(true);
        }}
      >
        Got it
      </button>
    </div>
  );
}

function Toasts() {
  const toasts = useStore((s) => s.toasts);
  if (toasts.length === 0) return null;
  return (
    <div className="pointer-events-none fixed bottom-20 left-0 right-0 z-50 flex flex-col items-center gap-2 px-4">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={`rounded-full px-4 py-2 text-sm shadow-lg ${
            t.tone === 'error' ? 'bg-red text-white' : t.tone === 'warn' ? 'bg-amber text-black' : 'bg-surface2 text-fg'
          }`}
        >
          {t.text}
        </div>
      ))}
    </div>
  );
}

export default function App() {
  const screen = useStore((s) => s.screen);
  const [session, setSession] = useState<Session | null>(null);
  const [authChecked, setAuthChecked] = useState(false);

  useEffect(() => {
    if (!supabaseConfigured()) {
      setAuthChecked(true);
      return;
    }
    const supabase = getSupabase();
    void supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setAuthChecked(true);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_evt, next) => setSession(next));
    return () => sub.subscription.unsubscribe();
  }, []);

  const signedIn = session !== null;

  useEffect(() => {
    if (signedIn) {
      startSyncLoop();
      startOutcomeWorker();
    }
  }, [signedIn]);

  if (!authChecked) {
    return <div className="flex h-dvh items-center justify-center text-muted">Loading…</div>;
  }

  if (supabaseConfigured() && !session) {
    return <AuthScreen />;
  }

  return (
    <div className="mx-auto flex h-dvh max-w-lg flex-col">
      {!supabaseConfigured() && (
        <Banner tone="warn" text="Supabase not configured — running local-only (no sync, no AI)." />
      )}
      <FirstRunNotice />
      <main className="flex-1 overflow-y-auto pb-20">
        {screen === 'home' && <HomeScreen />}
        {screen === 'history' && <HistoryScreen />}
        {screen === 'insights' && <InsightsScreen />}
        {screen === 'settings' && <SettingsScreen />}
      </main>
      <TabBar />
      <Toasts />
    </div>
  );
}
