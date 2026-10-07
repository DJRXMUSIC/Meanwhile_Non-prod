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
// *_FAST_MODEL is used for the jobs Danny waits on (route, estimate_meal, update_profile), which
// have a 15 s budget. Gemini Pro routinely needs 8-15 s even for these, so Gemini's fast model
// defaults to Flash; Claude's defaults to the main model unless CLAUDE_FAST_MODEL is set.
const FAST_JOBS: Job[] = ["route", "estimate_meal", "update_profile"];

function geminiModel(job: Job): string {
  if (FAST_JOBS.includes(job)) return Deno.env.get("GEMINI_FAST_MODEL") ?? "gemini-flash-latest";
  return Deno.env.get("GEMINI_MODEL") ?? "gemini-pro-latest";
}

function claudeModel(job: Job): string {
  const fast = Deno.env.get("CLAUDE_FAST_MODEL");
  if (fast && FAST_JOBS.includes(job)) return fast;
  return Deno.env.get("CLAUDE_MODEL") ?? "claude-opus-5-5";
}

export function available(p: Provider): boolean {
  return Boolean(Deno.env.get(p === "gemini" ? "GEMINI_API_KEY" : "ANTHROPIC_API_KEY"));
}

export function modelFor(p: Provider, job: Job): string {
  return p === "gemini" ? geminiModel(job) : claudeModel(job);
}

/** Effort per job: fast jobs stay inside their 15 s budget; the learn cycle thinks hardest. */
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

export async function callClaude(c: ProviderCall): Promise<ProviderResult> {
  const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!apiKey) throw new ProviderError("ANTHROPIC_API_KEY not set", false);
  anthropic ??= new Anthropic({ apiKey, maxRetries: 0 });
  const model = claudeModel(c.job);
  const params = {
    model,
    max_tokens: MAX_TOKENS[c.job],
    // Stable instructions first and cached; the per-call payload goes in the user turn.
    system: [{ type: "text" as const, text: c.system, cache_control: { type: "ephemeral" as const } }],
    messages: [{ role: "user" as const, content: c.user }],
    output_config: {
      effort: CLAUDE_EFFORT[c.job],
      format: { type: "json_schema" as const, schema: c.schema },
    },
    // On a safety-classifier decline, Anthropic re-runs the request on its recommended model.
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default" as const,
  };
  try {
    // Stream so long learn-cycle outputs never hit an HTTP timeout; finalMessage() collects it.
    const message = await anthropic.beta.messages
      .stream(params, { timeout: c.timeoutMs, signal: AbortSignal.timeout(c.timeoutMs) })
      .finalMessage();
    if (message.stop_reason === "refusal") {
      throw new ProviderError(`Claude declined (${message.stop_details?.category ?? "unspecified"})`);
    }
    if (message.stop_reason === "max_tokens") throw new ProviderError("Claude output hit max_tokens");
    const text = message.content
      .filter((b): b is Extract<typeof b, { type: "text" }> => b.type === "text")
      .map((b) => b.text)
      .join("");
    if (!text) throw new ProviderError("Claude returned no text");
    return { text, model: message.model ?? model };
  } catch (e) {
    if (e instanceof ProviderError) throw e;
    if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) {
      throw new ProviderError(`Claude auth failed: ${e.message}`, false);
    }
    if (e instanceof Anthropic.BadRequestError) throw new ProviderError(`Claude rejected the request: ${e.message}`);
    if (e instanceof Anthropic.RateLimitError) throw new ProviderError("Claude rate limited");
    if (e instanceof Anthropic.APIConnectionTimeoutError) throw new ProviderError(`Claude timed out after ${c.timeoutMs} ms`);
    if (e instanceof Anthropic.APIError) throw new ProviderError(`Claude API error ${e.status}: ${e.message}`);
    throw new ProviderError(`Claude call failed: ${(e as Error).message}`);
  }
}

/** Gemini REST generateContent with JSON-schema output (generationConfig.responseJsonSchema). */
export async function callGemini(c: ProviderCall): Promise<ProviderResult> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) throw new ProviderError("GEMINI_API_KEY not set", false);
  const model = geminiModel(c.job);
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
    const fatal = res.status === 401 || res.status === 403;
    throw new ProviderError(`Gemini HTTP ${res.status}: ${body.slice(0, 300)}`, !fatal);
  }
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
    .filter((p: any) => typeof p.text === "string" && !p.thought)
    .map((p: any) => p.text)
    .join("");
  if (!text) throw new ProviderError("Gemini returned no text");
  return { text, model: data.modelVersion ?? model };
}

export function call(p: Provider, c: ProviderCall): Promise<ProviderResult> {
  return p === "gemini" ? callGemini(c) : callClaude(c);
}
