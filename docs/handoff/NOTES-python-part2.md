# Python on demand part 2 (SHELVED 2026-11-03 by the user: not worth ~6.7 MB; libpython stays in the APK)

Goal: drop libpython3.12.so (6.7 MB) from jniLibs; fetch it with the rest of the runtime (part 1, `Plugins.preparePythonHome`).

Facts (source-checked 2026-11-03):
- `android/core/plugins.cmake:11-15`: `orca_plugins` (static, in liborca_jni) links imported libpython, `IMPORTED_NO_SONAME` ->
  liborca_jni has DT_NEEDED `libpython3.12.so`, resolved at `System.loadLibrary("orca_jni")` (`OrcaNative.kt:15`, `ObnNative.kt:9`).
- Python starts only in `Plugins.start` (after Orca Cloud sign-in), home `<dataDir>/python`. `:scad` process does not load orca_jni.
- Chaquopy libpython: no DT_SONAME; dynamic has `FLAGS_1 = NOW` (0x1); exports 1733 symbols, all Py*/_Py* plus
  `__emutls_get_address`. liborca_jni imports 188 of them, **including `__emutls_get_address`** (it gets emutls from libpython today).
- **liborca_jni runs Python code at load time:** `PYBIND11_EMBEDDED_MODULE` static inits (`android/core/plugin/AndroidPluginRuntime.cpp:301`,
  `src-orca/src/slic3r/plugin/PythonPluginBridge.cpp:607`) call `Py_IsInitialized` + `PyImport_AppendInittab`.
  `OrcaEngine.cpp:200` `python_running()` is a member check (no Py call). No Py_Finalize/atexit in android/core.

Phone probe (Android 16, tiny libs in /data/local/tmp, 2026-11-03), stub has soname, user lib DT_NEEDED it:
- real lib dlopen'd with plain `RTLD_GLOBAL` (stub loaded first or not): **stub wins** (bionic ignores RTLD_GLOBAL for lookup order).
- real lib with **DF_1_GLOBAL** (`-z global`): **real wins**, both with stub loaded first and with real loaded first + stub via DT_NEEDED.
- real lib with DT_SONAME loaded first also wins, but adding a soname needs file growth; DF_1_GLOBAL is a 1-byte in-place edit.

Plan (revised after the probe):
1. Stub `libpython3.12.so` in jniLibs: DT_SONAME `libpython3.12.so`, exports every libpython symbol (dynsym list from the real lib,
   generated in `stage_python.py`, built with NDK clang). Functions -> one `__android_log_print` + `abort()`; data -> zero bss objects;
   **exceptions:** `Py_IsInitialized` returns 0, `PyImport_AppendInittab` returns 0 (load-time static inits), `__emutls_get_address`
   = a working emutls (link compiler-rt builtins' emutls, or build the stub for an API level that still uses emutls). Build still links
   liborca_jni against the real lib (`python/prefix/lib`).
2. Manifest: `file arm64-v8a.zip jniLibs/arm64-v8a/libpython3.12.so lib/libpython3.12.so` + new line `preload lib/libpython3.12.so`.
   `preparePythonHome`: after extraction set DF_1_GLOBAL in each `preload` lib (ELF64: PT_DYNAMIC -> DT_FLAGS_1 0x6ffffffb |= 0x2;
   fail if missing). SHA stays on the zip.
3. Process start (`OrcaApp.onCreate` after the `:scad` return, before anything touches OrcaNative/ObnNative; FileProvider loads nothing):
   if `<dataDir>/python/.runtime` == asset manifest, `System.load` each `preload` lib; set `PythonLib.preloaded`.
   Then liborca_jni's DT_NEEDED loads the stub but binds to the real lib (DF_1_GLOBAL = namespace global group, searched first).
   Find `dataDir` from the `Plugins.start` callers (`AppViewModel.kt:109`, `OrcaCloudSection.kt:175`).
4. `Plugins.start`: if not preloaded after `preparePythonHome`: skip `PluginNative.start`, status "Restart OrcaStudio to finish
   installing the plugin runtime" (+ restart button if cheap). Guard every PluginNative call (`openPage`, `pageMessage`, `openConfig`,
   `saveConfig`, `closePage`) on `started`.
5. Test on the phone VD (WSA wedged): fresh data (fetch -> restart prompt -> plugins load), old part-1 data (stamp differs ->
   refetch), probe imports (ssl, sqlite3, ctypes.pythonapi) as in the part 1 test (archive 2026-11-03), app start without runtime
   (stub path, no crash). Measure APK in CI, lower budgets.
