import { z } from 'zod';
import { getSettings } from '../settings';
import { getSupabase, supabaseConfigured } from '../supabase';
import { TASK_SCHEMAS, type AiTask } from './schemas';

const TIMEOUT_MS = 20_000;

export type AiResult<T> =
  | { ok: true; data: T; latencyMs: number; model: string; provider: string }
  | { ok: false; error: string };

export async function callAi<T extends AiTask>(
  task: T,
  payload: object,
): Promise<AiResult<z.infer<(typeof TASK_SCHEMAS)[T]>>> {
  if (!supabaseConfigured()) return { ok: false, error: 'Not signed in' };
  const settings = getSettings();
  const model = task === 'extract' ? settings.extractModel : settings.rationaleModel;
  const provider = settings.aiProvider;

  const { data: sessionData } = await getSupabase().auth.getSession();
  const token = sessionData.session?.access_token;
  if (!token) return { ok: false, error: 'Not signed in' };

  const started = performance.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch('/api/ai', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ task, payload, provider, model: task === 'insights' ? settings.extractModel : model }),
      signal: controller.signal,
    });
    const body: unknown = await res.json();
    const envelope = z
      .object({ ok: z.boolean(), data: z.unknown().optional(), error: z.string().optional() })
      .safeParse(body);
    if (!envelope.success || !envelope.data.ok) {
      return { ok: false, error: envelope.success ? (envelope.data.error ?? 'AI request failed') : 'Malformed AI response' };
    }
    const parsed = TASK_SCHEMAS[task].safeParse(envelope.data.data);
    if (!parsed.success) return { ok: false, error: 'AI returned malformed data' };
    return {
      ok: true,
      data: parsed.data as z.infer<(typeof TASK_SCHEMAS)[T]>,
      latencyMs: Math.round(performance.now() - started),
      model: task === 'insights' ? settings.extractModel : model,
      provider,
    };
  } catch (e) {
    const aborted = e instanceof DOMException && e.name === 'AbortError';
    return { ok: false, error: aborted ? 'AI request timed out' : 'AI unavailable' };
  } finally {
    clearTimeout(timer);
  }
}
