## What to try (M4 — dose engine + IOB)

The dose math is in and all spec golden tests (§5.5 dose cases, §6 IOB) pass in CI.

1. **Settings → Dose calculator (debug)** → enter e.g. 80 g carbs, 40 g fat, 25 g protein: expect
   **11 u**, split **7 now + 4 at +60 min**, with the full breakdown (carb dose, fat/protein weights,
   correction, IOB, combined multiplier, raw → rounded, lead time).
2. Tap extra factors (Sunburn 2.0, Sleep 1.25, Stress 1.20) and watch the combined multiplier cap at 2.0.
3. Set carbs 0, BG 80, IOB 2 → **0 u** and "consider ~28 g carbs".
4. Leave BG blank to use your live CGM value and trend.

Nothing is logged from this screen — logging arrives in M5.
