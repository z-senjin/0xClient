// game.hpp -- reading the game, and doing one thing to it.
//
// Everything here is either a guarded memory read or a call into a function the game already has. There
// is no packet building anywhere in 0xClient, on purpose: we ask the client to perform a menu action
// and it builds and sends the packet itself. That means we never have to track the wire protocol, which
// is the part that changes most often and is hardest to get right.
#pragma once
#include <windows.h>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include "offsets.hpp"
#include "log.hpp"

namespace oxc {

// ---------------------------------------------------------------------------------------------------
// Guarded reads. Everything we touch is a pointer we derived, in a process we do not own, while the game
// is actively mutating it. A torn or stale read is NORMAL. Never let one crash the client -- return a
// zero and skip that frame instead.
// ---------------------------------------------------------------------------------------------------
inline bool readable(std::uintptr_t p, std::size_t n) {
    if (p < 0x10000) return false;                       // null-ish / first page
    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(reinterpret_cast<void*>(p), &mbi, sizeof mbi)) return false;
    if (mbi.State != MEM_COMMIT) return false;
    if (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) return false;
    return p + n <= reinterpret_cast<std::uintptr_t>(mbi.BaseAddress) + mbi.RegionSize;
}

template <class T>
T rd(std::uintptr_t addr, T fallback = T{}) {
    return readable(addr, sizeof(T)) ? *reinterpret_cast<T*>(addr) : fallback;
}

inline std::uintptr_t rdp(std::uintptr_t addr) { return rd<std::uintptr_t>(addr); }

// ---------------------------------------------------------------------------------------------------
// The roots
// ---------------------------------------------------------------------------------------------------
inline std::uintptr_t moduleBase() {
    static std::uintptr_t b = reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
    return b;
}

/// The address of a GLOBAL (an image-base-relative RVA from offsets.hpp), or 0 when that RVA is 0 --
/// the loader's "not measured on this build" sentinel (client/offsets_json.hpp). Every read through a
/// 0 address fails closed (readable() refuses the first page), so a refused global reads as "not up".
inline std::uintptr_t globalAddr(std::uintptr_t rva) { return rva ? moduleBase() + rva : 0; }

/// The client object, or 0 if the game has not built it yet (it is null for the first few seconds),
/// or CLIENT_OBJ_PTR was not derived for this build.
inline std::uintptr_t clientObj() { return rdp(globalAddr(off::CLIENT_OBJ_PTR)); }

/// The scene object, or 0.
inline std::uintptr_t scene() {
    std::uintptr_t c = clientObj();
    return c ? rdp(c + off::SCENE) : 0;
}

struct Tile { int x = 0, y = 0; bool ok = false; };

/// The scene's south-west corner, in world tiles. Entities store SCENE coords; add this to get world.
inline Tile sceneBase() {
    std::uintptr_t s = scene();
    if (!s) return {};
    return { rd<std::int32_t>(s + off::SCENE_BASE_X), rd<std::int32_t>(s + off::SCENE_BASE_Y), true };
}

// ---------------------------------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------------------------------
struct Entity {
    int            uid = 0;
    std::uintptr_t addr = 0;
    int            sceneX = 0, sceneY = 0;
    // Render position in fine units (offsets.hpp ENTITY_FINE_*): where the model is actually drawn,
    // height included. fineX/fineY are the tile centre when the read is implausible, fineH 0 then.
    int            fineX = 0, fineY = 0, fineH = 0;
    int            plane = 0;
    int            animation = -1;
    int            orientation = 0;
    // Which of the registry's two tables this node came out of (offsets.hpp: +0x68 players, +0x98
    // NPCs). Player handles and NPC uids are separate keyspaces -- the client resolves PLAYER_IDS
    // only against the player table and scene+0xD0 only against the NPC table -- so a uid alone
    // cannot say which kind an entity is; the walker knows, and records it here rather than having
    // every consumer re-derive it with a PLAYER_IDS scan (which was O(entities x players) guarded
    // reads per frame and misclassified any NPC whose uid happened to equal a player handle).
    bool           player = false;
};

/// What KIND of NPC this is. Meaningless for players -- they have no definition object -- so check
/// Entity::player first. Returns -1 when the extra pointer hop lands somewhere unreadable, which
/// happens for an entity being spawned or despawned as we walk the table.
///
/// Range-guarded like ENTITY_PLANE: the layout behind this (def+0x0 == the id) is NOT re-verified on
/// client-240-6 (offsets.hpp), and if that slot turned out to be a vtable/refcount pointer the low
/// 32 bits would ship as a huge per-kind "id". OSRS npc ids are well under 0xFFFF, so anything
/// outside 0..0xFFFF is certainly not an id and goes out as -1 (Java's "unavailable"). An in-range
/// wrong value cannot be caught here -- the OXC_LOG probe in jvm.hpp prints the nearest NPC's raw
/// id next to its name so a Banker (id ~1613..1634) can be checked by eye.
inline int npcTypeId(std::uintptr_t entity) {
    if (!entity) return -1;
    std::uintptr_t def = rdp(entity + off::ENTITY_DEF_PTR);
    if (!def) return -1;
    int id = rd<std::int32_t>(def, -1);
    return (id < 0 || id > 0xFFFF) ? -1 : id;
}

/// A player's combat level. ALWAYS -1 on this build: the offset we had (PLAYER_COMBAT_LEVEL,
/// offsets.hpp) is wrong on client-240-6 -- it read a pointer fragment on NPCs and -1 on the local
/// player -- so reading it here shipped pointer garbage out as players' combat levels. Same
/// -1-means-unavailable convention as npcTypeId above; re-derive the offset (see its block in
/// offsets.hpp) before any read comes back.
inline int combatLevel(std::uintptr_t /*entity*/) {
    return -1;
}

/// Walk every entity in the client's registry -- players AND NPCs, from their separate tables -- and
/// hand each to `cb`. The structure (map -> groups -> table pair -> nodes) is documented in
/// offsets.hpp. Bounded at every level so a resize happening under us cannot spin forever: the game
/// rehashes these tables on its own thread, and a chain observed mid-rehash can point at itself.
template <class F>
void forEachEntity(F&& cb) {
    std::uintptr_t c = clientObj();
    if (!c) return;
    std::uintptr_t groups = rdp(c + off::REGISTRY_GROUPS);
    std::uint64_t  gcount = rd<std::uint64_t>(c + off::REGISTRY_GROUP_COUNT);
    if (!groups || gcount == 0 || gcount > 0x1000) return;      // never a real registry this big

    auto walkTable = [&](std::uintptr_t pair, std::uintptr_t bucketsOff, std::uintptr_t countOff,
                         bool players) {
        std::uintptr_t buckets = rdp(pair + bucketsOff);
        std::uint64_t  bcount  = rd<std::uint64_t>(pair + countOff);
        if (!buckets || bcount == 0 || bcount > 0x40000) return;  // never a real table this big
        for (std::uint64_t b = 0; b < bcount; ++b) {
            std::uintptr_t node = rdp(buckets + b * 8);
            for (int guard = 0; node && guard < 128; ++guard, node = rdp(node + off::NODE_NEXT)) {
                std::uintptr_t e = rdp(node + off::NODE_ENTITY);
                if (!e) continue;
                Entity ent;
                ent.uid    = static_cast<int>(rd<std::uint32_t>(node + off::NODE_UID));
                ent.addr   = e;
                ent.sceneX = rd<std::int32_t>(e + off::ENTITY_SCENE_X);
                ent.sceneY = rd<std::int32_t>(e + off::ENTITY_SCENE_Y);
                // The scene is 104 tiles, indices 0..103 (offsets.hpp: the live-read scene size is
                // (104,104)); 104 itself is one past the edge, and was let through here before.
                if (ent.sceneX < 0 || ent.sceneY < 0 || ent.sceneX >= 104 || ent.sceneY >= 104) continue;
                {
                    // Render position, sanity-checked against the tile it is standing on: a moving
                    // entity is at most a tile away from its scene tile, and a ground height beyond
                    // a few floors is a misread (both cases fall back to the tile centre at datum 0,
                    // which is what every caller drew before 2026-09-05).
                    const int cx = (ent.sceneX << 7) + 64, cy = (ent.sceneY << 7) + 64;
                    const int fx = rd<std::int32_t>(e + off::ENTITY_FINE_X, cx);
                    const int fy = rd<std::int32_t>(e + off::ENTITY_FINE_Y, cy);
                    const int fh = rd<std::int32_t>(e + off::ENTITY_FINE_H, 0);
                    const bool xyOk = std::abs(fx - cx) <= 256 && std::abs(fy - cy) <= 256;
                    ent.fineX = xyOk ? fx : cx;
                    ent.fineY = xyOk ? fy : cy;
                    ent.fineH = (fh >= -4000 && fh <= 1000) ? fh : 0;
                }
                // Plane: ENTITY_PLANE (0x420) is SUSPECT on this build and offsets.hpp's decompile
                // notes point at ENTITY_PLANE_COORD (0x7CC) instead -- the `coord` binding returns
                // {+0x7CC, +0x3F0, +0x418} and the last two ARE the verified scene x/y. Read both and
                // prefer the decompile-backed one when it is a plausible plane (0..3), else fall back
                // to the old read when THAT is plausible, else -1 = unknown. A preference, not a
                // guarantee: if 0x420 was right and +0x7CC is some other small int, this is now wrong
                // where it was right. Both raw ints go out on the OXC_LOG [proj] line; the live
                // staircase check (which one steps 0..3) decides -- if raw420 steps and raw7CC does
                // not, flip the preference here (or collapse back to 0x420). Java's
                // consumers of -1: AutoWalk walks without the plane filter; the RuneLite shim's
                // WorldView.getPlane() assumes the ground floor (its javadoc says why); 0xClient's own
                // entity overlays never read it.
                const int p7cc = rd<std::int32_t>(e + off::ENTITY_PLANE_COORD, -1);
                const int p420 = rd<std::int32_t>(e + off::ENTITY_PLANE, -1);
                ent.plane = (p7cc >= 0 && p7cc <= 3) ? p7cc
                          : (p420 >= 0 && p420 <= 3) ? p420 : -1;
                ent.animation   = rd<std::int32_t>(e + off::ENTITY_ANIMATION, -1);
                ent.orientation = rd<std::int32_t>(e + off::ENTITY_ORIENTATION);
                ent.player      = players;      // known from which table we are in, not from the uid
                cb(ent);
            }
        }
    };

    for (std::uint64_t g = 0; g < gcount; ++g) {
        std::uintptr_t grp = rdp(groups + g * 8);
        for (int gguard = 0; grp && gguard < 256; ++gguard, grp = rdp(grp + off::GROUP_NEXT)) {
            std::uintptr_t pair = rdp(grp + off::GROUP_TABLE);
            if (!pair) continue;
            walkTable(pair, off::PLAYER_BUCKETS, off::PLAYER_BUCKET_COUNT, true);
            walkTable(pair, off::NPC_BUCKETS,    off::NPC_BUCKET_COUNT,    false);
        }
    }
}

// (isPlayerUid -- a per-uid linear scan of PLAYER_IDS -- used to live here. It is gone on purpose:
// the registry walk already knows the kind from the table it is in (Entity::player), and the scan was
// both wrong under a uid collision between the two tables and the hottest thing in the frame. If a
// cross-check against PLAYER_IDS is ever wanted again, do it once per frame, not once per entity.)

/// Your own player handle, or -1.
inline int localPlayerUid() {
    std::uintptr_t c = clientObj();
    return c ? rd<std::int32_t>(c + off::LOCAL_PLAYER_IDX, -1) : -1;
}

/// You, as an entity. `found` is false before you are in the world. Matched in the PLAYER table only:
/// LOCAL_PLAYER_IDX is a player handle (offsets.hpp: playerFindSelf resolves it through the +0x68
/// table), so an NPC that happens to carry the same uid is never mistaken for you.
inline Entity localPlayer(bool& found) {
    Entity me;
    found = false;
    int uid = localPlayerUid();
    if (uid < 0) return me;
    forEachEntity([&](const Entity& e) {
        if (found || !e.player || e.uid != uid) return;
        me = e;
        found = true;
    });
    return me;
}

/// Look an entity up by uid AND kind -- the uid alone is ambiguous across the two tables. `found` is
/// false when it despawned between frames -- which is a normal answer, not an error; callers show
/// nothing and move on.
inline Entity findEntity(int uid, bool player, bool& found) {
    Entity hit;
    found = false;
    if (uid < 0) return hit;
    forEachEntity([&](const Entity& e) {
        if (found || e.player != player || e.uid != uid) return;
        hit = e;
        found = true;
    });
    return hit;
}

// ---------------------------------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------------------------------
inline constexpr int SKILL_COUNT = 25;

/// One skill's effective (boosted) level, base level, or total xp. `which` is 0..24 -- see Skill.java.
inline int skillEffective(int which) {
    std::uintptr_t c = clientObj();
    if (!c || which < 0 || which >= SKILL_COUNT) return 0;
    return rd<std::int32_t>(c + off::SKILL_EFFECTIVE + static_cast<std::uintptr_t>(which) * 4);
}
inline int skillBase(int which) {
    std::uintptr_t c = clientObj();
    if (!c || which < 0 || which >= SKILL_COUNT) return 0;
    return rd<std::int32_t>(c + off::SKILL_BASE + static_cast<std::uintptr_t>(which) * 4);
}
inline int skillXp(int which) {
    std::uintptr_t c = clientObj();
    if (!c || which < 0 || which >= SKILL_COUNT) return 0;
    return rd<std::int32_t>(c + off::SKILL_XP + static_cast<std::uintptr_t>(which) * 4);
}

/// Run energy, 0..10000 (so 10000 is a full bar). SUSPECT: the offset behind this (RUN_ENERGY,
/// offsets.hpp) could not be re-derived on client-240-6 and nothing here re-checks it, so treat the
/// number as unverified until that happens.
inline int runEnergy() {
    std::uintptr_t c = clientObj();
    return c ? rd<std::int32_t>(c + off::RUN_ENERGY) : 0;
}

/// The client's frame counter. Useful as a cheap "is the game actually running" check.
inline int cycle() {
    std::uintptr_t c = clientObj();
    return c ? rd<std::int32_t>(c + off::CYCLE) : 0;
}

// ---------------------------------------------------------------------------------------------------
// Game state, names, world map, widgets
// ---------------------------------------------------------------------------------------------------
/// The client's own state machine: 10 title, 20 logging in, 25 loading, 30 logged in. 0 until the
/// client object exists. The panel's "waiting for the game" gate is this being 30, not a guess.
inline int gameState() {
    std::uintptr_t c = clientObj();
    return c ? rd<std::int32_t>(c + off::GAME_STATE) : 0;
}

/// Read a client NxtString (the 24-byte inline-buffer-or-heap string documented in offsets.hpp) at
/// `str` into a std::string. The heap flag is ALWAYS the byte at str+0x17 -- this takes the string's
/// base address only, on purpose. The previous two-argument shape (`fieldAddr, flagOff`) added
/// flagOff to fieldAddr, and three of its four callers passed an object-relative offset
/// (ENTITY_NAME_OVERRIDE+0x17, DEF_NAME+0x17, IFTYPE_TEXT_FLAG) so the flag was read from
/// entity+0xE37 / def+0x27 / IfType+0x2C7 -- unrelated bytes -- and the name that came out depended
/// on them: a 0 there made an empty override look like 23 NUL bytes (so the def name was never
/// consulted and Java saw ""), and a heap-stored name returned pointer/length bytes as text. Never
/// exercised in-game before: the "VERIFIED LIVE" names in offsets.hpp came from a /proc/pid/mem walk,
/// not from this function. Falls back to "" for every torn/unreadable case -- a name is cosmetic,
/// it never blocks a frame. The result is trimmed at the first NUL so an inline buffer's tail never
/// leaks. Bytes are handed back raw (the client's encoding is NOT VERIFIED -- it pads with U+00A0);
/// jvm.hpp turns them into a jstring without ever feeding malformed UTF-8 to JNI.
/// `maxLen` is the sanity bound on the LENGTH the string claims, not a truncation: a heap string that
/// claims more than this is treated as torn and comes back "". 200 suits a name (the callers that pass
/// nothing); widget text is prose and routinely longer, so widget() passes a bound that fits a chatbox
/// line instead of silently reading an over-long dialogue as an empty widget (review 2026-09-06).
inline std::string nxtString(std::uintptr_t str, std::uint64_t maxLen = 200) {
    if (!str) return {};
    std::uint8_t flag = rd<std::uint8_t>(str + 0x17);
    std::uintptr_t p;
    std::uint64_t len;
    if (flag & 0x80) {
        p   = rdp(str);
        len = rd<std::uint64_t>(str + 8);
    } else {
        if (flag > 0x17) return {};          // not an inline length byte: torn or not a string
        p   = str;
        len = 0x17 - flag;
    }
    if (!p || len == 0 || len > maxLen || !readable(p, static_cast<std::size_t>(len))) return {};
    const char* s = reinterpret_cast<const char*>(p);
    std::string out(s, s + len);
    out.resize(strnlen(out.c_str(), out.size()));
    return out;
}

/// An NPC's name: its own +0x710 override first (normally empty), else the definition's +0x8.
inline std::string npcName(std::uintptr_t entity) {
    if (!entity) return {};
    std::string own = nxtString(entity + off::ENTITY_NAME_OVERRIDE);
    if (!own.empty()) return own;
    std::uintptr_t def = rdp(entity + off::ENTITY_DEF_PTR);
    return def ? nxtString(def + off::DEF_NAME) : std::string{};
}

/// A player's name. Players keep a pointer to a heap NxtString at +0x718 (no definition object).
inline std::string playerName(std::uintptr_t entity) {
    if (!entity) return {};
    std::uintptr_t sp = rdp(entity + off::PLAYER_NAME_PTR);
    return sp ? nxtString(sp) : std::string{};
}

/// The world map object, or 0. Its origin (WM_ORIGIN_*) is a MapCoord {level, x, z} in world tiles --
/// the client adds it to map coords before splitting into 64x64 map squares.
inline std::uintptr_t worldMap() {
    std::uintptr_t c = clientObj();
    return c ? rdp(c + off::WORLD_MAP) : 0;
}

// A widget, as the shim's getWidget wants it. `id` is (groupId << 16) | componentId -- the client's own
// encoding, unchanged from the Java client. `ok` is false when the group is not loaded (empty sentinel
// or null component data), which happens constantly for background groups; callers must treat every
// field as garbage unless it is set.
struct Widget {
    int         x = 0, y = 0, width = 0, height = 0;
    bool        hidden = false;
    std::string text;
    bool        ok = false;
};

/// Resolve a widget id to its IfType object, or 0. Bounded at both levels like forEachEntity: the
/// interface manager rebuilds these arrays while we read them.
inline std::uintptr_t widgetObj(int id) {
    std::uintptr_t c = clientObj();
    if (!c) return 0;
    std::uintptr_t mgr = rdp(c + off::IFACE_MANAGER);
    if (!mgr) return 0;
    int g = id >> 16;
    std::uint64_t gcount = rd<std::uint64_t>(mgr + off::IFACE_GROUP_COUNT);
    if (g < 0 || static_cast<std::uint64_t>(g) >= gcount || gcount > 0x1000) return 0;
    std::uintptr_t garr = rdp(mgr + off::IFACE_GROUP_ARRAY);
    if (!garr) return 0;
    std::uint64_t ccount = rd<std::uint64_t>(garr + static_cast<std::uintptr_t>(g) * off::IFACE_GROUP_ENTRY_STRIDE + off::IFACE_GROUP_ENTRY_COUNT);
    std::uintptr_t cdata = rdp(garr + static_cast<std::uintptr_t>(g) * off::IFACE_GROUP_ENTRY_STRIDE + off::IFACE_GROUP_ENTRY_DATA);
    int comp = id & 0xFFFF;
    if (!cdata || static_cast<std::uint64_t>(comp) >= ccount) return 0;
    std::uintptr_t w = rdp(cdata + static_cast<std::uintptr_t>(comp) * 16 + 8);
    // The empty group's slot holds a shared static empty object; comparing the pointee against the
    // sentinel's control field is exactly what the client's own null check does.
    if (!w || w == (off::IFACE_EMPTY_SENTINEL ? rdp(globalAddr(off::IFACE_EMPTY_SENTINEL) + 8) : 0)) return 0;
    return w;
}

/// One widget's bounds/state/text. `ok` false when the id is not loaded right now.
inline Widget widget(int id) {
    Widget wgt;
    std::uintptr_t w = widgetObj(id);
    if (!w) return wgt;
    wgt.x      = rd<std::int32_t>(w + off::IFTYPE_X);
    wgt.y      = rd<std::int32_t>(w + off::IFTYPE_Y);
    wgt.width  = rd<std::int32_t>(w + off::IFTYPE_WIDTH);
    wgt.height = rd<std::int32_t>(w + off::IFTYPE_HEIGHT);
    wgt.hidden = rd<std::uint8_t>(w + off::IFTYPE_HIDDEN) != 0;
    // The text is an NxtString at IFTYPE_TEXT; its flag byte IS IFTYPE_TEXT+0x17 (the constant
    // offsets.hpp records separately). Offsets are runtime values now (loaded per build from
    // offsets/client-<build>.json), so the loader checks the pair instead of a static_assert.
    // 4096, not the 200-byte name bound: a dialogue or chatbox line over 200 bytes used to come back
    // "" with no error, so a ported plugin reading it saw an EMPTY widget rather than a long one
    // (review 2026-09-06). readable() is still the real guard on the pointer.
    wgt.text   = nxtString(w + off::IFTYPE_TEXT, 4096);
    wgt.ok     = true;
    return wgt;
}

/// A widget's Nth dynamic child -- the client's own child-index addressing (the same index shape
/// FUN_1405B65E0 bounds-checks against IfType+0xB50). 0 when out of range or not loaded.
inline std::uintptr_t widgetChildObj(int id, int childIndex) {
    std::uintptr_t w = widgetObj(id);
    if (!w || childIndex < 0) return 0;
    std::uint64_t cnt = rd<std::uint64_t>(w + off::IFTYPE_CHILDREN_COUNT);
    std::uintptr_t data = rdp(w + off::IFTYPE_CHILDREN_DATA);
    if (!data || static_cast<std::uint64_t>(childIndex) >= cnt) return 0;
    return rdp(data + static_cast<std::uintptr_t>(childIndex) * 16 + 8);
}

// ---------------------------------------------------------------------------------------------------
// Absolute (canvas) widget geometry
// ---------------------------------------------------------------------------------------------------
// A component's IFTYPE_X/IFTYPE_Y are relative to its PARENT. RuneLite's getCanvasLocation is the sum
// of a component's own x/y and every ancestor's, and until now this client could not take that sum:
// nothing here could walk UP from a packed id, so a single-id lookup handed back a parent-relative
// position dressed as a canvas one. That is why the minimap read (53,8) while the minimap visibly sat
// near x=1143, and why both map overlays stood down.
//
// The link the sum needs is a field on the component. It is NOT written into offsets.hpp as a number
// because nobody has derived it with a decompiler on client-240-6 -- it is derived HERE, live, by a
// tally that only accepts an offset holding for every component of every loaded group, and refuses
// outright when two offsets both survive. offsets.hpp's "THE PARENT LINK" block is the specification;
// this is the implementation. When it refuses, widgetAbs reports complete = 0 and Java goes on
// refusing exactly as it did before -- the failure mode is "no overlay", never "overlay in the wrong
// place", which is the whole reason that flag exists.

/// What the whole-tree tally found. Either it carries an offset that held everywhere, or it carries
/// nothing and every absolute rectangle refuses. There is no middle setting, on purpose.
struct WidgetTreeScan {
    int groups = 0;          // groups with at least one live component
    int components = 0;      // components examined -- the denominator of every "held for every" claim
    int idOff = -1;          // reads (group<<16)|comp. NOT used to walk: it is the POSITIVE CONTROL,
                             // a field whose value we already know, found by the same rule as the
                             // unknown one. A tally that cannot find this has not earned belief.
    int parentIdOff = -1;    // reads the packed same-group parent id, -1 at a root (the Java shape)
    int parentPtrOff = -1;   // reads a pointer to the parent component, null at a root (the C++ shape)
    int idCands = 0, parentIdCands = 0, parentPtrCands = 0;  // survivors; >1 is an ambiguity = a refusal
    int parentRoots = 0;     // components whose chosen link says "I am a root"
    int maxDepth = 0;        // deepest chain the chosen link implies
    // Does IFTYPE_CHILDREN_* carry the STATIC tree or only runtime cc_create children? ClientState's
    // fillChildren asserts "both" and nothing ever measured it. This is the measurement.
    int childEntries = 0;    // child pointers seen
    int childInGroup = 0;    // ...of which resolved to a component of the same group
    int claimed = 0;         // components that appear as somebody's child
    std::string report;      // printed once by the probe native; the counts, not just the verdict
    bool usable() const { return parentIdOff >= 0 || parentPtrOff >= 0; }
};

namespace detail {

/// Live component objects of one group, indexed by component id (0 where the slot is empty).
inline void groupComponents(std::uintptr_t garr, std::uint64_t g, std::uintptr_t empty,
                            std::vector<std::uintptr_t>& out) {
    out.clear();
    const std::uintptr_t entry = garr + g * off::IFACE_GROUP_ENTRY_STRIDE;
    const std::uintptr_t data = rdp(entry + off::IFACE_GROUP_ENTRY_DATA);
    if (!data) return;
    std::uint64_t ccount = rd<std::uint64_t>(entry + off::IFACE_GROUP_ENTRY_COUNT);
    if (ccount == 0 || ccount > 4096) return;
    out.assign(static_cast<std::size_t>(ccount), 0);
    for (std::uint64_t i = 0; i < ccount; ++i) {
        std::uintptr_t w = rdp(data + i * 16 + 8);
        out[static_cast<std::size_t>(i)] = (w && w != empty) ? w : 0;
    }
}

/// A component is "examined" only if its whole scanned prefix is readable, so every tally denominator
/// means the same thing in every pass.
inline bool examinable(std::uintptr_t w) { return w && readable(w, off::IFTYPE_SCAN_SPAN); }

}  // namespace detail

/// Derive the parent link by tallying every loaded component. Read-only, bounded, and never run per
/// frame -- widgetLink() runs it a handful of times a session and caches the answer.
inline WidgetTreeScan scanWidgetTree() {
    WidgetTreeScan s;
    const std::uintptr_t c = clientObj();
    const std::uintptr_t mgr = c ? rdp(c + off::IFACE_MANAGER) : 0;
    if (!mgr) return s;
    const std::uint64_t gcount = rd<std::uint64_t>(mgr + off::IFACE_GROUP_COUNT);
    const std::uintptr_t garr = rdp(mgr + off::IFACE_GROUP_ARRAY);
    if (!garr || gcount == 0 || gcount > 0x1000) return s;
    const std::uintptr_t empty = (off::IFACE_EMPTY_SENTINEL ? rdp(globalAddr(off::IFACE_EMPTY_SENTINEL) + 8) : 0);

    const int SLOTS  = off::IFTYPE_SCAN_SPAN / 4;   // int slots
    const int PSLOTS = off::IFTYPE_SCAN_SPAN / 8;   // pointer slots
    std::vector<int> selfHit(SLOTS, 0), minusOne(SLOTS, 0), parentHit(SLOTS, 0), otherInt(SLOTS, 0);
    std::vector<int> ptrHit(PSLOTS, 0), ptrNull(PSLOTS, 0), ptrOther(PSLOTS, 0);
    std::vector<std::uintptr_t> objs, sorted;
    std::vector<unsigned char> buf(off::IFTYPE_SCAN_SPAN);
    std::vector<unsigned char> claimed;

    // ---- PASS A/B: one read of each component's prefix, tallied every way at once -------------------
    for (std::uint64_t g = 0; g < gcount; ++g) {
        detail::groupComponents(garr, g, empty, objs);
        if (objs.empty()) continue;
        const std::uint64_t ccount = objs.size();
        sorted.clear();
        for (std::uintptr_t p : objs) if (p) sorted.push_back(p);
        if (sorted.empty()) continue;
        std::sort(sorted.begin(), sorted.end());
        ++s.groups;
        claimed.assign(static_cast<std::size_t>(ccount), 0);

        for (std::uint64_t i = 0; i < ccount; ++i) {
            const std::uintptr_t w = objs[static_cast<std::size_t>(i)];
            if (!detail::examinable(w)) continue;
            // One readable() (one VirtualQuery) and one memcpy per component instead of 256 guarded
            // reads: the prefix is inside a single committed region or the component is skipped.
            std::memcpy(buf.data(), reinterpret_cast<void*>(w), off::IFTYPE_SCAN_SPAN);
            ++s.components;
            const std::int32_t own = static_cast<std::int32_t>((g << 16) | i);
            for (int k = 0; k < SLOTS; ++k) {
                std::int32_t v;
                std::memcpy(&v, buf.data() + k * 4, 4);
                if (v == own) { ++selfHit[k]; continue; }
                if (v == -1)  { ++minusOne[k]; continue; }
                const std::uint32_t uv = static_cast<std::uint32_t>(v);
                if ((uv >> 16) == static_cast<std::uint32_t>(g) && (uv & 0xFFFF) < ccount) ++parentHit[k];
                else ++otherInt[k];
            }
            for (int k = 0; k < PSLOTS; ++k) {
                std::uintptr_t p;
                std::memcpy(&p, buf.data() + k * 8, sizeof p);
                if (!p) { ++ptrNull[k]; continue; }
                if (p != w && std::binary_search(sorted.begin(), sorted.end(), p)) ++ptrHit[k];
                else ++ptrOther[k];
            }
            // PASS B, free while the group's pointer set is in hand: do the children arrays carry the
            // static tree, or only what cc_create spawned?
            const std::uint64_t cn = rd<std::uint64_t>(w + off::IFTYPE_CHILDREN_COUNT);
            const std::uintptr_t cd = rdp(w + off::IFTYPE_CHILDREN_DATA);
            if (cd && cn > 0 && cn <= 4096) {
                for (std::uint64_t j = 0; j < cn; ++j) {
                    const std::uintptr_t ch = rdp(cd + j * 16 + 8);
                    if (!ch || ch == empty) continue;
                    ++s.childEntries;
                    for (std::uint64_t k2 = 0; k2 < ccount; ++k2) {
                        if (objs[static_cast<std::size_t>(k2)] == ch) {
                            ++s.childInGroup;
                            claimed[static_cast<std::size_t>(k2)] = 1;
                            break;
                        }
                    }
                }
            }
        }
        for (std::uint64_t i = 0; i < ccount; ++i)
            if (objs[static_cast<std::size_t>(i)] && claimed[static_cast<std::size_t>(i)]) ++s.claimed;
    }
    if (s.components == 0) return s;

    // ---- candidates ---------------------------------------------------------------------------------
    // "Held for EVERY component" is the whole rule. An offset that is right 99% of the time is an
    // offset that draws an overlay in the wrong place 1% of the time, which is worse than none.
    std::vector<int> idc, pidc, pptrc;
    for (int k = 0; k < SLOTS; ++k) {
        if (selfHit[k] == s.components) idc.push_back(k * 4);
        if (selfHit[k] == 0 && otherInt[k] == 0 && parentHit[k] > 0) pidc.push_back(k * 4);
    }
    for (int k = 0; k < PSLOTS; ++k)
        if (ptrOther[k] == 0 && ptrHit[k] > 0) pptrc.push_back(k * 8);

    // ---- validation: the implied tree must be an acyclic forest --------------------------------------
    // This is what throws out an unrelated index field that merely happens to LOOK like a packed id in
    // every group. A real parent link terminates from every component within a handful of steps.
    // Each validator checks readability ONCE per component (the same examinable() the tally used) and
    // then reads inside that checked 0x400 prefix directly. Going through rd() per hop would be a
    // VirtualQuery per hop -- candidates x components x depth of them -- and this runs on the render
    // thread, where that is a visible hitch rather than a cost.
    std::vector<char> okv;
    auto validateInt = [&](int o, int& maxDepth, int& roots) -> bool {
        maxDepth = 0; roots = 0;
        for (std::uint64_t g = 0; g < gcount; ++g) {
            detail::groupComponents(garr, g, empty, objs);
            if (objs.empty()) continue;
            const std::uint64_t ccount = objs.size();
            okv.assign(static_cast<std::size_t>(ccount), 0);
            for (std::size_t i = 0; i < objs.size(); ++i) okv[i] = detail::examinable(objs[i]) ? 1 : 0;
            for (std::uint64_t i = 0; i < ccount; ++i) {
                if (!okv[static_cast<std::size_t>(i)]) continue;
                std::uint64_t cur = i;
                int d = 0;
                for (;; ++d) {
                    if (d > off::IFTYPE_CHAIN_MAX) return false;   // cycle, or too deep to be a real tree
                    std::int32_t v;
                    std::memcpy(&v, reinterpret_cast<void*>(objs[static_cast<std::size_t>(cur)] + o), 4);
                    if (v == -1) { if (d == 0) ++roots; break; }
                    const std::uint32_t uv = static_cast<std::uint32_t>(v);
                    if ((uv >> 16) != static_cast<std::uint32_t>(g)) return false;
                    const std::uint64_t pc = uv & 0xFFFF;
                    // The parent must itself be a live, readable component of this group, or the next
                    // hop's read is unguarded -- which is also exactly what a bogus candidate looks like.
                    if (pc >= ccount || !okv[static_cast<std::size_t>(pc)] || pc == cur) return false;
                    cur = pc;
                }
                if (d > maxDepth) maxDepth = d;
            }
        }
        return true;
    };
    auto validatePtr = [&](int o, int& maxDepth, int& roots) -> bool {
        maxDepth = 0; roots = 0;
        for (std::uint64_t g = 0; g < gcount; ++g) {
            detail::groupComponents(garr, g, empty, objs);
            if (objs.empty()) continue;
            sorted.clear();
            for (std::uintptr_t p : objs) if (p && detail::examinable(p)) sorted.push_back(p);
            if (sorted.empty()) continue;
            std::sort(sorted.begin(), sorted.end());
            for (std::uintptr_t start : sorted) {
                std::uintptr_t cur = start;
                int d = 0;
                for (;; ++d) {
                    if (d > off::IFTYPE_CHAIN_MAX) return false;
                    std::uintptr_t p;
                    std::memcpy(&p, reinterpret_cast<void*>(cur + o), sizeof p);
                    if (!p) { if (d == 0) ++roots; break; }
                    if (p == cur || !std::binary_search(sorted.begin(), sorted.end(), p)) return false;
                    cur = p;
                }
                if (d > maxDepth) maxDepth = d;
            }
        }
        return true;
    };

    // Bounded: each validation is a full walk of the tree, and past a couple of survivors the answer is
    // "ambiguous" regardless. A tally with dozens of candidates is a tally that has proved nothing, and
    // spending a second on the render thread confirming that is not worth it.
    const std::size_t VALIDATE_CAP = 24;
    std::vector<int> pidOk, pptrOk;
    int pidDepth = 0, pidRoots = 0, pptrDepth = 0, pptrRoots = 0;
    for (std::size_t n = 0; n < pidc.size() && n < VALIDATE_CAP; ++n) {
        int d = 0, r = 0;
        if (validateInt(pidc[n], d, r)) { pidOk.push_back(pidc[n]); pidDepth = d; pidRoots = r; }
    }
    for (std::size_t n = 0; n < pptrc.size() && n < VALIDATE_CAP; ++n) {
        int d = 0, r = 0;
        if (validatePtr(pptrc[n], d, r)) { pptrOk.push_back(pptrc[n]); pptrDepth = d; pptrRoots = r; }
    }

    s.idCands = static_cast<int>(idc.size());
    s.parentIdCands = static_cast<int>(pidOk.size());
    s.parentPtrCands = static_cast<int>(pptrOk.size());
    if (idc.size() == 1) s.idOff = idc[0];
    // EXACTLY one, or none. Two survivors means the evidence does not pick between them, and taking
    // the lower offset because it is lower would be a guess wearing a derivation's clothes. The int
    // form wins over the pointer form when both are unambiguous: it is the shape the cache format
    // documents, and it lets the walk bound every hop to the same group.
    if (pidOk.size() == 1) { s.parentIdOff = pidOk[0]; s.maxDepth = pidDepth; s.parentRoots = pidRoots; }
    else if (pidOk.empty() && pptrOk.size() == 1) { s.parentPtrOff = pptrOk[0]; s.maxDepth = pptrDepth; s.parentRoots = pptrRoots; }

    // ---- report ---------------------------------------------------------------------------------------
    char line[400];
    auto list = [](const std::vector<int>& v) {
        std::string out;
        char b[32];
        for (std::size_t i = 0; i < v.size() && i < 8; ++i) {
            std::snprintf(b, sizeof b, "%s+0x%X", i ? ", " : "", v[i]);
            out += b;
        }
        if (v.empty()) out = "(none)";
        else if (v.size() > 8) out += ", ...";
        return out;
    };
    std::snprintf(line, sizeof line, "%d groups, %d components examined (struct prefix 0x%X)\n",
                  s.groups, s.components, static_cast<unsigned>(off::IFTYPE_SCAN_SPAN));
    s.report += line;
    std::snprintf(line, sizeof line, "id (POSITIVE CONTROL): %d candidate(s) %s\n",
                  s.idCands, list(idc).c_str());
    s.report += line;
    std::snprintf(line, sizeof line,
                  "parentId (int, same-group packed): %d survivor(s) of %d candidate(s) %s%s\n",
                  static_cast<int>(pidOk.size()), static_cast<int>(pidc.size()), list(pidOk).c_str(),
                  pidc.size() > pidOk.size() ? "  [the rest implied a cyclic or over-deep tree]" : "");
    s.report += line;
    // Per survivor: the raw tally beside the walk's own count of roots. The two are computed by
    // different code over the same data and MUST agree -- a disagreement means the tree changed between
    // the two passes, which is exactly the condition under which an offset must not be believed.
    for (int o : pidOk) {
        int d = 0, r = 0;
        validateInt(o, d, r);
        std::snprintf(line, sizeof line,
                      "  +0x%X: %d parent, %d root(-1), %d other of %d; walk says %d root(s), max depth %d%s\n",
                      o, parentHit[o / 4], minusOne[o / 4], otherInt[o / 4], s.components, r, d,
                      minusOne[o / 4] == r ? "" : "  [MISMATCH -- the tree moved mid-scan, do not trust this]");
        s.report += line;
    }
    std::snprintf(line, sizeof line,
                  "parentPtr (pointer to a same-group component): %d survivor(s) of %d candidate(s) %s\n",
                  static_cast<int>(pptrOk.size()), static_cast<int>(pptrc.size()), list(pptrOk).c_str());
    s.report += line;
    for (int o : pptrOk) {
        int d = 0, r = 0;
        validatePtr(o, d, r);
        std::snprintf(line, sizeof line,
                      "  +0x%X: %d in-group, %d null, %d other of %d; walk says %d root(s), max depth %d%s\n",
                      o, ptrHit[o / 8], ptrNull[o / 8], ptrOther[o / 8], s.components, r, d,
                      ptrNull[o / 8] == r ? "" : "  [MISMATCH -- the tree moved mid-scan, do not trust this]");
        s.report += line;
    }
    std::snprintf(line, sizeof line,
                  "children array: %d entries, %d resolved in-group, %d of %d components appear as a child"
                  " (~0%% means the array is cc_create-only and parentId is the ONLY route up)\n",
                  s.childEntries, s.childInGroup, s.claimed, s.components);
    s.report += line;
    if (s.parentIdOff >= 0)
        std::snprintf(line, sizeof line,
                      "VERDICT: absolute rectangles ENABLED via parentId@+0x%X (%d roots, max depth %d)%s\n",
                      s.parentIdOff, s.parentRoots, s.maxDepth,
                      s.idOff >= 0 ? "" : " -- but WITHOUT the id positive control, so treat it as a candidate");
    else if (s.parentPtrOff >= 0)
        std::snprintf(line, sizeof line,
                      "VERDICT: absolute rectangles ENABLED via parentPtr@+0x%X (%d roots, max depth %d)\n",
                      s.parentPtrOff, s.parentRoots, s.maxDepth);
    else if (pidOk.size() > 1 || pptrOk.size() > 1)
        std::snprintf(line, sizeof line,
                      "VERDICT: REFUSED -- %d int and %d pointer offsets both survive; the evidence does not"
                      " pick between them. Pin one in offsets.hpp with these counts as the reason.\n",
                      static_cast<int>(pidOk.size()), static_cast<int>(pptrOk.size()));
    else
        std::snprintf(line, sizeof line,
                      "VERDICT: REFUSED -- no offset holds for all %d components. Absolute rectangles stay"
                      " off and every overlay that needs one refuses, exactly as before.\n", s.components);
    s.report += line;
    return s;
}

/// The derived link, computed at most a few times a session and then cached.
///
/// It re-tries while it has failed because the interface tree is EMPTY at the login screen and only a
/// couple of groups are up during loading -- a derivation taken then would be taken from nothing. Only
/// a look at a real tree (>= 64 components) spends one of the eight attempts, so a session that starts
/// at the login screen does not burn the budget before there is anything to look at. Called from the
/// render thread; the statics are written only there.
inline const WidgetTreeScan& widgetLink(bool rescan = false) {
    static WidgetTreeScan cached;
    static int attempts = 0;
    if (rescan) attempts = 0;
    if (rescan || (!cached.usable() && attempts < 8)) {
        WidgetTreeScan s = scanWidgetTree();
        if (s.components >= 64) ++attempts;
        if (rescan || s.usable() || s.components > cached.components) cached = std::move(s);
    }
    return cached;
}

/// A widget's rectangle in CANVAS coordinates -- RuneLite's getCanvasLocation, done in C++.
///
/// `complete` is the contract, and it is what makes pointing every widget consumer at this safe: it is
/// 1 only when the walk reached a real root having given up on nothing, so Java can refuse on a
/// half-summed position instead of drawing at one. When it is 0, x/y are the component's own relative
/// values -- exactly what widget() has always returned, so nothing gets worse.
///
/// WHY C++ AND NOT JAVA: each ancestor step is one widgetObj(), three guarded derefs. In Java it would
/// be one JNI round trip per ancestor per widget per drawn point, and Perspective.localToMinimap asks
/// for the minimap rectangle for EVERY point it draws.
///
/// NOT SUBTRACTED: an ancestor's scrollX/scrollY, which RuneLite does subtract. No scroll offset is
/// derived on this build (offsets.hpp, THE PARENT LINK, note (i)). Neither map has a scrolling
/// ancestor; a row inside a scrolled list comes out off by the scroll amount.
struct WidgetAbs {
    int  x = 0, y = 0, w = 0, h = 0;
    bool hidden = false;
    int  depth = 0;          // ancestors summed
    bool complete = false;   // the walk reached a root -- x/y are a canvas position
    bool ok = false;         // the id resolved at all
};

inline WidgetAbs widgetAbs(int id) {
    WidgetAbs a;
    const std::uintptr_t w = widgetObj(id);
    if (!w) return a;
    a.ok     = true;
    a.x      = rd<std::int32_t>(w + off::IFTYPE_X);
    a.y      = rd<std::int32_t>(w + off::IFTYPE_Y);
    a.w      = rd<std::int32_t>(w + off::IFTYPE_WIDTH);
    a.h      = rd<std::int32_t>(w + off::IFTYPE_HEIGHT);
    a.hidden = rd<std::uint8_t>(w + off::IFTYPE_HIDDEN) != 0;

    const WidgetTreeScan& s = widgetLink();
    if (!s.usable()) return a;                       // complete stays 0: Java refuses, nothing is drawn
    // Integrity: the interface manager rebuilds these arrays under us. A component whose stored id is
    // not the id we looked it up by means the tree moved mid-walk -- bail rather than sum strangers.
    if (s.idOff >= 0 && rd<std::int32_t>(w + s.idOff, id) != id) return a;

    if (s.parentIdOff >= 0) {
        std::int32_t pid = rd<std::int32_t>(w + s.parentIdOff, -1);
        std::int32_t seen = id;
        for (; a.depth < off::IFTYPE_CHAIN_MAX && pid >= 0; ++a.depth) {
            if ((pid >> 16) != (id >> 16)) return a;  // same-group by the decode rule: else it is torn
            if (pid == seen) return a;                // cycle
            const std::uintptr_t p = widgetObj(pid);
            if (!p) return a;
            const std::int32_t px = rd<std::int32_t>(p + off::IFTYPE_X);
            const std::int32_t py = rd<std::int32_t>(p + off::IFTYPE_Y);
            if (px < -8192 || px > 8192 || py < -8192 || py > 8192) return a;   // torn read
            a.x += px;
            a.y += py;
            seen = pid;
            pid = rd<std::int32_t>(p + s.parentIdOff, -1);
        }
        a.complete = (pid < 0);
        return a;
    }

    std::uintptr_t p = rdp(w + s.parentPtrOff);
    std::uintptr_t seen = w;
    for (; a.depth < off::IFTYPE_CHAIN_MAX && p; ++a.depth) {
        if (p == seen || !readable(p + off::IFTYPE_HEIGHT, 4)) return a;
        const std::int32_t px = rd<std::int32_t>(p + off::IFTYPE_X);
        const std::int32_t py = rd<std::int32_t>(p + off::IFTYPE_Y);
        if (px < -8192 || px > 8192 || py < -8192 || py > 8192) return a;
        a.x += px;
        a.y += py;
        seen = p;
        p = rdp(p + s.parentPtrOff);
    }
    a.complete = (p == 0);
    return a;
}

/// The whole chain as one line, so a wrong answer says WHICH link is wrong instead of just being
/// wrong. Diagnostic only -- once a session under OXC_LOG, never per frame:
///
///   161:30 (53,8) 152x152 <- 161:22 (1090,4) 224x160 <- 161:0 (0,0) 1314x900
///     => abs (1143,12) 152x152 via parentId, depth 2, complete
///
/// The LAST hop is the self-test, and it needs no eye measurement: a group root must come out (0,0) at
/// exactly the canvas size. If it does not, IFTYPE_X/Y are cache ORIGINALS rather than the laid-out
/// rect and this whole approach is the wrong one -- see the fork at the end of offsets.hpp's
/// THE PARENT LINK block.
inline std::string widgetChainString(int id) {
    char b[192];
    std::string out;
    const std::uintptr_t w = widgetObj(id);
    if (!w) {
        std::snprintf(b, sizeof b, "%d:%d is not loaded", id >> 16, id & 0xFFFF);
        return b;
    }
    const WidgetTreeScan& s = widgetLink();
    auto hop = [&](int hid, std::uintptr_t obj) {
        if (hid >= 0) std::snprintf(b, sizeof b, "%d:%d ", hid >> 16, hid & 0xFFFF);
        else std::snprintf(b, sizeof b, "(ptr) ");
        out += b;
        std::snprintf(b, sizeof b, "(%d,%d) %dx%d",
                      rd<std::int32_t>(obj + off::IFTYPE_X), rd<std::int32_t>(obj + off::IFTYPE_Y),
                      rd<std::int32_t>(obj + off::IFTYPE_WIDTH), rd<std::int32_t>(obj + off::IFTYPE_HEIGHT));
        out += b;
    };
    hop(id, w);
    if (s.parentIdOff >= 0) {
        std::int32_t pid = rd<std::int32_t>(w + s.parentIdOff, -1);
        std::int32_t seen = id;
        for (int d = 0; d < off::IFTYPE_CHAIN_MAX && pid >= 0; ++d) {
            if ((pid >> 16) != (id >> 16) || pid == seen) break;
            const std::uintptr_t p = widgetObj(pid);
            if (!p) break;
            out += " <- ";
            hop(pid, p);
            seen = pid;
            pid = rd<std::int32_t>(p + s.parentIdOff, -1);
        }
    } else if (s.parentPtrOff >= 0) {
        std::uintptr_t p = rdp(w + s.parentPtrOff), seen = w;
        for (int d = 0; d < off::IFTYPE_CHAIN_MAX && p; ++d) {
            if (p == seen || !readable(p + off::IFTYPE_HEIGHT, 4)) break;
            out += " <- ";
            hop(-1, p);          // a pointer link carries no id; the rectangle is what matters
            seen = p;
            p = rdp(p + s.parentPtrOff);
        }
    }
    const WidgetAbs a = widgetAbs(id);
    std::snprintf(b, sizeof b, " => abs (%d,%d) %dx%d via %s, depth %d, %s", a.x, a.y, a.w, a.h,
                  s.parentIdOff >= 0 ? "parentId" : (s.parentPtrOff >= 0 ? "parentPtr" : "NO DERIVED LINK"),
                  a.depth, a.complete ? "complete" : "INCOMPLETE (Java refuses)");
    out += b;
    return out;
}

// ---------------------------------------------------------------------------------------------------
// Varps and containers
// ---------------------------------------------------------------------------------------------------
// Both are GLOBALS (image-base relative), not fields on the client object, so these readers do not go
// through clientObj(). Both fail closed: a varp we cannot read is 0, a container we cannot find is
// empty. Java applies its own meaning on top (see ClientState.getVarbitValue for why 0 and not -1).

/// One varp value by id. 0 when the array is not up yet or the id is out of range.
inline int varp(int id) {
    if (id < 0) return 0;
    std::uintptr_t arr = rdp(globalAddr(off::VARP_ARRAY_PTR));
    if (!arr) return 0;
    return rd<std::int32_t>(arr + static_cast<std::uintptr_t>(id) * 4);
}

/// The container node for `containerId`, or 0. Bounded walk: the sentinel sits one bucket past the end.
inline std::uintptr_t containerNode(int containerId) {
    std::int32_t mask = rd<std::int32_t>(globalAddr(off::CONTAINER_MASK));
    if (mask <= 0 || mask > 0x10000) return 0;               // never a real bucket count this big
    // The global at CONTAINER_BUCKETS is a POINTER CELL to the heap-allocated bucket array, not the
    // array itself: the client's own lookup is `mov rdx,[CONTAINER_BUCKETS]; mov rax,[rdx+idx*8]`
    // (240-6 FUN_140032610, 241-3 the same shape). An earlier reading of this as an inline array
    // was never confirmed against a running game.
    std::uintptr_t buckets = rdp(globalAddr(off::CONTAINER_BUCKETS));
    if (!buckets) return 0;
    std::uintptr_t node = rdp(buckets + static_cast<std::uintptr_t>(static_cast<std::uint32_t>(containerId) % mask) * 8);
    std::uintptr_t sentinel = rdp(buckets + static_cast<std::uintptr_t>(mask) * 8);
    for (int guard = 0; node && node != sentinel && guard < 512; ++guard) {
        if (rd<std::int32_t>(node) == containerId) return node;
        node = rdp(node + off::CONTAINER_NODE_NEXT);
    }
    return 0;
}

/// How many slots a container holds, or -1 if the container does not exist right now.
inline int containerSize(int containerId) {
    std::uintptr_t n = containerNode(containerId);
    if (!n) return -1;
    auto start = rd<std::uintptr_t>(n + off::CONTAINER_NODE_IDS);
    auto end   = rd<std::uintptr_t>(n + off::CONTAINER_NODE_IDS_END);
    if (!start || end < start || end - start > 0x100000) return -1;   // torn update mid-resize
    return static_cast<int>((end - start) >> 2);
}

/// One container entry. Item id comes back -1 and quantity 0 past the end (the client's own
/// invGetObjId/invGetNum conventions), which callers read as "no item in this slot".
inline int containerItem(int containerId, int slot) {
    std::uintptr_t n = containerNode(containerId);
    int size = containerSize(containerId);
    if (!n || slot < 0 || slot >= size) return -1;
    return rd<std::int32_t>(rd<std::uintptr_t>(n + off::CONTAINER_NODE_IDS) + static_cast<std::uintptr_t>(slot) * 4, -1);
}

inline int containerQty(int containerId, int slot) {
    std::uintptr_t n = containerNode(containerId);
    int size = containerSize(containerId);
    if (!n || slot < 0 || slot >= size) return 0;
    return rd<std::int32_t>(rd<std::uintptr_t>(n + off::CONTAINER_NODE_QTYS) + static_cast<std::uintptr_t>(slot) * 4);
}

// ---------------------------------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------------------------------
/// Project a point in FINE coordinates to screen pixels, using the game's own projection -- so it is
/// always exactly right, including while the camera is moving, which is the whole reason we call the
/// game's function instead of reimplementing the maths.
///
/// Fine coordinates are tiles << 7: 128 units per tile, so the centre of scene tile (x, y) is
/// ((x << 7) + 64, ((y << 7) + 64). `fineHeight` is the vertical axis in the client's own height
/// datum (negative = up, the same axis CAMERA_FINE_H is in). 0 is the DATUM, NOT the ground: the
/// terrain sits at a per-tile height in this axis that we cannot read yet (no heightmap offset is
/// derived -- see offsets.hpp), so every caller passing 0 draws at datum height, which is below or
/// above the feet by however far the ground is from 0 at that tile. The live trace (2026-09-05)
/// showed the camera height varying ~50 units between nearby spots at fixed pitch/zoom, i.e. the
/// ground there is not flat and not proven to be at 0. KNOWN LIMITATION until a tile-height reader
/// exists; the OXC_LOG probe in jvm.hpp sweeps candidate heights so the residual can be measured.
///
/// Returns false when the point is behind the camera or otherwise off in the weeds. Do not draw it.
inline bool projectFine(int fineX, int fineHeight, int fineY, float& outX, float& outY) {
    // 0 = not derived for this build (the loader refuses an unmeasured RVA): calling a stale address
    // would crash the game, so there is simply no projection until the pipeline derives it.
    if (off::WORLD_TO_SCREEN == 0) return false;
    using Fn = float* (__fastcall*)(void*, float*, int*);
    auto fn = reinterpret_cast<Fn>(moduleBase() + off::WORLD_TO_SCREEN);

    float out[2] = { 0.f, 0.f };
    int   fine[3] = { fineX, fineHeight, fineY };
    fn(nullptr, out, fine);

    outX = out[0];
    outY = out[1];
    if (!std::isfinite(outX) || !std::isfinite(outY)) return false;
    return !(outX < -10000.f || outX > 10000.f || outY < -10000.f || outY > 10000.f);
}

/// The centre of a SCENE tile, at height-datum 0 (not the ground -- see projectFine).
inline bool project(int sceneX, int sceneY, float& outX, float& outY) {
    return projectFine((sceneX << 7) + 64, 0, (sceneY << 7) + 64, outX, outY);
}

// ---------------------------------------------------------------------------------------------------
// Doing something
// ---------------------------------------------------------------------------------------------------
// doAction / walkTo / interactNpc live in client/actions.hpp: they queue the action, and the client's
// own sender runs it on the game thread (no packet is built here, and none of it runs on our thread).

}  // namespace oxc
