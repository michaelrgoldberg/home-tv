# Bedroom TV (home-tv) — working rules

## Keep the manual current — every change
Mike keeps the **Bedroom TV Manual** on his phone's home screen:
https://claude.ai/artifact/D2DobsVcbSjvMnJD6KNBkV

After ANY user-visible change to the TV (dashboard, Sports, Gamecast, props/parlays card, pop-ups,
Ambient, Sleep, remote/button behavior, settings, the native app), update that artifact in the same
session: read it with the Artifact tool (`action: "read"`), edit the relevant tab(s), and publish to the
same URL (never a new artifact — his home-screen icon points at this link). Mark new items with the
green NEW pill; drop stale instructions.

## Standing constraints
- Free only, no ads, plain language, usable from the Fire TV remote at 10 feet.
- Bets, earnings and calendar data are never readable on this public site — they live only in the
  AES-GCM-encrypted props.json / private.json (key is on the devices, never in the repo).
- tools/data_plain.json, tools/set_props.py and tools/build_private.py are gitignored on purpose.
- Picks (props/parlays) carry a `date` and expire at 2:00 AM Eastern.
- Never delete files on Mike's laptop.
