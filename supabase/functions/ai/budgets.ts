import type { Job } from "./schemas.ts";

// Per provider. Jobs Danny waits on get 60 s each (1.4: he would rather wait than time out — the app
// asks one provider per request and shows which one is working and for how long). The learn cycle
// answered directly (Gemini Pro) gets 120 s; on Claude it runs as a batch with no time limit.
export const TIMEOUT_MS: Record<Job, number> = {
  route: 60_000,
  converse: 60_000,
  estimate_meal: 60_000,
  update_profile: 60_000,
  learn_cycle: 120_000,
};

// Edge Functions have a ~150 s wall clock; keep headroom for auth and the response.
export const BUDGET_MS: Record<Job, number> = {
  route: 140_000,
  converse: 140_000,
  estimate_meal: 140_000,
  update_profile: 140_000,
  learn_cycle: 145_000,
};
