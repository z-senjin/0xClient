// jvm.hpp -- start a Java VM inside the game and hand it the few things it needs.
//
// This is the bit that makes 0xClient worth forking: plugins are Java, not C++. You edit a .java file,
// run one build, restart the client, and your plugin is live. Nobody needs a C++ toolchain to write a
// bot, or to draw an overlay.
//
// The split is deliberately lopsided:
//
//   C++  does the unsafe things -- reading a live process, calling into the game, putting pixels on the
//        screen. That is this file, and it is the entire unsafe surface.
//   Java does everything else   -- plugin logic, state machines, config, and ALL the drawing.
//
// Every native below is a place that can crash the game, so the bar for adding one is: could the Java
// side do this with what it already has? Nine is more than the four we started with, and each of the
// extra five earned its place by removing a whole category of thing C++ would otherwise have to know
// about -- what an NPC is, what a skill is, what a box looks like. The four input natives (postChar,
// postKey, postMouse, inputTarget -- 2026-09-05) are the first that push something INTO the game
// rather than read it; they exist because only the DLL knows the game's window handle and the Win32
// lParam bit layout, and they are deliberately PostMessageW-only (see the section comment).
#pragma once
#include <windows.h>
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <atomic>
#include <cwchar>
#include <string>
#include <vector>
#include "game.hpp"
#include "actions.hpp"
#include "scenery.hpp"
#include "overlay.hpp"
#include "panel.hpp"

namespace oxc {

inline JavaVM*   g_vm     = nullptr;
inline jclass    g_api    = nullptr;   // oxclient.OxClient  -- lifecycle
inline jclass    g_nat    = nullptr;   // oxclient.Natives     -- where the natives are registered
inline jmethodID g_tick   = nullptr;
inline jmethodID g_status = nullptr;
// Mouse events from the panel window, delivered up to Java. Optional: an older jar without
// panelMouse still runs, it just never hears about clicks on the panel.
inline jmethodID g_panelMouse = nullptr;

// The panel bridge (launcher mode), resolved lazily by bridgeResolve(). Everything here is optional:
// a jar from before the launcher has no oxclient.panel.PanelBridge at all, and launcher mode then runs
// with an empty panel model and no edits rather than a dead game. Each method is guarded on its own,
// so a half-matched jar degrades exactly as far as it has to.
inline jclass    g_bridgeCls      = nullptr;   // oxclient.panel.PanelBridge
inline bool      g_bridgeTried    = false;
inline jmethodID g_bridgeRevision = nullptr;   // static long modelRevision()
inline jmethodID g_bridgeSnapshot = nullptr;   // static int[] snapshot()
inline jmethodID g_bridgeSetBool  = nullptr;   // static void setBool(int, String, boolean)
inline jmethodID g_bridgeSetInt   = nullptr;   // static void setInt(int, String, int)
inline jmethodID g_bridgeSetEnum  = nullptr;   // static void setEnum(int, String, int)
inline jmethodID g_bridgeSetText  = nullptr;   // static void setText(int, String, String)

// The format-2 edits (bridge.hpp EditKind 4..14). These are COMMANDS -- reset, pin, profile and hub
// operations -- so each lands on its own PanelBridge static rather than on the four set* methods
// above, and a jar built before format 2 has none of them. They are resolved by bridgeResolveV2 the
// first time a v2 edit arrives, not with the rest of the bridge: a session that never touches pin,
// profiles or the hub never pays for eleven GetStaticMethodID calls, and an old jar is only
// "missing methods" from the moment it is asked to do something it cannot.
inline bool      g_bridgeV2Tried         = false;
inline jmethodID g_bridgeResetSetting    = nullptr;   // static void resetSetting(int, String)
inline jmethodID g_bridgeResetPlugin     = nullptr;   // static void resetPlugin(int)
inline jmethodID g_bridgeSetPinned       = nullptr;   // static void setPinned(int, boolean)
inline jmethodID g_bridgeHubInstall      = nullptr;   // static void hubInstall(String)
inline jmethodID g_bridgeHubRemove       = nullptr;   // static void hubRemove(String)
inline jmethodID g_bridgeHubRefresh      = nullptr;   // static void hubRefresh()
inline jmethodID g_bridgeProfileSwitch   = nullptr;   // static void profileSwitch(int)
inline jmethodID g_bridgeProfileCreate   = nullptr;   // static void profileCreate(String)
inline jmethodID g_bridgeProfileDelete   = nullptr;   // static void profileDelete(int)
inline jmethodID g_bridgeProfileRename   = nullptr;   // static void profileRename(int, String)
inline jmethodID g_bridgeProfileDuplicate = nullptr;  // static void profileDuplicate(int)
// Bit per edit kind that has been logged as "no Java method for this" -- one line PER KIND, not one
// per edit (the ring can carry a burst) and not one line total (with kinds 4..14 in play, a single
// flag meant the first dropped kind permanently silenced the ones behind it: a launcher sending pins
// plus profile edits against an old jar would show only whichever arrived first). Kind 15+ shares
// bit 31 -- anything that far off contract is garbage, and one line for the family is enough.
inline std::uint32_t g_bridgeKindsLogged = 0;

/// Set by dllmain: the game's own window, so we can report its size to Java.
inline HWND g_gameWindow = nullptr;

/// The window Java's pixels and coordinates are measured against -- see overlay.hpp g_canvasWindow.
inline HWND canvasWindow() {
    if (oxc::g_canvasWindow && IsWindow(oxc::g_canvasWindow)) return oxc::g_canvasWindow;
    return g_gameWindow;
}

// ---------------------------------------------------------------------------------------------------
// The natives. These are the ONLY things Java can do to the game.
// ---------------------------------------------------------------------------------------------------

/// True once the game has built its client object -- i.e. you are actually in-game.
inline jboolean JNICALL nReady(JNIEnv*, jclass) {
    return clientObj() ? JNI_TRUE : JNI_FALSE;
}

/// Every visible entity, flattened, TEN ints each:
///     uid, sceneX, sceneY, isPlayer, typeId, animation, orientation, fineX, fineH, fineY
///
/// The last three are the render position (offsets.hpp ENTITY_FINE_*): where the model is drawn, in
/// fine units, height included -- the height is what puts a box on the entity instead of at datum 0.
///
/// `typeId` is the NPC's type id; for players it is always -1 on this build -- PLAYER_COMBAT_LEVEL
/// (offsets.hpp) is wrong here and reading it shipped pointer-fragment garbage as combat levels -- so
/// the slot is -1 for players until that offset is re-derived.
///
/// One array rather than one object per entity on purpose -- this is called thirty times a second, and
/// allocating a few hundred short-lived objects a frame is exactly the kind of thing that turns into a
/// stutter you then spend an evening profiling. Java unpacks it into records once.
inline jintArray JNICALL nEntities(JNIEnv* env, jclass) {
    std::vector<jint> flat;
    flat.reserve(256 * 10);
    forEachEntity([&](const Entity& e) {
        // The kind comes from the table the walker found the node in (Entity::player), not from a
        // per-entity scan of PLAYER_IDS: that scan was (#entities x #players) VirtualQuery'd reads
        // per frame -- tens of thousands in a crowd, enough to drag the overlay behind moving
        // entities -- and it misclassified any NPC whose uid equalled a present player's handle.
        flat.push_back(e.uid);
        flat.push_back(e.sceneX);
        flat.push_back(e.sceneY);
        flat.push_back(e.player ? 1 : 0);
        // -1 for players: combatLevel() is gated off because PLAYER_COMBAT_LEVEL is wrong on this
        // build (see offsets.hpp and game.hpp).
        flat.push_back(e.player ? combatLevel(e.addr) : npcTypeId(e.addr));
        flat.push_back(e.animation);
        flat.push_back(e.orientation);
        flat.push_back(e.fineX);
        flat.push_back(e.fineH);
        flat.push_back(e.fineY);
    });
    jintArray arr = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (arr && !flat.empty()) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}

/// int[] {worldX, worldY} of the scene's south-west corner, or empty if the world is not loaded.
inline jintArray JNICALL nSceneBase(JNIEnv* env, jclass) {
    Tile b = sceneBase();
    jint v[2] = { b.x, b.y };
    jintArray arr = env->NewIntArray(b.ok ? 2 : 0);
    if (arr && b.ok) env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

/// You: {uid, sceneX, sceneY, plane, animation, orientation, runEnergy, cycle}. Empty before you spawn.
inline jintArray JNICALL nLocal(JNIEnv* env, jclass) {
    bool found = false;
    Entity me = localPlayer(found);
    if (!found) return env->NewIntArray(0);
    // Eleven ints: the eight oxclient.api.Local always had, then the render position {fineX, fineH,
    // fineY} (see nEntities). Local.read() checks the length, so both sides move together.
    jint v[11] = { me.uid, me.sceneX, me.sceneY, me.plane,
                   me.animation, me.orientation, runEnergy(), cycle(),
                   me.fineX, me.fineH, me.fineY };
    jintArray arr = env->NewIntArray(11);
    if (arr) env->SetIntArrayRegion(arr, 0, 11, v);
    return arr;
}

/// One varp (client config variable) by id. 0 before the game has the array up.
///
/// A pass-through read rather than a snapshot array: the shim asks for individual ids a handful of
/// times per frame (transport gates in the pathfinder), and copying a few thousand ints per frame to
/// maybe read three of them is allocation for its own sake.
inline jint JNICALL nVarp(JNIEnv*, jclass, jint id) {
    return varp(id);
}

/// An item container's current shape: {slot0Id, slot0Qty, slot1Id, slot1Qty, ...}, or empty when the
/// container does not exist right now (bank closed, before login). One snapshot call per
/// ItemContainerChanged-sized read rather than one JNI hop per slot.
inline jintArray JNICALL nContainer(JNIEnv* env, jclass, jint containerId) {
    jintArray arr = nullptr;
    int size = containerSize(containerId);
    if (size <= 0 || size > 4096) return env->NewIntArray(0);

    std::vector<jint> flat;
    flat.reserve(static_cast<std::size_t>(size) * 2);
    for (int i = 0; i < size; ++i) {
        flat.push_back(containerItem(containerId, i));
        flat.push_back(containerQty(containerId, i));
    }
    arr = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (arr && !flat.empty()) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}

/// All 25 skills at once: effective[25], base[25], xp[25], in that order. One call rather than 75.
inline jintArray JNICALL nSkills(JNIEnv* env, jclass) {
    if (!clientObj()) return env->NewIntArray(0);
    jint v[SKILL_COUNT * 3];
    for (int i = 0; i < SKILL_COUNT; ++i) {
        v[i]                     = skillEffective(i);
        v[SKILL_COUNT + i]       = skillBase(i);
        v[SKILL_COUNT * 2 + i]   = skillXp(i);
    }
    jintArray arr = env->NewIntArray(SKILL_COUNT * 3);
    if (arr) env->SetIntArrayRegion(arr, 0, SKILL_COUNT * 3, v);
    return arr;
}

/// Project a fine-coordinate point to the screen. Returns the two screen coordinates packed into a
/// long, or Long.MIN_VALUE when the point is not on screen.
///
/// Packed into a long rather than returned in an array because a plugin drawing tile outlines calls
/// this four times per tile, and an allocation per call would dominate the cost of the whole overlay.
inline jlong JNICALL nProject(JNIEnv*, jclass, jint fineX, jint fineHeight, jint fineY) {
    float sx = 0.f, sy = 0.f;
    if (!projectFine(fineX, fineHeight, fineY, sx, sy)) return static_cast<jlong>(0x8000000000000000ULL);

    // The leaf has no "is it visible" answer -- it writes two floats, always. A point BEHIND the
    // camera comes back mirrored rather than rejected (the perspective division flips sign), and
    // projectFine's ±10000 clamp is far wider than the canvas, so entities the camera never faced
    // drew markers anyway, scattered over the window: the "boxes everywhere but not on the npcs"
    // report. The honest screen test is the canvas itself -- the overlay is exactly the canvas
    // window's client rect -- so anything outside it (64px margin covers a box + label hanging half
    // off the edge) is not visible and must not draw. Needs canvasWindow() and the CAMERA_*/VIEW_*
    // offsets (offsets.hpp, both NOT VERIFIED in-game).
    HWND cw = canvasWindow();
    int cwW = 0, cwH = 0;
    if (cw && IsWindow(cw)) {
        RECT r{};
        GetClientRect(cw, &r);
        cwW = r.right - r.left;
        cwH = r.bottom - r.top;
    }
    if (cwW <= 0 || cwH <= 0) return static_cast<jlong>(0x8000000000000000ULL);
    if (sx < -64.f || sy < -64.f || sx > cwW + 64.f || sy > cwH + 64.f)
        return static_cast<jlong>(0x8000000000000000ULL);

    // Off unless OXC_LOG is set -- the same gate the launcher's input diagnostics print behind
    // (dllmain.cpp redirects stdout to the file it names). Unconditional here meant one line per
    // second in every session, which is noise nobody asked for. Once enabled, it MEASUREs the space
    // instead of arguing about it. What the first live run (2026-09-05) settled and what it left:
    //
    //   SETTLED: the camera ints are scene-fine (within a tile of the player's sceneX<<7), and the
    //   leaf's final rescale is identity -- view= printed x1606/1606 y900/900 once the pairs were
    //   read in the roles offsets.hpp gives them (the first version of this line paired +0x60 over
    //   +0x5C, a height over a width, and read as a bogus 0.56 factor). No ratio correction belongs
    //   anywhere in the projection path; if view= ever prints a non-1 ratio, THAT is the correction.
    //
    //   OPEN: whether the point lands on the character. The first version projected the tile's
    //   SOUTH-WEST CORNER (sceneX<<7, no +64), which sits ~70 px off the centre at the logged depth
    //   and so could not be judged; this one projects the CENTRE, like every real caller does.
    //   And every caller projects at height 0, which is the client's height datum, not the ground
    //   (game.hpp projectFine): the camera height moved ~50 units between three nearby spots at
    //   fixed pitch/zoom, so the terrain is not at 0 everywhere. The h-sweep line projects the
    //   centre at several candidate heights next to the current mouse position (canvas space, same
    //   as nInput): hover your own feet and the h whose y matches the cursor IS the ground height
    //   there. No offset for a tile-height reader is derived; this is how to size the residual.
    //
    //   Also printed: plane as game.hpp reads it plus the raw ints at entity+0x420 (ENTITY_PLANE,
    //   SUSPECT) and +0x7CC (the decompile's candidate) -- read-only, so the offsets.hpp staircase
    //   check (walk up a floor, see which one steps 0..3) can be done from the log; and the nearest
    //   NPC's uid / def pointer / raw *(int*)def / name, so npcTypeId's unverified layout can be
    //   checked against a known NPC (a Banker should read a small id and "Banker").
    // This is diagnosis for offsets that have never been verified in-game -- delete the probe once
    // the boxes sit on the NPCs.
    // Throttled by WALL CLOCK, not by call count: nProject runs once per projected point, and a
    // frame projects five points per NPC with tiles on, one per player, and 104x104 tiles on a
    // right-click -- so "% 300" fired nearly every frame in a bank and 36 times in one right-click
    // frame, each burst walking the registry twice on the frame thread (review, 2026-09-05).
    static ULONGLONG lastBurst = 0;
    static bool probeEnabled = ::getenv("OXC_LOG") != nullptr;
    const ULONGLONG nowMs = GetTickCount64();
    if (probeEnabled && nowMs - lastBurst >= 10000) {   // one burst per 10 s: a trace, not a firehose
        lastBurst = nowMs;
        bool found = false;
        Entity me = localPlayer(found);
        const int cx = found ? (me.sceneX << 7) + 64 : 0;   // tile CENTRE, like Game.projectTile
        const int cy = found ? (me.sceneY << 7) + 64 : 0;
        float mx = 0.f, my = 0.f;
        if (found && projectFine(cx, 0, cy, mx, my)) {
            int camX = 0, camH = 0, camY = 0, inW = 0, inH = 0, outW = 0, outH = 0;
            std::uintptr_t c = clientObj();
            if (c) {
                camX = rd<std::int32_t>(c + off::CAMERA_FINE_X);
                camH = rd<std::int32_t>(c + off::CAMERA_FINE_H);
                camY = rd<std::int32_t>(c + off::CAMERA_FINE_Y);
                std::uintptr_t v10 = rdp(c + off::VIEW_OBJ) + off::VIEW_OBJ_SCALE_BASE;
                inW  = rd<std::int32_t>(v10 + off::VIEW_IN_W);
                inH  = rd<std::int32_t>(v10 + off::VIEW_IN_H);
                outW = rd<std::int32_t>(v10 + off::VIEW_OUT_W);
                outH = rd<std::int32_t>(v10 + off::VIEW_OUT_H);
            }
            // Both raw plane candidates, unguarded, so the staircase check can be read off the log
            // (game.hpp prefers ENTITY_PLANE_COORD when it is 0..3 -- `plane=` shows the winner).
            const int raw420 = rd<std::int32_t>(me.addr + off::ENTITY_PLANE, -1);        // 0x420, SUSPECT
            const int raw7CC = rd<std::int32_t>(me.addr + off::ENTITY_PLANE_COORD, -1);  // 0x7CC, decompile
            oxc::logf("[proj] you@scene(%d,%d) centre -> (%.1f,%.1f) canvas=%dx%d cam=(%d,%d,%d) "
                     "cam-you=(%d,%d,%d) view=x%d/%d y%d/%d plane=%d raw420=%d raw7CC=%d\n",
                     me.sceneX, me.sceneY, mx, my, cwW, cwH, camX, camH, camY,
                     camX - cx, camH, camY - cy, outW, inW, outH, inH, me.plane, raw420, raw7CC);

            // Height sweep at the centre, next to the mouse (canvas space, as nInput reports it).
            POINT mp{ -1, -1 };
            if (GetCursorPos(&mp)) ScreenToClient(cw, &mp);
            char sweep[256];
            int n = std::snprintf(sweep, sizeof sweep, "[proj] h-sweep mouse=(%ld,%ld)", mp.x, mp.y);
            for (int h : { 0, -100, -200, -300, -400 }) {
                float hx = 0.f, hy = 0.f;
                if (n < static_cast<int>(sizeof sweep) && projectFine(cx, h, cy, hx, hy))
                    n += std::snprintf(sweep + n, sizeof sweep - n, " h%d->(%.0f,%.0f)", h, hx, hy);
            }
            oxc::logf("%s\n", sweep);

            // Render-position candidates on the local player's own struct. Live 2026-09-05 the tile
            // centre at datum height 0 drew ~290 canvas px below the character's feet, and the sweep
            // put the ground there at about -390: the client positions its models from a heightmap
            // this DLL cannot read yet. The entity struct must hold the model's own fine position to
            // render it, so this scans the struct for ints/floats within a tile of the fine x and y
            // we already trust (ENTITY_SCENE_X/Y << 7) and for plausible heights (-1500..-50). A triple
            // (x, h, y) at neighbouring offsets is the render position; the height that tracks a
            // staircase in the log is the offset to promote into offsets.hpp (NOT VERIFIED until
            // then). Anchored on two distinctive known values, not a byte pattern; capped so one
            // burst stays readable.
            {
                const int fx = (me.sceneX << 7) + 64, fy = (me.sceneY << 7) + 64;
                char cand[1024];
                int n2 = std::snprintf(cand, sizeof cand, "[proj] me@%p cands:", reinterpret_cast<void*>(me.addr));
                int shown = 0;
                for (std::uintptr_t o = 0; o < 0x1000 && shown < 40 && n2 < static_cast<int>(sizeof cand) - 40; o += 4) {
                    const std::int32_t v = rd<std::int32_t>(me.addr + o, 0x7FFFFFFF);
                    if (v == 0x7FFFFFFF) continue;
                    float f; std::memcpy(&f, &v, 4);
                    const bool fOk = f == f && f > -1.0e6f && f < 1.0e6f;
                    const char* tag = nullptr; double val = 0;
                    if (std::abs(v - fx) <= 128)                { tag = "xi"; val = v; }
                    else if (std::abs(v - fy) <= 128)           { tag = "yi"; val = v; }
                    else if (v >= -1500 && v <= -50)            { tag = "hi"; val = v; }
                    else if (fOk && std::fabs(f - fx) <= 128.f) { tag = "xf"; val = f; }
                    else if (fOk && std::fabs(f - fy) <= 128.f) { tag = "yf"; val = f; }
                    else if (fOk && f >= -1500.f && f <= -50.f && std::fabs(f - std::floor(f)) < 0.001f) { tag = "hf"; val = f; }
                    if (!tag) continue;
                    n2 += std::snprintf(cand + n2, sizeof cand - n2, " +%llx:%s=%.0f",
                                        static_cast<unsigned long long>(o), tag, val);
                    ++shown;
                }
                oxc::logf("%s%s\n", cand, shown >= 40 ? " ..." : "");
            }

            // Nearest NPC, for the typeId layout check. One extra registry walk per burst.
            bool haveNpc = false;
            Entity npc;
            int best = 1 << 30;
            forEachEntity([&](const Entity& e) {
                if (e.player) return;
                int d = std::abs(e.sceneX - me.sceneX) + std::abs(e.sceneY - me.sceneY);
                if (d < best) { best = d; npc = e; haveNpc = true; }
            });
            if (haveNpc) {
                std::uintptr_t def = rdp(npc.addr + off::ENTITY_DEF_PTR);
                oxc::logf("[proj] nearest npc uid=%d def=%p rawId=%d id=%d name=\"%s\" dist=%d\n",
                         npc.uid, reinterpret_cast<void*>(def), def ? rd<std::int32_t>(def, -1) : -1,
                         npcTypeId(npc.addr), npcName(npc.addr).c_str(), best);
                // The two NxtStrings the name comes from, as raw bytes: the name read "" live on
                // 2026-09-05 for an NPC with a valid id (6521), so DEF_NAME / ENTITY_NAME_OVERRIDE (or
                // the inline/heap flag convention at +0x17) is what these 24+24 bytes are for judging.
                auto dump = [](const char* what, std::uintptr_t at) {
                    char line[256];
                    int n = std::snprintf(line, sizeof line, "[proj]   %s @%p:", what, reinterpret_cast<void*>(at));
                    for (int i = 0; i < 24 && n < static_cast<int>(sizeof line) - 4; ++i)
                        n += std::snprintf(line + n, sizeof line - n, " %02x", rd<std::uint8_t>(at + i, 0));
                    oxc::logf("%s\n", line);
                };
                dump("override", npc.addr + off::ENTITY_NAME_OVERRIDE);
                if (def) dump("def+name", def + off::DEF_NAME);
                // Some NPCs read "" at def+DEF_NAME while most read fine (live 2026-09-06: ids 5885
                // and 6521) -- the shape of a TRANSFORM npc, whose base definition is nameless and
                // whose varbit-chosen child carries the name. The client must hold the resolved child
                // somewhere to draw it; this scans the entity for 8-byte-aligned pointers to anything
                // whose +DEF_NAME reads as printable text, and prints offset + name. A hit that is not
                // ENTITY_DEF_PTR is the resolved-definition pointer to promote into offsets.hpp.
                if (npcName(npc.addr).empty()) {
                    char line[768];
                    int n = std::snprintf(line, sizeof line, "[proj]   resolved-def cands:");
                    int shown = 0;
                    for (std::uintptr_t o = 0; o < 0x800 && shown < 10 && n < static_cast<int>(sizeof line) - 96; o += 8) {
                        std::uintptr_t ptr = rdp(npc.addr + o);
                        if (!ptr || ptr == def || !readable(ptr + off::DEF_NAME, 0x18)) continue;
                        std::string nm = nxtString(ptr + off::DEF_NAME);
                        if (nm.size() < 3 || nm.size() > 40) continue;
                        bool print = true;
                        for (unsigned char ch : nm) if (ch < 0x20 || ch > 0x7E) { print = false; break; }
                        if (!print) continue;
                        n += std::snprintf(line + n, sizeof line - n, " +%llx->\"%s\"(id %d)",
                                           static_cast<unsigned long long>(o), nm.c_str(), rd<std::int32_t>(ptr, -1));
                        ++shown;
                    }
                    oxc::logf("%s\n", line);
                    // Nothing on the entity: the transform must be resolved through the DEFINITION.
                    // Walk the base def for pointers to other named defs (direct children) and for
                    // pointers to arrays of such pointers (a child table), printing offset -> name(id).
                    // The varbit/varp that picks among them is the next thing to find once the table's
                    // offset is known; until then a nameless base with children can at least show one.
                    if (def) {
                        char l2[1024];
                        int n2 = std::snprintf(l2, sizeof l2, "[proj]   def child cands:");
                        int shown2 = 0;
                        auto named = [&](std::uintptr_t ptr, std::string& out) -> bool {
                            if (!ptr || ptr == def || !readable(ptr + off::DEF_NAME, 0x18)) return false;
                            out = nxtString(ptr + off::DEF_NAME);
                            if (out.size() < 3 || out.size() > 40) return false;
                            for (unsigned char ch : out) if (ch < 0x20 || ch > 0x7E) return false;
                            return true;
                        };
                        for (std::uintptr_t o = 0; o < 0x400 && shown2 < 12 && n2 < static_cast<int>(sizeof l2) - 120; o += 8) {
                            std::uintptr_t ptr = rdp(def + o);
                            std::string nm;
                            if (named(ptr, nm)) {
                                n2 += std::snprintf(l2 + n2, sizeof l2 - n2, " +%llx->%s(%d)",
                                                    static_cast<unsigned long long>(o), nm.c_str(), rd<std::int32_t>(ptr, -1));
                                ++shown2;
                            } else if (ptr && readable(ptr, 0x40)) {
                                // one level down: an array of pointers?
                                for (int k = 0; k < 8 && shown2 < 12; ++k) {
                                    std::uintptr_t pk = rdp(ptr + k * 8);
                                    if (named(pk, nm)) {
                                        n2 += std::snprintf(l2 + n2, sizeof l2 - n2, " +%llx[%d]->%s(%d)",
                                                            static_cast<unsigned long long>(o), k, nm.c_str(), rd<std::int32_t>(pk, -1));
                                        ++shown2;
                                    }
                                }
                            }
                        }
                        oxc::logf("%s\n", l2);
                    }
                }
                // DEF_NAME (+0x8) read an EMPTY inline string live for NPC 6521 (2026-09-05), so the
                // name lives elsewhere on this build's definition. Scan the definition for anything
                // shaped like an NxtString holding printable text -- inline (flag byte at +0x17 <=
                // 0x17, text at +0) or heap (flag & 0x80, pointer at +0, length at +8) -- and print
                // the offset and text. The one that reads the NPC's real name is DEF_NAME.
                if (def) {
                    char line[1024];
                    int n = std::snprintf(line, sizeof line, "[proj]   def strings:");
                    int shown = 0;
                    for (std::uintptr_t o = 0; o < 0x400 && shown < 12 && n < static_cast<int>(sizeof line) - 80; o += 8) {
                        std::string s = nxtString(def + o);
                        if (s.size() < 3 || s.size() > 60) continue;
                        bool print = true;
                        for (unsigned char ch : s) if (ch < 0x20 || ch > 0x7E) { print = false; break; }
                        if (!print) continue;
                        n += std::snprintf(line + n, sizeof line - n, " +%llx=\"%s\"",
                                           static_cast<unsigned long long>(o), s.c_str());
                        ++shown;
                    }
                    oxc::logf("%s\n", line);
                }
            }
            std::fflush(stdout);
        }
    }

    jlong x = static_cast<jlong>(static_cast<jint>(sx));
    jlong y = static_cast<jlong>(static_cast<jint>(sy));
    return (x << 32) | (y & 0xFFFFFFFFLL);
}

/// Perform a menu action. SCENE coordinates. See game.hpp for why this is the only way we act.
///
/// Returns false when the action was NOT issued: DO_ACTION is 0 for this build (the address was never
/// derived -- calling a guessed address would crash the game) or the client object is not up yet. The
/// game-side call is then a silent no-op, so this boolean is the caller's only signal that nothing
/// happened; plugins must not report success on it.
inline jboolean JNICALL nDoAction(JNIEnv*, jclass, jint sx, jint sy, jint opcode, jint targetId) {
    return doAction(sx, sy, opcode, targetId) ? JNI_TRUE : JNI_FALSE;
}

/// Interact with an NPC by uid, looking its tile up for you. Returns false when the uid did not
/// resolve (it despawned this frame) or the action was dropped -- see nDoAction.
inline jboolean JNICALL nInteractNpc(JNIEnv*, jclass, jint uid, jint opcode) {
    return interactNpc(uid, opcode) ? JNI_TRUE : JNI_FALSE;
}

/// Take an item option: `op` (1..10) on the item in `slot` of interface component `widgetId`. Queued
/// like every action (actions.hpp: itemAction); false when it was dropped.
inline jboolean JNICALL nItemAction(JNIEnv*, jclass, jint widgetId, jint slot, jint op, jint itemId) {
    return itemAction(widgetId, slot, op, itemId) ? JNI_TRUE : JNI_FALSE;
}

/// The scenery within `radius` tiles of you on your floor, four ints each, flattened:
/// {id, sceneX, sceneY, layer} -- the origin tile in scene coordinates like entities(), layer 2 for a
/// game object (trees, booths) and 0 for a wall (doors, gates). Empty before you spawn or when the
/// scene grid is not readable (scenery.hpp).
inline jintArray JNICALL nLocs(JNIEnv* env, jclass, jint radius) {
    bool found = false;
    const Entity me = localPlayer(found);
    if (!found) return env->NewIntArray(0);
    const int r = radius < 1 ? 1 : radius > 104 ? 104 : radius;
    std::vector<jint> flat;
    flat.reserve(256);
    const int n = forEachLoc(me.plane >= 0 ? me.plane : 0, me.sceneX, me.sceneY, r, [&](const Loc& l) {
        flat.push_back(l.id);
        flat.push_back(l.sceneX);
        flat.push_back(l.sceneY);
        flat.push_back(l.layer);
    });
    static bool logged = false;
    if (!logged) {
        logged = true;
        oxc::logf("[scenery] first read: %d game objects within %d tiles of scene (%d,%d) plane %d\n",
                  n, r, me.sceneX, me.sceneY, me.plane);
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (arr && !flat.empty()) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}

inline jstring gameBytesToJString(JNIEnv* env, const std::string& s);   // defined below

/// A loc's name from the client's definition cache ("Tree"), or "" when it is not cached right now.
inline jstring JNICALL nLocName(JNIEnv* env, jclass, jint id) {
    return gameBytesToJString(env, locName(id));
}

/// A loc's five options, newline-separated ("Chop down\n\n\n\n" -- option n is line n), or "" when the
/// definition is not cached right now.
inline jstring JNICALL nLocOptions(JNIEnv* env, jclass, jint id) {
    const auto ops = locOptions(id);
    std::string joined;
    bool any = false;
    for (int i = 0; i < 5; ++i) {
        if (i) joined += '\n';
        joined += ops[i];
        any = any || !ops[i].empty();
    }
    return gameBytesToJString(env, any ? joined : std::string{});
}

/// Take option `op` (1..5) on scenery object `id` at SCENE tile (sx, sy). Queued; false when dropped.
inline jboolean JNICALL nObjectAction(JNIEnv*, jclass, jint sx, jint sy, jint id, jint op) {
    return objectAction(sx, sy, id, op) ? JNI_TRUE : JNI_FALSE;
}

/// The game's client area on screen: {x, y, width, height}. Java needs the size to make its image and
/// the position to park the control panel beside the game.
inline jintArray JNICALL nViewport(JNIEnv* env, jclass) {
    jintArray arr = env->NewIntArray(4);
    HWND w = canvasWindow();
    if (!arr || !w || !IsWindow(w)) return arr;
    RECT r{};
    GetClientRect(w, &r);
    POINT tl{ r.left, r.top };
    ClientToScreen(w, &tl);
    jint v[4] = { tl.x, tl.y, r.right - r.left, r.bottom - r.top };
    env->SetIntArrayRegion(arr, 0, 4, v);
    return arr;
}

/// Put a finished frame on the screen. `px` is w*h premultiplied ARGB pixels, top row first.
///
/// GetPrimitiveArrayCritical rather than GetIntArrayElements: the former hands back a pointer to the
/// array's real storage instead of copying eight megabytes we are about to copy again. The window
/// between the two calls must contain nothing that could block or allocate, which is why the only
/// thing in it is the memcpy into a DIB that ALREADY exists -- ensure() (CreateDIBSection on a resize)
/// and the GDI present (UpdateLayeredWindow, an X11 round trip under Wine that can take milliseconds)
/// both happen after ReleasePrimitiveArrayCritical. Holding the critical region across them would
/// stall every Java thread, the frame thread mid-tick included, for the length of a GDI call.
inline void JNICALL nPresent(JNIEnv* env, jclass, jintArray px, jint w, jint h) {
    if (!px || w <= 0 || h <= 0) return;
    if (env->GetArrayLength(px) < w * h) return;          // never trust a length we did not compute
    if (!g_overlay.ensure(w, h)) return;                  // may allocate a new DIB: outside the critical

    void* raw = env->GetPrimitiveArrayCritical(px, nullptr);
    if (!raw) return;
    std::memcpy(g_overlay.pixels, raw, static_cast<std::size_t>(w) * h * 4);
    env->ReleasePrimitiveArrayCritical(px, raw, JNI_ABORT);   // ABORT: we did not modify it

    g_overlay.show();
}

/// The control panel's frame: same pixels-in contract as nPresent, but it lands on the panel window,
/// pinned to the game's right edge. See panel.hpp for why the panel is a second window.
inline void JNICALL nPresentPanel(JNIEnv* env, jclass, jintArray px, jint w, jint h) {
    if (!px || w <= 0 || h <= 0) return;
    if (env->GetArrayLength(px) < w * h) return;

    // Same split as nPresent: memcpy inside the critical region, GDI after it. The window check is
    // the one panel::copyIn would have done -- no panel (launcher mode) means no DIB to copy into.
    if (!panel::g_panel.hwnd || !IsWindow(panel::g_panel.hwnd)) return;
    if (!panel::g_panel.ensure(w, h)) return;

    void* raw = env->GetPrimitiveArrayCritical(px, nullptr);
    if (!raw) return;
    std::memcpy(panel::g_panel.pixels, raw, static_cast<std::size_t>(w) * h * 4);
    env->ReleasePrimitiveArrayCritical(px, raw, JNI_ABORT);

    panel::blit_last();
}

/// Mouse, modifier keys and keyboard edges, all read from Windows rather than game memory:
///     {mouseX, mouseY, shift, ctrl, alt, leftButton, rightButton, middleButton, vk1, vk2, ...}
///
/// mouseX/mouseY are client-area coordinates of the game window (Java's canvas space), so this needs
/// no offset at all -- the same window handle nViewport already uses. Held down = 1. GetAsyncKeyState's
/// short is signed; mask it rather than comparing, since the high bit is the "pressed since last call"
/// bit and comparing the raw value breaks the moment the process has seen any other key.
///
/// The trailing entries are KEY EDGES: the virtual-key codes that went from up to down since the
/// previous call, so Java can dispatch real KeyEvents for plugin hotkeys. The game's own key handling
/// is untouched -- this is a global poll, the same one every bit of Windows software does. Two honest
/// costs: keys typed into the game's chat also show up here (Java filters nothing, hotkeys can fire
/// while you type -- matching RuneLite without its focus widget is a later problem), and the scan is
/// per-frame global state, so a press is seen by whichever frame runs next, ~33ms later at worst.
// ---------------------------------------------------------------------------------------------------
// Button-press LATCH. nInput samples GetAsyncKeyState once per overlay frame (~33 ms), so a click that
// is pressed AND released between two samples -- a fast human right-click, or any synthetic one -- was
// never seen at all: the shim's popup (oxclient.rl.MenuPopup) keys off the up->down edge and simply did
// not open (live 2026-09-06: a shift+right-click reached the game's own menu and nothing of ours).
// A WH_MOUSE hook on the game's window thread sees every WM_xBUTTONDOWN the game itself receives, so
// it records "pressed since the last snapshot" plus the shift state AT the press -- the popup needs
// shift as it was when the user clicked, not 30 ms later. Read-and-cleared by nInput. The hook is
// in-process (we are a DLL in the game), does nothing but one atomic store, and always calls on.
// ---------------------------------------------------------------------------------------------------
// The right button's latch carries its shift state IN THE SAME WORD: bit0 = pressed since the last
// snapshot, bit1 = shift was held AT that press. Two separate atomics could not be read as a pair --
// nInput exchanged the latch and then loaded the shift flag, so a plain right-click landing between
// the hook's two stores was reported with the PREVIOUS click's shift ("Set target" offered on a click
// that never held shift). One store, one exchange, no window (review 2026-09-06).
constexpr int RB_PRESSED = 1 << 0;
constexpr int RB_SHIFT   = 1 << 1;
inline std::atomic<int> g_lbLatch{0}, g_rbLatch{0};
inline HHOOK g_mouseHook = nullptr;
inline DWORD g_mouseHookTid = 0;      // the thread g_mouseHook is on; NXT can move the window to another

inline LRESULT CALLBACK mouseLatchHook(int code, WPARAM w, LPARAM l) {
    if (code >= 0) {
        if (w == WM_LBUTTONDOWN) g_lbLatch.store(1);
        else if (w == WM_RBUTTONDOWN) {
            g_rbLatch.store(RB_PRESSED | ((GetKeyState(VK_SHIFT) & 0x8000) ? RB_SHIFT : 0));
            // Every latched right press, with the message that caused it: an ordinary LEFT click was
            // seen opening the right-click popup live (2026-09-06), and this line is what says whether
            // the hook is mislabelling a message or something downstream invents the press.
            oxc::logf("[input] latch: RBUTTONDOWN (msg 0x%x)\n", static_cast<unsigned>(w));
        }
    }
    return CallNextHookEx(g_mouseHook, code, w, l);
}

/// Install the latch hook on the thread that owns `gameWindow`. hMod is NULL on purpose: a thread
/// hook whose procedure lives in the current process must pass NULL (SetWindowsHookEx docs).
inline void installMouseLatch(HWND gameWindow) {
    if (!gameWindow || !IsWindow(gameWindow)) return;
    DWORD tid = GetWindowThreadProcessId(gameWindow, nullptr);
    if (!tid) return;
    // A WH_MOUSE hook is per THREAD. When NXT recreates its window during boot the new one can belong
    // to a different thread, and the old hook then latches nothing -- the right-click popup would stop
    // opening for the rest of the session (review 2026-09-06, introduced with the recreation fix).
    // Same thread: keep what we have. Different thread: move the hook.
    if (g_mouseHook) {
        if (tid == g_mouseHookTid) return;
        UnhookWindowsHookEx(g_mouseHook);
        g_mouseHook = nullptr;
    }
    g_mouseHookTid = tid;
    g_mouseHook = SetWindowsHookExW(WH_MOUSE, mouseLatchHook, nullptr, tid);
    oxc::logf("[input] mouse latch hook %s (thread %lu)\n", g_mouseHook ? "installed" : "FAILED",
             static_cast<unsigned long>(tid));
}

inline jintArray JNICALL nInput(JNIEnv* env, jclass) {
    jint v[8 + 16];
    int n = 8;
    v[0] = v[1] = v[2] = v[3] = v[4] = v[5] = v[6] = v[7] = 0;
    // Measured against the same window the projection answers in (canvasWindow): mouse coordinates
    // and marker coordinates must share one space, or the popup draws next to the cursor.
    if (canvasWindow() && IsWindow(canvasWindow())) {
        POINT p{};
        if (GetCursorPos(&p)) {
            ScreenToClient(canvasWindow(), &p);
            v[0] = p.x;
            v[1] = p.y;
        }
    }
    // Held right now, OR pressed since the last snapshot (the latch above): a press shorter than a
    // frame still shows as down for exactly one snapshot, which is the edge the popup needs. Shift
    // is reported as it was AT the right-click when the latch fires, so a quick shift+right-click
    // reaches the plugins as shift-held even if shift was let go before this sample.
    const int lbLatched = g_lbLatch.exchange(0);
    const int rbState   = g_rbLatch.exchange(0);          // pressed + shift-at-press in one read
    const int rbLatched = (rbState & RB_PRESSED) != 0;
    v[2] = ((GetAsyncKeyState(VK_SHIFT) & 0x8000) || (rbState & RB_SHIFT)) ? 1 : 0;
    v[3] = (GetAsyncKeyState(VK_CONTROL) & 0x8000) ? 1 : 0;
    v[4] = (GetAsyncKeyState(VK_MENU) & 0x8000)    ? 1 : 0;
    v[5] = ((GetAsyncKeyState(VK_LBUTTON) & 0x8000) || lbLatched) ? 1 : 0;
    v[6] = ((GetAsyncKeyState(VK_RBUTTON) & 0x8000) || rbLatched) ? 1 : 0;
    v[7] = (GetAsyncKeyState(VK_MBUTTON) & 0x8000) ? 1 : 0;

    // Edge detection against last frame. 0x08-0xFF are the keys (the buttons are the fixed fields
    // above); 0xFF is unassigned. Static, not per-window: keyboard state is global in Windows anyway.
    // prevDown updates for EVERY key regardless of buffer space -- if it stopped early, a key still
    // held next frame would look like a fresh edge and re-fire.
    static bool prevDown[256] = {};
    for (int vk = 0x08; vk < 0xFF; ++vk) {
        bool down = (GetAsyncKeyState(vk) & 0x8000) != 0;
        if (down && !prevDown[vk] && n < 8 + 16) v[n++] = vk;
        prevDown[vk] = down;
    }

    jintArray arr = env->NewIntArray(n);
    if (arr) env->SetIntArrayRegion(arr, 0, n, v);
    return arr;
}

// ---------------------------------------------------------------------------------------------------
// Input INTO the game (2026-09-05, NOT yet exercised live -- oxclient.plugins.AutoLogin is the first user).
//
// Everything here is PostMessageW to NXT's JagRenderView child, nothing else. Why that and not
// SendInput: SendInput is delivered to whatever window is FOREGROUND and focused. Our queues are
// attached to the game's (dllmain attachInput), so normally that is the game -- but if the user has
// alt-tabbed to another application while autologin is typing, SendInput would type the PASSWORD
// into that application. PostMessageW is targeted at one hwnd, thread-agnostic, never blocks (it
// fails only for an invalid hwnd or a full message queue) and needs no focus at all.
//
// Why text goes as WM_CHAR and never as WM_KEYDOWN: a read-only import scan of osclient.exe
// (client-240-6, 2026-09-05) shows TranslateMessage/DispatchMessage/GetMessage/PeekMessage imported
// and NO RegisterRawInputDevices/GetRawInputData/ToUnicode/GetKeyboardState/MapVirtualKey/VkKeyScan/
// SendInput -- NXT reads typed text as the WM_CHAR its own TranslateMessage produces, so a posted
// WM_CHAR lands in the same handler as real typing. A posted WM_KEYDOWN for a letter would be
// TranslateMessage'd using the thread's PHYSICAL shift/caps state (posted key messages do not touch
// the key-state table), giving the wrong case for a mixed-case password, AND it would emit a second
// WM_CHAR, doubling every character. Tab/Enter/Backspace DO go as WM_KEYDOWN/WM_KEYUP: that is
// exactly the real sequence, and NXT's TranslateMessage supplies the tab/return/backspace WM_CHAR
// itself.
//
// Posted messages never show up in GetAsyncKeyState, so nothing injected here can feed back into
// pollKeys/nInput hotkeys. The one open risk: NXT imports GetAsyncKeyState (purpose unknown); if it
// validates mouse clicks against physical button state, posted clicks are ignored. The autologin
// script therefore defaults to Tab rather than clicks, and the live log shows whether state moved.
// A SendInput fallback is deliberately NOT here; if one is ever added it must check
// GetForegroundWindow() root == GetAncestor(target, GA_ROOT) && GetFocus() == target immediately
// before EVERY call and abort the whole attempt otherwise.
// ---------------------------------------------------------------------------------------------------

/// The window that receives injected input: the JagRenderView child (NXT's keyboard/mouse window,
/// the one dllmain SetFocus()es), falling back to the game root. Logged once per change of target so
/// the live log proves which window the messages went to.
inline HWND inputTarget() {
    HWND t = canvasWindow();
    if (!(t && t != g_gameWindow)) {
        HWND rv = g_gameWindow ? FindWindowExW(g_gameWindow, nullptr, L"JagRenderView", nullptr) : nullptr;
        t = rv ? rv : g_gameWindow;
    }
    static HWND logged = nullptr;
    if (t != logged) {
        logged = t;
        wchar_t cls[64] = L"";
        if (t && IsWindow(t)) GetClassNameW(t, cls, 64);
        oxc::logf("[input] target %p class=%ls\n", static_cast<void*>(t), cls);
    }
    return t;
}

/// WM_KEYDOWN / WM_KEYUP lParam as the keyboard driver would build it: repeat count 1, scan code in
/// bits 16-23; for the up message also bit 30 (previous state down) and bit 31 (transition).
inline LPARAM keyLParam(UINT vk, bool down) {
    UINT scan = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
    LPARAM l = 1 | static_cast<LPARAM>(scan & 0xFF) << 16;
    if (!down) l |= (static_cast<LPARAM>(1) << 30) | (static_cast<LPARAM>(1) << 31);
    return l;
}

/// Post one UTF-16 code unit as WM_CHAR. Text goes as WM_CHAR and never as WM_KEYDOWN: NXT's own
/// TranslateMessage would derive the case from the PHYSICAL shift/caps state and emit a second
/// WM_CHAR (osclient.exe imports TranslateMessage and no ToUnicode/GetKeyboardState -- import scan
/// 2026-09-05). Logs nothing about the character: this is the password path. Earns its place in the
/// unsafe surface because only the DLL knows the target hwnd and the lParam scan-code bits.
inline jboolean JNICALL nPostChar(JNIEnv*, jclass, jint ch) {
    HWND t = inputTarget();
    if (!t || !IsWindow(t)) return JNI_FALSE;
    SHORT vks = VkKeyScanW(static_cast<WCHAR>(ch));
    UINT scan = vks == -1 ? 0 : MapVirtualKeyW(LOBYTE(vks), MAPVK_VK_TO_VSC);
    LPARAM l = 1 | static_cast<LPARAM>(scan & 0xFF) << 16;
    return PostMessageW(t, WM_CHAR, static_cast<WPARAM>(ch), l) ? JNI_TRUE : JNI_FALSE;
}

/// Post WM_KEYDOWN (down) or WM_KEYUP (up) for a virtual key.
///
/// Tab and Backspace go as the bare key pair: live 2026-09-06 that alone moved between the login
/// fields and erased text, so NXT's own TranslateMessage is supplying their WM_CHAR and a second one
/// from us would Tab twice (back to the field we left). Enter and Escape did NOT act the same night
/// (the form never submitted: state stayed 10 with both fields typed), so for those two the WM_CHAR
/// ('\r' / 0x1B) is posted explicitly between down and up -- a duplicate Enter can only re-submit
/// the same form, a duplicate Escape only re-cancel. Which of the two the form actually listens to
/// is what the next live run tells.
inline jboolean JNICALL nPostKey(JNIEnv*, jclass, jint vk, jboolean down) {
    HWND t = inputTarget();
    if (!t || !IsWindow(t)) return JNI_FALSE;
    bool d = down != JNI_FALSE;
    BOOL ok = PostMessageW(t, d ? WM_KEYDOWN : WM_KEYUP, static_cast<WPARAM>(vk),
                           keyLParam(static_cast<UINT>(vk), d));
    if (ok && d && (vk == VK_RETURN || vk == VK_ESCAPE))
        PostMessageW(t, WM_CHAR, static_cast<WPARAM>(vk == VK_RETURN ? L'\r' : 0x1B),
                     keyLParam(static_cast<UINT>(vk), true));
    return ok ? JNI_TRUE : JNI_FALSE;
}

/// Mouse in CANVAS client coordinates (the space nInput/nViewport measure -- the same window, so no
/// conversion). action 0 move, 1 left down, 2 left up. Java sequences move -> down -> (next tick) up.
inline jboolean JNICALL nPostMouse(JNIEnv*, jclass, jint x, jint y, jint action) {
    HWND t = inputTarget();
    if (!t || !IsWindow(t)) return JNI_FALSE;
    LPARAM l = MAKELPARAM(x, y);
    switch (action) {
        case 0: return PostMessageW(t, WM_MOUSEMOVE, 0, l) ? JNI_TRUE : JNI_FALSE;
        case 1: return PostMessageW(t, WM_LBUTTONDOWN, MK_LBUTTON, l) ? JNI_TRUE : JNI_FALSE;
        case 2: return PostMessageW(t, WM_LBUTTONUP, 0, l) ? JNI_TRUE : JNI_FALSE;
        default: return JNI_FALSE;
    }
}

/// {targetExists, targetIsRenderView, focusIsTarget, gameIsForeground, canvasW, canvasH}. With grab,
/// SetFocus(target) first -- guarded by a 50 ms WM_NULL SendMessageTimeoutW probe, because a
/// cross-thread SetFocus over the attached queues hangs while NXT is not pumping (launcher gamePumps).
/// Diagnosis only: the posted-message path needs no focus; this is what the status line reports.
inline jintArray JNICALL nInputTarget(JNIEnv* env, jclass, jboolean grab) {
    HWND t = inputTarget();
    jint v[6] = {0, 0, 0, 0, 0, 0};
    if (t && IsWindow(t)) {
        wchar_t cls[32] = L"";
        GetClassNameW(t, cls, 32);
        v[0] = 1;
        v[1] = wcscmp(cls, L"JagRenderView") == 0 ? 1 : 0;
        if (grab) {
            DWORD_PTR ign = 0;
            if (SendMessageTimeoutW(t, WM_NULL, 0, 0, SMTO_ABORTIFHUNG, 50, &ign)) SetFocus(t);
            else oxc::logf("[input] game not pumping -- focus grab skipped\n");
        }
        v[2] = GetFocus() == t ? 1 : 0;
        HWND fg = GetForegroundWindow();
        v[3] = fg && GetAncestor(fg, GA_ROOT) == GetAncestor(t, GA_ROOT) ? 1 : 0;
        RECT r{};
        GetClientRect(t, &r);
        v[4] = r.right - r.left;
        v[5] = r.bottom - r.top;
    }
    jintArray arr = env->NewIntArray(6);
    if (arr) env->SetIntArrayRegion(arr, 0, 6, v);
    return arr;
}

/// The client's own state machine -- 10 title, 20 logging in, 25 loading, 30 logged in. This is the
/// field the game itself branches on (offsets.hpp GAME_STATE), so the shim's GameStateChanged events
/// fire on real transitions instead of a synthesised login sequence. 0 before the client object exists.
inline jint JNICALL nGameState(JNIEnv*, jclass) {
    return gameState();
}


/// Find where the client keeps a string we already know the value of, so a field can be located
/// without a decompiler: walk this process's committed read/write regions and return the addresses
/// whose bytes equal `needle` (Latin-1, the client's own encoding for these fields).
///
/// This is DIAGNOSIS, not a feature: it is how the login username's field was located live, and the
/// password field is its neighbour on the same object. It never returns or logs the bytes -- only
/// addresses -- so a caller may pass a secret and print the result. Capped at 64 hits and skipped
/// entirely for a needle under 3 bytes, which would match everywhere.
inline jlongArray JNICALL nFindString(JNIEnv* env, jclass, jstring needle) {
    std::vector<jlong> hits;
    if (needle) {
        const char* utf = env->GetStringUTFChars(needle, nullptr);
        if (utf) {
            std::string pat(utf);
            env->ReleaseStringUTFChars(needle, utf);
            if (pat.size() >= 3 && pat.size() <= 128) {
                SYSTEM_INFO si{};
                GetSystemInfo(&si);
                auto addr = reinterpret_cast<std::uintptr_t>(si.lpMinimumApplicationAddress);
                const auto maxAddr = reinterpret_cast<std::uintptr_t>(si.lpMaximumApplicationAddress);
                MEMORY_BASIC_INFORMATION mbi{};
                while (addr < maxAddr && hits.size() < 64 && VirtualQuery(reinterpret_cast<void*>(addr), &mbi, sizeof mbi)) {
                    const DWORD prot = mbi.Protect & 0xFF;
                    const bool rw = (mbi.State == MEM_COMMIT)
                                 && (prot == PAGE_READWRITE || prot == PAGE_EXECUTE_READWRITE)
                                 && !(mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS));
                    if (rw && mbi.RegionSize <= (256u << 20)) {
                        const auto* base = reinterpret_cast<const char*>(mbi.BaseAddress);
                        const std::size_t n = static_cast<std::size_t>(mbi.RegionSize);
                        for (std::size_t i = 0; i + pat.size() <= n && hits.size() < 64; ++i) {
                            if (base[i] == pat[0] && std::memcmp(base + i, pat.data(), pat.size()) == 0)
                                hits.push_back(static_cast<jlong>(reinterpret_cast<std::uintptr_t>(base + i)));
                        }
                    }
                    addr = reinterpret_cast<std::uintptr_t>(mbi.BaseAddress) + mbi.RegionSize;
                }
            }
        }
    }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(hits.size()));
    if (arr && !hits.empty()) env->SetLongArrayRegion(arr, 0, static_cast<jsize>(hits.size()), hits.data());
    return arr;
}

/// Bytes at an address, as a hex line, for identifying what a nFindString hit sits inside. Read
/// through the same guarded reader as every other native, so a bad address is "" and not a crash.
/// Never called with an address the client did not give us.
inline jstring JNICALL nPeek(JNIEnv* env, jclass, jlong at, jint len) {
    std::string out;
    const auto a = static_cast<std::uintptr_t>(at);
    const int n = (len < 1) ? 1 : (len > 64 ? 64 : len);
    if (a && readable(a, static_cast<std::size_t>(n))) {
        char b[4];
        for (int i = 0; i < n; ++i) {
            std::snprintf(b, sizeof b, "%02x", rd<std::uint8_t>(a + i, 0));
            out += b;
        }
    }
    return env->NewStringUTF(out.c_str());
}

/// Write one NUL-terminated login field. THE ONLY WRITE INTO GAME MEMORY IN THIS DLL.
///
/// The address comes from nFindString -- i.e. from a pattern match, not from a derived offset chain --
/// so it could be anything: another process object, our own JVM heap, a page that merely happens to
/// contain the same bytes. A wrong write there is a corrupted client at best and a corrupted JVM heap
/// at worst. So this refuses on every doubt and writes nothing:
///
///   1. cap in 1..256, a non-empty value that fits inside it (and no longer than 128 bytes).
///   2. The whole cap window is readable, through the same guarded reader as every other native.
///   3. The region is MEM_COMMIT and PAGE_READWRITE / PAGE_EXECUTE_READWRITE, with no guard page, and
///      the window does not run off the end of it. There is deliberately NO VirtualProtect: a buffer
///      that is not already writable is not the client's form, it is a mistake.
///   4. The bytes already there are either ALL ZERO (the empty password field -- the "Please enter
///      your password" state this exists for) or exactly `value` (the username field, which is how
///      the address was found in the first place; that write is a no-op that proves the address).
///   5. The value plus its terminator fits in the existing content plus the run of zeroes after it,
///      which is the only part of the buffer we have any evidence is ours to touch.
///   6. The buffer is not shaped like an inline NxtString (flag byte at +0x17 holding 0x17 - length).
///      Those carry their length in that byte and writing the text without it renders the OLD length;
///      this will not guess, it refuses and says so. The candidate pair found live is 508 bytes apart
///      with binary padding between, which is a fixed-buffer struct and not two 24-byte NxtStrings,
///      so this gate is a tripwire rather than the normal path.
///
/// It never logs the value, its length, or any byte of the buffer -- only the verdict.
///
/// Returns 0 on success; -1 bad argument, -2 not readable, -3 not writable, -4 unexpected content,
/// -5 no room, -6 inline NxtString.
inline jint JNICALL nSetLoginField(JNIEnv* env, jclass, jlong at, jstring value, jint cap) {
    if (!value || cap < 1 || cap > 256) return -1;
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (!utf) return -1;
    std::string v(utf);
    env->ReleaseStringUTFChars(value, utf);
    if (v.empty() || v.size() > 128 || static_cast<jint>(v.size()) + 1 > cap) return -1;

    const auto a = static_cast<std::uintptr_t>(at);
    const std::size_t window = static_cast<std::size_t>(cap);
    if (!oxc::readable(a, window)) return -2;

    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(reinterpret_cast<void*>(a), &mbi, sizeof mbi)) return -3;
    const DWORD prot = mbi.Protect & 0xFF;
    if (mbi.State != MEM_COMMIT) return -3;
    if (mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS)) return -3;
    if (prot != PAGE_READWRITE && prot != PAGE_EXECUTE_READWRITE) return -3;
    if (a + window > reinterpret_cast<std::uintptr_t>(mbi.BaseAddress) + mbi.RegionSize) return -3;

    auto* buf = reinterpret_cast<char*>(a);
    std::size_t existing = 0;
    while (existing < window && buf[existing] != '\0') ++existing;
    if (existing == window) return -4;                       // no terminator inside cap: not a field
    if (existing != 0 && (existing != v.size() || std::memcmp(buf, v.data(), v.size()) != 0)) return -4;

    std::size_t zeros = 0;
    while (existing + zeros < window && buf[existing + zeros] == '\0') ++zeros;
    const std::size_t room = existing + zeros;               // all we have any right to write over
    if (v.size() + 1 > room) return -5;

    // The NxtString tripwire: an inline one keeps 0x17 - length in the byte at +0x17, and that byte
    // has to move with the text. Only meaningful when it is inside the window we checked.
    constexpr std::size_t NXT_FLAG = 0x17;
    if (NXT_FLAG < window && existing <= NXT_FLAG
        && static_cast<unsigned char>(buf[NXT_FLAG]) == static_cast<unsigned char>(NXT_FLAG - existing)) {
        return -6;
    }

    std::memcpy(buf, v.data(), v.size());
    buf[v.size()] = '\0';
    oxc::logf("[loginfield] wrote a field at %p (verified writable, buffer was %s)\n",
             reinterpret_cast<void*>(a), existing == 0 ? "empty" : "already this value");
    return 0;
}

/// Bytes read out of the game, as a jstring, without ever handing JNI malformed modified-UTF-8
/// (NewStringUTF's contract; a stray high byte there is undefined behaviour in the VM). The client's
/// string encoding is NOT VERIFIED: offsets.hpp records that names are padded with U+00A0, which is
/// a lone 0xA0 byte if the strings are Latin-1 and C2 A0 if they are UTF-8. So: if the bytes are
/// valid UTF-8 they go through NewStringUTF unchanged; otherwise each byte is widened as Latin-1 and
/// the string is built from UTF-16 with NewString, which is exact for a single-byte encoding.
inline jstring gameBytesToJString(JNIEnv* env, const std::string& s) {
    if (s.empty()) return env->NewStringUTF("");
    bool utf8 = true;
    for (std::size_t i = 0; i < s.size() && utf8;) {
        unsigned char b = static_cast<unsigned char>(s[i]);
        // No 4-byte leads: NewStringUTF speaks MODIFIED UTF-8, where supplementary characters are
        // two 3-byte surrogates and a 4-byte form is malformed -- so those take the Latin-1 path.
        int extra = b < 0x80 ? 0 : (b & 0xE0) == 0xC0 ? 1 : (b & 0xF0) == 0xE0 ? 2 : -1;
        if (extra < 0 || i + static_cast<std::size_t>(extra) >= s.size()) { utf8 = false; break; }
        for (int k = 1; k <= extra; ++k)
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) { utf8 = false; break; }
        i += extra + 1;
    }
    if (utf8) return env->NewStringUTF(s.c_str());
    std::vector<jchar> wide(s.size());
    for (std::size_t i = 0; i < s.size(); ++i) wide[i] = static_cast<jchar>(static_cast<unsigned char>(s[i]));
    return env->NewString(wide.data(), static_cast<jsize>(wide.size()));
}


/// Every loaded interface component that carries text, as "group:component x,y w,h hidden text" lines.
///
/// This is how a plugin finds a button by its LABEL instead of by a pixel offset somebody measured on
/// one window size: "CLICK HERE TO PLAY" is a component, and its id is stable where a coordinate is
/// not. The walk is the same one widget() does (offsets.hpp: manager -> group array -> componentData),
/// just over every group rather than one id.
///
/// Capped at `max` lines and 32 KB. Called from a probe, not per frame: it walks the whole interface
/// tree. Text goes through the same Latin-1/UTF-8 path as every other string the natives return.
inline jstring JNICALL nDumpWidgetText(JNIEnv* env, jclass, jint max) {
    std::string out;
    int lines = 0;
    const int cap = (max < 1) ? 1 : (max > 4096 ? 4096 : max);
    std::uintptr_t c = clientObj();
    std::uintptr_t mgr = c ? rdp(c + off::IFACE_MANAGER) : 0;
    if (mgr) {
        const std::uint64_t gcount = rd<std::uint64_t>(mgr + off::IFACE_GROUP_COUNT);
        const std::uintptr_t garr = rdp(mgr + off::IFACE_GROUP_ARRAY);
        if (garr && gcount > 0 && gcount <= 0x1000) {
            for (std::uint64_t g = 0; g < gcount && lines < cap && out.size() < 32768; ++g) {
                const std::uintptr_t entry = garr + g * off::IFACE_GROUP_ENTRY_STRIDE;
                const std::uintptr_t data = rdp(entry + off::IFACE_GROUP_ENTRY_DATA);
                if (!data) continue;
                std::uint64_t ccount = rd<std::uint64_t>(entry + off::IFACE_GROUP_ENTRY_COUNT);
                if (ccount > 4096) ccount = 4096;
                for (std::uint64_t i = 0; i < ccount && lines < cap && out.size() < 32768; ++i) {
                    // +8: the component pointer sits in the SECOND half of the 16-byte shared_ptr
                    // entry, exactly as widgetObj() reads it. Reading +0 hands back the control block,
                    // and every component then measures 1x1 -- which is what made this walk report
                    // that the whole rectangle block was wrong (it is not; the walk was).
                    const std::uintptr_t w = rdp(data + i * 16 + 8);
                    if (!w || w == (off::IFACE_EMPTY_SENTINEL ? rdp(globalAddr(off::IFACE_EMPTY_SENTINEL) + 8) : 0)) continue;
                    std::string text = nxtString(w + off::IFTYPE_TEXT);
                    bool printable = !text.empty();
                    for (unsigned char ch : text) if (ch < 0x20 || ch > 0x7E) { printable = false; break; }
                    if (!printable || text.size() > 80) text.clear();
                    // A button can be a SPRITE with no text at all -- "CLICK HERE TO PLAY" is one
                    // (live 2026-09-06: the welcome screen has groups loaded and not one component
                    // carries text). So report every component that has a real rectangle and let the
                    // caller find the one whose rect contains a point it already knows works.
                    const int ww = rd<std::int32_t>(w + off::IFTYPE_WIDTH);
                    const int wh = rd<std::int32_t>(w + off::IFTYPE_HEIGHT);
                    if (ww <= 0 || wh <= 0 || ww > 4096 || wh > 4096) continue;
                    if (text.empty()) text = "-";
                    // x,y are CANVAS coordinates wherever the parent chain resolves, and the stored
                    // parent-relative pair only where it does not (widgetAbs falls back to exactly what
                    // widget() has always returned, and says so through `complete`). That one change is
                    // what makes oxclient.api.Widgets.smallestContaining work at all: it matches a point
                    // that is known good on the canvas against these rectangles, and against relative
                    // ones no component ever contained the point -- its own doc predicted that failure.
                    // The LINE FORMAT is deliberately untouched; oxclient.api.Widgets.parseLine reads it and
                    // is covered by tests.
                    const WidgetAbs abs = widgetAbs(static_cast<int>((g << 16) | i));
                    char line[256];
                    std::snprintf(line, sizeof line, "%llu:%llu %d,%d %dx%d %s %s\n",
                                  static_cast<unsigned long long>(g), static_cast<unsigned long long>(i),
                                  abs.x, abs.y,
                                  ww, wh,
                                  rd<std::uint8_t>(w + off::IFTYPE_HIDDEN) ? "hidden" : "shown", text.c_str());
                    out += line;
                    ++lines;
                }
            }
        }
    }
    return gameBytesToJString(env, out);
}

/// Which addresses inside the CLIENT OBJECT's first `span` bytes point at (or just before) `target`.
///
/// The discriminator a plain value scan cannot give: a string the game renders is reachable from the
/// client object, while an identical copy in the JVM heap is not. A hit here says "this buffer belongs
/// to a client structure, at this offset", which is exactly what goes in offsets.hpp. Read-only, and
/// it returns offsets, never bytes.
inline jintArray JNICALL nPointersTo(JNIEnv* env, jclass, jlong target, jint span, jint slack) {
    std::vector<jint> offs;
    const auto t = static_cast<std::uintptr_t>(target);
    const std::uintptr_t c = clientObj();
    const int n = (span < 8) ? 8 : (span > (1 << 20) ? (1 << 20) : span);
    const int s = (slack < 0) ? 0 : (slack > 4096 ? 4096 : slack);
    if (c && t) {
        for (int o = 0; o + 8 <= n && offs.size() < 64; o += 8) {
            const std::uintptr_t p = rdp(c + o);
            if (p && p <= t && t - p <= static_cast<std::uintptr_t>(s)) offs.push_back(o);
        }
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(offs.size()));
    if (arr && !offs.empty()) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(offs.size()), offs.data());
    return arr;
}


/// Where a component stores a rectangle: scan every loaded component's struct for two ints equal to
/// `w` and `h` (the canvas size, which a top-level interface matches) and report the offsets found.
///
/// STALE PREMISE, kept because the tool is still useful: this says it exists because IFTYPE_WIDTH/
/// HEIGHT "read 1 for every component". That claim was RETRACTED the same day it was made -- the probe
/// behind it had read the 16-byte shared_ptr entry at +0 (the control block) instead of +8 (the object),
/// and the rectangle block at 0x5C..0x68 is fine. What was actually wrong was that x/y are
/// PARENT-RELATIVE, which is now handled by widgetAbs, not by a different rect offset. The method here
/// -- "look for a value you already know" -- is still how an unknown offset gets derived without a
/// decompiler, so this stays; it is just no longer looking for something that is missing.
inline jstring JNICALL nFindWidgetRect(JNIEnv* env, jclass, jint w, jint h) {
    std::string out;
    int found = 0;
    std::uintptr_t c = clientObj();
    std::uintptr_t mgr = c ? rdp(c + off::IFACE_MANAGER) : 0;
    if (mgr) {
        const std::uint64_t gcount = rd<std::uint64_t>(mgr + off::IFACE_GROUP_COUNT);
        const std::uintptr_t garr = rdp(mgr + off::IFACE_GROUP_ARRAY);
        if (garr && gcount > 0 && gcount <= 0x1000) {
            for (std::uint64_t g = 0; g < gcount && found < 24; ++g) {
                const std::uintptr_t entry = garr + g * off::IFACE_GROUP_ENTRY_STRIDE;
                const std::uintptr_t data = rdp(entry + off::IFACE_GROUP_ENTRY_DATA);
                if (!data) continue;
                std::uint64_t ccount = rd<std::uint64_t>(entry + off::IFACE_GROUP_ENTRY_COUNT);
                if (ccount > 4096) ccount = 4096;
                for (std::uint64_t i = 0; i < ccount && found < 24; ++i) {
                    const std::uintptr_t comp = rdp(data + i * 16 + 8);   // +8: see nDumpWidgetText
                    if (!comp || comp == (off::IFACE_EMPTY_SENTINEL ? rdp(globalAddr(off::IFACE_EMPTY_SENTINEL) + 8) : 0)) continue;
                    // Look for w at some offset with h nearby (the usual {x,y,w,h} or {w,h} layout).
                    for (std::uintptr_t o = 0; o + 8 <= 0x400; o += 4) {
                        if (rd<std::int32_t>(comp + o, -1) != w) continue;
                        for (int d : { 4, 8, -4, 12 }) {
                            if (rd<std::int32_t>(comp + o + d, -1) != h) continue;
                            char line[160];
                            std::snprintf(line, sizeof line, "%llu:%llu w@+%llx h@+%llx\n",
                                          static_cast<unsigned long long>(g),
                                          static_cast<unsigned long long>(i),
                                          static_cast<unsigned long long>(o),
                                          static_cast<unsigned long long>(o + d));
                            out += line;
                            ++found;
                            break;
                        }
                        if (found >= 24) break;
                    }
                }
            }
        }
    }
    if (out.empty()) out = "(no component holds the canvas size -- try again while an interface is open)\n";
    return gameBytesToJString(env, out);
}

/// An entity's name by uid AND kind: player names come off the heap NxtString at entity+0x718, NPC
/// names off the definition's +0x8 (offsets.hpp). The kind is an argument because the uid alone is
/// ambiguous -- players and NPCs live in separate tables with separate keyspaces (game.hpp
/// findEntity). "" when it despawned or the read failed -- a name is cosmetic, it never blocks
/// anything. NOTE the name read itself was never exercised in-game before 2026-09-05 (the NxtString
/// flag byte was read from the wrong address -- game.hpp nxtString); the offsets are live-verified,
/// this function's output is not yet.
inline jstring JNICALL nEntityName(JNIEnv* env, jclass, jint uid, jboolean player) {
    bool found = false;
    Entity e = findEntity(uid, player == JNI_TRUE, found);
    std::string name = found ? (e.player ? playerName(e.addr) : npcName(e.addr)) : std::string{};
    return gameBytesToJString(env, name);
}

/// One widget's state: {ok, x, y, width, height, hidden}, or empty when the id is not loaded right
/// now -- which is constant for background groups, not an error. x/y are the values the widget stores
/// (relative to its parent for nested widgets); id is the client's own (group << 16) | component.
inline jintArray JNICALL nWidget(JNIEnv* env, jclass, jint id) {
    Widget w = widget(id);
    if (!w.ok) return env->NewIntArray(0);
    jint v[6] = { 1, w.x, w.y, w.width, w.height, w.hidden ? 1 : 0 };
    jintArray arr = env->NewIntArray(6);
    if (arr) env->SetIntArrayRegion(arr, 0, 6, v);
    return arr;
}

/// A widget's primary text line, exactly as the game stores it (colour tags and all -- the shim's
/// net.runelite.client.util.Text strips them where a plugin wants that). "" when not loaded.
inline jstring JNICALL nWidgetText(JNIEnv* env, jclass, jint id) {
    Widget w = widget(id);
    return gameBytesToJString(env, w.text);
}

/// A widget's dynamic child by index: {ok, x, y, width, height, hidden}, or empty when out of range.
/// x/y are relative to the parent -- Java accumulates them while descending, matching RuneLite's
/// absolute getCanvasLocation.
inline jintArray JNICALL nWidgetChild(JNIEnv* env, jclass, jint id, jint childIndex) {
    std::uintptr_t w = widgetChildObj(id, childIndex);
    if (!w) return env->NewIntArray(0);
    jint v[6] = {
        1,
        rd<std::int32_t>(w + off::IFTYPE_X),
        rd<std::int32_t>(w + off::IFTYPE_Y),
        rd<std::int32_t>(w + off::IFTYPE_WIDTH),
        rd<std::int32_t>(w + off::IFTYPE_HEIGHT),
        rd<std::uint8_t>(w + off::IFTYPE_HIDDEN) != 0 ? 1 : 0,
    };
    jintArray arr = env->NewIntArray(6);
    if (arr) env->SetIntArrayRegion(arr, 0, 6, v);
    return arr;
}


/// A widget's rectangle in CANVAS coordinates: {ok, absX, absY, width, height, hidden, depth,
/// complete}, or empty when the id is not loaded.
///
/// `complete` is the one field a caller must branch on. It is 1 only when the parent chain was walked
/// all the way to a root, which is the only case where absX/absY are a canvas position; when it is 0
/// the pair is the component's own PARENT-RELATIVE x/y -- identical to what widget() returns -- and
/// Java must refuse rather than draw at it. See game.hpp widgetAbs and the THE PARENT LINK block in
/// offsets.hpp for why the link this walks is derived at runtime instead of being a constant.
inline jintArray JNICALL nWidgetAbs(JNIEnv* env, jclass, jint id) {
    WidgetAbs a = widgetAbs(id);
    if (!a.ok) return env->NewIntArray(0);
    jint v[8] = { 1, a.x, a.y, a.w, a.h, a.hidden ? 1 : 0, a.depth, a.complete ? 1 : 0 };
    jintArray arr = env->NewIntArray(8);
    if (arr) env->SetIntArrayRegion(arr, 0, 8, v);
    return arr;
}

/// The parent chain behind one widgetAbs answer, as a line a human can check in one look. Diagnostic:
/// called once a session under OXC_LOG, never per frame. The last hop is the self-test -- a group
/// root must read (0,0) at exactly the canvas size, and that says whether IFTYPE_X/Y are the laid-out
/// rect (this whole approach) or the cache originals (a much bigger job) without measuring anything by
/// eye.
inline jstring JNICALL nWidgetChain(JNIEnv* env, jclass, jint id) {
    return gameBytesToJString(env, widgetChainString(id));
}

/// Re-derive the parent link from scratch and return the tally, counts and all.
///
/// This is the evidence behind every absolute rectangle in the shim: which offset holds
/// (group<<16)|comp on every component (the positive control), which holds a same-group parent id or
/// a same-group parent pointer, how many survived the acyclic-forest check, and -- free while the
/// group's pointer set is in hand -- whether IFTYPE_CHILDREN_* carries the static tree or only what
/// cc_create spawned. Walks every loaded component's first 0x400 bytes twice, so call it from a probe,
/// at most once a session, never per frame.
inline jstring JNICALL nWidgetTreeProbe(JNIEnv* env, jclass) {
    return gameBytesToJString(env, widgetLink(true).report);
}
/// The world map's state: {level, originX, originZ, centreX, centreZ}, or empty when the map object
/// does not exist yet. The origin is the map's own coordinate base in world tiles (MapCoord at
/// wm+0x54B8, VERIFIED LIVE). The centre ints are the map centre in 8-world-tile units
/// (centreTile = 8 * centre = origin + 48; see the WM_* block in offsets.hpp), but they are passed
/// through RAW here: the centre is derived while the zoom is NOT, so there is nothing to turn them
/// into, and nothing on the Java side consumes them right now. There is deliberately no zoom in this
/// array either: the derivation pass proved there is no zoom field anywhere in the world-map object
/// or its view, so inventing one would be a lie.
inline jintArray JNICALL nWorldMap(JNIEnv* env, jclass) {
    std::uintptr_t wm = worldMap();
    if (!wm) return env->NewIntArray(0);
    jint v[5] = {
        rd<std::int32_t>(wm + off::WM_ORIGIN_LEVEL),
        rd<std::int32_t>(wm + off::WM_ORIGIN_X),
        rd<std::int32_t>(wm + off::WM_ORIGIN_Z),
        rd<std::int32_t>(wm + off::WM_CENTRE_X),
        rd<std::int32_t>(wm + off::WM_CENTRE_Z),
    };
    jintArray arr = env->NewIntArray(5);
    if (arr) env->SetIntArrayRegion(arr, 0, 5, v);
    return arr;
}

/// The ids of every widget group whose component data is loaded right now, ascending. The shim diffs
/// this against the previous frame to fire WidgetLoaded/WidgetClosed -- the same trigger shape the
/// client itself uses (a group's data is built on demand when its interface opens). Bounded by the
/// same 0x1000 group-count ceiling as the widget lookup.
inline jintArray JNICALL nLoadedGroups(JNIEnv* env, jclass) {
    std::vector<jint> ids;
    std::uintptr_t c = clientObj();
    if (c) {
        std::uintptr_t mgr = rdp(c + off::IFACE_MANAGER);
        if (mgr) {
            std::uint64_t gcount = rd<std::uint64_t>(mgr + off::IFACE_GROUP_COUNT);
            std::uintptr_t garr = rdp(mgr + off::IFACE_GROUP_ARRAY);
            if (garr && gcount > 0 && gcount <= 0x1000) {
                ids.reserve(static_cast<std::size_t>(gcount));
                for (std::uint64_t g = 0; g < gcount; ++g) {
                    if (rdp(garr + g * off::IFACE_GROUP_ENTRY_STRIDE + off::IFACE_GROUP_ENTRY_DATA))
                        ids.push_back(static_cast<jint>(g));
                }
            }
        }
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (arr && !ids.empty()) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(ids.size()), ids.data());
    return arr;
}

// ---------------------------------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------------------------------

/// Windows paths are wide; the JNI option string is narrow. Convert properly rather than truncating.
///
/// The obvious `std::string(w.begin(), w.end())` compiles, works on every path you personally test, and
/// then mangles the classpath for anybody whose Windows username is not pure ASCII -- which is a lot of
/// people, and whose symptom is "oxclient/Natives not found" with a perfectly correct-looking path in the
/// error. UTF-8 is what the JVM expects here.
inline std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                                nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string out(static_cast<std::size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), out.data(), n,
                        nullptr, nullptr);
    return out;
}

/// Load jvm.dll. `javaHome` comes from 0xclient.ini so nobody has to guess where your JDK is.
/// `detail` is filled in on failure with the exact path tried and the Win32 error, because "check
/// java= in 0xclient.ini" is useless advice on its own -- it does not say what the client READ, and a
/// path that is subtly mangled (a lost backslash, a stray quote) looks correct at a glance in the file.
/// Print what was attempted and the problem is usually obvious on sight.
inline HMODULE loadJvmDll(const std::wstring& javaHome, std::string& detail) {
    // Tell the loader about the JDK's own bin directory before asking for jvm.dll.
    //
    // jvm.dll does not stand alone -- it pulls in siblings that live in the JDK's bin, one level up
    // from bin\server. A plain LoadLibrary resolves those through the HOST process's search path, and
    // we are inside somebody else's process: if the game has narrowed its default search directories
    // (a normal hardening step), the load fails with ERROR_MOD_NOT_FOUND for a file that is plainly
    // sitting right there. AddDllDirectory is additive and per-process rather than replacing anything,
    // so unlike SetDllDirectory it cannot disturb how the game resolves its own DLLs.
    std::wstring bin = javaHome + L"\\bin";
    AddDllDirectory(bin.c_str());

    // A JDK has it under bin\server, a JRE sometimes under bin\client. Try both, then give up.
    const wchar_t* rel[] = { L"\\bin\\server\\jvm.dll", L"\\bin\\client\\jvm.dll" };
    DWORD lastError = 0;
    for (const wchar_t* r : rel) {
        std::wstring full = javaHome + r;

        // The widened search first; then a plain load, because the flags below need the directory to
        // have been registered and an older or stranger host may not cooperate. Whichever works, works.
        HMODULE m = LoadLibraryExW(full.c_str(), nullptr,
                                   LOAD_LIBRARY_SEARCH_DEFAULT_DIRS |
                                   LOAD_LIBRARY_SEARCH_USER_DIRS |
                                   LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR);
        if (!m) m = LoadLibraryW(full.c_str());
        if (m) return m;
        lastError = GetLastError();
    }

    detail = narrow(javaHome + rel[0]);
    detail += "  (error " + std::to_string(lastError);
    if (lastError == 2)        detail += ": no such file -- is java= the JDK folder itself?";
    else if (lastError == 126) detail += ": a dependency of jvm.dll is missing";
    else if (lastError == 193) detail += ": that is a 32-bit JDK, the game is 64-bit";
    detail += ")";
    return nullptr;
}

/// Start the VM, load the classes, wire the natives, call start(). Returns false with a reason you can
/// show the user -- silent failure here is miserable to debug.
inline bool startJvm(const std::wstring& javaHome, const std::wstring& jarPath, std::string& err) {
    std::string detail;
    HMODULE jvmDll = loadJvmDll(javaHome, detail);
    if (!jvmDll) { err = "could not load " + detail; return false; }

    using CreateFn = jint(JNICALL*)(JavaVM**, void**, void*);
    auto create = reinterpret_cast<CreateFn>(GetProcAddress(jvmDll, "JNI_CreateJavaVM"));
    if (!create) { err = "jvm.dll has no JNI_CreateJavaVM"; return false; }

    std::string cp = "-Djava.class.path=" + narrow(jarPath);

    // The control panel is a Swing window, so the VM must not come up headless. Some environments set
    // that by default and the failure is a confusing HeadlessException from inside a plugin.
    std::string headless = "-Djava.awt.headless=false";

    // NOTE: -Djava.security.egd was tried here and does nothing under Wine -- the JDK's entropy
    // collector calls NetworkInterface regardless of the source, and Wine's GetAdaptersAddresses
    // fails. The fix is on the Java side: oxclient.WineRandomProvider, inserted before any plugin loads.

    std::vector<std::string> optStrings{ cp, headless };
    // With OXC_LOG set, a JVM crash report lands next to the log instead of in the game's working
    // directory, where nobody looks for it. Java's own System.out/err already go to the log: log.hpp
    // installed the file as the process's standard handles before we got here.
    if (const char* log = ::getenv("OXC_LOG")) {
        std::string dir(log);
        auto cut = dir.find_last_of("\\/");
        dir = cut == std::string::npos ? "." : dir.substr(0, cut);
        optStrings.push_back("-XX:ErrorFile=" + dir + "\\oxc_hs_err_%p.log");
    }
    std::vector<JavaVMOption> opt(optStrings.size());
    for (std::size_t i = 0; i < optStrings.size(); ++i) opt[i].optionString = optStrings[i].data();

    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8;
    args.nOptions = static_cast<jint>(opt.size());
    args.options = opt.data();
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv* env = nullptr;
    if (create(&g_vm, reinterpret_cast<void**>(&env), &args) != JNI_OK || !env) {
        err = "JNI_CreateJavaVM failed";
        return false;
    }

    jclass natLocal = env->FindClass("oxclient/Natives");
    if (!natLocal) { err = "oxclient/Natives not found -- is 0xclient.jar next to the DLL?"; return false; }
    g_nat = static_cast<jclass>(env->NewGlobalRef(natLocal));

    const JNINativeMethod natives[] = {
        { const_cast<char*>("ready"),       const_cast<char*>("()Z"),     reinterpret_cast<void*>(nReady) },
        { const_cast<char*>("entities"),    const_cast<char*>("()[I"),    reinterpret_cast<void*>(nEntities) },
        { const_cast<char*>("sceneBase"),   const_cast<char*>("()[I"),    reinterpret_cast<void*>(nSceneBase) },
        { const_cast<char*>("varp"),        const_cast<char*>("(I)I"),    reinterpret_cast<void*>(nVarp) },
        { const_cast<char*>("container"),   const_cast<char*>("(I)[I"),   reinterpret_cast<void*>(nContainer) },
        { const_cast<char*>("local"),       const_cast<char*>("()[I"),    reinterpret_cast<void*>(nLocal) },
        { const_cast<char*>("skills"),      const_cast<char*>("()[I"),    reinterpret_cast<void*>(nSkills) },
        { const_cast<char*>("project"),     const_cast<char*>("(III)J"),  reinterpret_cast<void*>(nProject) },
        { const_cast<char*>("doAction"),    const_cast<char*>("(IIII)Z"), reinterpret_cast<void*>(nDoAction) },
        { const_cast<char*>("interactNpc"), const_cast<char*>("(II)Z"),   reinterpret_cast<void*>(nInteractNpc) },
        { const_cast<char*>("itemAction"),  const_cast<char*>("(IIII)Z"), reinterpret_cast<void*>(nItemAction) },
        { const_cast<char*>("locs"),        const_cast<char*>("(I)[I"),   reinterpret_cast<void*>(nLocs) },
        { const_cast<char*>("locName"),     const_cast<char*>("(I)Ljava/lang/String;"), reinterpret_cast<void*>(nLocName) },
        { const_cast<char*>("locOptions"),  const_cast<char*>("(I)Ljava/lang/String;"), reinterpret_cast<void*>(nLocOptions) },
        { const_cast<char*>("objectAction"),const_cast<char*>("(IIII)Z"), reinterpret_cast<void*>(nObjectAction) },
        { const_cast<char*>("viewport"),    const_cast<char*>("()[I"),    reinterpret_cast<void*>(nViewport) },
        { const_cast<char*>("input"),       const_cast<char*>("()[I"),    reinterpret_cast<void*>(nInput) },
        { const_cast<char*>("present"),     const_cast<char*>("([III)V"), reinterpret_cast<void*>(nPresent) },
        { const_cast<char*>("presentPanel"),const_cast<char*>("([III)V"), reinterpret_cast<void*>(nPresentPanel) },
        { const_cast<char*>("gameState"),   const_cast<char*>("()I"),     reinterpret_cast<void*>(nGameState) },
        { const_cast<char*>("entityName"),  const_cast<char*>("(IZ)Ljava/lang/String;"), reinterpret_cast<void*>(nEntityName) },
        { const_cast<char*>("widget"),      const_cast<char*>("(I)[I"),   reinterpret_cast<void*>(nWidget) },
        { const_cast<char*>("widgetText"),  const_cast<char*>("(I)Ljava/lang/String;"), reinterpret_cast<void*>(nWidgetText) },
        { const_cast<char*>("widgetChild"), const_cast<char*>("(II)[I"),  reinterpret_cast<void*>(nWidgetChild) },
        { const_cast<char*>("widgetAbs"),   const_cast<char*>("(I)[I"),   reinterpret_cast<void*>(nWidgetAbs) },
        { const_cast<char*>("widgetChain"), const_cast<char*>("(I)Ljava/lang/String;"), reinterpret_cast<void*>(nWidgetChain) },
        { const_cast<char*>("widgetTreeProbe"), const_cast<char*>("()Ljava/lang/String;"), reinterpret_cast<void*>(nWidgetTreeProbe) },
        { const_cast<char*>("worldMap"),    const_cast<char*>("()[I"),    reinterpret_cast<void*>(nWorldMap) },
        { const_cast<char*>("loadedGroups"),const_cast<char*>("()[I"),    reinterpret_cast<void*>(nLoadedGroups) },
        { const_cast<char*>("findString"),  const_cast<char*>("(Ljava/lang/String;)[J"), reinterpret_cast<void*>(nFindString) },
        { const_cast<char*>("peek"),        const_cast<char*>("(JI)Ljava/lang/String;"), reinterpret_cast<void*>(nPeek) },
        { const_cast<char*>("dumpWidgetText"), const_cast<char*>("(I)Ljava/lang/String;"), reinterpret_cast<void*>(nDumpWidgetText) },
        { const_cast<char*>("pointersTo"),  const_cast<char*>("(JII)[I"), reinterpret_cast<void*>(nPointersTo) },
        { const_cast<char*>("findWidgetRect"), const_cast<char*>("(II)Ljava/lang/String;"), reinterpret_cast<void*>(nFindWidgetRect) },
        // The one write into game memory; every gate is in nSetLoginField's comment.
        { const_cast<char*>("setLoginField"), const_cast<char*>("(JLjava/lang/String;I)I"), reinterpret_cast<void*>(nSetLoginField) },
        // Input into the game -- see the "Input INTO the game" section above nGameState.
        { const_cast<char*>("postChar"),    const_cast<char*>("(I)Z"),    reinterpret_cast<void*>(nPostChar) },
        { const_cast<char*>("postKey"),     const_cast<char*>("(IZ)Z"),   reinterpret_cast<void*>(nPostKey) },
        { const_cast<char*>("postMouse"),   const_cast<char*>("(III)Z"),  reinterpret_cast<void*>(nPostMouse) },
        { const_cast<char*>("inputTarget"), const_cast<char*>("(Z)[I"),   reinterpret_cast<void*>(nInputTarget) },
    };
    if (env->RegisterNatives(g_nat, natives, sizeof(natives) / sizeof(natives[0])) != JNI_OK) { err = "RegisterNatives failed"; return false; }

    jclass local = env->FindClass("oxclient/OxClient");
    if (!local) { err = "oxclient/OxClient not found"; return false; }
    g_api = static_cast<jclass>(env->NewGlobalRef(local));

    jmethodID start = env->GetStaticMethodID(g_api, "start", "()V");
    g_tick   = env->GetStaticMethodID(g_api, "tick", "(I)V");
    g_status = env->GetStaticMethodID(g_api, "status", "()Ljava/lang/String;");
    g_panelMouse = env->GetStaticMethodID(g_api, "panelMouse", "(IIIZ)V");
    if (!start || !g_tick) { err = "oxclient.OxClient needs static start() and tick(int)"; return false; }

    env->CallStaticVoidMethod(g_api, start);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    return true;
}

/// The JNIEnv for this thread, attaching it the first time. Null if the VM is not up.
inline JNIEnv* env() {
    if (!g_vm) return nullptr;
    JNIEnv* e = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_8) != JNI_OK) {
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&e), nullptr) != JNI_OK) return nullptr;
    }
    return e;
}

/// Run one frame: plugins tick, overlays draw, and Java calls present() before returning. `keys` is the
/// F-key edge mask.
inline void tickJvm(int keys) {
    JNIEnv* e = env();
    if (!e || !g_tick) return;
    e->CallStaticVoidMethod(g_api, g_tick, static_cast<jint>(keys));
    // A plugin throwing must never take the game down. Print it and carry on.
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

/// One mouse event on the panel window, up to Java. button: 0 move, 1 left, 2 middle, 3 right, 4 wheel
/// (down = wheel away from you). Coordinates are the panel window's own client space, which is exactly
/// SidePanel's coordinate system. No-op if the jar predates the panel.
inline void panelMouse(int x, int y, int button, bool down) {
    JNIEnv* e = env();
    if (!e || !g_panelMouse) return;
    e->CallStaticVoidMethod(g_api, g_panelMouse, static_cast<jint>(x), static_cast<jint>(y),
                            static_cast<jint>(button), down ? JNI_TRUE : JNI_FALSE);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

// ---------------------------------------------------------------------------------------------------
// The panel bridge's JNI half (launcher mode). Every entry point here is written so that a jar
// without oxclient.panel.PanelBridge is an EMPTY PANEL, never a crash: bridgeResolve() tries the class
// exactly once, clears whatever NotFound exception the attempt raised, and the callers then see
// bridgeAvailable() == false and fall back (see bridge.hpp). The same guards cover a jar that has the
// class but not a given method, which is the in-between state during development.
// ---------------------------------------------------------------------------------------------------

/// Find oxclient.panel.PanelBridge and its statics, once. Cheap after the first call: two branch reads.
inline bool bridgeResolve() {
    if (g_bridgeTried) return g_bridgeRevision != nullptr && g_bridgeSnapshot != nullptr;
    g_bridgeTried = true;

    JNIEnv* e = env();
    if (!e) return false;
    jclass local = e->FindClass("oxclient/panel/PanelBridge");
    if (!local) {                       // NoClassDefFoundError on an old jar -- cleared, not fatal
        if (e->ExceptionCheck()) e->ExceptionClear();
        return false;
    }
    g_bridgeCls = static_cast<jclass>(e->NewGlobalRef(local));

    g_bridgeRevision = e->GetStaticMethodID(g_bridgeCls, "modelRevision", "()J");
    g_bridgeSnapshot = e->GetStaticMethodID(g_bridgeCls, "snapshot", "()[I");
    g_bridgeSetBool  = e->GetStaticMethodID(g_bridgeCls, "setBool", "(ILjava/lang/String;Z)V");
    g_bridgeSetInt   = e->GetStaticMethodID(g_bridgeCls, "setInt", "(ILjava/lang/String;I)V");
    g_bridgeSetEnum  = e->GetStaticMethodID(g_bridgeCls, "setEnum", "(ILjava/lang/String;I)V");
    g_bridgeSetText  = e->GetStaticMethodID(g_bridgeCls, "setText", "(ILjava/lang/String;Ljava/lang/String;)V");
    if (e->ExceptionCheck()) e->ExceptionClear();     // a missing method above throws; that is fine

    if (!g_bridgeRevision || !g_bridgeSnapshot) {
        g_bridgeRevision = g_bridgeSnapshot = nullptr;
        return false;
    }
    return true;
}

/// True when the jar can answer both halves of the bridge: the model read and the edits.
inline bool bridgeAvailable() { return bridgeResolve(); }

/// Resolve the format-2 edit methods, once, on the first v2 edit. Same rules as bridgeResolve: every
/// method stands on its own, a miss throws NotFound which is cleared immediately, and the resulting
/// null jmethodID is handled by the dispatch in bridgeApply (which logs once and drops the edit).
/// Deliberately NOT part of bridgeResolve's success test: a jar that can publish a model and take
/// the four set* edits is worth running even if none of the v2 commands exist, and failing the whole
/// bridge over them would empty the panel of a jar that is merely older.
inline void bridgeResolveV2(JNIEnv* e) {
    g_bridgeV2Tried = true;
    g_bridgeResetSetting     = e->GetStaticMethodID(g_bridgeCls, "resetSetting",     "(ILjava/lang/String;)V");
    g_bridgeResetPlugin      = e->GetStaticMethodID(g_bridgeCls, "resetPlugin",      "(I)V");
    g_bridgeSetPinned        = e->GetStaticMethodID(g_bridgeCls, "setPinned",        "(IZ)V");
    g_bridgeHubInstall       = e->GetStaticMethodID(g_bridgeCls, "hubInstall",       "(Ljava/lang/String;)V");
    g_bridgeHubRemove        = e->GetStaticMethodID(g_bridgeCls, "hubRemove",        "(Ljava/lang/String;)V");
    g_bridgeHubRefresh       = e->GetStaticMethodID(g_bridgeCls, "hubRefresh",       "()V");
    g_bridgeProfileSwitch    = e->GetStaticMethodID(g_bridgeCls, "profileSwitch",    "(I)V");
    g_bridgeProfileCreate    = e->GetStaticMethodID(g_bridgeCls, "profileCreate",    "(Ljava/lang/String;)V");
    g_bridgeProfileDelete    = e->GetStaticMethodID(g_bridgeCls, "profileDelete",    "(I)V");
    g_bridgeProfileRename    = e->GetStaticMethodID(g_bridgeCls, "profileRename",    "(ILjava/lang/String;)V");
    g_bridgeProfileDuplicate = e->GetStaticMethodID(g_bridgeCls, "profileDuplicate", "(I)V");
    if (e->ExceptionCheck()) e->ExceptionClear();     // a missing method above throws; that is fine
}

/// Java's model revision, or -1 when there is no bridge to ask. The value itself is opaque (see
/// PanelBridge.modelRevision): it is only ever compared against the last one published.
inline std::int64_t bridgeModelRevision() {
    JNIEnv* e = env();
    if (!e || !bridgeResolve()) return -1;
    jlong rev = e->CallStaticLongMethod(g_bridgeCls, g_bridgeRevision);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); return -1; }
    return static_cast<std::int64_t>(rev);
}

/// The packed panel model (PanelBridge.snapshot's format, parsed by bridge.hpp). Empty when there is
/// no bridge or the call failed -- both mean "publish nothing", not "publish an empty model", so the
/// launcher keeps showing the last good list.
inline std::vector<jint> bridgeSnapshot() {
    JNIEnv* e = env();
    if (!e || !bridgeResolve()) return {};
    auto arr = static_cast<jintArray>(e->CallStaticObjectMethod(g_bridgeCls, g_bridgeSnapshot));
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); return {}; }
    if (!arr) return {};
    jsize n = e->GetArrayLength(arr);
    std::vector<jint> out(static_cast<std::size_t>(n));
    if (n > 0) e->GetIntArrayRegion(arr, 0, n, out.data());
    e->DeleteLocalRef(arr);
    return out;
}

/// The class name of a pending exception, for one log line about it. ExceptionDescribe's stack trace
/// goes to stderr, which a launcher-spawned game does not even have (OXC_LOG only redirects stdout),
/// and the edit ring can deliver a burst of records in one tick -- the name is what the line needs,
/// and Java's own code logs the detail around whatever it did not expect.
///
/// With an exception pending, JNI only guarantees the Exception* and Release* calls, so this clears
/// first, does its reflection in the clean window, and re-throws the ORIGINAL -- the caller clears
/// it once the line is out. A reflection that itself goes wrong just leaves "?" and is discarded.
inline std::string exceptionName(JNIEnv* e) {
    jthrowable t = e->ExceptionOccurred();            // peek: the exception stays pending
    if (!t) return "?";
    e->ExceptionClear();

    std::string out = "?";
    if (jclass cls = e->GetObjectClass(t)) {          // e.g. java/lang/IndexOutOfBoundsException
        // getName lives on java/lang/Class, so the methodID comes off the class OF the class.
        // Cached: the same methodID answers for every throwable there will ever be.
        if (jclass meta = e->GetObjectClass(cls)) {
            static jmethodID getName = e->GetMethodID(meta, "getName", "()Ljava/lang/String;");
            if (getName) {
                auto js = static_cast<jstring>(e->CallObjectMethod(cls, getName));
                if (js) {
                    if (const char* c = e->GetStringUTFChars(js, nullptr)) {
                        out = c;
                        e->ReleaseStringUTFChars(js, c);
                    }
                    e->DeleteLocalRef(js);
                }
            }
            e->DeleteLocalRef(meta);
        }
        e->DeleteLocalRef(cls);
    }
    if (e->ExceptionCheck()) e->ExceptionClear();     // a lookup failure must not shadow the original
    e->Throw(t);                                      // hand the original back; the caller clears it
    e->DeleteLocalRef(t);
    return out;
}

/// One edit record from the launcher, into Java. `kind` is bridge.hpp's EditKind (the numbers are
/// written out below because jvm.hpp cannot include bridge.hpp for the enum -- bridge.hpp includes
/// this file). Returns false only when the edit could not be DELIVERED (no bridge, string allocation
/// failed) -- a Java-level rejection (unknown plugin, unknown key, enum index out of range) is logged
/// by Java and counts as consumed, because re-delivering it forever would just burn the bridge's
/// edit ring.
inline bool bridgeApply(std::int32_t kind, std::int32_t pluginIdx, const char* key,
                        std::int64_t intVal, const char* text) {
    JNIEnv* e = env();
    if (!e || !bridgeResolve()) return false;

    // Kinds 4..14 need methods an old jar does not have. Resolve them on the first one we see, so a
    // session that never pins, never touches profiles and never opens the hub never asks.
    if (kind >= 4 && !g_bridgeV2Tried) bridgeResolveV2(e);

    // An edit this DLL has no method for (a kind from a newer launcher, or a jar that has the class
    // but not that method) is DROPPED, not retried: leaving it unconsumed would wedge the edit ring
    // on the first record the two sides disagree about, and every edit behind it with it. One log
    // line says what happened; the launcher's next model publish re-syncs what its widgets show.
    jmethodID method = nullptr;
    switch (kind) {
        case 0:  method = g_bridgeSetBool;          break;   // (int, String, boolean)
        case 1:  method = g_bridgeSetInt;           break;   // (int, String, int)
        case 2:  method = g_bridgeSetEnum;          break;   // (int, String, int)
        case 3:  method = g_bridgeSetText;          break;   // (int, String, String)
        case 4:  method = g_bridgeResetSetting;     break;   // (int, String)
        case 5:  method = g_bridgeResetPlugin;      break;   // (int)
        case 6:  method = g_bridgeSetPinned;        break;   // (int, boolean)
        case 7:  method = g_bridgeHubInstall;       break;   // (String)
        case 8:  method = g_bridgeHubRemove;        break;   // (String)
        case 9:  method = g_bridgeHubRefresh;       break;   // ()
        case 10: method = g_bridgeProfileSwitch;    break;   // (int)
        case 11: method = g_bridgeProfileCreate;    break;   // (String)
        case 12: method = g_bridgeProfileDelete;    break;   // (int)
        case 13: method = g_bridgeProfileRename;    break;   // (int, String)
        case 14: method = g_bridgeProfileDuplicate; break;   // (int)
        default: break;
    }
    if (!method) {
        const unsigned bit = (kind >= 0 && kind < 31) ? (1u << kind) : (1u << 31);
        if (!(g_bridgeKindsLogged & bit)) {
            g_bridgeKindsLogged |= bit;
            oxc::logf("[bridge] edit kind %d has no Java method on this jar -- dropped\n", kind);
            std::fflush(stdout);
        }
        return true;
    }

    // Build only the jstrings the callee's signature actually takes: `key` names a setting (kinds
    // 0-4), `text` carries the free-form argument (kind 3's value, a hub plugin id for 7/8, a
    // profile name for 11/13). The command kinds -- reset plugin, pin, profile by index, refresh --
    // carry no string at all, and allocating an empty one per edit would be waste for nothing.
    auto jstr = [&](const char* s) -> jstring {
        jstring j = e->NewStringUTF(s ? s : "");
        if (!j) e->ExceptionClear();               // out of memory: retry the edit later
        return j;
    };
    const bool needsKey  = kind <= 4;
    const bool needsText = (kind == 3 || kind == 7 || kind == 8 || kind == 11 || kind == 13);
    jstring jkey = nullptr, jtext = nullptr;
    if (needsKey)  { jkey = jstr(key);  if (!jkey) return false; }
    if (needsText) {
        jtext = jstr(text);
        if (!jtext) { if (jkey) e->DeleteLocalRef(jkey); return false; }
    }

    auto jbool = [](std::int64_t v) { return static_cast<jboolean>(v ? JNI_TRUE : JNI_FALSE); };

    bool delivered = true;
    switch (kind) {
        case 0:  e->CallStaticVoidMethod(g_bridgeCls, method, pluginIdx, jkey, jbool(intVal)); break;
        case 1:
        case 2:  e->CallStaticVoidMethod(g_bridgeCls, method, pluginIdx, jkey,
                                         static_cast<jint>(intVal)); break;
        case 3:  e->CallStaticVoidMethod(g_bridgeCls, method, pluginIdx, jkey, jtext); break;
        case 4:  e->CallStaticVoidMethod(g_bridgeCls, method, pluginIdx, jkey); break;
        case 5:  e->CallStaticVoidMethod(g_bridgeCls, method, pluginIdx); break;
        case 6:  e->CallStaticVoidMethod(g_bridgeCls, method, pluginIdx, jbool(intVal)); break;
        case 7:
        case 8:
        case 11: e->CallStaticVoidMethod(g_bridgeCls, method, jtext); break;
        case 9:  e->CallStaticVoidMethod(g_bridgeCls, method); break;
        case 10:
        case 12:
        case 14: e->CallStaticVoidMethod(g_bridgeCls, method, static_cast<jint>(intVal)); break;
        case 13: e->CallStaticVoidMethod(g_bridgeCls, method, static_cast<jint>(intVal), jtext); break;
        default: delivered = false; break;   // unreachable: the method lookup above already gated it
    }

    if (jkey)  e->DeleteLocalRef(jkey);
    if (jtext) e->DeleteLocalRef(jtext);
    // Java's edit handlers catch their own failures and log them (a bad plugin or profile index is a
    // log line there, not an exception). What still lands here is the case nobody expected -- one
    // line naming it, then clear, and the edit is CONSUMED either way: re-delivering a record Java
    // has rejected would wedge the ring on it and every edit behind it.
    if (e->ExceptionCheck()) {
        oxc::logf("[bridge] edit kind %d threw %s (cleared)\n", kind, exceptionName(e).c_str());
        std::fflush(stdout);
        e->ExceptionClear();
    }
    return delivered;
}

/// Tell Java which process owns the panel. Called once, right after the VM starts: true when the
/// launcher embedded this game (the panel is ImGui, drawn by the launcher, and Java must not draw
/// SidePanel or listen for panel mouse events), false for today's direct-inject behaviour. Guarded
/// like g_panelMouse, so an older jar without setPanelMode keeps its default (Java owns the panel).
inline void notifyPanelMode(bool imgui) {
    JNIEnv* e = env();
    if (!e || !g_api) return;
    jmethodID m = e->GetStaticMethodID(g_api, "setPanelMode", "(Z)V");
    if (!m) { e->ExceptionClear(); return; }
    e->CallStaticVoidMethod(g_api, m, imgui ? JNI_TRUE : JNI_FALSE);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

/// One line per plugin, for when Java is up but nothing is drawing yet.
inline std::string jvmStatus() {
    JNIEnv* e = env();
    if (!e || !g_status) return {};
    auto js = static_cast<jstring>(e->CallStaticObjectMethod(g_api, g_status));
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); return {}; }
    if (!js) return {};
    const char* c = e->GetStringUTFChars(js, nullptr);
    std::string out = c ? c : "";
    if (c) e->ReleaseStringUTFChars(js, c);
    e->DeleteLocalRef(js);
    return out;
}

}  // namespace oxc
