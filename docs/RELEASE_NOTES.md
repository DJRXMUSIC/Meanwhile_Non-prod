## What to try (M3 — CGM intake + foreground service)

Setup: `docs/INSTALL.md` §6 (turn on xDrip+ Web Service + Broadcast locally, Identify receiver
`app.meanwhile.v4`), then the in-app **Finish setup** checklist.

1. Open Meanwhile: the main screen shows your BG (color-coded), trend arrow, rate and reading age.
   A persistent notification shows the same.
2. New readings appear within ~1 minute of xDrip+ (instantly when broadcasts are on).
3. Back-fill test: force-stop Meanwhile (long-press icon → App info → Force stop), wait 15+ min,
   reopen — the gap fills from xDrip+ (Settings → CGM shows "Back-filled N readings").
4. Stale test: stop xDrip+ for 15+ min → a red banner on the main screen and a "CGM readings are stale"
   notification.
5. Reboot the phone: the BG notification comes back by itself.
