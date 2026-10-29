# MCP token efficiency: rule review + tool analysis (Opus, 2026-10-27)

## Measured (phone, r450 release, 47 tools; object_hollow not in r450)
- `tools/list` = 28.7 KB (~7.5k tokens): descriptions 11.9 KB, schemas 14.2 KB. Paid once per client session (prompt-cached after).
- Heaviest (desc + schema bytes): shape_add 1919 (schema 1712), shape_update 1800 (schema 1621; same `FIELDS` as shape_add),
  view_set 1703, design_set 1642 (desc 1365: its Format text repeats `FIELDS`), sketch_set 1293, object_transform 1161, object_sculpt 1008.
  Every other tool is under 820 B. Vec3 fields repeat `"type":"array","items":{"type":"number"},"minItems":3,"maxItems":3` (~70 B each).
- Replies (1 object, 3-shape design): plate_describe 461 B, of which ~300 B is the printer/process/filament header; then ~110 B per
  object + ~70 B per copy. design_describe 416 B (~120 B per shape + fit check). view_state 304, app_state 552, tuning_get 520,
  design_get 1014, log_tail 20 lines 2347 B (mostly GPU driver noise).
- Every plate edit returns the full plate_describe (`plateEdit`, McpTools.kt:735); every shape edit returns the full design tree
  (`edit`, McpTools.kt:858-866). These grow with the scene and repeat on every call: **replies are the real cost**, not the list.
  Example: 30 edits on a 15-shape design = ~2 KB x 30 = 60 KB (~15k tokens); a diff reply would be ~0.2 KB x 30 = 6 KB.

## Draft rule: verdict = keep the intent, edit four points
1. "changed fields" must include side effects (arrange / duplicate move other copies, a dim moves every bound shape, group
   bounds follow a child). Missing them is the quality risk: the agent assumes the rest stayed put.
2. Warnings the edit can trigger stay in the reply (shape fit check "floating / apart", errors). Dropping them forces a Read call.
3. "Full scene only on request": use the existing Read tools (plate_describe / design_describe), not a per-tool `full` flag
   (a flag adds schema bytes to every edit tool).
4. "Measure before merge" needs a number and a tool, or it won't happen: `~/orca-android-tools/mcp_list_sizes.py` (list bytes per
   tool) and `mcp_reply_sizes.py` (Read-tool reply bytes). Description bytes are paid once per session, reply bytes per call:
   never trim description text that prevents a wrong call (a retry costs more).

Proposed rule (for the user's OK):
> MCP tools (new or changed): an edit replies with one ack line, everything it changed (side effects included) and any warning
> it can trigger; unchanged context (preset header, untouched shapes / copies) stays out, the Read tools give the full state.
> Descriptions say what the tool does, units / frames and the mistake it invites; shared field docs live once, other tools
> point to them. Before merge run `mcp_list_sizes.py`: list <= 30 KB, a tool over 1 KB desc + schema needs a reason.

## Plan (step 3, not started; one CI build together with Hollow)
A. `plateEdit` diff reply. `plateState(): Map<String, String>` keyed "Plate N" / "object N copy C" (line = plate + slice state;
   object name, triangles, plate, centre, size, rot, scale via a shared `copyText` also used by `plateDescribe`). Snapshot before
   `op()`, reply "AI: summary" + changed/new lines + "X: removed"; none = "Nothing changed on the plates." No preset header.
   **`transform` with rot + pos runs two plateEdits (McpTools.kt:752-755): snapshot once before both or the turn is lost.**
B. `edit()` diff reply: split `describe()` into header + dim lines + one block per top-level shape (its subtree, indented as now);
   reply = header (count, selection) + changed dims / blocks + "#id: removed" + the fit check (always). `design_set`, merge,
   design_describe keep the full tree.
C. Descriptions: `PLATE_EDIT` "Returns plate_describe." -> "Returns what changed; plate_describe has the rest."; the shape-edit reply
   is explained once in `INSTRUCTIONS` (McpTools.kt:1164), not in each `EDIT`.
D. Optional, smaller: shape_update field descriptions -> "as in shape_add" (types kept), design_set Format points to shape_add's
   fields (~2 KB together). Vec3 min/maxItems drop (~1 KB; server already validates). Judge after A-C.
Test on WSA debug: transform, arrange, duplicate, hollow, shape_add/update/group, dim_set on a bound dim; compare reply bytes.
