## What to try (M2 — data layer + Supabase)

Setup first (once): `docs/INSTALL.md` §5 — create the Supabase project, add the five secrets,
re-run the **supabase** and **android** workflows.

1. Install this release and open it. Create your account (email + password) on the first screen,
   or tap "Use without an account" to stay local-only for now.
2. **Settings → App note**: save a note. The chip at the top of the main screen shows pending/synced.
3. Offline test: airplane mode → save another note → "1 pending". Turn data on → it syncs within a
   minute; check Supabase **Table Editor → feedback**.
4. **Settings → Export…** → pick dates → **Build export** → **Share zip** (or one table's CSV).
5. Restore test: uninstall → reinstall → sign in → your notes are back (Export shows them).

Then in Supabase turn off **Allow new users to sign up** (INSTALL §5e).
