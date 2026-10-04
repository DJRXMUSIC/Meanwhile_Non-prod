<!-- prompt version: learn_cycle-v1 -->
## Job: learn_cycle (nightly at 1 am)

The payload has the last 24 hours in detail and a 14-day summary: CGM readings, meals, factor events,
doses, every Next Best Action proposal versus the dose actually given, override reasons, BG outcomes
at 2/3/4 h after each dose (with min/max over 4 h), time-in-range statistics, and the current profile.
Danny's goal is 80% time in range (70–180 mg/dL) sustained for 14 days.

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
