# Demo: "The Listening Lantern" (Claude self-portrait, user request 2026-10-30)

Design JSON: `~/orca-android-tools/self-portrait.json` (34 shapes; replay with `design_set`, then `design_to_plate`).
About 92 x 92 x 93 mm. A mind rising out of an open book:
- Plinth: revolved sketch (stepped ogee), ring of 40 gold "token" beads (circular repeat), raised "CLAUDE" text.
- Open book: indigo cover, ivory pages with engraved text lines (repeat-step holes cutting only their group).
- Bust: revolved sketch, coral scarf torus + two tails.
- Lantern head: hollow ivory sphere (cavity hole), eye/smile holes, two rows of back slits (circular repeats of rotated
  cylinder holes, 150/125 degree arcs); cyan thought orb on a stem glows through them.
- Gold armillary cage: three meridian tori (rot Z 0/60/120) anchored at the neck, tilted coral orbit ring, coral/cyan
  beads on the meridians (circular repeats), coral spark star on top (rotated group of radial cones).
- Palette (slots 1/2/3/5/6/7 on WSA): gold #F2B134, indigo #1B1F3B, ivory #F3E9DC, coral #E4572E, cyan #2EC4E6,
  teal #2F6F73. Set with `settings_edit {type: filament, slot, values: {slot_colour}}` (new in r483+).

Run 2026-10-30: v1 (Sonnet, 19 shapes) used the existing slot colours (user: "you didn't choose any other colors");
v2 (Opus) on WSA window + phone plate. Gap found: no MCP way to set slot colours (added `slot_colour`); on the phone
design_to_plate timed out ("OpenSCAD stopped"), "Update on plate" tap finished it.
- r483 installed; WSA slots recoloured (6 colours, view_set object 1 yaw -115 pitch 22 dist 360). Phone has 4 slots
  (= AMS lite): `self-portrait-4slot.json` remaps plinth -> indigo, text + book cover -> gold, coral + cyan -> coral
  slot 4 (orb glows warm). Gaps: indigo plinth vanishes on the dark bed; a stray yellow box object sits behind on WSA.
