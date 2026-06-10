import { useState } from 'react';
import { getSupabase } from '../../lib/supabase';

export function AuthScreen() {
  const [mode, setMode] = useState<'signin' | 'signup'>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    const supabase = getSupabase();
    const { error: err } =
      mode === 'signin'
        ? await supabase.auth.signInWithPassword({ email, password })
        : await supabase.auth.signUp({ email, password });
    if (err) setError(err.message);
    setBusy(false);
  };

  return (
    <div className="mx-auto flex h-dvh max-w-sm flex-col justify-center px-6">
      <h1 className="text-3xl font-bold">T1D Harness</h1>
      <p className="mt-1 text-muted">Personal decision support.</p>
      <div className="mt-8 space-y-3">
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="Email"
          autoComplete="email"
          className="w-full rounded-xl bg-surface px-4 py-3 outline-none"
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && void submit()}
          placeholder="Password"
          autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
          className="w-full rounded-xl bg-surface px-4 py-3 outline-none"
        />
        {error && <p className="text-sm text-red">{error}</p>}
        <button
          onClick={() => void submit()}
          disabled={busy || !email || !password}
          className="w-full rounded-xl bg-accent py-3 font-semibold text-black disabled:opacity-50"
        >
          {busy ? '…' : mode === 'signin' ? 'Sign in' : 'Create account'}
        </button>
        <button
          onClick={() => setMode((m) => (m === 'signin' ? 'signup' : 'signin'))}
          className="w-full py-2 text-sm text-muted"
        >
          {mode === 'signin' ? 'Need an account? Sign up' : 'Have an account? Sign in'}
        </button>
      </div>
    </div>
  );
}
