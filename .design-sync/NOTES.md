# Design sync notes

- 2026-11-14, user decision (first /design-sync run): this repo's UI is Jetpack Compose, with no package.json, no Storybook
  and no React components, so the converter's package / storybook shapes don't apply. The user chose a **tokens-only project**:
  pull the Compose theme into CSS variables plus a short conventions guide, and upload those to a NEW Claude Design project.
  There is no component bundle; mockups built there rebuild every component by hand.
- The user has already accepted the cost and scope (asked and answered); don't ask again. Still to do: the project name
  (`list_projects` for a free name, then confirm with the user), the upload approval, the upload itself.
- Sources: `android/app/src/main/java/app/orcaandroid/ui/theme/` (`AppColors.kt`, `Tokens.kt`, `DesignPanel.kt`; 828 lines).
  Light and dark schemes: emit both (`:root` and `@media (prefers-color-scheme: dark)` / `[data-theme="dark"]`).
- Layout (off-script; the gates still apply where they make sense): `ds-bundle/styles.css` (`@import`s `tokens/*.css`),
  `tokens/colors.css`, `tokens/type.css`, `tokens/shape.css` (radii, spacing, 48 dp touch target), `README.md` with
  `.design-sync/conventions.md` as its header. Record `projectId` in `.design-sync/config.json` as soon as the project exists.
- Paused before the build because the session context was past the user's /clear limit.
