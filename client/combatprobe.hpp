// combatprobe.hpp -- find "who is this actor fighting" by watching a fight. Read-only.
//
// The account builder needs to know when you are in combat: an NPC attacking you (its target is you) and
// the NPC you are attacking (your target is it). Neither field could be named off the binary (2026-10-09:
// the actor constructor 0x9dcd0 sets dozens of fields to -1 and none is labelled), so this watches one:
// every 600 ms it reads your actor and every NPC within 10 tiles, and looks for a 4-byte field whose value
// is the OTHER side's id -- your player handle on an NPC, an NPC's uid on you -- as is, + 0x8000 or
// + 0x10000 (the encodings the OSRS clients have used for "the target is a player"). Matches are tallied
// by offset; the offset that matches the attacker, and only while it attacks, is the field.
//
// Off unless OXC_COMBATPROBE=1 (gradlew debugRun -Pcombat). Prints [combat] lines to the OXC_LOG file:
// a line whenever the set of matches changes, and a tally every 10 seconds.
#pragma once
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "game.hpp"
#include "log.hpp"

namespace oxc::combatprobe {

constexpr std::size_t SPAN = 0xC00;      // bytes of each actor scanned (animation is at 0x4D8)
constexpr int RADIUS = 10;

struct Tally {
    int hits = 0;
    std::set<std::string> who;           // names it matched on
};

class Probe {
public:
    void tick() {
        if (!checked) { checked = true; const char* e = ::getenv("OXC_COMBATPROBE"); on = e && e[0] == '1'; if (on) logf("[combat] probe on\n"); }
        if (!on) return;
        const DWORD now = GetTickCount();
        if (now - last < 600) return;
        last = now;
        sample();
        if (now - lastTally > 10000) { lastTally = now; tally(); }
    }

private:
    bool checked = false, on = false;
    DWORD last = 0, lastTally = 0;
    std::map<std::string, Tally> npcSide, meSide;   // "off kind" -> tally
    std::string lastSet;

    // which encoding of `id` is `v`, or nullptr
    static const char* kind(std::int32_t v, int id) {
        if (id < 0) return nullptr;
        if (v == id && id >= 32) return "id";      // tiny ids match too many constants to mean anything
        if (v == id + 0x8000) return "id+0x8000";
        if (v == id + 0x10000) return "id+0x10000";
        return nullptr;
    }

    static bool grab(std::uintptr_t a, std::vector<std::uint8_t>& buf) {
        if (!a || !readable(a, SPAN)) return false;
        buf.resize(SPAN);
        std::memcpy(buf.data(), reinterpret_cast<const void*>(a), SPAN);
        return true;
    }

    void sample() {
        bool found = false;
        Entity me = localPlayer(found);
        if (!found) return;
        const int myUid = me.uid;
        struct Nearby { Entity e; std::string name; };
        std::vector<Nearby> nearby;
        forEachEntity([&](const Entity& e) {
            if (e.player) return;
            int dx = e.sceneX - me.sceneX, dy = e.sceneY - me.sceneY;
            if (dx < -RADIUS || dx > RADIUS || dy < -RADIUS || dy > RADIUS) return;
            nearby.push_back({e, npcName(e.addr)});
        });
        std::string set;
        std::vector<std::uint8_t> buf;
        for (const Nearby& n : nearby) {                       // an NPC holding your id
            if (!grab(n.e.addr, buf)) continue;
            for (std::size_t off = 0; off + 4 <= SPAN; off += 4) {
                std::int32_t v;
                std::memcpy(&v, buf.data() + off, 4);
                if (const char* k = kind(v, myUid)) {
                    char key[48];
                    std::snprintf(key, sizeof key, "npc+0x%zX %s", off, k);
                    Tally& t = npcSide[key];
                    t.hits++;
                    t.who.insert(n.name);
                    set += std::string(key) + " on " + n.name + " (anim " + std::to_string(n.e.animation) + "); ";
                }
            }
        }
        if (grab(me.addr, buf)) {                          // you holding an NPC's uid
            for (std::size_t off = 0; off + 4 <= SPAN; off += 4) {
                std::int32_t v;
                std::memcpy(&v, buf.data() + off, 4);
                for (const Nearby& n : nearby) {
                    if (const char* k = kind(v, n.e.uid)) {
                        char key[48];
                        std::snprintf(key, sizeof key, "me+0x%zX %s", off, k);
                        Tally& t = meSide[key];
                        t.hits++;
                        t.who.insert(n.name);
                        set += std::string(key) + " -> " + n.name + "; ";
                        break;
                    }
                }
            }
        }
        if (set != lastSet) {
            lastSet = set;
            logf("[combat] my uid %d, %zu npcs nearby, my anim %d, hp %d/%d: %s\n", myUid, nearby.size(), me.animation,
                 skillEffective(3), skillBase(3), set.empty() ? "(no matches)" : set.c_str());
        }
    }

    void tally() {
        auto dump = [](const char* side, const std::map<std::string, Tally>& m) {
            std::vector<std::pair<int, std::string>> rows;
            for (const auto& [k, t] : m) {
                std::string who;
                for (const auto& w : t.who) { if (!who.empty()) who += ","; who += w; if (who.size() > 60) break; }
                rows.push_back({t.hits, k + " x" + std::to_string(t.hits) + " [" + who + "]"});
            }
            std::sort(rows.begin(), rows.end(), [](auto& a, auto& b) { return a.first > b.first; });
            for (std::size_t i = 0; i < rows.size() && i < 8; ++i) logf("[combat] tally %s: %s\n", side, rows[i].second.c_str());
        };
        dump("npc", npcSide);
        dump("me", meSide);
    }
};

}  // namespace oxc::combatprobe
