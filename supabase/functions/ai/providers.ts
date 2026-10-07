import Anthropic from "npm:@anthropic-ai/sdk@0.131.0";
import type { Job } from "./schemas.ts";

export type Provider = "gemini" | "claude";

export interface ProviderCall {
  job: Job;
  system: string;
  user: string;
  schema: Record<string, unknown>;
  timeoutMs: number;
}

export interface ProviderResult {
  text: string;
  model: string;
}

export class ProviderError extends Error {
  constructor(message: string, readonly retryable = true) {
    super(message);
  }
}

// Model names come from env so they can be upgraded without code changes (spec §10.1).
// *_FAST_MODEL is used for the jobs Danny waits on (route, estimate_meal, update_profile): Gemini
// 3.8 Flash (Danny's choice for day-to-day) with Claude Opus 5.5 as the fallback (Danny: no Haiku;
// he would rather wait than time out). 1.4: the quick jobs kept timing out because Gemini 3.x thinks
// deeply by default; they now ask Gemini for minimal/low thinking, and each provider gets 60 s.
// If Google doesn't know a configured model, the call retries once on the matching
// "-latest" alias rather than failing. Learning on Claude runs as a batch at max effort
// (CLAUDE_LEARN_MODEL / CLAUDE_LEARN_EFFORT; see below).
export const FAST_JOBS: Job[] = ["route", "estimate_meal", "update_profile"];

function geminiModel(job: Job): string {
  if (FAST_JOBS.includes(job)) return Deno.env.get("GEMINI_FAST_MODEL") ?? "gemini-3.8-flash";
  return Deno.env.get("GEMINI_MODEL") ?? "gemini-pro-latest";
}

function claudeModel(job: Job): string {
  const main = Deno.env.get("CLAUDE_MODEL") ?? "claude-opus-5-5";
  return FAST_JOBS.includes(job) ? Deno.env.get("CLAUDE_FAST_MODEL") ?? main : main;
}

/**
 * Gemini 3.x thinking depth (`thinkingLevel`; the older `thinkingBudget` is an error on newer
 * models). Quick jobs think as little as the job allows — Danny is waiting; null = the model's
 * default (deep) for the learn cycle.
 */
export const GEMINI_THINKING: Record<Job, "MINIMAL" | "LOW" | null> = {
  route: "MINIMAL",
  estimate_meal: "LOW",
  update_profile: "LOW",
  learn_cycle: null,
};

/** Haiku takes no `effort`; the other current Claude models do. */
function supportsEffort(model: string): boolean {
  return !model.startsWith("claude-haiku");
}

export function available(p: Provider): boolean {
  return Boolean(Deno.env.get(p === "gemini" ? "GEMINI_API_KEY" : "ANTHROPIC_API_KEY"));
}

export function modelFor(p: Provider, job: Job): string {
  return p === "gemini" ? geminiModel(job) : claudeModel(job);
}

/** Effort per job (models that take it): quick jobs stay quick; the learn cycle thinks hardest. */
const CLAUDE_EFFORT: Record<Job, "low" | "medium" | "high"> = {
  route: "low",
  estimate_meal: "low",
  update_profile: "medium",
  learn_cycle: "high",
};

const MAX_TOKENS: Record<Job, number> = {
  route: 2_000,
  estimate_meal: 2_000,
  update_profile: 8_000,
  learn_cycle: 32_000,
};

let anthropic: Anthropic | null = null;

function anthropicClient(): Anthropic {
  const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!apiKey) throw new ProviderError("ANTHROPIC_API_KEY not set", false);
  anthropic ??= new Anthropic({ apiKey, maxRetries: 0 });
  return anthropic;
}

/** Maps an Anthropic SDK error to a ProviderError (auth problems are not worth retrying). */
function claudeError(e: unknown, timeoutMs?: number): ProviderError {
  if (e instanceof ProviderError) return e;
  if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) {
    return new ProviderError(`Claude auth failed: ${e.message}`, false);
  }
  if (e instanceof Anthropic.BadRequestError) return new ProviderError(`Claude rejected the request: ${e.message}`);
  if (e instanceof Anthropic.RateLimitError) return new ProviderError("Claude rate limited");
  if (e instanceof Anthropic.APIConnectionTimeoutError) return new ProviderError(`Claude timed out${timeoutMs ? ` after ${timeoutMs} ms` : ""}`);
  if (e instanceof Anthropic.APIError) return new ProviderError(`Claude API error ${e.status}: ${e.message}`);
  return new ProviderError(`Claude call failed: ${(e as Error).message}`);
}

export async function callClaude(c: ProviderCall): Promise<ProviderResult> {
  const client = anthropicClient();
  const model = claudeModel(c.job);
  const outputConfig = {
    ...(supportsEffort(model) ? { effort: CLAUDE_EFFORT[c.job] } : {}),
    format: { type: "json_schema" as const, schema: c.schema },
  };
  const base = {
    model,
    max_tokens: MAX_TOKENS[c.job],
    // Stable instructions first and cached; the per-call payload goes in the user turn.
    system: [{ type: "text" as const, text: c.system, cache_control: { type: "ephemeral" as const } }],
    messages: [{ role: "user" as const, content: c.user }],
    output_config: outputConfig,
  };
  const options = { timeout: c.timeoutMs, signal: AbortSignal.timeout(c.timeoutMs) };
  try {
    // Stream so long outputs never hit an HTTP timeout; finalMessage() collects it. Opus/Sonnet
    // also ask Anthropic to re-run a safety-classifier decline on its recommended model (a Haiku
    // set via CLAUDE_FAST_MODEL goes without that beta).
    // deno-lint-ignore no-explicit-any
    const message: any = !supportsEffort(model)
      // deno-lint-ignore no-explicit-any
      ? await client.messages.stream(base as any, options).finalMessage()
      // deno-lint-ignore no-explicit-any
      : await client.beta.messages.stream({ ...base, betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" } as any, options).finalMessage();
    if (message.stop_reason === "refusal") {
      throw new ProviderError(`Claude declined (${message.stop_details?.category ?? "unspecified"})`);
    }
    if (message.stop_reason === "max_tokens") throw new ProviderError("Claude output hit max_tokens");
    const text = (message.content ?? [])
      // deno-lint-ignore no-explicit-any
      .filter((b: any) => b.type === "text")
      // deno-lint-ignore no-explicit-any
      .map((b: any) => b.text)
      .join("");
    if (!text) throw new ProviderError("Claude returned no text");
    return { text, model: message.model ?? model };
  } catch (e) {
    throw claudeError(e, c.timeoutMs);
  }
}

// --- Learning on Claude at max effort, as a Message Batch (1.4) -----------------------------------
// A max-effort review can think far longer than an Edge Function may run (~150 s), so it goes to
// the Message Batches API: submitted in a second, collected by the phone's later polls (most batches
// end within an hour; at most 24 h), at half the price. Batches take no `fallbacks` parameter.

export const LEARN_CUSTOM_ID = "learn_cycle";
/** Opus 5.5's output limit: room for max-effort thinking plus the answer. */
export const LEARN_BATCH_MAX_TOKENS = 128_000;

export function claudeLearnModel(): string {
  return Deno.env.get("CLAUDE_LEARN_MODEL") ?? Deno.env.get("CLAUDE_MODEL") ?? "claude-opus-5-5";
}

export function claudeLearnEffort(): "low" | "medium" | "high" | "xhigh" | "max" {
  const e = Deno.env.get("CLAUDE_LEARN_EFFORT");
  return e === "low" || e === "medium" || e === "high" || e === "xhigh" || e === "max" ? e : "max";
}

/** The batch request for one learn-cycle review: stable instructions cached, the payload as the user turn. */
export function learnBatchParams(system: string, user: string, schema: Record<string, unknown>) {
  return {
    model: claudeLearnModel(),
    max_tokens: LEARN_BATCH_MAX_TOKENS,
    system: [{ type: "text" as const, text: system, cache_control: { type: "ephemeral" as const } }],
    messages: [{ role: "user" as const, content: user }],
    output_config: {
      effort: claudeLearnEffort(),
      format: { type: "json_schema" as const, schema },
    },
  };
}

export async function submitLearnBatch(system: string, user: string, schema: Record<string, unknown>): Promise<{ id: string; model: string }> {
  const client = anthropicClient();
  const params = learnBatchParams(system, user, schema);
  try {
    // deno-lint-ignore no-explicit-any
    const batch = await client.messages.batches.create({ requests: [{ custom_id: LEARN_CUSTOM_ID, params: params as any }] }, { timeout: 30_000 });
    return { id: batch.id, model: params.model };
  } catch (e) {
    throw claudeError(e, 30_000);
  }
}

export type BatchPoll =
  | { state: "processing"; status: string }
  | { state: "done"; text: string; model: string }
  | { state: "failed"; error: string };

export async function pollLearnBatch(id: string): Promise<BatchPoll> {
  const client = anthropicClient();
  try {
    const batch = await client.messages.batches.retrieve(id, {}, { timeout: 20_000 });
    if (batch.processing_status !== "ended") return { state: "processing", status: batch.processing_status };
    // deno-lint-ignore no-explicit-any
    const results: any[] = [];
    for await (const r of await client.messages.batches.results(id, {}, { timeout: 20_000 })) results.push(r);
    return readBatchResults(results);
  } catch (e) {
    throw claudeError(e, 20_000);
  }
}

/** The learn review's outcome among a finished batch's results (pure, so it is tested without the API). */
// deno-lint-ignore no-explicit-any
export function readBatchResults(results: any[]): BatchPoll {
  const r = results.find((x) => x?.custom_id === LEARN_CUSTOM_ID) ?? results[0];
  if (!r) return { state: "failed", error: "the batch ended with no result" };
  switch (r.result?.type) {
    case "succeeded": {
      const msg = r.result.message;
      if (msg?.stop_reason === "refusal") return { state: "failed", error: `Claude declined (${msg.stop_details?.category ?? "unspecified"})` };
      if (msg?.stop_reason === "max_tokens") return { state: "failed", error: "Claude output hit max_tokens" };
      // deno-lint-ignore no-explicit-any
      const text = (msg?.content ?? []).filter((b: any) => b?.type === "text").map((b: any) => b.text).join("");
      if (!text) return { state: "failed", error: "Claude returned no text" };
      return { state: "done", text, model: msg.model ?? claudeLearnModel() };
    }
    case "errored": {
      const err = r.result.error;
      return { state: "failed", error: `Claude couldn't run the review: ${err?.error?.message ?? err?.message ?? err?.type ?? "unknown error"}` };
    }
    case "expired":
      return { state: "failed", error: "the batch expired before Claude ran it" };
    case "canceled":
      return { state: "failed", error: "the batch was canceled" };
    default:
      return { state: "failed", error: `unexpected batch result: ${r.result?.type ?? "none"}` };
  }
}

/**
 * Gemini REST generateContent with JSON-schema output (generationConfig.responseJsonSchema). Every
 * retry inside (unknown model → its -latest alias; thinking level not supported → without it)
 * shares the one [ProviderCall.timeoutMs].
 */
export async function callGemini(c: ProviderCall): Promise<ProviderResult> {
  const started = Date.now();
  const left = () => Math.max(1_000, c.timeoutMs - (Date.now() - started));
  const thinking = GEMINI_THINKING[c.job];
  const attempt = async (model: string) => {
    try {
      return await callGeminiModel({ ...c, timeoutMs: left() }, model, thinking);
    } catch (e) {
      if (e instanceof ThinkingUnsupportedError && thinking !== null) return await callGeminiModel({ ...c, timeoutMs: left() }, model, null);
      throw e;
    }
  };
  const model = geminiModel(c.job);
  try {
    return await attempt(model);
  } catch (e) {
    const alias = FAST_JOBS.includes(c.job) ? "gemini-flash-latest" : "gemini-pro-latest";
    if (e instanceof UnknownModelError && model !== alias) return await attempt(alias);
    throw e;
  }
}

class UnknownModelError extends ProviderError {}
class ThinkingUnsupportedError extends ProviderError {}

async function callGeminiModel(c: ProviderCall, model: string, thinking: string | null): Promise<ProviderResult> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) throw new ProviderError("GEMINI_API_KEY not set", false);
  let res: Response;
  try {
    res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: c.system }] },
        contents: [{ role: "user", parts: [{ text: c.user }] }],
        generationConfig: {
          responseMimeType: "application/json",
          responseJsonSchema: c.schema,
          maxOutputTokens: MAX_TOKENS[c.job],
          ...(thinking ? { thinkingConfig: { thinkingLevel: thinking } } : {}),
        },
      }),
      signal: AbortSignal.timeout(c.timeoutMs),
    });
  } catch (e) {
    const name = (e as Error).name;
    throw new ProviderError(name === "TimeoutError" ? `Gemini timed out after ${c.timeoutMs} ms` : `Gemini network error: ${(e as Error).message}`);
  }
  const body = await res.text();
  if (!res.ok) {
    if (res.status === 404) throw new UnknownModelError(`Gemini doesn't know model ${model}: ${body.slice(0, 200)}`);
    if (res.status === 400 && thinking && /thinking/i.test(body)) throw new ThinkingUnsupportedError(`Gemini ${model} rejected thinkingLevel: ${body.slice(0, 200)}`);
    const fatal = res.status === 401 || res.status === 403;
    throw new ProviderError(`Gemini HTTP ${res.status}: ${body.slice(0, 300)}`, !fatal);
  }
  // deno-lint-ignore no-explicit-any
  let data: any;
  try {
    data = JSON.parse(body);
  } catch {
    throw new ProviderError("Gemini returned non-JSON");
  }
  if (data.promptFeedback?.blockReason) throw new ProviderError(`Gemini blocked the prompt (${data.promptFeedback.blockReason})`);
  const cand = data.candidates?.[0];
  if (!cand) throw new ProviderError("Gemini returned no candidates");
  if (cand.finishReason && !["STOP", "FINISH_REASON_UNSPECIFIED"].includes(cand.finishReason)) {
    throw new ProviderError(`Gemini finished with ${cand.finishReason}`);
  }
  const text = (cand.content?.parts ?? [])
    // deno-lint-ignore no-explicit-any
    .filter((p: any) => typeof p.text === "string" && !p.thought)
    // deno-lint-ignore no-explicit-any
    .map((p: any) => p.text)
    .join("");
  if (!text) throw new ProviderError("Gemini returned no text");
  return { text, model: data.modelVersion ?? model };
}

export function call(p: Provider, c: ProviderCall): Promise<ProviderResult> {
  return p === "gemini" ? callGemini(c) : callClaude(c);
}
