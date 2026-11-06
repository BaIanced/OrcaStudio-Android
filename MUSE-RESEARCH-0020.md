# Task 20 research: hand-tracking plate placement (design)

Research only; nothing installed or run. Read 2026-11-06. VERIFIED = read in
the cited source; INFERRED = mine; UNKNOWN = no source read settles it. Fit:
sideloaded native OpenXR GLES APK, Quest 3, Windows host. Already built
(NOTES-vr.md, claude/vr-placement): aim ray + grip hold, stick up/down =
distance, left/right = zoom, plate level, no yaw, no view motion, One Euro
on position, no inertia, limits 0.2-6 m / 0.1-8x, zoom stops at 1:1.

## 1. Recommended design

- Grab (VERIFIED inputs, §3.1): aim with the runtime-filtered hand aim pose; grab = `aim_activate_ext/value` over threshold, ray on the plate.
- Move (INFERRED): keep PlateGrab - held point rides the aim ray; while pinched, hand push/pull along ray moves plate in depth.
- Distance past arm's reach (INFERRED mapping, VERIFIED input): microgesture swipe_forward/back = farther/nearer steps - stick up/down made discrete (§3.1).
- Zoom (VERIFIED pattern, §2): two-hand pinch spread about held point, extra only; one-hand fallback: swipe_left/right steps.
- 1:1 reset (INFERRED): thumb tap, mirroring the built stick-click reset.
- Rotate: none - no source requires it for a plate; it breaks the built level, no-yaw, 1:1 tabletop rule.
- Release (INFERRED, §3.3): pinch release; damp the last frames of ray motion at release; keep no-inertia. Tracking lost mid-hold: freeze.

## 2. Candidate gestures (item 2)

| Gesture | Hands | Needs | How; risk / fatigue | Source |
|---|---|---|---|---|
| Pinch-hold, move hand along ray | 1 | EXT hand interaction or FB aim | Depth = hand motion to arm's reach, clutch past it. Low accidental risk; long holds fatigue [P] | [P], [H] |
| Two-hand pinch spread | 2 | Both hands' aim/pinch | Apart/together scales the held object. Extra only: hands can occlude each other [H] | [P], [H] |
| Microgesture swipe forward/back | 1 | META microgestures + EXT profile | Discrete distance steps. State true only a few frames; no hold-repeat found, so step/repeat is app-side. "Low-calorie" | [S-MG] |
| Microgesture swipe left/right | 1 | Same | Discrete zoom steps, same caveat; D-pad symmetry with stick axes | [S-MG] |
| Thumb tap | 1 | Same | Discrete command (here: 1:1 reset); same caveat | [S-MG] |
| Distance grab (pull-to-hand) | 1 | Interaction SDK (Unity/Unreal only) | No native OpenXR equivalent found; do not design on it | [ISDK] |
| Arm-extension ray gain | 1 | None | Not described for hands in sources read; UNKNOWN | - |

## 3. Findings

### 3.1 Item 1 - what the runtime gives an app

`XR_EXT_hand_interaction`: profile `/interaction_profiles/ext/hand_interaction_ext`,
user paths `/user/hand/left|right`. VERIFIED paths [U2] (`*` = full EXT only):
`input/aim/pose`, `input/grip/pose`, `input/pinch_ext/pose`*,
`input/pinch_ext/value`* (0-1; 1 = thumb/finger touching),
`input/pinch_ext/ready_ext`*, `input/poke_ext/pose`* (fingertip contact),
`input/aim_activate_ext/value`* + `ready_ext`* (activate what aim points at),
`input/grasp_ext/value`* + `ready_ext`* (fist), `input/select/value`,
`input/squeeze/value`. **Grab verdict (INFERRED): far grab = aim pose + aim_activate (or
pinch_ext/value); pinch_ext/pose is the near-field pose at the fingers;
grip/pose is palm pose, not a grab signal; poke taps surfaces.**
`XR_FB_hand_tracking_aim` is not an action profile: chain
`XrHandTrackingAimStateFB` onto `XrHandJointLocationsEXT` in
`xrLocateHandJointsEXT`; read `aimPose` (system-determined, filtered),
`pinchStrength{Index,Middle,Ring,Little}` (0-1), status bits incl. index
pinching, dominant hand. VERIFIED [R-AIM].
Microgestures ride the same profile as boolean actions (VERIFIED [S-MG]):
`/user/hand/<side>/input/swipe_left_meta/click`, `swipe_right_meta`,
`swipe_forward_meta`, `swipe_backward_meta`, `tap_thumb_meta`. Enable both
`XR_EXT_hand_interaction` and `XR_META_hand_tracking_microgestures`. They
are thumb tap/swipes on the index finger's side, "discrete D-pad-like
commands" [S-MG].
Exact manifest lines (VERIFIED [H]; sample's identical but `required`) [S-HF]:
```
<uses-permission android:name="com.oculus.permission.HAND_TRACKING" />
<uses-feature android:name="oculus.software.handtracking" android:required="false" />
```
Without both, hand devices are not enumerated [H]. `required="false"` =
controllers-and-hands; `true` = hands-only [U1]; this app keeps
controllers, so `false`. Optional, inside `<application>` [H]:
`<meta-data android:name="com.oculus.handtracking.frequency" android:value="HIGH"/>`.
User must enable hand tracking in device settings, else no in-app hands
[H]. Default (no multimodal, §3.4): hands *or* controllers, auto-switching
on put-down or double-tap [H] - picking up a controller mid-placement ends
hand input; treat as tracking-lost (freeze), not a release.

### 3.2 Item 2 notes

Meta: pinch is the primary selection gesture (trigger equivalent, near
and far); keep gestures simple (pinch/point most reliable); avoid
sustained poses [P]. Horizon's select/scroll/drag uses pinch and
pinch-and-drag [H]. Virtual Desktop: no hand distance gesture found -
menu settings instead [VD]. No shipped hand equivalent for the stick's
distance axis; §1's steps substitute.

### 3.3 Item 3 - jitter and latency

Causes (VERIFIED): tracking "can exhibit some noise", lighting affected;
occlusion drops confidence [H]; hands leaving the camera view are lost -
design for graceful loss [P]. Meta's
recommendations (VERIFIED [H]): do not derive your own
ray - use the *filtered* pointer pose (FB aimPose is the OpenXR form) and
detected pinch state; trigger on the detected pinch bit/status, not raw
strength (strength is feedback only); pointer-valid
unset = do not use/render the ray; fade hands at low confidence.
Prediction (VERIFIED [S-HF]): locate joints at the frame's predicted
display time (`locateInfo.time = ToXrTime(PredictedDisplayTime)`).
Pinch-time ray motion (Heisenberg effect) is real, partly OS-mitigated
(Hands 2.3: "stabilising the cursor while pinching" [BLOG]); app-side,
anchor the grab at pinch start, damp release frames (INFERRED).
Release drift beyond that: unquantified in primary sources (UNKNOWN, §4).
Filtering: built One Euro parameters are project guesses (NOTES-vr.md);
no Quest-specific published set found (UNKNOWN); tuning method in [1EU].

### 3.4 Item 4 - hands plus controllers

VERIFIED [M] (Aug 11, 2026): multimodal
(`XR_META_simultaneous_hands_and_controllers`) tracks hands and controllers
at once, per hand; one controller + bare hand is documented ("single
controller gameplay") - "one controller does everything, free hand adds"
works. Enable with
`xrResumeSimultaneousHandsAndControllersTrackingMETA` (pause likewise),
after checking `supportsSimultaneousHandsAndControllers`; overrides auto-switching. Limits (VERIFIED [M]): incompatible
with Inside-Out Body Tracking / Full Body Synthesis; Fast Motion Mode
loses to it; passthrough + multimodal + Wide Motion Mode together disable
Wide Motion Mode (this app uses passthrough); in-hand signal is imperfect
(false triggers on tracking loss, still controllers) - Meta: do not drop
held objects on false short transitions. Support: Quest 2, Pro, 3, 3S
[M]; power cost unquantified (UNKNOWN).

### 3.5 Item 5 - traps

- System gesture reserved: palm pinch (look at palm, hold thumb+index)
  opens the system menus. VRC: it "is reserved, and
  should not trigger any other actions" [VRC]; runtime flags it while
  processing (`SystemGestureProcessing`; `MenuPressed` one frame) -
  suspend app gestures while flagged. VERIFIED [H].
- OS regressions (community/issue reports; native GLES impact UNKNOWN):
  Horizon OS v2.4 (May 2026) reportedly broke `XR_FB_hand_tracking_aim`
  far-interaction (ray stuck forward; EXT joints unaffected; mitigation:
  derive ray from joints) [F]. Firmware v2.3 (Apr 2026) reportedly made
  raw-joint pinches double-fire (fingertips clip, then snap apart),
  Meta's shell clean (root cause = reporter's inference) [W2].
- Sleep/focus: after sleep/focus loss an app can see stale/zero poses
  until focus returns (Godot editor, Quest 3; closed as OS-side). Native
  here UNKNOWN - re-check on return to FOCUSED. [G]
- Stale doc conflict: [H] says hands/controllers "are not currently
  tracked at the same time" - true only without multimodal (§3.4); its
  VrApi half is not OpenXR. Per-eye: no hand-specific source found
  (NOTES-vr.md M1 trap is pose-independent). No other GLES-only failure
  found (UNKNOWN).

### 3.6 Item 6 - reusable code

All [SDK] @ bbed2f20, GLES, Oculus SDK License - learn patterns; copying
into this public AGPL repo is Task 21's question.
- `Samples/XrSamples/XrHandsFB/Src/main.cpp`: chain
  `XrHandTrackingAimStateFB` (+ capsules, velocities) onto
  `XrHandJointLocationsEXT`, locate at predicted display time (~442-490);
  click = `aimState.status & XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB`,
  ray = `aimState.aimPose` (~536-540).
- `Samples/XrSamples/XrMicrogestures/Src/main.cpp`: §3.1 bindings;
  AppInit's `supportsHandTracking` check.
- `Samples/XrSamples/XrHandsAndControllers/Src/main.cpp`: multimodal -
  property check (~615-622), resume/pause loading (~647-654), resume call
  (~951-954); one controller + bare hand together [S-HC].
- Edge case (XrHandDataSource sample not read in depth): joints can be
  controller-sourced (`XR_HAND_TRACKING_DATA_SOURCE_CONTROLLER_EXT`) vs
  optical (VERIFIED [R-DS]) - a held-controller pose is inferred, not
  camera-tracked; don't treat sources as equal (INFERRED).


## 4. Not read; device-only questions

Not read: full OpenXR 1.1 spec HTML (fetch returned empty body) - facts
rest on Khronos per-structure registry pages, SDK source, one vendor page
listing profile paths; [H]'s llmstxt twin (403; HTML read instead).
Device-only: (a) EXT hand interaction in this GLES build on Quest 3 v210;
FB aim health on the installed OS (v2.4 report)? (b) pinch thresholds/
hysteresis, release drift, One Euro values. (c) microgesture step/repeat
feel; false-swipe rate while pinching.
(d) HAND_TRACKING on v210: prompt or install-granted; com.oculus
permission name still accepted, or is a horizonos.* name required? (e) hands across sleep/wake, return to FOCUSED. (f) multimodal
power/thermal with passthrough.

## Sources (dates where stated; accessed 2026-11-06)

[H] https://developers.meta.com/vr/documentation/native/android/mobile-hand-tracking/
[U1] https://developers.meta.com/vr/documentation/unity/unity-handtracking-hands-setup/ (Oct 14, 2026)
[U2] https://docs.ultraleap.com/openxr/features/hand-interaction-profile/index.html
[R-AIM] https://registry.khronos.org/OpenXR/specs/1.0/man/html/XrHandTrackingAimStateFB.html
[R-DS] https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrHandTrackingDataSourceEXT.html
[M] https://developers.meta.com/vr/documentation/native/android/native-multimodal/ (Aug 11, 2026)
[SDK] https://github.com/meta-quest/Meta-OpenXR-SDK @ bbed2f20 (2026-02-12); [S-HF]=Samples/XrSamples/XrHandsFB, [S-MG]=XrMicrogestures, [S-HC]=XrHandsAndControllers
[P] https://github.com/meta-quest/agentic-tools/blob/HEAD/skills/hz-immersive-designer/references/interaction-patterns.md
[VD] https://vrdiscord.com/guides/quest-wireless/virtualdesktop.html (community)
[BLOG] https://www.meta.com/blog/meta-quest-v72-update-remote-desktop-hand-tracking-keyboard-more/ (Dec 9, 2024)
[1EU] https://registry.platformio.org/libraries/casiez/1euroFilter
[VRC] https://developers.meta.com/vr/documentation/unreal/unreal-hand-tracking/ (Apr 14, 2026)
[F] https://communityforums.atmeta.com/questions-and-discussions-26/horizon-os-v2-4-breaks-hand-far-interaction-in-all-openxr-unity-apps-179067 (May 2026)
[W2] https://github.com/wivrn/wivrn/issues/898 (May 4, 2026)
[G] https://github.com/godotengine/godot/issues/121600 (closed Jul 21, 2026)
[ISDK] https://developers.meta.com/vr/documentation/unreal/unreal-isdk-overview/ (Aug 31, 2026)
