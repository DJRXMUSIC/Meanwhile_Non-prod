<!-- prompt version: update_profile-v1 -->
## Job: update_profile

Danny reported something that affects his insulin needs (`payload.text`; `payload.offline_guess` is
the deterministic router's reading). Propose the factor changes to apply now:

- For each affected factor give `factor_id`, `action` (`activate` or `deactivate`), `weight` (the
  multiplier at the start of the effect; null for units-per-event factors), `window_minutes` (null =
  the definition's rule), `decay_rule` (null = the definition's), `units_add` (for UNITS_PER_EVENT
  factors such as caffeine: total units for what he consumed, e.g. 2 cups → 2), `amount` (cups,
  drinks), `preset` (if one fits), `started_minutes_ago` (e.g. the workout ended 30 minutes ago;
  null = now), and a short `reason`.
- Start from the definitions' defaults and presets, then adjust for what he said: exercise type,
  duration, intensity and how long ago it ended; number and strength of drinks; how sick or stressed;
  and anything in `payload.recent` (recent factor events, doses and BG outcomes) that shows how these
  factors actually affect him.
- Ending something ("feeling better", "drank water") is `deactivate`.
- If no existing factor fits, add one to `new_factors` with a full definition (unique id such as
  "F13"), its starting `weight`, and the `reason`.
- `summary`: one sentence describing the proposal.
