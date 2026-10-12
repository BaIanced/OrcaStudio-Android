# AI tool layer: MCP server in the app (design note)

Status: milestones 1-2 built (2026-10-12: server, token, read tools, shape / dim / sketch edit tools, `design_preview`, `design_to_plate` with Allow / Deny; `ai/McpServer.kt`, `ai/McpTools.kt`). Not built yet: Undo action in the "AI: ..." toast, "AI connected" chip, milestones 3-4. Roadmap step 2 after the shape builder.

Goal: let an AI client (Claude Code, Claude Desktop, any MCP client) model with the shape builder
and inspect the plate **without the user writing code**, while every risky action stays behind an
on-screen confirm.

## Transport

- **Streamable HTTP**, one endpoint `POST /mcp` (JSON-RPC 2.0). Reply with plain
  `application/json`; no SSE stream in v1 (no server-initiated messages needed). `GET /mcp` -> 405.
  `Mcp-Session-Id` header issued on `initialize`.
- **Bind `127.0.0.1` only** by default (port 8765, configurable). Reach it from a PC with
  `adb forward tcp:8765 tcp:8765` (real devices; untested). **Not on WSA:** its ip rules send
  Android's loopback traffic to Windows (tables 127/128), so adbd's connect to 127.0.0.1:8765 lands
  on adb's own forward listener and loops (thousands of sockets, adb wedges; seen 2026-10-12).
  So the server also listens on the abstract unix socket `orcastudio_mcp`:
  `adb forward tcp:8765 localabstract:orcastudio_mcp` (works for WSA and USB devices). Opt-in switch
  "Also accept connections from the local network" binds TCP on all interfaces (Wi-Fi clients; WSA
  doesn't route inbound TCP from Windows, tried 127.0.0.1, loopback0 and the PC's LAN address).
- **Auth even on localhost** (other apps on the device can connect): random bearer token, shown /
  regenerated in Settings, required as `Authorization: Bearer <token>`. Reject requests that carry
  a browser `Origin` header (DNS-rebinding guard the MCP spec asks for).
- **Off by default.** Settings toggle "AI tools (MCP)"; the server runs only while the app process
  is alive and shows a persistent "AI connected" chip while a session is active.
- **No new dependency**: the app has no HTTP server library (`android/app/build.gradle.kts:166-177`).
  A small `ServerSocket` HTTP/1.1 handler (request line, headers, `Content-Length` body, one JSON
  reply, keep-alive off) is enough for JSON-RPC. Fallback if it grows: NanoHTTPD (BSD-3).

Client setup (example):
`claude mcp add --transport http orca http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"`

## Threading and state

- Tools go through the **UI-layer controllers**, never raw `OrcaNative.call`: `Store` holds the
  app state, and the engine is single-threaded (`core/Engine.kt:14-20`). Calling the engine behind
  the UI's back would desync the object list, undo and previews.
- Shape edits use `ShapeController` (`ui/ShapeController.kt`), so each tool call is **one undo
  step** via `commit()` (`:31-43`) and shows up live in the editor. The user can always press Undo.
- Each tool call runs on the main dispatcher (controllers are UI-owned), awaits the result, returns.

## Tools v1 (shape builder)

Design data is the `ShapeDesign` JSON (`scad/ShapeModel.kt:188-196`, `decode` `:480`); shape kinds
`box, rounded_box, cylinder, prism, cone, pyramid, sphere, dome, torus, tube, wedge, text, sketch,
mesh, group` (`:88-90`).

| Tool | Maps to | Tier |
|---|---|---|
| `design_get` | `ShapeDesign.json()` + selection | read |
| `design_scad` | `ShapeDesign.toScad()` (`:279`) | read |
| `design_describe` | indented text tree: id, kind, size, position, hole, group, bound dims (cheap "vision") | read |
| `design_snapshot` | small PNG (256-512 px) of the rendered preview, MCP `image` content | read |
| `design_new` | `ScadController.newShapes()` (`ui/ScadController.kt:141`) | edit |
| `design_set` | full replace: validate with `ShapeDesign.decode`, then `commit` | edit |
| `shape_add` / `shape_update` / `shape_delete` | `add` `:77`, `update`/`setField` `:116-122`, `delete` `:182` | edit |
| `shape_move` / `shape_turn` / `shape_resize` | `move` `:212`, `turn` `:147`, `resize` `:135` | edit |
| `shape_align` / `shape_mirror` / `shape_hole` | `align` `:219`, `mirror` `:206`, `setHole` `:201` | edit |
| `shape_group` / `shape_ungroup` / `shape_repeat` | `group` `:248`, `ungroup` `:263`, `setRepeat` `:243` | edit |
| `sketch_set` | `setSketch` `:161`, `setRevolve` `:167` | edit |
| `dim_add` / `dim_set` / `dim_bind` / `dim_remove` | `addDim` `:302`, `setDim` `:317`, `bind` `:309`, `removeDim` `:322` | edit |
| `design_preview` | `ScadController.preview()` (`:239`), returns render errors | edit |
| `design_to_plate` | `ScadController.render()` (`:358`) | confirm |

Selection-based controller calls (`resize`, `turn`, `align`, ...) take explicit `ids` in the tool
and select them first, so the AI never depends on what the user tapped.

`design_snapshot`: v1 reuses the native CPU renderer `render_thumbnail` (`core/jni/Thumbnails.cpp:49`,
already used for 3MF plate thumbnails at `OrcaProject.cpp:154`). It takes a `Model`, while the
preview mesh comes from `ScadService` (separate `:scad` process), so the new engine call loads the
preview STL into a temporary `Model` and renders that. Cheapest-first rule: the client should prefer `design_describe`, then `design_get`,
and ask for a PNG only when shape matters.

## Tools v2 (plate / engine, read first)

Read: `scene` (`EngineCalls.cpp:73`), plate list, object list with sizes, current printer /
filament / process names, slice estimate if sliced. Edit (undoable through engine `undo`, `:108`):
`arrange`, `setTransform`, `duplicate`, `layOnFace`, `autoOrient`. All through the existing
`AppViewModel` paths, not new raw calls.

## Confirm-on-screen rules

| Tier | Behaviour |
|---|---|
| read | runs freely |
| edit | runs, one undo step, toast "AI: <tool summary>" with Undo |
| confirm | blocks the call; app shows a dialog with the tool name and a plain summary; Allow / Deny; 60 s timeout = denied. The tool returns `isError` with "declined by user" |
| never | not exposed over MCP at all |

- **confirm:** adding to or changing the user's plate (`design_to_plate`, deleting plate objects,
  `deletePlate`), file writes / exports, anything that leaves the device (uploads).
- **never:** print / send to printer, save or delete presets, sign in / out, account and cloud
  calls, reading auth or session files. (Roadmap step 3 may add preset *proposals*: the AI
  returns overrides, the user saves them on screen.)
- Tool descriptions state the tier so the client knows a call may wait for the user.

## Milestones

1. Server skeleton: toggle, token, `initialize` / `tools/list` / `tools/call`, `design_get`,
   `design_describe`, `design_set`. Test: `adb forward` + curl, then Claude Code.
2. Shape edit tools + `design_preview` + confirm dialog (`design_to_plate`).
3. `design_snapshot` (new engine call), plate read tools.
4. Plate edit tools; then settings tools (roadmap step 3).

## Open questions

- Port (8765 fixed for now). LAN mode exists as an opt-in switch (needed for WSA and Wi-Fi only).
- Should `design_to_plate` on an empty temp plate skip the confirm?
