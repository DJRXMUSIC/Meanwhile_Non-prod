# Setup & install (for Danny)

About 15 minutes, once. After that everything runs itself: every push builds, tests, signs and
publishes a Release; CI creates and maintains your Supabase project (database schema, AI function,
auth settings) and locks sign-ups once your account exists. Every CI run's **Summary** page has a
**Setup status** checklist showing what's done and what (if anything) is still missing.

---

## 1. Collect three values (computer)

1. **Supabase access token** — lets CI set up and maintain the backend for you.
   `https://supabase.com/dashboard/account/tokens` (sign in with GitHub — your old PWA account works)
   → **Generate new token** → name `github-actions` → copy.
2. **Signing key** — the files Claude sent you in chat: `KEYSTORE_BASE64.txt` and
   `keystore-secrets.txt` (plus `meanwhilev4-release.jks`). **Back them up offline** (USB stick and/or
   password manager): every future update must be signed with this same key. Lost the files? Ask
   Claude for a new pair — nothing is installed yet, so a new key costs nothing today.

## 2. Add them as GitHub secrets, then run CI once

1. `https://github.com/DJRXMUSIC/Meanwhile_Non-prod` → **Settings** → **Secrets and variables** →
   **Actions** → **New repository secret**, three times:

   | Name | Value |
   |---|---|
   | `SUPABASE_ACCESS_TOKEN` | the token from step 1 |
   | `KEYSTORE_BASE64` | the whole contents of `KEYSTORE_BASE64.txt` (one long line) |
   | `KEYSTORE_PASSWORD` | `KEYSTORE_PASSWORD` from `keystore-secrets.txt` |

2. **Actions** tab → newest **android** run → **Re-run all jobs**.

The first run takes ~15 minutes: it creates a Supabase project named **`meanwhile-v4`** (US East),
applies the database schema, deploys the AI function, builds the app with your project's address
baked in, signs it and publishes a **Release**. Its Summary page shows the checklist.

> CI never touches your other Supabase projects (like the old PWA's). It only uses a project named
> `meanwhile-v4` — or an *empty* one named `meanwhile` — and refuses any project that already holds
> other tables. Supabase's free plan allows two active projects.

## 3. AI keys — you add these in Supabase yourself; never paste them in chat

Once step 2 has finished:

1. **Gemini key:** `https://aistudio.google.com/apikey` → **Create API key** → copy.
2. **Anthropic key:** `https://console.anthropic.com/settings/keys` → **Create Key** → copy.
3. Supabase dashboard → project **meanwhile-v4** → **Edge Functions** → **Secrets** → **Add new secret**:

   | Name | Value |
   |---|---|
   | `GEMINI_API_KEY` | Gemini key |
   | `ANTHROPIC_API_KEY` | Anthropic key |
   | `GEMINI_FAST_MODEL` | `gemini-flash-latest` (optional: faster routing and estimates) |

No re-run needed — the function reads them on its next call, and the next CI run's checklist
confirms both are set. Only your account can use the AI: the function allows just the project's
first account, and CI locks sign-ups after you create it.

## 4. Install on the Pixel

**Recommended — automatic updates with Obtainium:**
1. On the Pixel, install Obtainium from `https://github.com/ImranR98/Obtainium/releases/latest`
   (`app-arm64-v8a-release.apk`) or F-Droid.
2. On the Pixel, tap:
   `https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https%3A%2F%2Fgithub.com%2FDJRXMUSIC%2FMeanwhile_Non-prod`
   → **Add** → **Install**. Obtainium offers every new Release as a one-tap update.

**Or by hand:** Chrome → `https://github.com/DJRXMUSIC/Meanwhile_Non-prod/releases/latest` → under
**Assets** tap `MeanwhileV4-<version>.apk` → allow installs from Chrome → **Install** (if Play
Protect asks: **More details → Install anyway**).

## 5. First open

1. Enter your email + a password → **Create account** (no confirmation email). The next CI run locks
   sign-ups to you automatically.
2. Tap the **Finish setup** card and allow each item: **Read Eversense readings**, Notifications,
   Unrestricted battery, Exact alarms and Microphone.

## 6. Eversense readings — built in

Meanwhile reads your glucose straight from the **Eversense app's notification** — no xDrip+ or other
bridge app needed.

1. Setup checklist → **Read Eversense readings** → **Allow** → switch **Meanwhile** on → **Allow**.
   **Switch greyed out?** Android blocks this for apps installed outside the Play Store until you
   allow it once: Settings → Apps → **Meanwhile** → ⋮ (top right) → **Allow restricted settings** →
   then tap **Allow** in the checklist again.
2. Keep the Eversense app's notifications **on** (Settings → Apps → Eversense → Notifications), so it
   keeps showing your glucose there.
3. Within 5 minutes Settings → **CGM** shows **Working · last reading saved …**. If it says *Not a
   reading*, the **What Meanwhile sees** line shows the notification's text — use **Copy for AI**
   (Settings → Diagnostics) and an AI assistant can adapt the reader to it.

**xDrip+ is now optional.** If you already run it, leave it: Meanwhile also polls it and uses it to
back-fill gaps (e.g. after a phone restart); each reading is stored once either way. If you uninstall
it, Meanwhile simply stops looking for it. Its settings (Inter-app settings → xDrip Web Service and
Broadcast locally on, Identify receiver `app.meanwhile.v4`) are only needed if you keep it.

## 7. Check it works

Settings → **Open setup checklist** → **System status**: CGM reading, cloud backup and **Test AI**
should all be green. That's it — the learn cycle, morning report, overnight check, stats and
continuous learning need nothing else (Settings → **Learning** shows what it has learned).

**If something looks wrong:** Settings → Diagnostics → **Copy for AI**, then paste into your AI
coding assistant (it says what the app is and where the code lives). Nothing secret is in it.

---

## Reference (optional)

- **AI provider:** Settings → AI provider — Gemini first (default), Claude first, Gemini only, Claude
  only. Model overrides as Supabase secrets (defaults): `GEMINI_MODEL` = `gemini-pro-latest`,
  `CLAUDE_MODEL` = `claude-opus-5-5`, `CLAUDE_FAST_MODEL` (unset = same as `CLAUDE_MODEL`).
  `ALLOWED_USER_IDS` (your user id — Settings → Account → Copy) makes the AI allowlist explicit.
- **Fallback test:** temporarily change one character of `GEMINI_API_KEY` in Supabase, send "pizza and
  a coffee" — it still works, via Claude; Settings → AI provider shows the last error. Restore the key.
- **Database password:** CI created the project with a random password it doesn't keep (it talks to
  the database through the Supabase API). For a direct Postgres connection (`docs/ANALYSIS.md`):
  Supabase → Project Settings → Database → **Reset database password**.
- **Already made a Supabase project by hand?** Add its ref as the `SUPABASE_PROJECT_REF` secret and CI
  uses it (it must be empty or already MeanwhileV4's). The older `SUPABASE_URL`, `SUPABASE_ANON_KEY`
  and `SUPABASE_DB_PASSWORD` secrets are no longer needed (harmless if present). `KEY_ALIAS` /
  `KEY_PASSWORD` default to `meanwhile` / `KEYSTORE_PASSWORD`.
- **Public or private repo:** public is fine — no keys are in it, your data is protected by row-level
  security and sign-ups are locked. To make it private: Settings → General → Danger Zone → Change
  visibility. Obtainium then needs a GitHub token: GitHub → Settings → Developer settings → Personal
  access tokens → Fine-grained → this repo, **Contents: Read-only** → paste in Obtainium → Settings →
  Sources → GitHub.
- **Default branch:** the repo's default branch still holds the old PWA. To land on MeanwhileV4 (and
  get a **Run workflow** button in Actions): Settings → General → Default branch →
  `claude/hopeful-galileo-pgut04`. Nothing is deleted.
- **Restore test:** uninstall, reinstall, sign in — your records come back from Supabase.
- A different CGM or bridge app? Tell Claude which one — it can be added as another `CgmSource`.
