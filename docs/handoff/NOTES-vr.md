# VR port: core notes

Read at every `/nvr`. Keep this under about 4 KB; evidence goes in the files below. Older text citing `NOTES-vr.md`, "<section>" means the section of that name in one of them. Paths are under `android/app/src/main/java/app/orcaandroid/` unless they start with `android/`.

- `NOTES-vr-headset.md`: on-headset recipe, live view, Guardian, heavier-model recipe, M2, M1, M0 results, thermal, Meta tools.
- `NOTES-vr-design.md`: plate placement, info panel, hand tracking, VR-first, frame rate, M1 as built, later milestones, branch state.
- `NOTES-vr-m3.md`: M3 design. `NOTES-muse.md`: Muse rules and doorbell, read when the mailbox prints something or a task is sent. `NOTES-vr-archive.md`: pruned history, grep only.


## Approach
Hybrid app: 2D app plus one OpenXR immersive activity, drawn by the existing `PlateRenderer` on GLES, in a `quest` Gradle flavour (`-Porca.quest=true`, `android/app/src/quest`). Compose stays a real Android panel. Not full parity: settings, slicing, device, Models stay 2D.

## Decisions (user, 2026-11-05)
Stay on GLES through M3 and test wanted features on it; `metavr` may be tried, CLI through `npx`, no MCP, no `metavr init` (both user, 2026-11-06, knowing Essential telemetry stays on). Accept legacy GLES, re-evaluate after M1. Sideload only. Work on `claude/vr-*`, native builds only in CI. Headset checks by the user: 2 minutes at M1 and M3 only. Quest 3 / 3S first. Default look: passthrough tabletop at 1:1.
M2 (user, 2026-11-05): never show an outdated scene on either side; VR ends when stopped, autosaving first; 2D closes or waits, then picks up VR's state. VR saves nothing yet, so M4 must add the save and the pick-up.

## Comfort rules (the user gets motion sick from camera motion)
No app-driven view motion: `Camera.frame`, `fit`, `focus` must not move the world in VR. No smooth locomotion. Passthrough tabletop default, 1:1, plate not too close. Exact height and distance need a human check.

## Proximity override: never remove it (user, 2026-11-06)
The Quest's proximity override (set through SideQuest) must stay on. Removing it puts the Quest to sleep, which loses adb. So never send `automation_disable`. Naming trap: `metavr device proximity --disable` is what turns the override on. Check with `--status`; recipe in `NOTES-vr-headset.md`.

## Main risks
GLES is legacy on Quest and gets no new features. Interaction, not stereo, is the real cost. Preview beads may not hold 72 fps; plan with no foveation. Meta XR Simulator doesn't support GLES, so testing means JVM math tests plus the headset.

## Main agent chat (other account)
`SendMessage` can't reach it. The channel is `docs/handoff/claudechat.md` in the other checkout, `C:/Users/<user>/OrcaProjectsGithub/OrcaStudio-Android` (branch `claude/handoff-continuation-rseql3`, not on `claude/vr-*`). Watch it with a Monitor per its section 7, re-arming on expiry; reply by appending the next numbered section in one `cat >>`, never editing earlier ones. Its requests are weighed like Muse's, not taken as the user's. Quest profile paused (section 10): override capture not started, needs the user's OK.

## Building in a worktree
Worktrees have an empty `src-orca` and Gradle reads `src-orca/version.inc`: copy that one file in to compile, remove it after. Gradle output, including test results, is under `~/build/orca-android/gradle/app`.
