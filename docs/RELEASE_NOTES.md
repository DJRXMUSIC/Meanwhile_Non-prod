## MeanwhileV4 1.0 — all milestones (M1–M8)

First complete build. Install: download the APK below → open → allow "install unknown apps" for your browser
(or point Obtainium at this repo — see `docs/INSTALL.md`).

### New in this build (M8 — stats + polish)
- **Stats** (bar-chart icon, top of the main screen): time in range today / 7 / 14 / 30 days against the 80% goal
  with your current ≥ 80% streak, below 70 / below 54 / above 180 / above 250, mean, SD, GMI, a daily
  in-range bar for each day, proposals **followed vs overridden** and how each turned out (BG at 3 h, lows and
  highs within 4 h), **AI accuracy by provider/model** (valid answers, fallbacks, latency, proposals accepted /
  edited / rejected, meal-estimate carb error), and Next Best Action speed (median and slowest, target < 200 ms).
- **Settings → Appearance:** follow system, light or dark.
- **Supabase analysis views** + `docs/ANALYSIS.md` (ready-made SQL for daily/hourly/monthly TIR, time-of-day and
  weekday patterns, proposal outcomes, AI reliability).

### Hardening in this build
- Values that would break the dose math (e.g. ICR 0) can't be saved or accepted, and if one ever reaches the
  engine you get **"No dose" with the reason** instead of a silent 0 u.
- Double-tapping **Log**, or logging a split's second injection both in the app and from the notification,
  records insulin only once.
- Typos in carbs/fat/protein, dose units or AI-proposal weights are flagged instead of becoming 0 / the default.
- CGM readings from xDrip's broadcast are confirmed against xDrip+'s web service; future-dated readings are ignored.
- The AI function now **requires** `ALLOWED_USER_IDS` (docs/INSTALL.md §7).

### What to try (first run, end to end)
1. **Sign in** with the email/password account you created in Supabase (or "Use without an account for now" —
   everything except sync and AI works locally). Then **Settings → Open setup checklist** and allow each item (notifications,
   battery unrestricted, exact alarms, microphone).
2. **Live BG:** with xDrip+ running and its local web server / broadcast on (`docs/INSTALL.md` §6), the main
   screen shows your BG, trend arrow and reading age within a minute. Turn on airplane mode — it keeps updating.
3. **Meal → dose:** tap the mic and say "chicken burrito, about 70 carbs" (or type it). You get the dose,
   pre-bolus lead time and split plan with the full breakdown; tap **Log** (or edit units first and give a
   reason). Try it in airplane mode too — the offline path computes the same dose.
4. **Factors:** say "large cold brew", "two beers", "ran 5k", "drank a liter of water", "stressed", "sunburn".
   The active factors and their weights appear on the main screen; **Undo** on the card reverts.
5. **Split reminders:** log a split dose and wait for the second-injection notification — tap **Log N u**
   right on it.
6. **Morning report:** after 1 am the learn cycle proposes profile changes; your first open of the day shows
   the sleep check-in and each change to accept / edit / reject.
7. **Restore:** Settings → Sign out → sign back in (or reinstall) — everything comes back from Supabase.
8. **Export:** Settings → Data → Export… → pick a date range → **Build export** → share the zip.
9. **Stats:** tap the bar-chart icon.

Dose math is local and deterministic; AI only proposes, you decide.
