// scenery.hpp -- the scenery ("locs") around you: trees, rocks, furniture -- and their names.
//
// Read-only, from any thread, like the rest of game.hpp: a walk over the scene's tile grid and a lookup
// in the client's loc definition cache. offsets.hpp's SCENERY block says where every number came from;
// the two handle shifts below are read off the client's own decoders.
//
// GAME OBJECTS (layer 2 -- trees, rocks, booths, most things you click) and WALLS (layer 0 -- walls,
// fences, doors, gates) are listed; wall decorations and ground decorations are not. A multi-tile game
// object is listed once, at its origin (south-west) tile, which is the tile the client's own menu sends
// with its option; a wall is listed on the tile it sits on.
//
// Game objects and names: VERIFIED LIVE 2026-10-09 (client-241-3: the self-check and MntnChopper).
// Walls (TILE_WALL / WALL_HANDLE) and options (LOCDEF_OPS): derived statically, NOT VERIFIED LIVE.
#pragma once
#include <array>
#include <cstdint>
#include <string>

#include "game.hpp"
#include "offsets.hpp"

namespace oxc {

// A loc handle is a packed u64. The client's decoders (241-3) are `shr rax,cl` with cl = 0x14 then
// `movzx eax,ax` for the id (0x652270 -> 0x63cbe0), and cl = 0x10 then `and eax,7` for the kind
// (0x652230); its "is a game object" test (0x652110) is kind == 2. Constants of the handle format,
// not struct offsets, so they are not in the per-build table.
constexpr int LOC_ID_SHIFT = 0x14;
constexpr int LOC_KIND_SHIFT = 0x10;
constexpr int LOC_KIND_GAME_OBJECT = 2;

// The loc definition cache's node layout, read off the getter (0x5ed080 on 241-3): `cmp rdi,[rdx]`
// (the id at +0), `mov rdx,[rdx+0x40]` (next), and it returns node+8 as {control block, definition},
// so the definition is at +0x10.
constexpr std::uintptr_t LOCDEF_NODE_DEF = 0x10;
constexpr std::uintptr_t LOCDEF_NODE_NEXT = 0x40;

// One entry of a definition's options vector (LOCDEF_OPS): 0x40 bytes, text at +0 -- the helpers index it
// `shl rsi,6` / `sar rax,6` (0x5e63b0 on 241-3). Option i+1 is entry i.
constexpr std::uintptr_t LOCDEF_OP_STRIDE = 0x40;
constexpr int LOC_LAYER_WALL = 0;
constexpr int LOC_LAYER_GAME_OBJECT = 2;

struct Loc {
    int id = 0;
    int sceneX = 0, sceneY = 0;   // origin tile, scene coordinates
    int layer = LOC_LAYER_GAME_OBJECT;
    std::uintptr_t obj = 0;
};

/// The scene's tile grid, or 0.
inline std::uintptr_t sceneGrid() {
    std::uintptr_t s = scene();
    return s ? rdp(s + off::SCENE_GRID) : 0;
}

/// Call `fn(const Loc&)` for every game object whose origin is within `radius` tiles (Chebyshev) of
/// scene tile (cx, cy) on `level`. Returns how many it visited; 0 when the grid is not up.
template <class Fn>
inline int forEachLoc(int level, int cx, int cy, int radius, Fn&& fn) {
    const std::uintptr_t grid = sceneGrid();
    if (!grid || level < 0 || level > 3) return 0;
    const int dimX = rd<std::int32_t>(grid + off::GRID_DIM_X);
    const int dimY = rd<std::int32_t>(grid + off::GRID_DIM_Y);
    if (dimX <= 0 || dimY <= 0 || dimX > 1024 || dimY > 1024) return 0;   // torn or not the grid
    const std::uintptr_t tiles = rdp(grid + off::GRID_TILES);
    if (!tiles) return 0;
    const int x0 = cx - radius < 0 ? 0 : cx - radius, x1 = cx + radius >= dimX ? dimX - 1 : cx + radius;
    const int y0 = cy - radius < 0 ? 0 : cy - radius, y1 = cy + radius >= dimY ? dimY - 1 : cy + radius;
    int n = 0;
    for (int x = x0; x <= x1; ++x) {
        // one column of entries is contiguous: read it once instead of tile by tile
        const std::uintptr_t col = tiles + static_cast<std::uintptr_t>((level * dimX + x) * dimY + y0) * 16;
        const int rows = y1 - y0 + 1;
        if (rows <= 0 || !readable(col, static_cast<std::size_t>(rows) * 16)) continue;
        for (int y = y0; y <= y1; ++y) {
            const std::uintptr_t tile = *reinterpret_cast<const std::uintptr_t*>(col + static_cast<std::uintptr_t>(y - y0) * 16 + 8);
            if (!tile) continue;
            if (const std::uintptr_t wall = rdp(tile + off::TILE_WALL)) {
                const std::uint64_t h = rd<std::uint64_t>(wall + off::WALL_HANDLE);
                if (h) {
                    Loc l;
                    l.id = static_cast<int>((h >> LOC_ID_SHIFT) & 0xFFFF);
                    l.sceneX = x;
                    l.sceneY = y;
                    l.layer = LOC_LAYER_WALL;
                    l.obj = wall;
                    fn(l);
                    ++n;
                }
            }
            const int count = rd<std::int32_t>(tile + off::TILE_OBJ_COUNT);
            if (count <= 0 || count > 32) continue;
            const std::uintptr_t arr = rdp(tile + off::TILE_OBJS);
            if (!arr || !readable(arr, static_cast<std::size_t>(count) * 16)) continue;
            for (int k = 0; k < count; ++k) {
                const std::uintptr_t obj = *reinterpret_cast<const std::uintptr_t*>(arr + static_cast<std::uintptr_t>(k) * 16 + 8);
                if (!obj) continue;
                const std::uint64_t h = rd<std::uint64_t>(obj + off::LOC_HANDLE);
                if (((h >> LOC_KIND_SHIFT) & 7) != LOC_KIND_GAME_OBJECT) continue;
                Loc l;
                l.sceneX = rd<std::int32_t>(obj + off::LOC_X, -1);
                l.sceneY = rd<std::int32_t>(obj + off::LOC_Y, -1);
                if (l.sceneX != x || l.sceneY != y) continue;     // counted on its origin tile only
                l.id = static_cast<int>((h >> LOC_ID_SHIFT) & 0xFFFF);
                l.layer = LOC_LAYER_GAME_OBJECT;
                l.obj = obj;
                fn(l);
                ++n;
            }
        }
    }
    return n;
}

/// A loc's definition from the client's own cache, or 0 when it is not cached right now or LOCDEF_CACHE is
/// not measured on this build. Never loads one: an object on screen has been looked up by the client
/// already, so a miss is rare and just means "ask again later".
inline std::uintptr_t locDef(int id) {
    if (id < 0) return 0;
    const std::uintptr_t cell = globalAddr(off::LOCDEF_CACHE);
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

/// A loc's name ("Tree", "Oak tree", "Door"), or "" when its definition is not cached right now.
inline std::string locName(int id) {
    const std::uintptr_t def = locDef(id);
    return def ? nxtString(def + off::LOCDEF_NAME) : std::string{};
}

/// A loc's five right-click options as the definition names them ("Chop down", "Bank", "Open"); option n
/// is [n-1], "" where it has none or the definition is not cached. The definition's conditional
/// overrides (an option renamed by a game variable) are not applied -- the base text is returned.
inline std::array<std::string, 5> locOptions(int id) {
    std::array<std::string, 5> out{};
    const std::uintptr_t def = locDef(id);
    if (!def || !off::LOCDEF_OPS) return out;
    const std::uintptr_t begin = rdp(def + off::LOCDEF_OPS), end = rdp(def + off::LOCDEF_OPS + 8);
    if (!begin || end < begin || (end - begin) % LOCDEF_OP_STRIDE || end - begin > 16 * LOCDEF_OP_STRIDE) return out;
    const int n = static_cast<int>((end - begin) / LOCDEF_OP_STRIDE);
    for (int i = 0; i < n && i < 5; ++i) out[i] = nxtString(begin + static_cast<std::uintptr_t>(i) * LOCDEF_OP_STRIDE, 60);
    return out;
}

}  // namespace oxc
