# Setup & install (for Danny)

Everything here is one-time unless noted. Steps are written for a desktop browser on github.com
unless they say "on the Pixel".

---

## 1. Signing secrets (M1)

Claude generated your release keystore and sent you three files in chat:
`meanwhilev4-release.jks`, `KEYSTORE_BASE64.txt`, `keystore-secrets.txt`.

**Back them up offline first** (USB stick and/or password manager). If the keystore is lost, future
updates can't install over the existing app — you'd have to uninstall (local data is restored from
Supabase, but it's a hassle). Never commit them to the repo.

Add four repository secrets:

1. Open `https://github.com/DJRXMUSIC/Meanwhile_Non-prod`.
2. **Settings** (top tab) → left sidebar **Secrets and variables** → **Actions**.
3. Click **New repository secret** and add each of these (Name exactly as shown, Secret = value):

   | Name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the whole contents of `KEYSTORE_BASE64.txt` (one long line) |
   | `KEYSTORE_PASSWORD` | from `keystore-secrets.txt` |
   | `KEY_ALIAS` | `meanwhile` |
   | `KEY_PASSWORD` | from `keystore-secrets.txt` (same as `KEYSTORE_PASSWORD`) |

4. Re-run the latest build so it signs and publishes a Release:
   **Actions** tab → click the newest **android** run → **Re-run all jobs** (top right) → **Re-run jobs**.
   After ~6–10 minutes a new Release appears under **Releases** on the repo's main page.

## 2. Install on the Pixel 9a

1. On the Pixel, open Chrome → `https://github.com/DJRXMUSIC/Meanwhile_Non-prod/releases/latest`.
2. Under **Assets**, tap `MeanwhileV4-<version>.apk`.
3. When Chrome asks, tap **Settings** → enable **Allow from this source** → back → **Install**.
4. If Play Protect says the app is unknown: **More details → Install anyway**.
5. Open **Meanwhile**.

Updates: repeat steps 1–2 for a newer release and tap **Update** — or use Obtainium (below).

## 3. Optional: automatic updates with Obtainium

1. On the Pixel, install Obtainium from `https://github.com/ImranR98/Obtainium/releases/latest`
   (download the `app-arm64-v8a-release.apk`, install as above) or from F-Droid.
2. On the Pixel, tap this link to add Meanwhile in one step:
   `https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https%3A%2F%2Fgithub.com%2FDJRXMUSIC%2FMeanwhile_Non-prod`
   — or in Obtainium: **Add App** → App source URL: `https://github.com/DJRXMUSIC/Meanwhile_Non-prod` → **Add**.
3. Obtainium checks for new Releases and offers one-tap updates.
4. If you make the repo private, Obtainium needs a GitHub token: GitHub → your avatar → **Settings** →
   **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token** →
   Repository access: *Only select repositories* → this repo → Permissions: **Contents: Read-only** →
   Generate. In Obtainium: **Settings** → **Sources** → GitHub → paste the token.

## 4. Recommended repo settings

- **Public or private:** the repo is public by your choice, which is fine — no keys are in it, your data
  is protected by row-level security, new sign-ups are off (§5e) and the AI only answers the user ids in
  `ALLOWED_USER_IDS` (§7). To make it private later: **Settings** → **General** → **Danger Zone** →
  **Change repository visibility** → **Make private** (Obtainium then needs a token, §3).
- **Default branch**: the repo's default branch still holds the old PWA. To make MeanwhileV4 the
  landing page (and to get a **Run workflow** button in Actions): **Settings** → **General** →
  **Default branch** → switch icon → choose `claude/hopeful-galileo-pgut04` → **Update**. Nothing is deleted —
  the PWA stays on its own branch.

## 5. Supabase (M2) — cloud backup, sync and restore

**a) Create the project**
1. Go to `https://supabase.com/dashboard` → sign in (GitHub login is fine) → **New project**.
2. Name: `meanwhile` · Database password: click **Generate a password** and save it in your password
   manager (needed below) · Region: **East US** · **Create new project**. Wait ~2 minutes.

**b) Collect five values**
1. **Project URL** and **anon key**: left sidebar **Project Settings** (gear) → **API Keys** →
   **Legacy API keys** tab → copy `anon` `public`. Then **Data API** (or **API**) → copy the **Project URL**
   (looks like `https://abcdefgh.supabase.co`). The anon key is safe to ship in the app; row-level
   security limits every row to your account.
2. **Project ref**: the `abcdefgh` part of that URL.
3. **Database password**: from step a).
4. **Access token** (lets GitHub Actions apply the database schema for you):
   `https://supabase.com/dashboard/account/tokens` → **Generate new token** → name `github-actions` → copy.

**c) Add them as GitHub secrets** (repo → **Settings** → **Secrets and variables** → **Actions** →
**New repository secret**, one each):

| Name | Value |
|---|---|
| `SUPABASE_URL` | Project URL |
| `SUPABASE_ANON_KEY` | anon public key |
| `SUPABASE_PROJECT_REF` | project ref |
| `SUPABASE_DB_PASSWORD` | database password |
| `SUPABASE_ACCESS_TOKEN` | access token |

**d) Apply the schema** — repo **Actions** tab → **supabase** workflow → newest run → **Re-run all jobs**.
It should finish green; in Supabase, **Table Editor** now lists `cgm_readings`, `doses`, `meals`, …
(If you'd rather not use the access token: Supabase **SQL Editor** → **New query** → paste the whole file
`supabase/migrations/20261004000100_meanwhile_init.sql` → **Run**.)

**e) Create your account — the rest is automatic**
1. Re-run the **android** workflow (Actions → newest android run → **Re-run all jobs**) so the APK
   includes your Supabase URL/key, then install that release.
2. Open the app → enter your email + a password → **Create account**. No confirmation email — CI
   already turned that off for you.
3. That's it. On its next run, CI **disables new sign-ups automatically** (it checks that your
   account exists first, so you can't be locked out). Every android/supabase run's **Summary** page
   shows a setup checklist with what, if anything, is still missing.

**Check it works:** in the app, **Settings → App note** → type something → **Save note**. With the phone in
airplane mode it stays "1 pending"; turn data back on and within a minute it shows **Synced**, and the
note appears in Supabase **Table Editor → feedback**. Restore test: uninstall, reinstall, sign in — your
records come back.

## 6. xDrip+ bridge (M3) — live Eversense readings

Meanwhile reads your CGM from **xDrip+** running on the same phone (xDrip+ bridges the Eversense).

1. In **xDrip+**: ☰ menu → **Settings** → **Inter-app settings**:
   - **xDrip Web Service** → **On** (Meanwhile polls `http://127.0.0.1:17580/sgv.json` every 60 s).
     Leave "Open Web Service" off — Meanwhile is on the same phone, no secret needed.
   - **Broadcast locally** → **On** (instant readings in addition to polling). Keep the web service on
     too: Android can't tell which app sent a broadcast, so Meanwhile treats it as "fetch now" and takes
     the reading from xDrip+'s web service; the broadcast value is only used if that service is off.
   - **Identify receiver** → type `app.meanwhile.v4` (lets xDrip+ wake Meanwhile even if it was killed).
2. Install xDrip+ **before** (or reinstall Meanwhile after) so Android grants Meanwhile xDrip's
   broadcast permission. Polling works either way.
3. In **Meanwhile**: tap the **Finish setup** card (or Settings → Open setup checklist) and allow
   Notifications, Unrestricted battery, Exact alarms and Microphone.
4. Settings → **CGM (xDrip+)** → **Test connection** should show your latest reading.

If you run a different bridge app, tell Claude which one — it can be added as another `CgmSource`.

## 7. AI keys (M6) — you set these yourself; never paste them in chat

The AI runs in a Supabase Edge Function (`ai`). Keys live only in Supabase's secret store — never in
the repo, the APK, logs or chat.

1. **Gemini key:** `https://aistudio.google.com/apikey` → **Create API key** → copy.
2. **Anthropic key:** `https://console.anthropic.com/settings/keys` → **Create Key** → copy.
3. Supabase dashboard → your project → **Edge Functions** (left sidebar) → **Secrets** →
   **Add new secret**, one at a time:

   | Name | Value |
   |---|---|
   | `GEMINI_API_KEY` | Gemini key |
   | `ANTHROPIC_API_KEY` | Anthropic key |
   | `GEMINI_FAST_MODEL` | `gemini-flash-latest` (optional: faster routing/estimates) |

   You do **not** need `ALLOWED_USER_IDS` any more: the function automatically allows only the
   project's **first account** (yours), and CI locks sign-ups so no later account can exist. If you
   ever want to be explicit, set `ALLOWED_USER_IDS` to your user id — it's one tap in the app:
   **Settings → Account → Copy**.

   Optional model overrides (defaults shown): `GEMINI_MODEL` = `gemini-pro-latest`,
   `CLAUDE_MODEL` = `claude-opus-5-5`, `CLAUDE_FAST_MODEL` (unset = same as `CLAUDE_MODEL`).
4. Deploy the function: GitHub **Actions** → **supabase** → newest run → **Re-run all jobs** (uses the
   access token from §5). In Supabase → **Edge Functions** you should now see `ai`.
5. In the app: **Settings → AI provider** — Gemini first (default), Claude first, Gemini only, Claude only.
6. Check it works: **Settings → Open setup checklist → System status → Test AI**.

**Fallback test:** temporarily delete the `GEMINI_API_KEY` secret (or change one character), send
"pizza and a coffee" — it still works, via Claude; Settings → AI provider shows the last error, and
the exported `ai_calls` CSV shows `fallback_used = true`. Restore the key afterwards.

---
## 8. Nothing else to set up

The nightly learn cycle (1 am), morning report, overnight-highs check (6 am) and stats need no extra
setup beyond **Exact alarms** in the setup checklist and the AI keys above. The Supabase analysis
views are applied by the same **supabase** workflow; how to query them is in `docs/ANALYSIS.md`.

Learning runs on its own (Settings → **Learning** to see it or switch to *Ask me first*).

**If something looks wrong:** Settings → Diagnostics → **Copy for AI**, then paste into your AI
coding assistant (it says what the app is and where the code lives). Nothing secret is in it.
