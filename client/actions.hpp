// actions.hpp -- doing something in the game, the way a click does it.
//
// 0xClient never builds a packet. A menu entry in this client carries a small callback that calls one
// of the client's SENDERS (walk, NPC option n, object option n), and the sender has the client build
// and queue the packet. doAction() calls the same sender with the same arguments -- see offsets.hpp's
// ACTIONS block for each one's signature and how they were found.
//
// WHERE it calls them matters more than what it calls. The sender writes into the client's outgoing
// packet stream, which the game thread is writing into too; calling it from our thread (or the JVM's)
// races the game and can corrupt the stream. So doAction() only QUEUES the action. A hardware execute
// breakpoint on the client's per-frame tick (ACT_TICK, via hwbp.hpp -- nothing patched) runs pump() on
// the game thread, between frames, and pump() calls at most one sender per frame.
//
// Off unless every ACT_* offset was measured for this build (the loader zeroes an unmeasured code RVA);
// OXC_ACTIONS=0 turns it off by hand. Seen working in game on client-241-3 (2026-10-09): walk and NPC
// options sent this way matched the client's own clicks argument for argument and took effect; object
// options (tree chopping) and item options (inventory Drop, option 7) worked the same day. Every
// action that is dropped says why, once, in the log; every action sent is logged.
#pragma once
#include <windows.h>
#include <atomic>
#include <cstdint>
#include <cstdlib>

#include "game.hpp"
#include "hwbp.hpp"
#include "log.hpp"
#include "offsets.hpp"

namespace oxc {
namespace actions {

enum Kind : int { Walk = 1, Npc = 2, Loc = 3, IfOp = 4, ObjOp = 5, OnItem = 6, OnLoc = 7, OnNpc = 8, OnObj = 9 };

struct Act {
    int kind = 0;
    int a = 0, b = 0, c = 0;   // Walk/Loc/ObjOp/OnLoc/OnObj: level, worldX, worldY   Npc/OnNpc: uid
                               // IfOp/OnItem: widgetId, slot
    int id = 0;                // Loc/OnLoc: object id   IfOp/OnItem/ObjOp/OnObj: item id
    int op = 0;                // Npc/Loc/ObjOp: option 1..5   IfOp: option 1..10
    int qty = 0;               // ObjOp: the ground stack's quantity
    int sw = 0, ss = 0, si = 0;  // On*: the item being used -- widget, slot, item id
};

struct Done {                  // what pump() did, for the DLL loop to log
    Act act;
    int result = 0;            // 1 sent, -1 NPC not found
};

constexpr std::uint32_t kQueue = 32;

struct State {
    Act queue[kQueue];
    std::atomic<std::uint32_t> head{0}, tail{0};   // producer: any caller of doAction; consumer: pump()
    Done done[kQueue];
    std::atomic<std::uint32_t> doneHead{0};
    std::uint32_t doneTail = 0;
    std::atomic<bool> armed{false};
    bool triedArm = false;
    std::atomic<bool> producing{false};            // serialises producers (JVM threads)
};

inline State& st() {
    static State s;
    return s;
}

using WalkFn = void (*)(void*, int*);
using NpcFn = void (*)(void*, std::uintptr_t, int, int);
using LocFn = void (*)(void*, int, int*, int, int);
using IfOpFn = void (*)(void*, int, int, int, int, int, char);
using ObjOpFn = void (*)(void*, int, int, int*, int, int);
using OnFn = void (*)(void*, int*);

// ---------------------------------------------------------------------------------------------------
// Varbits. The client's own reader (GET_VARBIT) loads a varbit's definition on first use, which is only
// safe on the game thread -- so a caller registers the ids it wants (varbit()) and pump() refreshes all of
// them every frame. A value is -1 until the first refresh after it was registered.
// ---------------------------------------------------------------------------------------------------
constexpr int kVarbits = 64;

struct Varbits {
    std::atomic<int> ids[kVarbits];
    std::atomic<int> values[kVarbits];
    std::atomic<int> count{0};
    Varbits() { for (int i = 0; i < kVarbits; ++i) { ids[i].store(-1); values[i].store(-1); } }
};

inline Varbits& varbits() {
    static Varbits v;
    return v;
}

using VarbitFn = unsigned (*)(int);

inline void refreshVarbits() {
    if (!off::GET_VARBIT) return;
    Varbits& v = varbits();
    const int n = v.count.load();
    const auto fn = reinterpret_cast<VarbitFn>(moduleBase() + off::GET_VARBIT);
    for (int i = 0; i < n && i < kVarbits; ++i) {
        const int id = v.ids[i].load();
        if (id >= 0) v.values[i].store(static_cast<int>(fn(id)));
    }
}

// ---------------------------------------------------------------------------------------------------
// "Use item on ..." -- point the client's selection at the item for one call (offsets.hpp, USE ITEM ON).
// ---------------------------------------------------------------------------------------------------
inline bool withSelection(const Act& a, std::uintptr_t sender, int* args) {
    const std::uintptr_t state = rdp(clientObj() + off::TARGET_STATE);
    if (!state) return false;
    const std::uintptr_t sel = state + off::TARGET_SEL;
    auto* w = reinterpret_cast<std::int32_t*>(sel + off::SEL_WIDGET);
    auto* sl = reinterpret_cast<std::int32_t*>(sel + off::SEL_SLOT);
    auto* it = reinterpret_cast<std::int32_t*>(sel + off::SEL_ITEM);
    if (!readable(reinterpret_cast<std::uintptr_t>(w), 4) || !readable(reinterpret_cast<std::uintptr_t>(sl), 4) ||
        !readable(reinterpret_cast<std::uintptr_t>(it), 4)) return false;
    const std::int32_t ow = *w, os = *sl, oi = *it;
    *w = a.sw; *sl = a.ss; *it = a.si;
    reinterpret_cast<OnFn>(moduleBase() + sender)(nullptr, args);
    *w = ow; *sl = os; *it = oi;
    return true;
}

// Runs on the game thread, from the tick breakpoint. One action per frame.
inline void pump(CONTEXT*) {
    refreshVarbits();
    State& s = st();
    const std::uint32_t t = s.tail.load();
    if (t == s.head.load()) return;
    const Act a = s.queue[t % kQueue];
    s.tail.store(t + 1);
    Done d{a, 1};
    const std::uintptr_t base = moduleBase();
    if (a.kind == Walk) {
        int args[3] = {a.a, a.b, a.c};
        reinterpret_cast<WalkFn>(base + off::ACT_WALK)(nullptr, args);
    } else if (a.kind == Npc) {
        bool found = false;
        const Entity e = findEntity(a.a, false, found);   // resolved now, on the game thread
        if (found) reinterpret_cast<NpcFn>(base + off::ACT_NPC_OP)(nullptr, e.addr, a.op, 0);
        else d.result = -1;
    } else if (a.kind == Loc) {
        int args[6] = {a.a, a.b, a.c, a.id, a.op, 0};
        reinterpret_cast<LocFn>(base + off::ACT_LOC_OP)(nullptr, a.id, args, a.op, 0);
    } else if (a.kind == IfOp) {
        // {widget, slot, op, subop 0, item, flag 0} -- the menu-entry callback's own layout (offsets.hpp)
        reinterpret_cast<IfOpFn>(base + off::ACT_IF_OP)(nullptr, a.a, a.b, a.op, 0, a.id, 0);
    } else if (a.kind == ObjOp) {
        // {level, x, y, id, quantity, op, flag} -- the menu thunk 0x3893d0's block (offsets.hpp)
        int args[7] = {a.a, a.b, a.c, a.id, a.qty, a.op, 0};
        reinterpret_cast<ObjOpFn>(base + off::ACT_OBJ_OP)(nullptr, a.id, a.qty, args, a.op, 0);
    } else if (a.kind == OnItem) {
        int args[3] = {a.a, a.b, a.id};
        if (!withSelection(a, off::ACT_ON_ITEM, args)) d.result = -2;
    } else if (a.kind == OnLoc) {
        // {level, x, y, id, id, 0}: the client's own "Use Logs -> Bank booth" passed the id twice (hooked live)
        int args[6] = {a.a, a.b, a.c, a.id, a.id, 0};
        if (!withSelection(a, off::ACT_ON_LOC, args)) d.result = -2;
    } else if (a.kind == OnNpc) {
        int args[1] = {a.a};
        if (!withSelection(a, off::ACT_ON_NPC, args)) d.result = -2;
    } else if (a.kind == OnObj) {
        int args[4] = {a.a, a.b, a.c, a.id};
        if (!withSelection(a, off::ACT_ON_OBJ, args)) d.result = -2;
    }
    const std::uint32_t i = s.doneHead.load();
    s.done[i % kQueue] = d;
    s.doneHead.store(i + 1);
}

// On whenever the senders are measured for this build (the loader zeroes them otherwise);
// OXC_ACTIONS=0 turns actions off regardless.
inline bool enabledByEnv() {
    const char* e = ::getenv("OXC_ACTIONS");
    return !(e && e[0] == '0');
}

/// Why actions cannot run right now, or nullptr when they can.
inline const char* unavailable() {
    if (!off::ACT_TICK || !off::ACT_WALK || !off::ACT_NPC_OP || !off::ACT_LOC_OP)
        return "the ACT_* senders were not derived for this build";
    if (!enabledByEnv()) return "actions are turned off (OXC_ACTIONS=0)";
    if (!st().armed.load()) return "the game-tick breakpoint is not armed (yet, or no free debug register)";
    return nullptr;
}

/// The sender a kind needs, or 0 when that sender is not measured on this build.
inline std::uintptr_t senderFor(int kind) {
    switch (kind) {
        case IfOp: return off::ACT_IF_OP;
        case ObjOp: return off::ACT_OBJ_OP;
        case OnItem: return off::ACT_ON_ITEM;
        case OnLoc: return off::ACT_ON_LOC;
        case OnNpc: return off::ACT_ON_NPC;
        case OnObj: return off::ACT_ON_OBJ;
        default: return 1;   // Walk/Npc/Loc: covered by unavailable()
    }
}

inline const char* kindName(int kind) {
    switch (kind) {
        case IfOp: return "item/interface options (ACT_IF_OP)";
        case ObjOp: return "ground item options (ACT_OBJ_OP)";
        case OnItem: return "use item on item (ACT_ON_ITEM)";
        case OnLoc: return "use item on object (ACT_ON_LOC)";
        case OnNpc: return "use item on NPC (ACT_ON_NPC)";
        case OnObj: return "use item on ground item (ACT_ON_OBJ)";
        default: return "?";
    }
}

inline bool submit(const Act& a) {
    if (!senderFor(a.kind)) {
        static bool warnedKind[16] = {};
        if (a.kind >= 0 && a.kind < 16 && !warnedKind[a.kind]) {
            warnedKind[a.kind] = true;
            oxc::logf("[actions] dropped: %s was not derived for this build\n", kindName(a.kind));
        }
        return false;
    }
    if (const char* why = unavailable()) {
        static bool warned = false;
        if (!warned) {
            warned = true;
            oxc::logf("[actions] dropped: %s\n", why);
        }
        return false;
    }
    State& s = st();
    bool expected = false;
    while (!s.producing.compare_exchange_weak(expected, true)) { expected = false; YieldProcessor(); }
    const std::uint32_t h = s.head.load();
    const bool room = h - s.tail.load() < kQueue;
    if (room) {
        s.queue[h % kQueue] = a;
        s.head.store(h + 1);
    }
    s.producing.store(false);
    return room;
}

inline void testHotkeys();   // defined below doAction

/// Call from the DLL loop (the only thread that touches hwbp's slots): arms the tick breakpoint once,
/// and logs what pump() did.
inline void tick() {
    testHotkeys();
    State& s = st();
    if (!s.triedArm && off::ACT_TICK && off::ACT_WALK && off::ACT_NPC_OP && off::ACT_LOC_OP && enabledByEnv()) {
        s.triedArm = true;
        const bool ok = hwbp::add(off::ACT_TICK, pump) >= 0;
        if (ok) hwbp::tick();      // arm the threads now, not two seconds from now
        s.armed.store(ok);
        oxc::logf(ok ? "[actions] armed on the game tick (rva 0x%llx)\n" : "[actions] could not arm the game tick (rva 0x%llx)\n",
                  static_cast<unsigned long long>(off::ACT_TICK));
    }
    for (int k = 0; k < 16 && s.doneTail != s.doneHead.load(); ++k, ++s.doneTail) {
        const Done& d = s.done[s.doneTail % kQueue];
        const Act& a = d.act;
        if (a.kind == Walk) oxc::logf("[actions] walk to (%d,%d) plane %d: sent\n", a.b, a.c, a.a);
        else if (a.kind == Npc) oxc::logf("[actions] NPC uid %d option %d: %s\n", a.a, a.op, d.result > 0 ? "sent" : "not found, dropped");
        else if (a.kind == Loc) oxc::logf("[actions] object %d at (%d,%d) option %d: sent\n", a.id, a.b, a.c, a.op);
        else if (a.kind == IfOp) oxc::logf("[actions] item %d in widget 0x%x slot %d option %d: sent\n", a.id, static_cast<unsigned>(a.a), a.b, a.op);
        else if (a.kind == ObjOp) oxc::logf("[actions] ground item %d x%d at (%d,%d) option %d: sent\n", a.id, a.qty, a.b, a.c, a.op);
        else {
            const char* what = a.kind == OnItem ? "item" : a.kind == OnLoc ? "object" : a.kind == OnNpc ? "NPC" : "ground item";
            if (d.result == -2) oxc::logf("[actions] use item %d on %s: dropped, the selection was not readable\n", a.si, what);
            else oxc::logf("[actions] use item %d (widget 0x%x slot %d) on %s %d at (%d,%d): sent\n",
                           a.si, static_cast<unsigned>(a.sw), a.ss, what, a.kind == OnNpc ? a.a : a.id, a.b, a.c);
        }
    }
}

inline int planeOf(const Entity& e) { return e.plane >= 0 ? e.plane : 0; }

}  // namespace actions

// ---------------------------------------------------------------------------------------------------
// The API the natives (jvm.hpp) and plugins use. SCENE coordinates in, like everything else here.
// ---------------------------------------------------------------------------------------------------

/// Perform a menu action as if it were clicked. `opcode` is one of 0xClient's own labels from
/// offsets.hpp (OP_WALK, OPNPC1..5, OPLOC1); `targetId` is the NPC uid or the object id. Returns true
/// when the action was QUEUED -- it is sent on the game thread within a frame or two -- and false when
/// it was dropped (actions unavailable on this build, the client not up, an unknown opcode, a full
/// queue). Plugins must not report success on false.
inline bool doAction(int sceneX, int sceneY, int opcode, int targetId) {
    if (!clientObj()) return false;
    const Tile b = sceneBase();
    if (!b.ok) return false;
    bool meFound = false;
    const Entity me = localPlayer(meFound);
    const int level = meFound ? actions::planeOf(me) : 0;
    actions::Act a;
    if (opcode == off::OP_WALK) {
        a.kind = actions::Walk; a.a = level; a.b = b.x + sceneX; a.c = b.y + sceneY;
    } else if (opcode >= off::OPNPC1 && opcode <= off::OPNPC5) {
        a.kind = actions::Npc; a.a = targetId; a.op = opcode - off::OPNPC1 + 1;
    } else if (opcode == off::OPLOC1) {
        a.kind = actions::Loc; a.a = level; a.b = b.x + sceneX; a.c = b.y + sceneY; a.id = targetId; a.op = 1;
    } else {
        static bool warned = false;
        if (!warned) { warned = true; oxc::logf("[actions] dropped: opcode %d is not one doAction knows\n", opcode); }
        return false;
    }
    return actions::submit(a);
}

/// Walk to a SCENE tile. The game pathfinds and sends the movement itself; we only say where.
inline bool walkTo(int sceneX, int sceneY) {
    return doAction(sceneX, sceneY, off::OP_WALK, 0);
}

/// The level the local player is on (0 when unknown).
inline int myLevel() {
    bool f = false;
    const Entity me = localPlayer(f);
    return f ? actions::planeOf(me) : 0;
}

/// Take option `op` (1..5) on the ground item `itemId` (a stack of `quantity`) at SCENE tile (sx, sy).
inline bool groundItemAction(int sceneX, int sceneY, int itemId, int quantity, int op) {
    if (!clientObj() || op < 1 || op > 5 || itemId < 0) return false;
    const Tile b = sceneBase();
    if (!b.ok) return false;
    actions::Act a;
    a.kind = actions::ObjOp; a.a = myLevel(); a.b = b.x + sceneX; a.c = b.y + sceneY; a.id = itemId; a.qty = quantity; a.op = op;
    return actions::submit(a);
}

/// "Use" the item (srcWidget, srcSlot, srcItem) on a target. kind: 6 item (tx = widget, ty = slot,
/// targetId = item), 7 object (tx, ty = SCENE tile, targetId = loc id), 8 NPC (targetId = uid),
/// 9 ground item (tx, ty = SCENE tile, targetId = item id).
inline bool useItemOn(int kind, int srcWidget, int srcSlot, int srcItem, int tx, int ty, int targetId) {
    if (!clientObj() || srcItem < 0 || srcSlot < 0) return false;
    actions::Act a;
    a.kind = kind; a.sw = srcWidget; a.ss = srcSlot; a.si = srcItem; a.id = targetId;
    if (kind == actions::OnItem) { a.a = tx; a.b = ty; }
    else if (kind == actions::OnNpc) { a.a = targetId; }
    else if (kind == actions::OnLoc || kind == actions::OnObj) {
        const Tile b = sceneBase();
        if (!b.ok) return false;
        a.a = myLevel(); a.b = b.x + tx; a.c = b.y + ty;
    } else return false;
    return actions::submit(a);
}

/// A varbit's value as of the last frame, or -1 until it has been read once (registering the id is what
/// starts the reads -- see actions::Varbits). Needs actions armed; -1 forever when they are not.
inline int varbit(int id) {
    if (id < 0) return -1;
    actions::Varbits& v = actions::varbits();
    const int n = v.count.load();
    for (int i = 0; i < n && i < actions::kVarbits; ++i) if (v.ids[i].load() == id) return v.values[i].load();
    if (n >= actions::kVarbits) return -1;
    int expected = n;
    if (v.count.compare_exchange_strong(expected, n + 1)) v.ids[n].store(id);
    return -1;
}

/// Take option `op` (1..5, as numbered in the object's right-click menu) on the scenery object `id`
/// whose origin is SCENE tile (sceneX, sceneY) -- the same call a click on that option makes. Queued; false
/// when dropped.
inline bool objectAction(int sceneX, int sceneY, int id, int op) {
    if (!clientObj() || op < 1 || op > 5 || id < 0) return false;
    const Tile b = sceneBase();
    if (!b.ok) return false;
    bool meFound = false;
    const Entity me = localPlayer(meFound);
    actions::Act a;
    a.kind = actions::Loc; a.a = meFound ? actions::planeOf(me) : 0; a.b = b.x + sceneX; a.c = b.y + sceneY;
    a.id = id; a.op = op;
    return actions::submit(a);
}

/// Take option `op` (1..10) on the item in `slot` of interface component `widgetId` ((group << 16) |
/// component; the inventory is 149:0 = 0x950000), the way clicking that option on the item does.
/// `itemId` is the item the caller expects there; the client sends it with the option and the server
/// checks it. The client itself drops an option the slot does not offer (offsets.hpp, ACT_IF_OP).
/// Returns true when QUEUED; false when dropped (ACT_IF_OP not measured, actions unavailable, bad
/// arguments, full queue).
inline bool itemAction(int widgetId, int slot, int op, int itemId) {
    // slot and item are -1 for a plain interface button (a component that is not an item slot)
    if (!clientObj() || slot < -1 || op < 1 || op > 10 || itemId < -1) return false;
    actions::Act a;
    a.kind = actions::IfOp; a.a = widgetId; a.b = slot; a.op = op; a.id = itemId;
    return actions::submit(a);
}

/// Interact with an NPC by uid -- the NPC is looked up again on the game thread when the action runs,
/// so one that despawned in between is dropped (and logged), not sent.
inline bool interactNpc(int uid, int opcode) {
    bool found = false;
    Entity target = findEntity(uid, false, found);
    if (!found) return false;
    return doAction(target.sceneX, target.sceneY, opcode, uid);
}

// ---------------------------------------------------------------------------------------------------
// OXC_ACTIONTEST=1: three hotkeys that exercise doAction from the DLL itself, so the first live test of
// the senders needs no plugin. Edge-triggered; only while the game window has focus.
//   Ctrl+Shift+W  walk two tiles east of you
//   Ctrl+Shift+T  the nearest NPC's option 1 (Talk-to on a person)
//   Ctrl+Shift+Y  the nearest NPC's option 2 (Attack on a monster)
// ---------------------------------------------------------------------------------------------------
inline void actions::testHotkeys() {
    static int enabled = -1;
    if (enabled < 0) {
        const char* e = ::getenv("OXC_ACTIONTEST");
        enabled = (e && e[0] == '1') ? 1 : 0;
        if (enabled) oxc::logf("[actions] test hotkeys on: Ctrl+Shift+W walk, Ctrl+Shift+T NPC option 1, Ctrl+Shift+Y NPC option 2\n");
    }
    if (!enabled) return;
    static bool was[3] = {false, false, false};
    const bool mods = (GetAsyncKeyState(VK_CONTROL) & 0x8000) && (GetAsyncKeyState(VK_SHIFT) & 0x8000);
    const int keys[3] = {'W', 'T', 'Y'};
    for (int k = 0; k < 3; ++k) {
        const bool down = mods && (GetAsyncKeyState(keys[k]) & 0x8000);
        if (down && !was[k]) {
            bool found = false;
            const Entity me = localPlayer(found);
            if (!found) { oxc::logf("[actions] test: you are not in the world\n"); }
            else if (k == 0) {
                const bool ok = walkTo(me.sceneX + 2, me.sceneY);
                oxc::logf("[actions] test: walk to scene (%d,%d): %s\n", me.sceneX + 2, me.sceneY, ok ? "queued" : "dropped");
            } else {
                int bestUid = -1, bestD = 1 << 30;
                forEachEntity([&](const Entity& e) {
                    if (e.player) return;
                    const int d = (e.sceneX - me.sceneX) * (e.sceneX - me.sceneX) + (e.sceneY - me.sceneY) * (e.sceneY - me.sceneY);
                    if (d < bestD) { bestD = d; bestUid = e.uid; }
                });
                if (bestUid < 0) oxc::logf("[actions] test: no NPC nearby\n");
                else {
                    const int op = k == 1 ? off::OPNPC1 : off::OPNPC2;
                    const bool ok = interactNpc(bestUid, op);
                    oxc::logf("[actions] test: NPC uid %d option %d: %s\n", bestUid, k, ok ? "queued" : "dropped");
                }
            }
        }
        was[k] = down;
    }
}

}  // namespace oxc
