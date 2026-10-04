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
2. Open Obtainium → **Add App** → App source URL: `https://github.com/DJRXMUSIC/Meanwhile_Non-prod` → **Add**.
3. Obtainium checks for new Releases and offers one-tap updates.
4. If you make the repo private, Obtainium needs a GitHub token: GitHub → your avatar → **Settings** →
   **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token** →
   Repository access: *Only select repositories* → this repo → Permissions: **Contents: Read-only** →
   Generate. In Obtainium: **Settings** → **Sources** → GitHub → paste the token.

## 4. Recommended repo settings

- **Make the repo private** (the spec asks for private; it's currently public): **Settings** → **General**
  → scroll to **Danger Zone** → **Change repository visibility** → **Make private**.
- **Default branch**: the repo's default branch still holds the old PWA. To make MeanwhileV4 the
  landing page (and to get a **Run workflow** button in Actions): **Settings** → **General** →
  **Default branch** → switch icon → choose `claude/hopeful-galileo-pgut04` → **Update**.

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

**e) Email sign-in settings**
1. Supabase → **Authentication** → **Sign In / Providers** → **Email**: enabled. Turn **Confirm email**
   off (simplest) — or leave it on and click the link Supabase emails you after creating the account.
2. Re-run the **android** workflow (Actions → newest android run → **Re-run all jobs**) so the APK
   includes your Supabase URL/key, then install that release.
3. Open the app → enter your email + a password → **Create account**.
4. Then lock the door: Supabase → **Authentication** → **Sign In / Providers** → turn off
   **Allow new users to sign up**. (The repo is public, so anyone can read the anon key from the APK;
   with sign-ups off and row-level security, nobody else can create an account or read your data.)

**Check it works:** in the app, **Settings → App note** → type something → **Save note**. With the phone in
airplane mode it stays "1 pending"; turn data back on and within a minute it shows **Synced**, and the
note appears in Supabase **Table Editor → feedback**. Restore test: uninstall, reinstall, sign in — your
records come back.

---
Later milestones add xDrip+ (M3) and AI keys (M6) — each gets its own section here.
