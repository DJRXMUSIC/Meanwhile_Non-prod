<!-- prompt version: learn_cycle-v2 -->
## Job: learn_cycle (continuous learning review)

`mode` says which review this is:
- `nightly` — the full review at the 1 am reset, over the 24 hours before it.
- `incremental` — a mid-day review because new dose outcomes arrived. Focus on what the newest
  lessons add; returning no changes is normal and fine.

The payload has the last 24 hours in detail and a 14-day summary: CGM readings, meals, factor events,
doses, every Next Best Action proposal versus the dose actually given, override reasons, BG outcomes
at 2/3/4 h after each dose (with min/max over 4 h), time-in-range statistics, and the current profile.
Danny's goal is 80% time in range (70–180 mg/dL) sustained for 14 days.

`learning` is what the app has already learned, computed deterministically on the phone:
- `lessons`: every completed dose turned into a measured error — `units_needed` is what would have
  landed on target (from the end BG, or from the low when it went below 70), and `implied_icr` /
  `implied_isf` / `implied_units_per_event` are the setting values that one outcome points to.
  `clean: false` lessons were confounded (another dose or meal in the 4 h, no BG, no CGM) — use them
  only as context.
- `evidence`: the median each tunable value's clean lessons imply. The phone already tunes ICR, ISF
  and units-per-event from this on its own, in steps; you add what it can't see — time-of-day
  patterns, factor weights and windows, fat/protein handling, lead time, new factors.
- `under_evaluation`: changes already applied and still being judged on the outcomes that follow
  them. Leave these alone unless the data clearly shows harm — the phone reverts them automatically
  if outcomes get worse.
- `recent_journal`: what was applied, kept, reverted or undone in the last 14 days. Don't re-propose a
  reverted or undone change without new evidence that addresses why it failed.
- `autonomy`: `auto` means your changes apply immediately (Danny is told and can undo; the phone
  judges and reverts them); `auto_factors` means factor changes apply and ICR/ISF/target/insulin-curve
  changes wait for Danny; `ask` means everything waits for review. Either way: propose exactly what
  the evidence supports, with the numbers.

Find what the data says about his real insulin needs and propose changes. There is no cap on how far a
value may move in one night and no minimum sample size — propose what the evidence supports and say
how strong it is.

- `proposed_settings`: new ICR, ISF, target, lead time base, IOB peak and duration — null for any you
  would keep.
- `factor_changes`: changes to an existing factor's `units_add`, `weight`/`default_weight`, `bounds`
  (new_json like {"min":0.8,"max":1.1}), `window` (minutes, or a full window object), or `decay`
  ({"steps":[{"from_minutes":0,"weight":0.7}, …]}). `old_json`/`new_json` are JSON-encoded values
  (e.g. "0.85", "{\"min\":0.8,\"max\":1.1}").
- `new_factors`: patterns not covered by existing factors (for example dawn phenomenon, menstrual
  cycle, a specific food) with a full definition.
- `setting_changes`: any other tunable by profile path, e.g. `meal.kFatPerG`, `meal.lowCarbThresholdG`,
  `split.minFatG`, `split.firstFraction`, `split.secondAfterMin`, `leadTime.highBgStepMin`,
  `factors.F11.params.perHour`, `dose.combinedCap`.
- `evidence` (per change): plain language with the numbers — which meals/doses/outcomes, how many, and
  what happened (e.g. "4 of 5 dinners with ≥40 g fat ran above 220 at +3 h with the dose followed").
- `observations`: other notable patterns, including where Danny overrode proposals and how that went.
- `summary`: two or three sentences for the morning report.

Return empty lists and nulls when the data supports no change.
