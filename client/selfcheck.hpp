// selfcheck.hpp -- test the loaded offsets against the running game, and write down what was seen.
//
// The derivation pipeline (tools/update/update.py) can only read numbers off the binary; whether a
// number is RIGHT is a question about the running game. This file asks it, once per session, as soon
// as you are logged in, with read-only checks whose answers we can predict without trusting the
// offset under test:
//
//   cross-checks   two independent reads that must agree. The player table walked through the
//                  registry must hold exactly the handles PLAYER_IDS lists; every skill's base level
//                  must be the level its xp earns; the combat level must be what the formula makes of
//                  your stats; an entity's render position must sit on the tile its scene coords name;
//                  the projection must put you inside the canvas. A field that passes one of these was
//                  measured, not guessed.  -> verdict "pass"
//   range checks   the value is the right SHAPE (an orientation in 0..2047, an energy in 0..10000) but
//                  a wrong offset could produce that shape too.  -> verdict "plausible", never "pass"
//   failures       a check whose prediction did not hold.  -> verdict "fail", with what was read
//
// Nothing here writes game memory or calls a game function except the projection (the overlay calls
// it every frame already, and only when WORLD_TO_SCREEN was measured on this build).
//
// The result goes to offsets\selfcheck-<build>.json beside the DLL and to the OXC_LOG file. To turn it
// into evidence in the repository: `python tools/update/verify.py <that file>` marks every "pass" as
// verified (with the date and what was seen) and every "fail" as suspect, in offsets/client-<build>.json.
// OXC_SELFCHECK=0 turns the whole thing off.
#pragma once
#include <windows.h>
#include <cmath>
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <algorithm>
#include <map>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "game.hpp"
#include "log.hpp"
#include "offsets.hpp"
#include "offsets_json.hpp"

namespace oxc::selfcheck {

struct Verdict {
    std::string verdict;   // pass | plausible | fail | skip
    std::string detail;
};

class Results {
public:
    // Record one check's verdict against every offset it exercised. Per offset the worst verdict wins
    // (fail > pass > plausible > skip), so one failed cross-check is never hidden by a passing range.
    void add(std::initializer_list<const char*> names, const std::string& verdict, const std::string& detail) {
        checks_.push_back({verdict, detail});
        for (const char* n : names) {
            Verdict& v = per_[n];
            if (rank(verdict) > rank(v.verdict)) { v.verdict = verdict; v.detail = detail; }
            else if (rank(verdict) == rank(v.verdict) && !v.detail.empty()) v.detail += "; " + detail;
            else if (v.detail.empty()) v.detail = detail;
        }
    }
    const std::map<std::string, Verdict>& perOffset() const { return per_; }
    const std::vector<Verdict>& checks() const { return checks_; }

private:
    static int rank(const std::string& v) {
        return v == "fail" ? 4 : v == "pass" ? 3 : v == "plausible" ? 2 : v == "skip" ? 1 : 0;
    }
    std::map<std::string, Verdict> per_;
    std::vector<Verdict> checks_;
};

inline std::string fmt(const char* f, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, f);
    std::vsnprintf(buf, sizeof buf, f, ap);
    va_end(ap);
    return buf;
}

inline bool printable(const std::string& s) {
    if (s.empty()) return false;
    for (unsigned char c : s) if (c < 0x20 && c != '\t') return false;
    return true;
}

// The OSRS level for an xp total (levels 1..99; the client caps base levels at 99).
inline int levelForXp(int xp) {
    double points = 0;
    for (int lvl = 1; lvl < 99; ++lvl) {
        points += std::floor(lvl + 300.0 * std::pow(2.0, lvl / 7.0));
        if (static_cast<int>(std::floor(points / 4)) > xp) return lvl;
    }
    return 99;
}

// The combat level the game shows for these base levels (Skill.java order: 0 attack, 1 defence,
// 2 strength, 3 hitpoints, 4 ranged, 5 prayer, 6 magic).
inline int combatFor(const int* b) {
    const double base = 0.25 * (b[1] + b[3] + std::floor(b[5] / 2.0));
    const double melee = 0.325 * (b[0] + b[2]);
    const double range = 0.325 * std::floor(b[4] * 1.5);
    const double mage = 0.325 * std::floor(b[6] * 1.5);
    return static_cast<int>(std::floor(base + (std::max)(melee, (std::max)(range, mage))));
}

// ---------------------------------------------------------------------------------------------------
// The checks. Each is independent and reads only through game.hpp's guarded readers.
// ---------------------------------------------------------------------------------------------------
inline void checkSkills(Results& r) {
    int base[SKILL_COUNT], eff[SKILL_COUNT], xp[SKILL_COUNT];
    for (int i = 0; i < SKILL_COUNT; ++i) { base[i] = skillBase(i); eff[i] = skillEffective(i); xp[i] = skillXp(i); }
    // 23 real skills in this build's order (indices 23/24 are sailing/unused; skip anything unset).
    int agree = 0, used = 0, unboosted = 0;
    std::string bad;
    for (int i = 0; i < SKILL_COUNT; ++i) {
        if (xp[i] == 0 && base[i] == 0) continue;
        ++used;
        if (levelForXp(xp[i]) == base[i]) ++agree;
        else if (bad.size() < 120) bad += fmt(" #%d base=%d xp=%d", i, base[i], xp[i]);
        if (eff[i] == base[i]) ++unboosted;
    }
    if (used >= 20 && agree == used)
        r.add({"CLIENT_OBJ_PTR", "SKILL_BASE", "SKILL_XP"}, "pass",
              fmt("all %d skills' base level is the level their xp earns", used));
    else
        r.add({"SKILL_BASE", "SKILL_XP"}, "fail", fmt("%d of %d skills agree with their xp:%s", agree, used, bad.c_str()));
    // Effective levels are boosted/drained only temporarily: most skills read exactly their base.
    if (used >= 20 && unboosted * 4 >= used * 3)
        r.add({"SKILL_EFFECTIVE"}, "pass", fmt("%d of %d skills read their base level as effective", unboosted, used));
    else
        r.add({"SKILL_EFFECTIVE"}, "fail", fmt("only %d of %d effective levels equal the base level", unboosted, used));
}

inline void checkStateAndCycle(Results& r, bool meFound) {
    const int gs = gameState();
    if (meFound) {
        r.add({"GAME_STATE"}, gs == 30 ? "pass" : "fail", fmt("read %d while the local player is in the world (30 = logged in)", gs));
    }
    const int c0 = cycle();
    Sleep(1000);
    const int c1 = cycle();
    const int d = c1 - c0;
    r.add({"CYCLE"}, (d >= 10 && d <= 1000) ? "pass" : "fail", fmt("advanced %d in one second", d));
}

inline void checkRegistry(Results& r, const Entity& me, bool meFound) {
    // Up to five attempts: the tables are rehashed on the game thread while we read them, so one
    // disagreement can be a torn read. Five in a row is not.
    std::set<int> walked, listed, npcWalked, npcListed;
    bool playersAgree = false, npcsAgree = false;
    for (int attempt = 0; attempt < 5 && !(playersAgree && npcsAgree); ++attempt) {
        walked.clear(); listed.clear(); npcWalked.clear(); npcListed.clear();
        forEachEntity([&](const Entity& e) { (e.player ? walked : npcWalked).insert(e.uid); });
        const std::uintptr_t c = clientObj();
        const int count = c ? rd<std::int32_t>(c + off::PLAYER_COUNT, -1) : -1;
        if (count >= 0 && count <= 2048)
            for (int i = 0; i < count; ++i) {
                const std::uint32_t id = rd<std::uint32_t>(c + off::PLAYER_IDS + static_cast<std::uintptr_t>(i) * 4, 0xFFFFFFFFu);
                if (id != 0xFFFFFFFFu) listed.insert(static_cast<int>(id));
            }
        const std::uintptr_t s = scene();
        const std::uintptr_t arr = s ? rdp(s + off::SCENE_NPC_UIDS) : 0;
        const int ncount = s ? rd<std::int32_t>(s + off::SCENE_NPC_UID_COUNT, -1) : -1;
        if (arr && ncount >= 0 && ncount <= 65536)
            for (int i = 0; i < ncount; ++i) npcListed.insert(rd<std::int32_t>(arr + static_cast<std::uintptr_t>(i) * 4, -1));
        playersAgree = !walked.empty() && walked == listed;
        npcsAgree = walked.empty() ? false : npcWalked == npcListed;
        if (!(playersAgree && npcsAgree)) Sleep(150);
    }
    const bool selfListed = meFound && walked.count(me.uid);
    if (playersAgree && selfListed)
        r.add({"REGISTRY_GROUPS", "REGISTRY_GROUP_COUNT", "GROUP_TABLE", "GROUP_NEXT", "PLAYER_BUCKETS",
               "PLAYER_BUCKET_COUNT", "NODE_UID", "NODE_ENTITY", "NODE_NEXT", "PLAYER_COUNT", "PLAYER_IDS",
               "LOCAL_PLAYER_IDX", "ENTITY_SCENE_X", "ENTITY_SCENE_Y"},
              "pass", fmt("the registry's player table holds exactly the %zu handles PLAYER_IDS lists, yours included",
                          walked.size()));
    else
        r.add({"PLAYER_COUNT", "PLAYER_IDS", "PLAYER_BUCKETS", "PLAYER_BUCKET_COUNT", "LOCAL_PLAYER_IDX"}, "fail",
              fmt("registry walk found %zu players, PLAYER_IDS lists %zu, local player %s", walked.size(), listed.size(),
                  selfListed ? "among them" : "NOT found"));
    if (npcListed.empty() && npcWalked.empty())
        r.add({"NPC_BUCKETS", "NPC_BUCKET_COUNT", "SCENE_NPC_UIDS", "SCENE_NPC_UID_COUNT"}, "skip", "no NPCs in the scene");
    else if (npcsAgree)
        r.add({"NPC_BUCKETS", "NPC_BUCKET_COUNT", "SCENE_NPC_UIDS", "SCENE_NPC_UID_COUNT"}, "pass",
              fmt("the registry's NPC table holds exactly the %zu uids the scene lists", npcWalked.size()));
    else
        r.add({"NPC_BUCKETS", "NPC_BUCKET_COUNT", "SCENE_NPC_UIDS", "SCENE_NPC_UID_COUNT"}, "fail",
              fmt("registry walk found %zu NPCs, the scene lists %zu", npcWalked.size(), npcListed.size()));
}

inline void checkEntities(Results& r, const Entity& me) {
    const std::uintptr_t e = me.addr;
    // Render position: an idle player stands on its tile's centre; a walking one within a tile of it.
    const int cx = (me.sceneX << 7) + 64, cy = (me.sceneY << 7) + 64;
    const int fx = rd<std::int32_t>(e + off::ENTITY_FINE_X), fy = rd<std::int32_t>(e + off::ENTITY_FINE_Y);
    const int fh = rd<std::int32_t>(e + off::ENTITY_FINE_H);
    if (std::abs(fx - cx) <= 192 && std::abs(fy - cy) <= 192)
        r.add({"ENTITY_FINE_X", "ENTITY_FINE_Y"}, "pass", fmt("render position (%d,%d) sits on scene tile (%d,%d)", fx, fy, me.sceneX, me.sceneY));
    else
        r.add({"ENTITY_FINE_X", "ENTITY_FINE_Y"}, "fail", fmt("render position (%d,%d), tile centre (%d,%d)", fx, fy, cx, cy));
    r.add({"ENTITY_FINE_H"}, (fh >= -4000 && fh <= 1000 && fh != 0) ? "plausible" : "fail", fmt("ground height %d", fh));

    // Scene base: the scene is built in 8-tile chunks, so the corner is always a multiple of 8.
    const Tile b = sceneBase();
    const bool baseOk = b.ok && b.x > 0 && b.y > 0 && b.x < 16384 && b.y < 16384 && b.x % 8 == 0 && b.y % 8 == 0;
    r.add({"SCENE", "SCENE_BASE_X", "SCENE_BASE_Y"}, baseOk ? "pass" : "fail",
          fmt("scene corner (%d,%d)%s", b.x, b.y, baseOk ? ": in the world and chunk-aligned" : ""));

    // Planes. Only a staircase settles which one is right; here both just have to look like a plane.
    const int p7 = rd<std::int32_t>(e + off::ENTITY_PLANE_COORD, -1), p4 = rd<std::int32_t>(e + off::ENTITY_PLANE, -1);
    r.add({"ENTITY_PLANE_COORD"}, (p7 >= 0 && p7 <= 3) ? "plausible" : "fail", fmt("read %d", p7));
    r.add({"ENTITY_PLANE"}, (p4 >= 0 && p4 <= 3) ? "plausible" : "fail", fmt("read %d", p4));

    // Orientation and animation over every entity: the right shape everywhere, and not all zero.
    int n = 0, orientOk = 0, orientNonZero = 0, animOk = 0;
    forEachEntity([&](const Entity& x) {
        ++n;
        if (x.orientation >= 0 && x.orientation < 2048) ++orientOk;
        if (x.orientation != 0) ++orientNonZero;
        if (x.animation >= -1 && x.animation < 20000) ++animOk;
    });
    r.add({"ENTITY_ORIENTATION"}, (n && orientOk == n && orientNonZero) ? "plausible" : "fail",
          fmt("%d of %d entities in 0..2047, %d nonzero", orientOk, n, orientNonZero));
    r.add({"ENTITY_ANIMATION"}, (n && animOk == n) ? "plausible" : "fail", fmt("%d of %d entities -1 or a sequence id", animOk, n));
}

inline void checkNames(Results& r, const Entity& me) {
    const std::string mine = playerName(me.addr);
    r.add({"PLAYER_NAME_PTR"}, (printable(mine) && mine.size() <= 12) ? "pass" : "fail",
          fmt("your name reads \"%s\"", mine.c_str()));
    int npcs = 0, named = 0, ids = 0;
    std::string sample;
    forEachEntity([&](const Entity& x) {
        if (x.player) return;
        ++npcs;
        const std::string nm = npcName(x.addr);
        if (printable(nm) && nm.size() < 64) { ++named; if (sample.size() < 60) sample += (sample.empty() ? "" : ", ") + nm; }
        if (npcTypeId(x.addr) >= 0) ++ids;
    });
    if (!npcs) {
        r.add({"ENTITY_DEF_PTR", "DEF_NAME", "ENTITY_NAME_OVERRIDE"}, "skip", "no NPCs in the scene");
        return;
    }
    // A name read through the definition pointer is two hops of offsets landing on real text.
    if (named * 10 >= npcs * 9)
        r.add({"ENTITY_DEF_PTR", "DEF_NAME", "ENTITY_NAME_OVERRIDE"}, "pass", fmt("%d of %d NPCs have a readable name (%s)", named, npcs, sample.c_str()));
    else
        r.add({"ENTITY_DEF_PTR", "DEF_NAME"}, "fail", fmt("only %d of %d NPCs have a readable name", named, npcs));
    r.add({"ENTITY_DEF_PTR"}, ids == npcs ? "plausible" : "fail", fmt("%d of %d NPC type ids in range", ids, npcs));
}

inline void checkCombat(Results& r, const Entity& me) {
    int b[7];
    for (int i = 0; i < 7; ++i) b[i] = skillBase(i);
    const int want = combatFor(b);
    const int got = rd<std::int32_t>(me.addr + off::PLAYER_COMBAT_LEVEL, -1);
    r.add({"PLAYER_COMBAT_LEVEL"}, got == want ? "pass" : "fail", fmt("read %d, your stats make %d", got, want));
}

inline void checkCameraAndProjection(Results& r, const Entity& me, HWND canvas) {
    const std::uintptr_t c = clientObj();
    const int camX = rd<std::int32_t>(c + off::CAMERA_FINE_X), camH = rd<std::int32_t>(c + off::CAMERA_FINE_H),
              camY = rd<std::int32_t>(c + off::CAMERA_FINE_Y);
    // The camera orbits the player: within a few tiles horizontally (max zoom-out is ~25 tiles), above.
    const bool camNear = std::abs(camX - me.fineX) < 40 * 128 && std::abs(camY - me.fineY) < 40 * 128;
    r.add({"CAMERA_FINE_X", "CAMERA_FINE_Y"}, camNear ? "pass" : "fail",
          fmt("camera (%d,%d), you (%d,%d)", camX, camY, me.fineX, me.fineY));
    r.add({"CAMERA_FINE_H"}, (camH < 0 && camH > -20000) ? "plausible" : "fail", fmt("camera height %d (negative is up)", camH));

    RECT rc{};
    const bool haveCanvas = canvas && GetClientRect(canvas, &rc);
    const int cw = rc.right - rc.left, ch = rc.bottom - rc.top;
    const std::uintptr_t view = rdp(c + off::VIEW_OBJ);
    // The four scale ints sit at view + SCALE_BASE + VIEW_* (the same arithmetic jvm.hpp's probe uses).
    const std::uintptr_t scales = view ? view + off::VIEW_OBJ_SCALE_BASE : 0;
    const int inW = rd<std::int32_t>(scales + off::VIEW_IN_W), inH = rd<std::int32_t>(scales + off::VIEW_IN_H);
    const int outW = rd<std::int32_t>(scales + off::VIEW_OUT_W), outH = rd<std::int32_t>(scales + off::VIEW_OUT_H);
    if (haveCanvas && std::abs(inW - cw) <= 2 && std::abs(inH - ch) <= 2 && std::abs(outW - cw) <= 2 && std::abs(outH - ch) <= 2)
        r.add({"VIEW_OBJ", "VIEW_OBJ_SCALE_BASE", "VIEW_IN_W", "VIEW_IN_H", "VIEW_OUT_W", "VIEW_OUT_H"}, "pass",
              fmt("both scale pairs read the canvas size %dx%d", cw, ch));
    else
        r.add({"VIEW_OBJ", "VIEW_OBJ_SCALE_BASE", "VIEW_IN_W", "VIEW_IN_H", "VIEW_OUT_W", "VIEW_OUT_H"}, "fail",
              fmt("scales in %dx%d out %dx%d, canvas %dx%d", inW, inH, outW, outH, cw, ch));

    if (off::WORLD_TO_SCREEN == 0) {
        r.add({"WORLD_TO_SCREEN"}, "skip", "not derived on this build (refused by the loader)");
        return;
    }
    float sx = 0, sy = 0;
    const bool ok = projectFine(me.fineX, me.fineH, me.fineY, sx, sy);
    const bool inside = ok && haveCanvas && sx >= 0 && sy >= 0 && sx < cw && sy < ch;
    r.add({"WORLD_TO_SCREEN"}, inside ? "pass" : "fail",
          fmt("your position projects to (%.0f,%.0f) on a %dx%d canvas", sx, sy, cw, ch));
}

inline void checkWidgets(Results& r, HWND canvas) {
    const std::uintptr_t c = clientObj();
    const std::uintptr_t mgr = c ? rdp(c + off::IFACE_MANAGER) : 0;
    const std::uint64_t gcount = mgr ? rd<std::uint64_t>(mgr + off::IFACE_GROUP_COUNT) : 0;
    const std::uintptr_t garr = mgr ? rdp(mgr + off::IFACE_GROUP_ARRAY) : 0;
    if (!mgr || !garr || gcount == 0 || gcount > 0x1000) {
        r.add({"IFACE_MANAGER", "IFACE_GROUP_COUNT", "IFACE_GROUP_ARRAY"}, "fail",
              fmt("manager %p, group count %llu, array %p", (void*)mgr, (unsigned long long)gcount, (void*)garr));
        return;
    }
    RECT rc{};
    GetClientRect(canvas, &rc);
    const int cw = rc.right - rc.left, ch = rc.bottom - rc.top;
    const std::uintptr_t empty = off::IFACE_EMPTY_SENTINEL ? rdp(globalAddr(off::IFACE_EMPTY_SENTINEL) + 8) : 0;
    int groups = 0, comps = 0, canvasRoots = 0, texts = 0, hiddenOk = 0, sentinelHits = 0;
    struct Rect { long long area; int x, y, w, h; };
    std::vector<Rect> top;
    std::vector<std::uintptr_t> list;
    for (std::uint64_t g = 0; g < gcount; ++g) {
        const std::uintptr_t entry = garr + g * off::IFACE_GROUP_ENTRY_STRIDE;
        const std::uintptr_t data = rdp(entry + off::IFACE_GROUP_ENTRY_DATA);
        const std::uint64_t cc = rd<std::uint64_t>(entry + off::IFACE_GROUP_ENTRY_COUNT);
        if (!data || cc == 0 || cc > 4096) continue;
        ++groups;
        for (std::uint64_t i = 0; i < cc; ++i) {
            const std::uintptr_t w = rdp(data + i * 16 + 8);
            if (!w) continue;
            if (w == empty) { ++sentinelHits; continue; }
            ++comps;
            const int x = rd<std::int32_t>(w + off::IFTYPE_X), y = rd<std::int32_t>(w + off::IFTYPE_Y);
            const int wd = rd<std::int32_t>(w + off::IFTYPE_WIDTH), ht = rd<std::int32_t>(w + off::IFTYPE_HEIGHT);
            if (x == 0 && y == 0 && std::abs(wd - cw) <= 2 && std::abs(ht - ch) <= 2) ++canvasRoots;
            // keep the three largest rectangles, so a failure says what the layout actually holds
            const long long area = static_cast<long long>(wd) * ht;
            if (wd > 0 && ht > 0 && wd < 100000 && ht < 100000) {
                top.push_back({area, x, y, wd, ht});
                std::sort(top.begin(), top.end(), [](const Rect& a, const Rect& b) { return a.area > b.area; });
                if (top.size() > 3) top.pop_back();
            }
            const std::uint8_t hid = rd<std::uint8_t>(w + off::IFTYPE_HIDDEN, 0xFF);
            if (hid <= 1) ++hiddenOk;
            if (printable(nxtString(w + off::IFTYPE_TEXT, 4096))) ++texts;
        }
    }
    std::string biggest;
    for (const Rect& t : top) biggest += fmt(" (%d,%d %dx%d)", t.x, t.y, t.w, t.h);
    r.add({"IFACE_MANAGER", "IFACE_GROUP_COUNT", "IFACE_GROUP_ARRAY", "IFACE_GROUP_ENTRY_STRIDE",
           "IFACE_GROUP_ENTRY_COUNT", "IFACE_GROUP_ENTRY_DATA"}, groups >= 5 && comps >= 50 ? "pass" : "fail",
          fmt("%d of %llu groups loaded, %d components", groups, (unsigned long long)gcount, comps));
    // The top-level groups are laid out to the canvas: a component at (0,0) exactly the canvas size.
    r.add({"IFTYPE_X", "IFTYPE_Y", "IFTYPE_WIDTH", "IFTYPE_HEIGHT"}, canvasRoots >= 1 ? "pass" : "fail",
          fmt("%d components sit at (0,0) at the canvas size %dx%d; largest seen:%s", canvasRoots, cw, ch, biggest.c_str()));
    r.add({"IFTYPE_TEXT", "IFTYPE_TEXT_FLAG"}, texts >= 5 ? "pass" : "fail", fmt("%d components carry readable text", texts));
    r.add({"IFTYPE_HIDDEN"}, comps && hiddenOk == comps ? "plausible" : "fail", fmt("%d of %d hidden flags are 0/1", hiddenOk, comps));
    r.add({"IFACE_EMPTY_SENTINEL"}, sentinelHits ? "pass" : "plausible",
          fmt("%d component slots hold the shared empty object", sentinelHits));
}

inline void checkGlobals(Results& r) {
    // Inventory (93) always exists once you are logged in; like the Java client's item containers it
    // is only as long as its last occupied slot (1..28), and every slot is either empty (-1, qty 0) or
    // an item id with a positive quantity. Equipment (94) does not exist at all until something is worn
    // (a fresh account), and is at most 14 slots when it does.
    auto wellFormed = [](int id, int n, int maxSlots, std::string& why) {
        const int size = containerSize(id);
        if (size < 1 || size > maxSlots) { why = fmt("container %d has %d slots (1..%d expected)", id, size, maxSlots); return false; }
        for (int s = 0; s < size; ++s) {
            const int item = containerItem(id, s), q = containerQty(id, s);
            if (!((item == -1 && q == 0) || (item >= 0 && item < 0x10000 && q > 0))) {
                why = fmt("container %d slot %d holds id %d qty %d", id, s, item, q);
                return false;
            }
        }
        why = fmt("container %d: %d well-formed slots", id, size);
        (void)n;
        return true;
    };
    std::string invWhy, eqWhy;
    const bool invOk = wellFormed(93, 0, 28, invWhy);
    const bool eqAbsent = containerSize(94) <= 0;
    const bool eqOk = eqAbsent || wellFormed(94, 0, 14, eqWhy);
    r.add({"CONTAINER_BUCKETS", "CONTAINER_MASK", "CONTAINER_NODE_IDS", "CONTAINER_NODE_IDS_END",
           "CONTAINER_NODE_QTYS", "CONTAINER_NODE_QTYS_END", "CONTAINER_NODE_NEXT"},
          invOk && eqOk ? "pass" : "fail",
          invWhy + "; " + (eqAbsent ? std::string("nothing worn") : eqWhy));

    const std::uintptr_t arr = rdp(globalAddr(off::VARP_ARRAY_PTR));
    int nonzero = 0;
    const bool readableArr = arr && readable(arr, 4000 * 4);
    if (readableArr) for (int i = 0; i < 4000; ++i) if (rd<std::int32_t>(arr + i * 4)) ++nonzero;
    r.add({"VARP_ARRAY_PTR"}, readableArr && nonzero > 10 ? "plausible" : "fail", fmt("%d of the first 4000 varps nonzero", nonzero));

    const std::uintptr_t wm = worldMap();
    const int lvl = wm ? rd<std::int32_t>(wm + off::WM_ORIGIN_LEVEL, -1) : -1;
    r.add({"WORLD_MAP"}, wm ? "plausible" : "fail", fmt("world map object %p", (void*)wm));
    r.add({"WM_ORIGIN_LEVEL"}, (lvl >= 0 && lvl <= 3) ? "plausible" : "fail", fmt("origin level %d", lvl));

    const int energy = runEnergy();
    r.add({"RUN_ENERGY"}, (energy >= 0 && energy <= 10000) ? "plausible" : "fail", fmt("read %d (0..10000 expected)", energy));
}

// ---------------------------------------------------------------------------------------------------
// The report
// ---------------------------------------------------------------------------------------------------
inline std::string jsonEscape(const std::string& s) {
    std::string o;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += static_cast<char>(c); }
        else if (c < 0x20) o += fmt("\\u%04x", c);
        else o += static_cast<char>(c);
    }
    return o;
}

inline unsigned long long valueOf(const std::string& name) {
    for (const auto& s : offjson::slots())
        if (name == s.name) return s.u ? static_cast<unsigned long long>(*s.u) : static_cast<unsigned long long>(*s.i);
    return 0;
}

inline void write(const std::wstring& dllDir, const Results& r) {
    std::string build;
    for (wchar_t ch : off::BUILD_VERSION) build += (ch < 128 ? static_cast<char>(ch) : '?');
    char when[32];
    std::time_t t = std::time(nullptr);
    std::tm tm{};
    gmtime_s(&tm, &t);
    std::strftime(when, sizeof when, "%Y-%m-%dT%H:%M:%SZ", &tm);

    std::string j = "{\n  \"build\": \"" + build + "\",\n  \"sha256\": \"" + offjson::hostSha256() +
                    "\",\n  \"when\": \"" + when + "\",\n  \"offsets\": {\n";
    int i = 0, pass = 0, fail = 0, plaus = 0;
    for (const auto& kv : r.perOffset()) {
        const Verdict& v = kv.second;
        pass += v.verdict == "pass";
        fail += v.verdict == "fail";
        plaus += v.verdict == "plausible";
        j += "    \"" + kv.first + "\": {\"value\": " + std::to_string(valueOf(kv.first)) + ", \"status\": \"" +
             offjson::statusOf(kv.first) + "\", \"verdict\": \"" + v.verdict + "\", \"detail\": \"" + jsonEscape(v.detail) + "\"}";
        j += (++i < static_cast<int>(r.perOffset().size())) ? ",\n" : "\n";
        oxc::logf("[selfcheck] %-24s %-9s (%s) %s\n", kv.first.c_str(), v.verdict.c_str(), offjson::statusOf(kv.first).c_str(), v.detail.c_str());
    }
    j += "  }\n}\n";
    CreateDirectoryW((dllDir + L"\\offsets").c_str(), nullptr);
    const std::wstring path = dllDir + L"\\offsets\\selfcheck-" + off::BUILD_VERSION + L".json";
    std::ofstream(std::filesystem::path(path), std::ios::binary) << j;
    oxc::logf("[selfcheck] %d pass, %d plausible, %d fail -- written to offsets\\selfcheck-%s.json; "
              "run `python tools/update/verify.py <that file>` in the repository to record it\n",
              pass, plaus, fail, build.c_str());
}

// ---------------------------------------------------------------------------------------------------
// The driver: call tick() from the DLL's loop. It waits until you are in the world, lets the scene
// settle, runs every check once, and writes the report. Cheap until then (one read per call).
// ---------------------------------------------------------------------------------------------------
class Runner {
public:
    void tick(const std::wstring& dllDir, HWND canvas) {
        if (done_) return;
        if (!enabled()) { done_ = true; return; }
        const std::uint64_t now = GetTickCount64();
        if (now < next_) return;
        next_ = now + 500;
        if (!clientObj()) return;
        bool found = false;
        localPlayer(found);
        if (!found) { settledSince_ = 0; return; }
        if (!settledSince_) settledSince_ = now;
        if (now - settledSince_ < 5000) return;            // let the scene finish loading
        // Render state (the player's render position, the camera) is only written once the scene has
        // actually been drawn; a check taken before that reads zeros and blames the offsets. Wait for
        // the render position to leave 0, but never more than a minute -- if it never does, the offset
        // itself may be wrong, and the check should run and say so.
        bool f2 = false;
        const Entity me = localPlayer(f2);
        const bool rendered = f2 && rd<std::int32_t>(me.addr + off::ENTITY_FINE_X) != 0;
        if (!rendered && now - settledSince_ < 60000) return;
        done_ = true;
        // The checks sleep (the cycle check waits a second) and retry torn reads, so they run on their
        // own thread: the loop that calls tick() is the one that presents the overlay every frame.
        std::thread([dllDir, canvas] { run(dllDir, canvas); }).detach();
    }

    static void run(const std::wstring& dllDir, HWND canvas) {
        Results r;
        bool meFound = false;
        const Entity me = localPlayer(meFound);
        if (!meFound) { oxc::logf("[selfcheck] the local player left the world before the checks ran; skipped\n"); return; }
        checkStateAndCycle(r, meFound);
        checkSkills(r);
        checkRegistry(r, me, meFound);
        checkEntities(r, me);
        checkNames(r, me);
        checkCombat(r, me);
        checkCameraAndProjection(r, me, canvas);
        checkWidgets(r, canvas);
        checkGlobals(r);
        r.add({"DO_ACTION", "OPLOC1", "OPNPC1", "OPNPC2", "OPNPC3", "OPNPC4", "OPNPC5", "OP_WALK",
               "PENDING_ACTION_PACKED_ID", "PENDING_ACTION_INDEX", "PENDING_ACTION_TARGET", "PENDING_ACTION_SEQ",
               "PENDING_ACTION_PENDING"},
              "skip", "not checkable by reading: needs a hook-and-log against a real click");
        write(dllDir, r);
    }

private:
    static bool enabled() {
        const char* e = ::getenv("OXC_SELFCHECK");
        return !(e && e[0] == '0');
    }
    bool done_ = false;
    std::uint64_t next_ = 0, settledSince_ = 0;
};

}  // namespace oxc::selfcheck
