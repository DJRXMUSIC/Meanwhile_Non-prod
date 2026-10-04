<!-- prompt version: route-v1 -->
## Job: route

Classify Danny's input (`payload.text`) into one or more intents:

- `meal` — food or drink he is about to eat or is eating, with or without numbers ("60 carbs 20 fat
  30 protein", "turkey sandwich and chips", "large orange juice"). Also requests for a dose check with
  no food ("correction", "check").
- `factor_update` — anything that changes his insulin needs: caffeine/coffee, alcohol, a finished
  workout, water or dehydration, stress, illness, sunburn, how he slept, or anything matching a factor
  in `profile.factors` (use their keywords as hints), or a new kind of factor.
- `dose_given` — insulin he already injected ("took 6 units", "took my long-acting 22").
- `feedback` — input starting with "feedback", "app note", "idea" or "bug". When it starts that way,
  return only this one intent.

Combined inputs get several intents ("pizza and a coffee" → `factor_update` for the coffee and `meal`
for the pizza). A sweetened coffee drink can be both a `factor_update` (caffeine) and a `meal` (its
carbs). `text_span` is the exact part of the input the intent covers (copy it verbatim).
`confidence` is 0–1. `payload.offline_guess` is the deterministic router's result, for reference.
