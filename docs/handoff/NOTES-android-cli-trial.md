# Android CLI trial (user 2026-10-29)
Goal: see if Google's Android CLI (developer.android.com/tools/agents/android-cli, plugin github.com/android/android-cli-plugin) makes our flow cheaper. Judge by tokens per finished step, quality kept.
Candidates: `android preview render` (Compose previews to PNG, no device; needs @Preview fixtures for MoreScreen), `android layout`/`screen capture` vs `phone-ui.py`, `android profiler`. Skip: signals (Crashlytics), emulator, studio subcommands (only with Studio running).
Steps: 1. Read the download page, ask before running Google's install script. 2. `android init`, set `--sdk=D:\android_sdk` in `%USERPROFILE%\.androidrc`. 3. Preview-render one More-screen section, compare to WSA debug install + phone-ui layout.
Track in a table below (task, old method tokens/time, CLI method tokens/time, quality): use the hook's "Context: Nk" before/after.
If worse: port only the useful bits into our scripts (no full adoption). Studio-side context offloading: judged not worth it (Studio's agent panel does not reduce my context; run claude in its terminal only for editor convenience).
## Log
