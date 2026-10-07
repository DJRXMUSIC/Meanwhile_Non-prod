## MeanwhileV4 1.4 — talk to it

Install: download the APK below → open → allow "install unknown apps" for your browser (or point
Obtainium at this repo — see `docs/INSTALL.md`). Updating keeps all your data.

### New in 1.4
- **A conversation, not a dashboard.** The main screen is now a chat: you say (or type) what you're
  doing, the app answers. Glucose, trend, age and insulin on board sit in one line at the top; tap
  "active factors" to unfold the rest. Earlier messages from today stay visible.
- **It talks back.** Anything that isn't a plain dose, number or meal gets an answer in words, based on
  your live BG, insulin on board and the last two days — "just testing", "how am I doing?", "going
  for a run in an hour, anything I should do?", "why did it say 6 units?". It only acts when there's
  something to act on, and any insulin or carb number it mentions comes from the dose math, never the
  AI. After an instant log ("took 6 units") a short reply follows. Replies are on screen only.
- **One clear next step.** Every answer is one action in big type — **Take 6 u, then eat in 12 min**,
  **Eat 16 g fast carbs** (a low, never insulin), **Eat ~12 g carbs** (insulin on board will
  overshoot), **Eat — no insulin needed**, **Nothing to do now** or **Check your BG** — with the button
  that logs it. **Why?** opens the full audit: BG used, the dose math line by line, the profile and
  the reasons. The thresholds (low = below 70, 15 g rule, recheck 15 min) are profile values.
- **Say it and it's logged.** "Took 6 units" is logged at once (with Undo). Right after a suggestion,
  "took it", "done" or "ate it" logs exactly what it said; a different amount ("took 7") is logged
  against it as an override. "BG 140" uses that value instead of the CGM for this answer.
- **Fix it by saying so.** "Never mind, I only took 5", "make that 4", "I didn't take any",
  "cancel that", "I took it 20 minutes ago" correct the last dose (up to 2 h back). Nothing is
  overwritten: the correction is a new record and insulin on board, outcomes, learning and stats all
  count the corrected dose.
- **You see it working.** Each message shows its steps live — understanding, updating your profile,
  estimating the meal, working out the next action — with the AI model and timing. If the AI is slow
  a **skip** button answers offline. The keyboard drops and the box clears as soon as you send.
- **A mic you can trust.** The mic shows every state — starting, *listening* (with a live level bar),
  *hearing you* (the words appear as you speak), *writing it down* — and the phone ticks when it starts
  and stops. What you said is sent when you stop talking (Settings → Voice to review it first).
- **AI that doesn't time out.** Day-to-day AI is Gemini 3.8 Flash with minimal thinking (it timed
  out before because Gemini 3.x thinks hard by default), then Claude Opus 5.5 if it doesn't answer —
  each gets up to 60 s, and the message shows which one is working and for how long.
  Messages the phone reads with certainty ("took 6 units", "60 carbs 20 fat", "BG 140") skip the AI
  entirely.
- **Learning on Claude Opus 5.5 at max effort.** The nightly review — and the mid-day reviews after
  new dose outcomes — go to Claude Opus 5.5 at maximum effort as an Anthropic batch: no time limit,
  half price, results usually within an hour (the morning report says when it's still thinking).
  Gemini Pro steps in if it fails. Settings → AI lets you choose per job.
- **Everything is written down, word for word — in Supabase too.** Every message (with what the mic
  heard and its alternatives), every step, every answer and every tap goes to a new conversation log;
  every AI call keeps its full request; and the app's own log uploads with each sync. A problem can
  now be looked up by time instead of reproduced.
- **Bug capture.** Settings → Diagnostics → **Start fresh capture**, reproduce the problem, then
  **Copy for AI**: the report then covers only what happened since — log and conversation included.
- **Your own CGM app.** Settings → CGM → local web service accepts any app on the phone that serves
  readings the way xDrip+ does. A custom address now counts as a real source (live readings and
  back-fill) even without xDrip+ installed.

### From 1.3
- **Eversense built in — no xDrip+ needed.** Meanwhile reads your glucose straight from the Eversense
  app's notification (allow it once in the setup checklist). It stores each reading once, ignores
  re-posts and a value that's stuck for 35 minutes, and shows exactly what it sees if the
  notification ever changes format. xDrip+ keeps working alongside it for back-fill if you keep it.
- **Learns from every dose, all day.** About 4 hours after each logged dose the app measures where
  you actually landed and works out what the dose *should* have been. Once three clean meals agree
  (no other food or insulin muddying the result), it nudges your ICR, ISF or caffeine units halfway
  toward what they point to — the same afternoon, offline, no AI needed. The AI reviews the new
  lessons mid-day too, not just at 1 am, and looks for what code can't see (time-of-day patterns,
  factor weights, fat/protein, new factors).
- **Checks its own work.** Every learned change is watched over the next meals: if your results got
  worse it's reverted on its own; a severe low (< 54) after a change toward more insulin reverts it
  immediately. You get a quiet notification either way.
- **Automatic, your way.** Settings → **Learning** shows what it learned, the evidence behind each
  number, what's still being judged, and a one-tap **Undo**. Choose **Automatic** (default),
  **Automatic for factors** (ICR/ISF/target wait for you) or **Ask me first**. Every threshold is
  editable in Profile → Learning.
- **Everything is written down.** A learning journal (synced, append-only) records each lesson,
  change, verdict and AI review; the morning report lists what was learned overnight.
- **Problems are easy to hand to an AI.** Settings → Diagnostics → **Copy for AI** puts one report on
  the clipboard — app version, phone, what's healthy and what isn't, every recent error with its
  stack trace, failed AI calls, the learning state and the log — with keys, tokens and emails
  stripped. Paste it into any AI coding assistant. **Share** sends it as a file; **Log** shows the
  raw app log. A badge shows how many warnings/errors happened in the last day.
- **Setup is much shorter** (`docs/INSTALL.md`): three GitHub secrets and one re-run — CI now creates
  and maintains the Supabase project itself (no project to create, no URL/key/password to copy),
  and leaves your old PWA's project alone. The AI keys still go into Supabase by you.
- **Much bigger test suite** behind every build: database migrations, sync, dose logging, CGM
  intake, the learning loop and diagnostics are tested on every push, alongside the dose-engine golden
  tests and the Supabase security rules.

### From 1.2
- **Faster forever:** dose calculations no longer slow down as months of profile history accumulate.
- **Self-healing:** a 15-minute watchdog restarts the CGM service if Android kills it and catches up
  missed work — even with the app closed, even offline.
- **Crashes report themselves** into your feedback log (private, on-device → your own Supabase).
- **Setup shrank:** no more `ALLOWED_USER_IDS` step — your first account automatically owns the AI,
  and CI locks sign-ups by itself after you create it. Each CI run's Summary page is a setup checklist.
- **Setup → System status:** CGM / backup / network at a glance plus a **Test AI** button.
- **Better voice:** the Pixel's on-device dictation engine is used explicitly, biased toward your
  dosing vocabulary and factor words, returns digits ("60 carbs"), and waits out a thinking pause.
  "Sixty carbs twenty fat" or "took six and a half units" now parse exactly like typed numbers —
  even offline.

### From 1.1
- **Main screen stripped down** (spec §15): glucose, active factors, the input bar — and a single
  Settings button. **Tap the big glucose number for Stats**, tap the profile line for the Profile.
  The sync/AI chips only appear when something needs attention.
- **17 color palettes** in Settings → Appearance (plus light/dark/system). Glucose colors stay the
  same in every palette — green/amber/red always mean the same thing.
- Keyboard **Send** submits the input; the morning report no longer pops up on a fresh install.
- Settings got shortcuts to Profile & Stats at the top.

### From 1.0 (M8 — stats + polish)
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
9. **Stats:** tap the big glucose number on the main screen.
10. **Make it yours:** Settings → Appearance — pick light/dark and one of 17 color palettes.

Dose math is local and deterministic; AI only proposes, you decide.
