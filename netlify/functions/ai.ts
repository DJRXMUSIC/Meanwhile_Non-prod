// The only server code: JWT-verified key proxy to Anthropic / OpenAI.
// Uses raw fetch (no provider SDKs) to keep cold-start size down, per spec §9.1.
import { createClient } from '@supabase/supabase-js';

const TIMEOUT_MS = 20_000;

type Task = 'extract' | 'rationale' | 'insights';
type Provider = 'anthropic' | 'openai';

// ---- Prompts (copies of src/lib/ai/prompts.ts — keep in sync) ----

const EXTRACT_SYSTEM_PROMPT = `You are the extraction layer of a personal Type 1 diabetes decision-support app.
Convert the user's utterance into structured data via the emit tool. Rules:
1. NEVER compute, suggest, or mention insulin doses. Dose math is handled by
   deterministic code downstream. You only extract.
2. Estimate carbs, fat, and protein in grams per food item using typical US
   portions. If the user states quantities, use them exactly. When uncertain,
   choose a realistic median and lower that item's confidence.
3. If ambiguity could shift total carbs by more than ~30%, set
   clarificationNeeded to ONE short question — but still emit your best-guess
   numbers so the app can show a provisional result.
4. Capture stated blood glucose ("I'm at 150" → userStatedBg: 150), insulin
   already taken ("took 3 units" → userStatedDoseUnits: 3), and context tags
   (exercise, alcohol, illness, stress, poor sleep, travel).
5. intent: "meal" if food is being eaten now or imminently; "correction_check"
   if no food and the user is asking about a high/low; "context_log" if purely
   informational; "question" for general questions.
6. overall confidence: your confidence in the TOTAL carb estimate, 0–1.`;

const RATIONALE_SYSTEM_PROMPT = `You write the explanation shown beneath a computed insulin recommendation in a
personal T1D decision-support app. You receive the deterministic calculation
that was already performed, including its exact breakdown lines and flags.
Rules:
1. Restate the reasoning in 2–4 plain, direct sentences. You MUST NOT alter,
   re-derive, or contradict any number. Never propose a different dose.
2. For each flag present, add one caution string: stale_bg → note the data age;
   large_dose / high_carb_parse → tell the user to verify the parsed meal;
   active_cob_falling → carbs still absorbing while BG falls; contextTags
   exercise_recent/alcohol → elevated delayed-hypo risk; high fat+protein meal
   (fatG+proteinG > 40) → possible delayed rise over 3–5 h.
3. Voice: direct and evidence-based. No filler, no moralizing, no "consult your
   doctor" boilerplate — the app's framing already covers that.`;

const INSIGHTS_SYSTEM_PROMPT = `You analyze aggregated outcome statistics from one user's logged T1D data and
propose at most 3 refinements. Rules:
1. Only suggest changes the data actually supports; cite the specific stats in
   \`evidence\`. If the data is insufficient (<20 outcomes or contradictory),
   return an empty suggestions array.
2. Conservative steps: no parameter change greater than 15% in one suggestion.
3. "behavior" suggestions cover timing patterns (e.g., pre-bolus lead time,
   overnight drift), not parameter edits.
4. Suggestions are advisory. The user reviews and accepts or dismisses each.`;

// ---- JSON Schemas for structured output ----

const EXTRACT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    intent: { type: 'string', enum: ['meal', 'correction_check', 'context_log', 'question'] },
    meal: {
      type: ['object', 'null'],
      additionalProperties: false,
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            additionalProperties: false,
            properties: {
              name: { type: 'string' },
              carbsG: { type: 'number' },
              fatG: { type: 'number' },
              proteinG: { type: 'number' },
              confidence: { type: 'number' },
            },
            required: ['name', 'carbsG', 'fatG', 'proteinG', 'confidence'],
          },
        },
        totalCarbsG: { type: 'number' },
        totalFatG: { type: 'number' },
        totalProteinG: { type: 'number' },
      },
      required: ['items', 'totalCarbsG', 'totalFatG', 'totalProteinG'],
    },
    contextTags: {
      type: 'array',
      items: {
        type: 'string',
        enum: ['exercise_recent', 'exercise_planned', 'alcohol', 'illness', 'stress', 'poor_sleep', 'travel', 'other'],
      },
    },
    userStatedBg: { type: ['number', 'null'] },
    userStatedDoseUnits: { type: ['number', 'null'] },
    question: { type: ['string', 'null'] },
    confidence: { type: 'number' },
    clarificationNeeded: { type: ['string', 'null'] },
  },
  required: [
    'intent', 'meal', 'contextTags', 'userStatedBg', 'userStatedDoseUnits',
    'question', 'confidence', 'clarificationNeeded',
  ],
};

const RATIONALE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    rationale: { type: 'string' },
    cautions: { type: 'array', items: { type: 'string' } },
  },
  required: ['rationale', 'cautions'],
};

const INSIGHTS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    suggestions: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          parameter: { type: 'string', enum: ['icr', 'isf', 'targetBg', 'behavior'] },
          current: { type: 'string' },
          suggested: { type: 'string' },
          expectedEffect: { type: 'string' },
          evidence: { type: 'string' },
          confidence: { type: 'number' },
        },
        required: ['parameter', 'current', 'suggested', 'expectedEffect', 'evidence', 'confidence'],
      },
    },
  },
  required: ['suggestions'],
};

const TASKS: Record<Task, { system: string; schema: object }> = {
  extract: { system: EXTRACT_SYSTEM_PROMPT, schema: EXTRACT_SCHEMA },
  rationale: { system: RATIONALE_SYSTEM_PROMPT, schema: RATIONALE_SCHEMA },
  insights: { system: INSIGHTS_SYSTEM_PROMPT, schema: INSIGHTS_SCHEMA },
};

const DEFAULT_MODELS: Record<Provider, string> = {
  anthropic: 'claude-sonnet-4-6',
  openai: 'gpt-4o',
};

function json(status: number, body: object): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

async function callAnthropic(
  model: string,
  system: string,
  userMessage: string,
  schema: object,
  signal: AbortSignal,
): Promise<unknown> {
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    signal,
    headers: {
      'x-api-key': process.env.ANTHROPIC_API_KEY ?? '',
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model,
      max_tokens: 1024,
      temperature: 0.2,
      system,
      messages: [{ role: 'user', content: userMessage }],
      tools: [{ name: 'emit', description: 'Emit the structured result', input_schema: schema }],
      tool_choice: { type: 'tool', name: 'emit' },
    }),
  });
  if (!res.ok) {
    const text = await res.text();
    console.error('anthropic error', res.status, text);
    throw new Error('provider_error');
  }
  const body = (await res.json()) as { content?: { type: string; input?: unknown }[] };
  // Find the tool_use block by type — never by position.
  const toolUse = body.content?.find((b) => b.type === 'tool_use');
  if (!toolUse) throw new Error('provider_error');
  return toolUse.input;
}

async function callOpenAi(
  model: string,
  system: string,
  userMessage: string,
  schema: object,
  signal: AbortSignal,
): Promise<unknown> {
  const res = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    signal,
    headers: {
      authorization: `Bearer ${process.env.OPENAI_API_KEY ?? ''}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model,
      temperature: 0.2,
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: userMessage },
      ],
      response_format: {
        type: 'json_schema',
        json_schema: { name: 'emit', strict: true, schema },
      },
    }),
  });
  if (!res.ok) {
    const text = await res.text();
    console.error('openai error', res.status, text);
    throw new Error('provider_error');
  }
  const body = (await res.json()) as { choices?: { message?: { content?: string } }[] };
  const content = body.choices?.[0]?.message?.content;
  if (!content) throw new Error('provider_error');
  return JSON.parse(content);
}

export default async function handler(req: Request): Promise<Response> {
  if (req.method !== 'POST') return json(405, { ok: false, error: 'Method not allowed' });

  // 1. Verify the Supabase JWT.
  const auth = req.headers.get('authorization') ?? '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (!token) return json(401, { ok: false, error: 'Unauthorized' });
  const supabase = createClient(process.env.SUPABASE_URL ?? '', process.env.SUPABASE_ANON_KEY ?? '');
  const { data: userData, error: authError } = await supabase.auth.getUser(token);
  if (authError || !userData.user) return json(401, { ok: false, error: 'Unauthorized' });

  // 2. Parse the request.
  let parsed: { task?: string; payload?: unknown; provider?: string; model?: string };
  try {
    parsed = await req.json();
  } catch {
    return json(400, { ok: false, error: 'Invalid JSON body' });
  }
  const task = parsed.task as Task;
  if (!task || !(task in TASKS)) return json(400, { ok: false, error: 'Unknown task' });

  const provider: Provider =
    parsed.provider === 'openai' || parsed.provider === 'anthropic'
      ? parsed.provider
      : ((process.env.AI_DEFAULT_PROVIDER as Provider | undefined) ?? 'anthropic');
  const model = parsed.model || DEFAULT_MODELS[provider];
  const { system, schema } = TASKS[task];
  const userMessage = JSON.stringify(parsed.payload ?? {});

  // 3. Call the provider with a hard timeout; never leak provider error bodies.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const data =
      provider === 'anthropic'
        ? await callAnthropic(model, system, userMessage, schema, controller.signal)
        : await callOpenAi(model, system, userMessage, schema, controller.signal);
    return json(200, { ok: true, data });
  } catch (e) {
    console.error('ai function error', e);
    const timedOut = e instanceof DOMException && e.name === 'AbortError';
    return json(timedOut ? 504 : 502, {
      ok: false,
      error: timedOut ? 'AI request timed out' : 'AI provider error',
    });
  } finally {
    clearTimeout(timer);
  }
}
