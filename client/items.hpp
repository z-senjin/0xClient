// items.hpp -- items: their definitions (name, stackable, value, ground options) and the items lying on
// the ground around you.
//
// Read-only, any thread, like scenery.hpp. offsets.hpp's GROUND ITEMS and ITEM DEFINITIONS blocks say
// where every number came from. Derived from client-241-3 statically (2026-10-09); NOT VERIFIED LIVE --
// the self-check (checkItems) reads your inventory's names and the ground around you on login.
#pragma once
#include <array>
#include <cstdint>
#include <string>

#include "game.hpp"
#include "offsets.hpp"
#include "scenery.hpp"

namespace oxc {

// The per-tile list container (offsets.hpp, GROUND ITEMS): each level of the scene, and each column of a
// level, is a 0x18-byte element whose pointer to the next dimension's elements sits at +0x10 -- the list
// getter 0x9d8b0 is `[base + i*0x18 + 0x10]` twice, then `+ y*0x18`. A shape of the container, not a
// field the game moves between builds, so it is a constant here.
constexpr std::uintptr_t OBJ_DIM_STRIDE = 0x18;
constexpr std::uintptr_t OBJ_DIM_DATA = 0x10;

struct GroundItem {
    int id = -1, quantity = 0;
    int sceneX = 0, sceneY = 0;
};

/// The head (sentinel) of one tile's ground-item list, or 0.
inline std::uintptr_t groundListHead(int level, int sceneX, int sceneY) {
    const std::uintptr_t s = scene();
    if (!s || level < 0 || level > 3 || sceneX < 0 || sceneY < 0 || sceneX >= 104 || sceneY >= 104) return 0;
    const std::uintptr_t lists = rdp(s + off::SCENE_OBJ_LISTS);
    if (!lists) return 0;
    const std::uintptr_t levels = rdp(lists + OBJ_DIM_DATA + static_cast<std::uintptr_t>(level) * OBJ_DIM_STRIDE);
    if (!levels) return 0;
    const std::uintptr_t cols = rdp(levels + OBJ_DIM_DATA + static_cast<std::uintptr_t>(sceneX) * OBJ_DIM_STRIDE);
    if (!cols) return 0;
    return cols + static_cast<std::uintptr_t>(sceneY) * OBJ_DIM_STRIDE;
}

/// Call fn(const GroundItem&) for every item on the ground within `radius` tiles of scene (cx, cy).
template <class Fn>
inline int forEachGroundItem(int level, int cx, int cy, int radius, Fn&& fn) {
    int n = 0;
    for (int x = cx - radius; x <= cx + radius; ++x) {
        for (int y = cy - radius; y <= cy + radius; ++y) {
            const std::uintptr_t head = groundListHead(level, x, y);
            if (!head) continue;
            std::uintptr_t node = rdp(head);
            for (int guard = 0; node && node != head && guard < 64; ++guard) {
                const std::uintptr_t obj = rdp(node + off::OBJ_NODE_OBJ);
                if (obj) {
                    GroundItem g;
                    g.id = rd<std::int32_t>(obj + off::OBJ_ID, -1);
                    g.quantity = rd<std::int32_t>(obj + off::OBJ_COUNT);
                    g.sceneX = x;
                    g.sceneY = y;
                    if (g.id >= 0 && g.quantity > 0) { fn(g); ++n; }
                }
                node = rdp(node);
            }
        }
    }
    return n;
}

/// An item's definition from the client's cache, or 0 when it is not cached right now (the client looks
/// up every item it draws, so anything in your inventory or on screen is cached). Never loads one.
inline std::uintptr_t itemDef(int id) {
    if (id < 0) return 0;
    const std::uintptr_t cell = globalAddr(off::ITEMDEF_CACHE);
    if (!cell) return 0;
    const std::uintptr_t buckets = rdp(cell);
    const std::uint32_t count = rd<std::uint32_t>(cell + 8);
    if (!buckets || count == 0 || count > 0x100000) return 0;
    const std::uintptr_t end = rdp(buckets + static_cast<std::uintptr_t>(count) * 8);
    std::uintptr_t node = rdp(buckets + static_cast<std::uintptr_t>(static_cast<std::uint64_t>(id) % count) * 8);
    for (int guard = 0; node && node != end && guard < 256; ++guard) {
        if (rd<std::uint64_t>(node) == static_cast<std::uint64_t>(id)) return rdp(node + LOCDEF_NODE_DEF);
        node = rdp(node + LOCDEF_NODE_NEXT);
    }
    return 0;
}

inline std::string itemName(int id) {
    const std::uintptr_t def = itemDef(id);
    return def ? nxtString(def + off::ITEMDEF_NAME) : std::string{};
}

/// {stackable 0/1, value} or {-1, -1} when the definition is not cached.
inline std::array<int, 2> itemInfo(int id) {
    const std::uintptr_t def = itemDef(id);
    if (!def) return {-1, -1};
    return {rd<std::int32_t>(def + off::ITEMDEF_STACKABLE) == 1 ? 1 : 0, rd<std::int32_t>(def + off::ITEMDEF_COST)};
}

/// The item's five ground options ("Take" is usually one of them); option n is [n-1].
inline std::array<std::string, 5> itemGroundOptions(int id) {
    std::array<std::string, 5> out{};
    const std::uintptr_t def = itemDef(id);
    if (!def || !off::ITEMDEF_GROUND_OPS) return out;
    const std::uintptr_t begin = rdp(def + off::ITEMDEF_GROUND_OPS), end = rdp(def + off::ITEMDEF_GROUND_OPS + 8);
    if (!begin || end < begin || (end - begin) % LOCDEF_OP_STRIDE || end - begin > 16 * LOCDEF_OP_STRIDE) return out;
    const int n = static_cast<int>((end - begin) / LOCDEF_OP_STRIDE);
    for (int i = 0; i < n && i < 5; ++i) out[i] = nxtString(begin + static_cast<std::uintptr_t>(i) * LOCDEF_OP_STRIDE, 60);
    return out;
}

}  // namespace oxc
