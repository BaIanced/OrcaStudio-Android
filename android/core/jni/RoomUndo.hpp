// The focus room's plate history rules (docs/design/FOCUS-ROOM.md section 7), on any deque of snapshots, so the
// rules are checked without libslic3r (tests/room_undo_test.cpp). OrcaEngine holds the state and the deques.
#pragma once

#include <algorithm>
#include <cstddef>
#include <deque>

namespace roomundo {

// m_room_open, m_room_floor, m_room_tool_floor (and whether the history changed during the visit).
struct Room
{
    bool        open       = false;
    std::size_t floor      = 0;     // undo[floor] is the snapshot the room's first op pushed: the model at entry
    std::size_t tool_floor = 0;     // Undo stops here while the room is open
    bool        changed    = false; // a push, undo or redo happened during the visit
};

// roomBegin: opens the room at the current depth; nothing if already open.
template<class T> void begin(Room &r, const std::deque<T> &undo)
{
    if (r.open)
        return;
    r.open       = true;
    r.floor      = undo.size();
    r.tool_floor = undo.size();
    r.changed    = false;
}

// roomFloor: Undo now stops at the current depth (a conversion, or the plate stack became the active one).
template<class T> void set_tool_floor(Room &r, const std::deque<T> &undo, std::deque<T> &redo)
{
    if (!r.open)
        return;
    r.tool_floor = undo.size();
    redo.clear();
}

// roomEnd: keeps the entry snapshot and drops the steps made in the room, so the visit is one plate step.
template<class T> void end(Room &r, std::deque<T> &undo, std::deque<T> &redo, std::size_t &calib_depth)
{
    if (!r.open)
        return;
    if (undo.size() > r.floor + 1)
        undo.erase(undo.begin() + std::ptrdiff_t(r.floor + 1), undo.end());
    if (r.changed)
        redo.clear();
    r.open      = false;
    r.changed   = false;
    calib_depth = std::min(calib_depth, undo.size());
}

// Scene JSON's can_undo.
template<class T> bool can_undo(const Room &r, const std::deque<T> &undo)
{
    return r.open ? undo.size() > r.tool_floor : !undo.empty();
}

// trim_undo: drops the oldest steps beyond `limit`. While the room is open the entry snapshot stays: the front goes
// while there are steps before the room; then the oldest step made in the room goes.
template<class T> void trim(Room &r, std::deque<T> &undo, std::size_t limit, std::size_t &calib_depth)
{
    while (undo.size() > limit) {
        if (!r.open || r.floor > 0) {
            undo.pop_front();
            if (r.open) {
                --r.floor;
                --r.tool_floor;
            }
            if (calib_depth > 0)
                --calib_depth;
        } else {
            undo.erase(undo.begin() + std::ptrdiff_t(r.floor + 1));
            if (r.tool_floor > r.floor + 1)
                --r.tool_floor;
            if (calib_depth > r.floor + 1)
                --calib_depth;
        }
    }
}

} // namespace roomundo
