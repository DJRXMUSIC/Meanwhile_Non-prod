## What to try (M6 — AI layer)

Setup: `docs/INSTALL.md` §7 — add your Gemini and Anthropic keys as Supabase secrets yourself,
then re-run the **supabase** workflow to deploy the `ai` Edge Function.

1. Online, say **"two slices of pepperoni pizza and a coffee"**: the path line shows `ai router`;
   you get an **AI proposal** (caffeine +1 u) to Accept / edit / Reject, and an **AI estimate** of the
   pizza's carbs/fat/protein to confirm or edit before the dose is calculated.
2. **"ran 4 miles hard, finished 20 minutes ago"** → the AI proposes an exercise weight and window;
   edit the weight before accepting if you like. Every AI value is shown in purple until accepted.
3. Offline (airplane mode), say **"had two beers"** → the default weight applies immediately and an AI
   refinement is queued. Turn data back on → a notification "AI refinement ready" → review each
   change (Accept / Edit / Reject).
4. **Settings → AI provider**: switch between Gemini first / Claude first / only one. The top bar
   shows **AI offline** whenever the AI can't be reached — dose math keeps working regardless.
5. Fallback test (INSTALL §7): break the Gemini key; requests still succeed via Claude.
