// Standalone checks of RoomUndo.hpp, the focus room's history rules (no libslic3r):
// g++ -std=c++17 -I../jni room_undo_test.cpp && ./a.out
// The engine itself needs libslic3r, so this test drives the same functions OrcaEngine calls on stand-in snapshots
// (an int per step: the model it holds) with copies of OrcaEngine's push_undo / undo / redo around them.
#include "RoomUndo.hpp"

#include <cassert>
#include <cstdio>
#include <deque>

using roomundo::Room;

struct Engine
{
    int             model = 0; // the scene; each op adds 1
    std::deque<int> undo, redo;
    std::size_t     limit = 200, calib = 0;
    Room            room;

    void push()
    {
        undo.push_back(model);
        roomundo::trim(room, undo, limit, calib);
        redo.clear();
        if (room.open)
            room.changed = true;
    }
    void op()
    {
        push();
        ++model;
    }
    bool can_undo() const { return roomundo::can_undo(room, undo); }
    bool do_undo()
    {
        if (!can_undo())
            return false;
        if (room.open)
            room.changed = true;
        redo.push_back(model);
        model = undo.back();
        undo.pop_back();
        return true;
    }
    bool do_redo()
    {
        if (redo.empty())
            return false;
        if (room.open)
            room.changed = true;
        undo.push_back(model);
        model = redo.back();
        redo.pop_back();
        return true;
    }
};

int main()
{
    // Rule 2: three ops in the room are one plate step; Undo restores the entry, Redo the visit.
    {
        Engine e;
        e.op();
        e.op(); // model 2, two steps before the room
        roomundo::begin(e.room, e.undo);
        const int entry = e.model;
        e.op();
        e.op();
        e.op();
        // Rule 1: inside, Undo works three times and stops at the floor.
        assert(e.do_undo() && e.do_undo() && e.do_undo());
        assert(!e.can_undo() && !e.do_undo() && e.model == entry);
        assert(e.do_redo() && e.do_redo() && e.do_redo());
        assert(e.model == entry + 3);
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(!e.room.open && e.undo.size() == 3 && e.redo.empty());
        assert(e.do_undo() && e.model == entry);
        assert(e.do_redo() && e.model == entry + 3);
        assert(e.do_undo() && e.do_undo() && e.model == 1); // then the step before the room
    }
    // Done with no change: the history is as it was, Redo from before the room included.
    {
        Engine e;
        e.op();
        e.op();
        e.do_undo();
        const std::size_t u = e.undo.size(), r = e.redo.size();
        const bool cu = e.can_undo();
        roomundo::begin(e.room, e.undo);
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.undo.size() == u && e.redo.size() == r && e.can_undo() == cu);
    }
    // A visit whose steps were all undone again leaves no step and no Redo into the room.
    {
        Engine e;
        e.op();
        roomundo::begin(e.room, e.undo);
        e.op();
        e.op();
        e.do_undo();
        e.do_undo();
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.undo.size() == 1 && e.redo.empty() && e.model == 1);
    }
    // roomBegin while open does nothing; roomFloor stops Undo at the current depth and drops Redo.
    {
        Engine e;
        roomundo::begin(e.room, e.undo);
        e.op();
        roomundo::begin(e.room, e.undo);
        assert(e.room.floor == 0);
        e.op();
        e.do_undo();
        roomundo::set_tool_floor(e.room, e.undo, e.redo);
        assert(e.room.tool_floor == 1 && e.redo.empty() && !e.can_undo());
        e.op();
        assert(e.do_undo() && !e.can_undo());
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.undo.size() == 1 && e.do_undo() && e.model == 0);
    }
    // roomFloor and roomEnd outside the room change nothing.
    {
        Engine e;
        e.op();
        e.do_undo();
        roomundo::set_tool_floor(e.room, e.undo, e.redo);
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.redo.size() == 1 && !e.room.open);
    }
    // trim_undo while open, floor > 0: the front goes and both floors follow.
    {
        Engine e;
        e.limit = 4;
        e.op();
        e.op(); // undo [0 1]
        roomundo::begin(e.room, e.undo); // floor 2
        e.op();
        e.op();
        e.op(); // [1 2 3 4] after one trim
        assert(e.undo.size() == 4 && e.room.floor == 1 && e.room.tool_floor == 1 && e.undo[e.room.floor] == 2);
        e.op(); // [2 3 4 5]
        assert(e.room.floor == 0 && e.undo[0] == 2);
        // floor == 0: the oldest step made in the room goes, the entry snapshot stays.
        e.op(); // [2 3 4 5 6] -> [2 4 5 6]
        assert(e.undo.size() == 4 && e.room.floor == 0 && e.undo[0] == 2 && e.undo[1] == 4);
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.undo.size() == 1 && e.do_undo() && e.model == 2);
    }
    // trim with floor == 0 lowers a tool floor above floor + 1, keeps one at floor + 1.
    {
        Engine e;
        e.limit = 3;
        roomundo::begin(e.room, e.undo);
        e.op();
        e.op();
        e.op(); // [0 1 2]
        roomundo::set_tool_floor(e.room, e.undo, e.redo); // 3
        e.op(); // [0 1 2 3] -> [0 2 3]
        assert(e.room.tool_floor == 2 && e.undo[1] == 2);
        assert(e.do_undo() && !e.can_undo() && e.model == 3);
        e.limit = 1;
        e.op();
        roomundo::trim(e.room, e.undo, e.limit, e.calib);
        assert(e.undo.size() == 1 && e.undo[0] == 0);
    }
    // The calibration depth: dropped with the front, and no deeper than the history after roomEnd.
    {
        Engine e;
        e.op();
        e.op();
        e.calib = 2;
        roomundo::begin(e.room, e.undo);
        e.op();
        e.calib = 3; // a calibration started in the room
        e.op();
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.undo.size() == 3 && e.calib == 3);
        Engine f;
        f.limit = 2;
        f.op();
        f.calib = 1;
        roomundo::begin(f.room, f.undo);
        f.op();
        f.op(); // [0 1 2] -> [1 2]: front gone, floor 0
        assert(f.calib == 0 && f.room.floor == 0);
    }
    // roomEnd with discard (a visit that only wrapped a mesh): the model is the entry snapshot, no step stays, Redo as before.
    {
        Engine e;
        e.op();
        e.op();
        e.do_undo(); // undo [0], redo [1]
        const int entry = e.model;
        const std::size_t u = e.undo.size(), r = e.redo.size();
        const bool cu = e.can_undo();
        std::deque<int> entry_redo = e.redo; // OrcaEngine::room_begin keeps this
        roomundo::begin(e.room, e.undo);
        e.op(); // the wrap: a step of its own, snapshot = the untagged model
        e.room.tool_floor = e.undo.size(); // roomFloor
        e.redo.clear();
        e.op(); // the first render's step, snapshot = the tagged model
        assert(e.undo.size() == u + 2 && e.undo[u] == entry);
        // OrcaEngine::room_end(discard): restore undo[floor], then erase from the floor.
        const int snap = e.undo[e.room.floor];
        assert(roomundo::discard(e.room, e.undo, e.redo, entry_redo, e.calib));
        e.model = snap;
        assert(!e.room.open && e.model == entry && e.undo.size() == u && e.redo.size() == r && e.can_undo() == cu);
        assert(e.do_redo() && e.model == entry + 1); // the Redo from before the visit still works
    }
    // Discard with nothing pushed: nothing to restore, calibration depth clamped, redo as before; outside the room: no-op.
    {
        Engine e;
        e.op();
        e.calib = 1;
        std::deque<int> entry_redo;
        roomundo::begin(e.room, e.undo);
        assert(!roomundo::discard(e.room, e.undo, e.redo, entry_redo, e.calib) && !e.room.open && e.undo.size() == 1);
        assert(!roomundo::discard(e.room, e.undo, e.redo, entry_redo, e.calib));
    }
    // A wrap followed by a real change is ONE plate step back to the untagged model at entry (no discard).
    {
        Engine e;
        e.op(); // model 1 = the untagged mesh at entry
        roomundo::begin(e.room, e.undo);
        const int entry = e.model;
        e.op(); // wrap (undo step: snapshot = untagged)
        e.room.tool_floor = e.undo.size(); // roomFloor
        e.op(); // first render
        e.op(); // a real change
        roomundo::end(e.room, e.undo, e.redo, e.calib);
        assert(e.undo.size() == 2 && e.undo[1] == entry);
        assert(e.do_undo() && e.model == entry && e.do_redo() && e.model == entry + 3);
    }
    // rollback_on_error copies the Room back as it was.
    {
        Engine e;
        roomundo::begin(e.room, e.undo);
        const Room saved = e.room;
        e.op();
        e.room = saved;
        assert(e.room.open && e.room.floor == 0 && e.room.tool_floor == 0 && !e.room.changed);
    }
    std::printf("room_undo_test: all passed\n");
    return 0;
}
