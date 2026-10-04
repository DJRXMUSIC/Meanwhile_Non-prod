import type { Job } from "./schemas.ts";

// Prompts are versioned text files (spec §10.4), deployed as static files (supabase/config.toml).
const cache = new Map<string, string>();

async function load(name: string): Promise<string> {
  const hit = cache.get(name);
  if (hit) return hit;
  const text = await Deno.readTextFile(new URL(`./prompts/${name}.md`, import.meta.url));
  cache.set(name, text);
  return text;
}

/** System = shared context + job instructions (stable, cacheable); user = this call's payload. */
export async function buildPrompt(job: Job, payload: unknown): Promise<{ system: string; user: string }> {
  const system = `${await load("_common")}\n\n${await load(job)}`;
  const user = "Payload:\n```json\n" + JSON.stringify(payload, null, 1) + "\n```";
  return { system, user };
}
