# Review 0012 — native JNI audit of `android/core/jni/` (Task 12)

Review only; no code changed. All line references are against `muse/work` at
`1e4a9be` (the audit began at `8e0430b`; `android/core/jni/` is unchanged
between the two). Method: every file in `android/core/jni/` was read in full (13
`.cpp`/`.hpp` files, ~6,500 lines) plus the Kotlin `external fun` declarations
under `android/app/src/main/java`. Nothing was compiled or run — this is a
static audit, so no device behaviour is claimed for any finding.

13 findings, ranked crash > wrong output > leak. Each is CONFIRMED (the path
was traced in the code) or SUSPECTED (the defect is present but the trigger
depends on something outside the traced code).

## Crash

### 1. ObnBridge.cpp has no exception guard on any of its 19 exports — CONFIRMED
`android/core/jni/ObnBridge.cpp` (whole file). The other two export files wrap
every export in a `guarded()` try/catch that converts a C++ exception into an
error JSON (`orca_jni.cpp`, `OrcaPlugins.cpp`); ObnBridge has no try/catch
anywhere. Its exports build `std::string`s and JSON and call into obn, so any
`std::exception` (bad_alloc, a nlohmann type error, an obn error surfaced as a
throw) propagates straight out of a `JNIEXPORT` function.
Failure scenario: an allocation failure or malformed printer payload during
any ObnNative call aborts the process instead of returning an error to Kotlin.

### 2. `pageClose` is the only PluginNative export not wrapped in `guarded()` — CONFIRMED
`android/core/jni/OrcaPlugins.cpp:144-147`. The other 11 exports in the file
return `guarded(env, ...)`; `pageClose` (a `void` export) calls
`orca::plugins::page_close(str(env, key), str(env, capability))` bare, so a
throw from `str()` (allocation) or from `page_close` itself escapes the
JNIEXPORT.
Failure scenario: closing a plugin page while the plugin runtime throws
aborts the app instead of being swallowed/logged like every sibling call.

### 3. `GetStringUTFChars` result used without a null check in two of the three string helpers — CONFIRMED
`android/core/jni/orca_jni.cpp:23-27` (`to_string`) and
`android/core/jni/OrcaPlugins.cpp:29-33` (`str`): both do
`std::string out(chars)` on the raw result. `GetStringUTFChars` returns
`nullptr` (with a pending `OutOfMemoryError`) when it cannot allocate, and
constructing a `std::string` from `nullptr` is a crash. The third helper,
`ObnBridge.cpp:95-102` (`jstr`), checks (`c ? c : ""`) and releases only on
success — the correct pattern already exists in-tree, which makes the other
two oversights rather than a convention.
Failure scenario: under memory pressure, any OrcaNative/PluginNative call
with a string argument crashes in the helper before the guarded body runs.

### 4. `paint()` never upper-bounds the hit facet on most paths — CONFIRMED
`android/core/jni/OrcaTools.cpp`: the fill/height paths call
`selector.state_at(h.facet, ...)` (line 731), `flood_surface(..., h.facet, ...)`
(736) and `bucket_fill_select_triangles(..., h.facet, ...)` (761, 768); the
brush path at 860-862 checks only `h.facet < 0` before `state_at` and
`bucket_fill_select_triangles`. Only the `limited` branch (line 869) checks
`h.facet >= int(region.faces.size())`, and `paint_state_at` bounds-checks too —
so the facet is validated on some paths and trusted on others. Facets arrive
from Kotlin pick results, and PaintHit's own contract is that samples queue up
per stroke; a mesh-changing operation (simplify, repair, split) between the
pick and the paint leaves stale facet indices, and TriangleSelector indexes
its triangle array by facet.
Failure scenario: paint with a stale facet after the mesh changed under a
queued stroke reads/writes outside the selector's triangle array — crash or
silently corrupted paint.

### 5. `preset_bundle()` hands the raw `PresetBundle*` to the plugin runtime, which reads it without the engine lock — CONFIRMED
`android/core/jni/OrcaEngine.hpp:312` returns `m_bundle.get()` and is
documented as "read by plugins without the engine lock";
`android/core/jni/OrcaPlugins.cpp:108` installs exactly that as the plugin
host callback. `load_presets()` replaces `m_bundle` (a `unique_ptr`) under
`m_mutex`, and `select_printer` mutates the bundle in place — neither is
visible to a plugin thread holding the raw pointer.
Failure scenario: a plugin reads the bundle on its own thread while presets
reload — use-after-free on the freed bundle, or a torn read of a bundle being
mutated. (The pointer escape and the unlocked read are traced; whether the
plugin runtime actually calls it off the UI thread is outside this tree —
see "Not covered".)

## Wrong output / wrong state

### 6. `installCert` trusts one session-global flag for every printer — CONFIRMED
`android/core/jni/ObnBridge.cpp:246-261`, with the flag at line 82, set at
line 89 and reset only in `connect()` at line 182. `g_cert_installed` is a
single `bool`: `push_event()` sets it when *any* `device_cert_installed`
message arrives (no device match), `installCert` never clears it on entry,
and its first check returns `JNI_TRUE` immediately if a previous printer's
exchange set it — without calling `bambu_network_install_device_cert` for
the new device at all.
Failure scenario: install the cert for printer A, then connect printer B —
`installCert(B)` reports success instantly and the encrypted print to B
fails (or is attempted without the cert obn needs).

### 7. `load_models` (and `calib_start`) destroy the scene before the replacement is validated — CONFIRMED
`android/core/jni/OrcaScene.cpp:589-642`: `push_undo()` runs first, then for
`append=false` the scene is cleared and the plates reset (lines 590-594);
only afterwards are files loaded, and if every file yields no printable
object the function throws at line 642 — with the user's scene already gone.
The undo snapshot makes it recoverable, but the error result reads as
"nothing changed" while the plate is in fact empty. Same pattern in
`OrcaExtras.cpp` `calib_start`: the scene is replaced before later
`set_object`/`cut_object` steps that can throw, leaving a calibration scene
with no calibration state.
Failure scenario: importing a corrupt/empty file over an existing scene
reports a plain load error and silently wipes the plate behind it.

### 8. ObnBridge `init` holds `g_mu` across `bambu_network_start`, whose callbacks also take `g_mu` — SUSPECTED
`android/core/jni/ObnBridge.cpp:150-168`: `init` locks `g_mu` and calls
`bambu_network_start(agent)` while holding it; the event path
`push_event()` locks the same non-recursive `std::mutex` at line 88. If obn
delivers any callback synchronously on the calling thread during start,
`init` deadlocks against itself and printer connect hangs forever. Rated
SUSPECTED because the obn sources are not in this tree (`android/obn/`
holds patches and a cmake file only), so the callback timing cannot be
traced from here.
Failure scenario: first connect hangs the app if obn fires a connect/status
callback before `bambu_network_start` returns.

### 9. `paint_fill_cancel` pops whatever undo snapshot happens to be on top — SUSPECTED
`android/core/jni/OrcaTools.cpp:916-935`: when a fill preview is active,
cancel restores the facets and unconditionally `m_undo.pop_back()`s, on the
comment's assumption that the top snapshot is the fill stroke's own step.
Any other engine call that pushes an undo snapshot between the fill preview
and the cancel makes it drop that operation's snapshot instead, and leaves
the fill's step in the stack to be "undone" later against already-restored
facets.
Failure scenario: fill-preview, do another scene operation, then cancel the
fill — one undo step silently vanishes from history. (Needs interleaved
calls while a fill preview is active; the app serializes user actions, so
this needs a queued/programmatic sequence.)

### 10. `GetMethodID` results are never checked — SUSPECTED (latent)
`android/core/jni/orca_jni.cpp:52` (`progress_forwarder`) and
`android/core/jni/OrcaPlugins.cpp:103-104` (`start`). If a lookup fails, the
method ID stays `nullptr` with a pending `NoSuchMethodError`:
`progress_forwarder` then calls `NewStringUTF` under the pending exception,
and OrcaPlugins' `call_listener` would eventually `CallVoidMethod` through
a null ID — a crash, not a clean error. All three listener signatures were
verified against the Kotlin interfaces today and match
(`ProgressListener.onProgress(Int, String)`,
`PrintListener.onProgress(Int, Int, String)`,
`PluginNative.Listener.onPageMessage/onOpenUrl`), so nothing is broken now;
the finding is that a future Kotlin-side rename turns into a native crash
at first progress/plugin callback instead of a detectable failure at setup.
Failure scenario: rename a listener method in Kotlin — slicing crashes on
the first progress update.

### 11. Kotlin callbacks are invoked while holding native locks — SUSPECTED, low
Two sites: engine progress reporting runs under `m_mutex` (e.g.
`OrcaEngine.cpp:593,646` inside the locked slice path, via the forwarder in
`orca_jni.cpp:47-63`), and OrcaPlugins' `call_listener` holds
`g_listener_mutex` across `CallVoidMethod` into Kotlin
(`OrcaPlugins.cpp:50-52`). A listener that synchronously called back into
the same native API on the same thread would self-deadlock. No in-tree
evidence that any listener does this (the Kotlin listeners post to the UI),
so this is a contract hazard, not an observed bug.
Failure scenario: a future listener implementation that queries the engine
from inside `onProgress` hangs the app mid-slice.

### 12. `add_text` truncates the object name by bytes, splitting UTF-8 — SUSPECTED, low
`android/core/jni/OrcaExtras.cpp:193`: the object is named
`text.substr(0, 32)` — a byte cut that can land inside a multi-byte UTF-8
sequence for non-ASCII text, storing an invalid-UTF-8 name in the model.
JNI replies mask it (`to_jstring` dumps with
`error_handler_t::replace`, `orca_jni.cpp:32`), but the name is written
verbatim into saved 3MF projects, whose XML is not well-formed with invalid
UTF-8 bytes in it.
Failure scenario: add a text object with a long non-ASCII label, save the
project — desktop OrcaSlicer may refuse the file for malformed XML.
(Interoperability impact inferred from the format, not reproduced.)

## Leak

### 13. `add_svg` leaks the `NSVGimage` if shape creation throws — CONFIRMED, low
`android/core/jni/OrcaExtras.cpp:201-207`: `nsvgParseFromFile`'s result is
released by `nsvgDelete(image)` only on the success path; if
`create_shape_with_ids` throws (e.g. bad_alloc during tessellation of a
large SVG), the parsed image leaks.
Failure scenario: repeatedly importing a pathological SVG leaks one parsed
image per failed attempt. Small and bounded by user action, hence last.

## Checked and cleared

- **Signature drift: none.** All 39 Kotlin `external fun` declarations
  (OrcaNative 5, ObnNative 19, PluginNative 12, ScadNative 4) match their
  C++ `Java_...` implementations in name, parameter types and return type —
  including the 11-parameter `ObnNative.print` (`ObnBridge.cpp:291`) and
  every multi-line signature. The 4 Scad natives live outside the audit
  directory in `android/openscad/bridge/scad_jni.cc` (separate
  `libopenscad.so`, per `ScadService.kt`); their presence and signatures
  were checked, their internals not audited.
- **No raw-pointer-as-`jlong` handles exist** anywhere in the tree: the API
  is a singleton engine plus JSON strings, so the classic
  use-after-release / double-free handle class does not apply, and the
  Kotlin side holds no native handle it could call after release.
- **Local refs in loops:** no export creates local refs in an unbounded
  loop; `progress_forwarder` deletes its `jstring` on every call, and the
  listener objects are held as global refs (`OrcaPlugins` `start` deletes
  the previous global ref before replacing it).
- **`JNIEnv*` thread discipline:** `progress_forwarder` drops calls from
  any thread but the JNI caller (libslic3r reports progress from TBB
  workers); OrcaPlugins' `call_listener` attaches foreign threads through
  the stored `JavaVM`. Engine state is serialized by `m_mutex` in every
  public method — the documented exceptions are `cancel()` (its own mutex,
  by design, so a slice can be interrupted) and `snapshot()` (renders a
  file, touches no scene state).
- **ObnBridge's hand-rolled JSON escaping** (`esc`, `ObnBridge.cpp:104-126`)
  was checked line by line: quotes, backslash, `\n`/`\r`/`\t` and `\u00XX`
  for other control characters — correct.
- **Paint caches keyed by mesh address** (`OrcaTools.cpp` `LivePaint` /
  `write_paint_mesh`) were traced for the freed-address-reuse (ABA) hazard:
  `LivePaint` pins its mesh with a `shared_ptr`, so a cached address cannot
  be recycled while the cache references it. Cleared.
- **`OnExit` state restores** in `load_cloud_presets` and `sync_filaments`
  (`OrcaProject.cpp`) restore the bundle's selection state on the throw
  paths as well. Cleared.
- The obn agent is created once and never destroyed; the Kotlin API has no
  destroy call either, so this matches the API shape and is not counted as
  a leak.

## Not covered

- **libslic3r internals** (`src-orca/`): finding 4's out-of-bounds
  consequence is inferred from the missing check plus how the selector is
  indexed elsewhere in `OrcaTools.cpp`; TriangleSelector's own bounds
  behaviour was not read.
- **obn library internals:** not present in this tree (patches only), so
  finding 8 cannot be resolved from this repo.
- **The plugin runtime** behind `orca::plugins::` (outside
  `android/core/jni/`): whether finding 5's host callback is actually
  invoked off the calling thread could not be confirmed from the JNI side.
- **Execution:** nothing was compiled or run for this review; build status
  is UNCOMPILED as with the other native tasks (Gradle cannot run in
  Muse's VM — Task 9/13), and no finding rests on device behaviour.
