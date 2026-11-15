# OrcaStudio Android: design conventions

OrcaStudio is a native Android (Jetpack Compose, Material 3) slicer/CAD app for 3D printing. This project has **tokens only, no component library**: build every component by hand in plain HTML/CSS (or React) and style it only with the variables below. Mockups should look like Material 3 on a phone.

## Setup
Link `styles.css` once; it `@import`s `tokens/colors.css`, `tokens/type.css` and `tokens/shape.css`. It also sets `body` background and text colour.
- Light is the default. Dark follows the system, or force it with `<html data-theme="dark">` (`data-theme="light"` forces light).
- Never hard-code hex values; every colour below flips with the theme automatically.

```html
<link rel="stylesheet" href="styles.css">
```

## Styling idiom: CSS variables only
No utility classes. Read `tokens/*.css` before styling; these are the real names.
- **Colour (Material 3 roles):** `--md-sys-color-{primary,on-primary,primary-container,on-primary-container,secondary,on-secondary,secondary-container,on-secondary-container,tertiary,on-tertiary,tertiary-container,on-tertiary-container,background,on-background,surface,on-surface,surface-variant,on-surface-variant,surface-container-lowest,surface-container-low,surface-container,surface-container-high,surface-container-highest,outline,outline-variant,error,on-error,error-container,on-error-container}`.
  Pair a fill with its `on-` colour. Orange `primary` = the accent / main action; blue `tertiary` = modified settings, warnings, status; panels use `surface-container*` (neutral grey, never tinted orange).
- **App colours:** rooms `--orca-room-{make,reshape,paint,cut}` (mode strips); 3D view `--orca-view-{part,selection,background,grid,active-plate}`; axes `--orca-axis-{x,y,z}` (arrows, bars, dots only, never text); `--orca-live-label` with `--orca-on-live-label`.
- **Type:** `font: var(--md-sys-typescale-{title-large,title-medium,title-small,body-large,body-medium,body-small,label-large,label-medium,label-small})`; `--orca-font`, `--orca-font-mono` (OpenSCAD editor).
- **Spacing:** `--orca-space-{xs,s,m,l,xl}` = 4/8/12/16/24 px. Use these for every gap and padding.
- **Shape:** `--orca-radius-{xs,s,m,l,xl,pill}` = 4/8/12/16/20/999 px; `m` (12) is the common card/panel radius.
- **Touch:** anything tappable is at least `--orca-touch-target` (48 px) square; size the icon, not the button.

## Build snippet
```html
<section style="background:var(--md-sys-color-surface-container);color:var(--md-sys-color-on-surface);
  border:1px solid var(--md-sys-color-outline-variant);border-radius:var(--orca-radius-m);padding:var(--orca-space-l)">
  <h2 style="font:var(--md-sys-typescale-title-medium);margin:0 0 var(--orca-space-s)">Plate 1</h2>
  <button style="min-height:var(--orca-touch-target);padding:0 var(--orca-space-l);border:0;
    border-radius:var(--orca-radius-pill);background:var(--md-sys-color-primary);
    color:var(--md-sys-color-on-primary);font:var(--md-sys-typescale-label-large)">Slice</button>
</section>
```

## Source of truth
`tokens/colors.css` (default "Prusa" preset, light + dark), `tokens/type.css`, `tokens/shape.css`. Values come from the app's Compose theme (`AppColors.kt`, `Tokens.kt`); the user-selectable colour presets and custom overrides are not mirrored.
