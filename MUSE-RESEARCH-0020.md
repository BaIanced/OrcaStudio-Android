# Task 20 research: hand-tracking plate placement (design)

Research only; nothing installed or run (2026-11-06). VERIFIED = read in
the cited source; INFERRED = mine; UNKNOWN = no source read settles it. Fit:
sideloaded native OpenXR GLES APK, Quest 3, Windows host. Already built
(NOTES-vr.md, claude/vr-placement): aim ray + grip hold, stick up/down =
distance, left/right = zoom, plate level/no yaw, One Euro
on position, no inertia, limits 0.2-6 m / 0.1-8x, zoom stops at 1:1.

## 1. Recommended design

- Grab (VERIFIED inputs, §3.1): aim with the runtime-filtered hand aim pose; grab = `aim_activate_ext/value` over threshold, ray on the plate.
- Move (INFERRED): keep PlateGrab - held point rides the aim ray; hand push/pull along the ray moves the plate in depth. Filtered aim to acquire; light filtering once held - lag hurts a held object more than jitter (§3.3).
- Distance past reach (INFERRED mapping, VERIFIED input): microgesture swipe_forward/back = farther/nearer steps, the stick up/down axis made discrete (§3.1).
- Zoom (VERIFIED pattern, §2): two-hand pinch spread about held point, extra only; one-hand fallback: swipe_left/right steps.
- 1:1 reset (INFERRED): thumb tap, mirroring the built stick-click reset.
- Rotate: none - breaks the built level, no-yaw, 1:1 tabletop rule; no source requires it.
- Release (INFERRED, §3.3): pinch release, last frames of ray motion damped; keep no-inertia. Tracking lost mid-hold: freeze.
- Conflict rule (INFERRED): pinch wins - a held pinch mutes that hand's microgestures; swipes/tap count only unpinched. (False-swipe rate unmeasured, §4.)
- Starting values (GUESSES, tune in vr_tuning.json): pinch on >= 0.80 pinchStrength, off <= 0.55 (same band on aim_activate); release damping = freeze plate at its pose 3 frames before the release edge; microgesture steps 0.25 m / x1.25 zoom.

## 2. Candidate gestures (item 2)

| Gesture | Hands | Needs | How; risk / fatigue | Source |
|---|---|---|---|---|
| Pinch-hold, move hand along ray | 1 | EXT hand interaction or FB aim | Depth to arm's reach, clutch past it. Low accidental risk; long holds fatigue | [P], [H] |
| Two-hand pinch spread | 2 | Both hands' aim/pinch | Apart/together scales held object. Extra only; hands can occlude each other | [P], [H] |
| Microgesture swipe forward/back | 1 | META microgestures + EXT profile | Discrete distance steps; state true a few frames only - step/repeat is app-side. "Low-calorie" | [S-MG] |
| Microgesture swipe left/right | 1 | Same | Discrete zoom steps, same caveat | [S-MG] |
| Thumb tap | 1 | Same | Discrete command (here: 1:1 reset); same caveat | [S-MG] |
| Arm-extension ray gain | 1 | None | Not described for hands; UNKNOWN | - |

## 3. Findings

### 3.1 Item 1 - what the runtime gives an app

`XR_EXT_hand_interaction`: profile `/interaction_profiles/ext/hand_interaction_ext`,
user paths `/user/hand/left|right`. VERIFIED paths [U2] (`*` = full EXT only):
`input/aim/pose`, `input/grip/pose`, `input/pinch_ext/pose`*,
`input/pinch_ext/value`* (0-1; 1 = touching),
`input/pinch_ext/ready_ext`*, `input/poke_ext/pose`* (fingertip contact),
`input/aim_activate_ext/value`* + `ready_ext`* (activate what aim points at),
`input/grasp_ext/value`* + `ready_ext`* (fist), `input/select/value`,
`input/squeeze/value`. **Grab verdict (INFERRED): far grab = aim pose + aim_activate (or
pinch_ext/value); pinch_ext/pose is the near-field pose at the fingers;
grip/pose is palm pose, not a grab signal; poke = surface taps.**
`XR_FB_hand_tracking_aim` is not an action profile: chain
`XrHandTrackingAimStateFB` onto `XrHandJointLocationsEXT` in
`xrLocateHandJointsEXT`; read `aimPose` (filtered),
`pinchStrength{Index,Middle,Ring,Little}` (0-1), status bits incl. index
pinching, dominant hand. VERIFIED [R-AIM].
Microgestures: boolean actions on the same profile (VERIFIED [S-MG]):
`/user/hand/<side>/input/swipe_left_meta/click`, `swipe_right_meta`,
`swipe_forward_meta`, `swipe_backward_meta`, `tap_thumb_meta`. Enable both
`XR_EXT_hand_interaction` and `XR_META_hand_tracking_microgestures`.
Thumb tap/swipes on the index finger's side; "discrete D-pad-like
commands" [S-MG].
Exact manifest lines (VERIFIED [H]; sample's identical but `required`) [S-HF]:
```
<uses-permission android:name="com.oculus.permission.HAND_TRACKING" />
<uses-feature android:name="oculus.software.handtracking" android:required="false" />
```
Without both, hand devices are not enumerated [H]. `required="false"` =
controllers-and-hands [U1]; this app keeps `false`. Optional, inside `<application>` [H]:
`<meta-data android:name="com.oculus.handtracking.frequency" android:value="HIGH"/>`.
Hand tracking must be enabled in device settings, else no in-app hands
[H]. Default (no multimodal, §3.4): hands *or* controllers, auto-switching
on put-down or double-tap [H] - a controller pickup mid-placement ends
hand input; treat as tracking-lost (freeze).

### 3.2 Item 2 notes

Meta: pinch is the primary selection gesture; keep gestures simple (pinch/point most reliable); avoid
sustained poses [P]. No shipped hand equivalent for the stick's
distance axis; §1's steps substitute.

### 3.3 Item 3 - jitter and latency

Causes (VERIFIED): tracking "can exhibit some noise", lighting affected;
occlusion drops confidence [H]; hands leaving camera view are lost [P].
Meta (VERIFIED [H]): do not derive your own ray - use the *filtered*
pointer pose (FB aimPose is the OpenXR form) and detected pinch state;
trigger on the pinch bit/status, not raw strength (feedback only);
pointer-valid unset = do not use/render the ray; fade hands at low
confidence. Prediction (VERIFIED [S-HF]): locate joints at the frame's
predicted display time.
Pinch-time ray motion (Heisenberg) is real, partly OS-mitigated
(Hands 2.3 cursor stabilisation [BLOG]); app-side: anchor at pinch
start, damp release frames (INFERRED).
Filtering: built One Euro values are project guesses; no Quest-specific
published set found (UNKNOWN).

### 3.4 Item 4 - hands plus controllers

VERIFIED [M] (Aug 11, 2026): multimodal
(`XR_META_simultaneous_hands_and_controllers`) tracks hands and controllers
at once, per hand; "single controller gameplay" is documented - "one
controller does everything, free hand adds" works. Enable:
`xrResumeSimultaneousHandsAndControllersTrackingMETA` (pause likewise)
after the `supportsSimultaneousHandsAndControllers` check; overrides
auto-switching. Limits (VERIFIED [M]): incompatible with Inside-Out Body
Tracking / Full Body Synthesis; Fast Motion Mode loses to it; passthrough
+ multimodal + Wide Motion Mode together disable WMM (this app uses
passthrough); in-hand signal imperfect (false triggers on tracking loss,
still controllers) - do not drop held objects on false short transitions.
Support: Quest 2, Pro, 3, 3S; power cost UNKNOWN.

### 3.5 Item 5 - traps

- System gesture reserved: palm pinch (look at palm, hold thumb+index)
  opens the system menus; VRC: it "is reserved, and
  should not trigger any other actions" [VRC]; runtime flags it while
  processing (`SystemGestureProcessing`; `MenuPressed` one frame) -
  suspend app gestures while flagged. VERIFIED [H].
- OS regressions (community/issue reports; native GLES impact UNKNOWN).
  Version naming (VERIFIED [RN], [UV]): Horizon OS 2 (Feb 2026)
  replaced the integer scheme (last v85) with 2.x; build numbers run
  in parallel (2.9 = v209), so v210 here = OS 2.10 - newer
  than both releases below; whether either bug survives into 2.10 is
  device-only (§4). 2.4 (May 2026) reportedly broke
  `XR_FB_hand_tracking_aim` far-interaction (ray stuck forward; EXT
  joints unaffected; mitigation: derive ray from joints) [F]. 2.3
  (Apr 2026): raw-joint pinches reportedly double-fire (fingertips
  clip, then snap apart); Meta's shell clean [W2].
- Sleep/focus: an app can see stale/zero poses after sleep/focus loss
  until focus returns (Godot editor, Quest 3; closed as OS-side). Native
  here UNKNOWN - re-check on FOCUSED. [G]
- Stale doc conflict: [H] says hands/controllers "are not currently
  tracked at the same time" - true only without multimodal (§3.4); its
  VrApi half is not OpenXR. Per-eye: no hand-specific source found.
  No other GLES-only failure found (UNKNOWN).

### 3.6 Item 6 - reusable code

All [SDK] @ bbed2f20, GLES, Oculus SDK License - learn patterns; copying
into this public AGPL repo is Task 21's question.
- `Samples/XrSamples/XrHandsFB/Src/main.cpp`: chain
  `XrHandTrackingAimStateFB` onto
  `XrHandJointLocationsEXT`, locate at predicted display time (~442-490);
  click = `aimState.status & XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB`,
  ray = `aimState.aimPose` (~536-540).
- `Samples/XrSamples/XrMicrogestures/Src/main.cpp`: §3.1 bindings;
  `supportsHandTracking` check.
- `Samples/XrSamples/XrHandsAndControllers/Src/main.cpp`: multimodal
  property check (~615-622), resume/pause loading (~647-654), resume call
  (~951-954) [S-HC].
- Edge case (sample not read in depth): joints can be
  controller-sourced (`XR_HAND_TRACKING_DATA_SOURCE_CONTROLLER_EXT`) vs
  optical (VERIFIED [R-DS]) - a held-controller pose is inferred, not
  camera-tracked; don't treat sources as equal (INFERRED).

## 4. Not read; device-only questions

Not read: full OpenXR 1.1 spec HTML (empty body on fetch) - facts
rest on Khronos registry pages, SDK source, one vendor path listing;
[H] llmstxt twin (403; HTML read instead).
Device-only: (a) EXT hand interaction in this GLES build on Quest 3
(OS 2.10 / v210); either §3.5 regression fixed by 2.10 - FB aim health,
raw-joint pinch behaviour? (b) pinch thresholds/
hysteresis, release drift, One Euro values. (c) microgesture step/repeat
feel; false-swipe rate while pinching.
(d) HAND_TRACKING on 2.10: prompt or install-granted; com.oculus
name still accepted? (e) hands across sleep/wake. (f) multimodal
power/thermal with passthrough.

## Sources (dates where stated; accessed 2026-11-06)

[H] https://developers.meta.com/vr/documentation/native/android/mobile-hand-tracking/
[U1] https://developers.meta.com/vr/documentation/unity/unity-handtracking-hands-setup/ (Oct 14, 2026)
[U2] https://docs.ultraleap.com/openxr/features/hand-interaction-profile/index.html
[R-AIM] https://registry.khronos.org/OpenXR/specs/1.0/man/html/XrHandTrackingAimStateFB.html
[R-DS] https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrHandTrackingDataSourceEXT.html
[M] https://developers.meta.com/vr/documentation/native/android/native-multimodal/ (Aug 11, 2026)
[SDK] https://github.com/meta-quest/Meta-OpenXR-SDK @ bbed2f20 (2026-02-12); [S-HF]=XrHandsFB, [S-MG]=XrMicrogestures, [S-HC]=XrHandsAndControllers (all under Samples/XrSamples/)
[P] https://github.com/meta-quest/agentic-tools/blob/HEAD/skills/hz-immersive-designer/references/interaction-patterns.md
[BLOG] https://www.meta.com/blog/meta-quest-v72-update-remote-desktop-hand-tracking-keyboard-more/ (Dec 9, 2024)
[VRC] https://developers.meta.com/vr/documentation/unreal/unreal-hand-tracking/ (Apr 14, 2026)
[F] https://communityforums.atmeta.com/questions-and-discussions-26/horizon-os-v2-4-breaks-hand-far-interaction-in-all-openxr-unity-apps-179067 (May 2026)
[W2] https://github.com/wivrn/wivrn/issues/898 (May 4, 2026)
[G] https://github.com/godotengine/godot/issues/121600 (closed Jul 21, 2026)
[RN] https://www.meta.com/help/quest/172903867975450/?srsltid=AfmBOoruMvVUlrnzFa6-fTDZZUN-e-m7UhCBAXTo6eguF3To1iCEsw-R (Meta Quest release notes, accessed 2026-11-06)
[UV] https://www.uploadvr.com/quests-latest-ptc-overhauls-window-management-brings-back-classic-home-environments/ (2.9 = v209)
