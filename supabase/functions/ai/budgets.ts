import type { Job } from "./schemas.ts";

// Per provider. Jobs Danny waits on get 20 s each (1.4: up from 15 s — Gemini Flash answers in a few
// seconds, so this only matters on a bad network or a cold start) and 45 s for the pair; the app
// shows every step while it waits and offers "skip AI". The learn cycle answered directly (Gemini
// Pro) gets 120 s; on Claude it runs as a batch with no time limit.
export const TIMEOUT_MS: Record<Job, number> = {
  route: 20_000,
  estimate_meal: 20_000,
  update_profile: 20_000,
  learn_cycle: 120_000,
};

// Edge Functions have a ~150 s wall clock; keep headroom for auth and the response.
export const BUDGET_MS: Record<Job, number> = {
  route: 45_000,
  estimate_meal: 45_000,
  update_profile: 45_000,
  learn_cycle: 145_000,
};
