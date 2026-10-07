<!-- prompt version: route-v2 -->
## Job: route

Danny talks to the app like a person: he says what he is doing, has done, or wants to know, and
sometimes corrects himself. Classify his message (`payload.text`) into one or more intents:

- `meal` — food or drink he is about to eat or is eating, with or without numbers ("60 carbs 20 fat
  30 protein", "turkey sandwich and chips", "large orange juice"). Also a question about what to do
  with no food ("what should I do?", "correction", "do I need insulin?", "should I eat something?") —
  `text_span` is then the question.
- `factor_update` — anything that changes his insulin needs: caffeine/coffee, alcohol, a finished
  workout, water or dehydration, stress, illness, sunburn, how he slept, or anything matching a factor
  in `profile.factors` (use their keywords as hints), or a new kind of factor.
- `dose_given` — insulin he says he injected, with the amount ("took 6 units", "took my long-acting
  22", "bolused 5 twenty minutes ago").
- `dose_correction` — he corrects or cancels a dose he logged a moment ago: "never mind, I only took
  5", "make that 4", "actually it was 5 units", "I didn't take any", "cancel that", "scratch that",
  "I took it 20 minutes ago, not now". A correction is never a new dose: "never mind I only took 5"
  is one `dose_correction`, not a `dose_given`.
- `followed` — he did what the app's last suggestion said, without giving numbers: "took it",
  "done", "did it", "ate it", "took the dose".
- `bg_reading` — a blood glucose value he states ("BG 140", "my sugar is 85", "I'm at 210",
  "fingerstick says 95"); the app uses it instead of the CGM for this message.
- `feedback` — input starting with "feedback", "app note", "idea" or "bug". When it starts that way,
  return only this one intent.

Combined inputs get several intents ("pizza and a coffee" → `factor_update` for the coffee and `meal`
for the pizza; "BG 180 and 45 carbs" → `bg_reading` and `meal`). A sweetened coffee drink can be both
a `factor_update` (caffeine) and a `meal` (its carbs). `text_span` is the exact part of the input the
intent covers (copy it verbatim) — the app reads every number from it with code, so keep the numbers
in the span. `confidence` is 0–1. `payload.offline_guess` is the deterministic router's result, for
reference.
