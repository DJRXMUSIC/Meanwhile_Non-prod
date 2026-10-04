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

---
Later milestones add Supabase (M2), xDrip+ (M3) and AI keys (M6) — each gets its own section here.
