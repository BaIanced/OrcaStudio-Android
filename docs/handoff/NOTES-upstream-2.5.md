# Upstream OrcaSlicer 2.5.0 alpha vs ours (Claude, 2026-10-25; release notes read in full, code skimmed only)
Source: https://www.orcaslicer.com/wiki/releases/release_2_5_0_alpha. src-orca is pinned at 2.5.0-dev (f8dd56053c, 2026-10-06), so
the source is already in our tree; nothing new to fetch. User rule: keep ours where objectively better, reuse theirs elsewhere.
The notes say nothing on push/pull, cut or mobile/touch. Verdicts below are first-pass; "compare" = not yet tested side by side.

| Area | Upstream 2.5.0 | Ours | Verdict |
|---|---|---|---|
| Parametric CAD (Design tab) | `libslic3r/CAD` + `GUI/CAD`, ~38.6k lines: sketch solver/constraints, extrude/revolve/sweep/loft/fillet/chamfer/shell/hole/thread/boolean, feature tree in 3MF; OpenCASCADE B-Rep. Our build sets `SLIC3R_CAD OFF` (android/core + deps CMakeLists) | Shape builder: touch-first primitives, groups, repeat, merge to mesh; OpenSCAD backstop | Different tools. Theirs wins on precision (fillets, constraints), ours on touch speed. Enabling it needs OCCT for Android (size, build time): a big [Opus] decision, not started. Keep ours. |
| Colour mixing | `FilamentMixer.cpp` (alternating-layer mixes, Decompose Color) in libslic3r; no use in our app | none | Reuse: expose through a patch + UI, don't port. Replaces the backlog "Filament colour mixing" item. |
| Texture displacement / bake / texture-to-colour | `TextureDisplacement`, `TextureBake`, `TextureToColor` | paint shop, no textures | New to us; candidate. Check they build without the GUI (`SLIC3R_GUI OFF`). |
| Realistic View in Preview, shadows (Off/Static/Orbit), opacity filaments, 7-colour gradients | yes | Realistic render 1+2, filament materials, own GL shaders | Compare on Android GPUs; ours is shipped and tuned for the app, keep unless worse. |
| Section view, X-Ray | yes | SectionView.kt (uSection clip in Shaders.kt) + X-Ray selection style | Already have both; nothing to do (correction 2026-10-25). |
| Speed Dial (Space) command search | yes | none | Idea; low. |
| Centre of mass markers, Focus mode | yes | none | Idea; low. |
| Fix Model rebuilt (`--repair`), mesh booleans with multi-piece cutters | yes | Check mesh | Compare before extending Check mesh or OrcaMesh. |
| Python plugins + permission system | yes (desktop GUI dialogs) | generic plugin runtime | Compare before finishing the plugin gaps. |
| Paint: support-paint axis locks, brim ear radius | yes | stroke axis lock exists | Nothing to do. |
| Belt printing, IDEX/IQEX parallel, separated infill, Publish 3MF | yes | none | Not relevant to our printers; ignore. |
Next: side-by-side checks on WSA where marked "compare"; then re-tag backlog items.
Check 2026-10-25 (source only, untested on device): FilamentMixer, TextureDisplacement, TextureBake/*, TextureToColor are in the
unconditional libslic3r source list (CMakeLists.txt:186-196, 485-508) and include no GUI/wx headers (TextureDisplacement.cpp:274 says so),
so they already compile into our libslic3r. Only JNI + UI are missing. Mixer API: blend_color(_multi), expand_mixed_filaments, gradient curves
(FilamentMixer.hpp:46-151). Next: Realistic/section/X-Ray compare on WSA.
Check 2026-10-25 (source only): section view, X-Ray, Realistic View and shadows live only in slic3r/GUI (GLCanvas3D, GCodeViewer,
GLShadersManager, 3DScene), which we don't build (wx + desktop GL). Not reusable; our own renderer keeps Realistic. Section/X-Ray
would be our own work: a clip plane + alpha pass in our GL shaders (cheap, touch-friendly). Verdict: keep ours; section/X-Ray = backlog idea.
Check 2026-10-25 (source only): Fix Model `--repair` is just the existing TriangleMesh::repair CLI path (OrcaSlicer.cpp:5084), no new
libslic3r API, so our Check mesh loses nothing. Python plugins (slic3r/plugin/PythonInterpreter, PythonPluginBridge) need an embedded
CPython inside the GUI lib: not usable on Android; keep our generic plugin runtime. All "compare" rows are now closed at source level.
Correction 2026-10-25: we already ship section view (render/SectionView.kt, Shaders.kt uSection) and X-Ray (SelectionStyle.XRAY). The 'section/X-Ray = own work' line above is void.
Check 2026-10-25 (source only): NO native patch needed for colour mixing. The core already resolves mixed slots at slice time
(ToolOrdering::resolve_mixed_filaments, ToolOrdering.cpp:2187; 3MF import of <mixed_filament> in bbs_3mf.cpp:4767; validation PrintConfig.cpp:12008).
A mixed filament = a filament slot with per-filament config: filament_is_mixed (bool), filament_mixed_components ("1,3", 1-based),
filament_mixed_sublayer_ratios ("0.7,0.3"), filament_mixed_gradient (+_range/_curve/_per_part) (PrintConfig.cpp:3365-3415, mode comDevelop).
So the work is Kotlin only: a "mix" slot editor that writes these keys via the existing per-filament option path, plus the preview colour
(blend_color_multi needs JNI or a Kotlin port; a plain linear-light blend in Kotlin is enough for swatches). Untested.
Path confirmed 2026-10-25 (source): OrcaEngine.cpp:496-520 copies any per-slot FilamentSlot.overrides entry of a vector option into that slot, so a
mixed slot is just FilamentSlot(overrides = {filament_is_mixed:"1", filament_mixed_components:"1,3", filament_mixed_sublayer_ratios:"0.7,0.3"}).
Zero native change. Keys are comDevelop: they may be hidden in the settings editor, so the editor needs a dedicated "Mix" sheet. Untested on device.
