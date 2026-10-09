// actionprobe.hpp -- hook-and-log for the menu-action path, with no code patched.
//
// DO_ACTION and the menu opcodes cannot be read off the binary: this engine has no single opcode
// dispatcher (each menu entry carries its own callback), so the only way to learn "what does the client
// call, with what, when I click Walk here / Attack / Chop down" is to watch it happen. This file watches,
// and does nothing else:
//
//   * HARDWARE breakpoints (the CPU's debug registers DR0..DR3) on up to four functions, set on every
//     thread of the game. Nothing in the game's code is modified -- no detour, no trampoline, no byte
//     written -- so a wrong address cannot corrupt anything; at worst a breakpoint never fires.
//   * A vectored exception handler that, when one fires, copies the registers, a few stack arguments,
//     a short memory dump and a stack backtrace into a fixed ring buffer, sets the resume flag, and
//     returns. It allocates nothing, takes no lock and does no I/O: it runs on the game's thread, in
//     the middle of the game's code.
//   * tick(), called from the DLL's own loop, which prints the ring buffer to the OXC_LOG file as
//     [probe] lines, and re-arms the debug registers on threads the game has started since.
//
// Off unless OXC_ACTIONPROBE=1. Which functions: OXC_ACTIONPROBE_RVAS="37d280,37d090,6795c0,372870"
// (hex RVAs, up to four); without it, the defaults below are used on the build they were found on and
// nothing is armed on any other build.
//
// The defaults on client-241-3 are the four action HANDLERS a menu entry's callback calls (found by the
// first probe run, 2026-10-09, which watched these roles -- usable again through OXC_ACTIONPROBE_RVAS):
//   0x37d280  a menu's "execute the clicked entry" (virtual): finds the entry, copies it, runs its callback
//   0x37d090  copies the clicked entry's 0x150-byte record into the menu -- RDX+0x100 is dumped
//   0x6795c0  starts every outgoing packet: EDX is the packet's opcode; logged only within two seconds
//             of a 0x37d280 hit, with a backtrace
//   0x372870  the widget-menu action (fills the pending-action record)
// Each handler's RDX is its argument block, dumped in full -- the layout actions.hpp builds by hand.
#pragma once
#include <windows.h>
#include <tlhelp32.h>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

#include "game.hpp"
#include "hwbp.hpp"
#include "log.hpp"
#include "offsets.hpp"

namespace oxc::probe {

constexpr int kFrames = 14;
constexpr int kDump = 0x60;
constexpr int kRing = 256;

struct Hit {
    std::uint64_t tick;
    int slot;                       // which debug register fired (0..3)
    std::uint64_t rip, rcx, rdx, r8, r9;
    std::uint64_t stack[6];         // [rsp+0x28 ..] -- the 5th.. arguments at entry
    std::uint64_t dumpFrom;         // where the dump was taken
    int dumpLen;
    unsigned char dump[kDump];
    std::uint64_t frames[kFrames];  // return addresses, innermost first
    int nFrames;
    std::int32_t sel[3];            // the "Use" selection {SEL_WIDGET, SEL_SLOT, SEL_ITEM} at the hit
    bool selOk;
};

struct State {
    std::uint64_t base = 0, size = 0;
    std::uint64_t rva[4] = {0, 0, 0, 0};
    int armed = 0;
    std::uint64_t packetRva = 0;     // the slot logged only shortly after a click
    std::uint64_t entryExecRva = 0;  // the click itself
    std::uint64_t entryCopyRva = 0;  // dump RDX+0x100
    std::atomic<std::uint64_t> lastClickTick{0};
    Hit ring[kRing];
    std::atomic<std::uint32_t> head{0};
    std::uint32_t tail = 0;
    std::atomic<std::uint32_t> dropped{0};
};

inline State& st() {
    static State s;
    return s;
}

inline bool readableRaw(std::uint64_t p, std::size_t n) {
    if (p < 0x10000) return false;
    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(reinterpret_cast<void*>(p), &mbi, sizeof mbi)) return false;
    if (mbi.State != MEM_COMMIT || (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD))) return false;
    return p + n <= reinterpret_cast<std::uint64_t>(mbi.BaseAddress) + mbi.RegionSize;
}

// Record one hit. Called by hwbp.hpp on the game thread that hit the breakpoint: copies, nothing more.
inline void record(CONTEXT* c) {
    State& s = st();
    const std::uint64_t rva = c->Rip - s.base;
    const std::uint64_t now = GetTickCount64();
    if (rva == s.entryExecRva) s.lastClickTick.store(now);
    if (rva == s.packetRva && now - s.lastClickTick.load() >= 2000) return;   // packets: only after a click
    const std::uint32_t i = s.head.load();
    if (i - s.tail >= kRing) { s.dropped.fetch_add(1); return; }
    Hit& h = s.ring[i % kRing];
    h.tick = now;
    h.slot = 0;
    for (int k = 0; k < 4; ++k) if (s.rva[k] == rva) h.slot = k;
    h.rip = c->Rip; h.rcx = c->Rcx; h.rdx = c->Rdx; h.r8 = c->R8; h.r9 = c->R9;
    for (int k = 0; k < 6; ++k) {
        const std::uint64_t a = c->Rsp + 0x28 + k * 8;
        h.stack[k] = readableRaw(a, 8) ? *reinterpret_cast<std::uint64_t*>(a) : 0;
    }
    // The "Use" selection, so a hook on a use-item sender shows which field holds what (offsets.hpp,
    // USE ITEM ON: the slot/item order is the open question). Raw reads: this runs inside the handler.
    h.selOk = false;
    {
        const std::uint64_t cl = s.base + off::CLIENT_OBJ_PTR;
        if (off::CLIENT_OBJ_PTR && off::TARGET_STATE && readableRaw(cl, 8)) {
            const std::uint64_t client = *reinterpret_cast<std::uint64_t*>(cl);
            if (client && readableRaw(client + off::TARGET_STATE, 8)) {
                const std::uint64_t st8 = *reinterpret_cast<std::uint64_t*>(client + off::TARGET_STATE);
                const std::uint64_t sel = st8 + off::TARGET_SEL;
                if (st8 && readableRaw(sel + off::SEL_WIDGET, 4) && readableRaw(sel + off::SEL_SLOT, 4) && readableRaw(sel + off::SEL_ITEM, 4)) {
                    h.sel[0] = *reinterpret_cast<std::int32_t*>(sel + off::SEL_WIDGET);
                    h.sel[1] = *reinterpret_cast<std::int32_t*>(sel + off::SEL_SLOT);
                    h.sel[2] = *reinterpret_cast<std::int32_t*>(sel + off::SEL_ITEM);
                    h.selOk = true;
                }
            }
        }
    }
    h.dumpFrom = (rva == s.entryCopyRva) ? c->Rdx + 0x100 : c->Rdx;
    h.dumpLen = readableRaw(h.dumpFrom, kDump) ? kDump : 0;
    if (h.dumpLen) std::memcpy(h.dump, reinterpret_cast<void*>(h.dumpFrom), kDump);
    // Backtrace through the x64 unwind data the binary ships (no symbols needed).
    CONTEXT u = *c;
    h.nFrames = 0;
    for (int k = 0; k < kFrames; ++k) {
        DWORD64 imageBase = 0;
        PRUNTIME_FUNCTION fe = RtlLookupFunctionEntry(u.Rip, &imageBase, nullptr);
        if (!fe) {  // a leaf: the return address is at [rsp]
            if (!readableRaw(u.Rsp, 8)) break;
            u.Rip = *reinterpret_cast<DWORD64*>(u.Rsp);
            u.Rsp += 8;
        } else {
            PVOID handlerData = nullptr;
            DWORD64 establisher = 0;
            RtlVirtualUnwind(UNW_FLAG_NHANDLER, imageBase, u.Rip, fe, &u, &handlerData, &establisher, nullptr);
        }
        if (!u.Rip) break;
        h.frames[h.nFrames++] = u.Rip;
    }
    s.head.store(i + 1);
}

inline std::string hex(std::uint64_t v) {
    char b[24];
    std::snprintf(b, sizeof b, "%llx", static_cast<unsigned long long>(v));
    return b;
}

// Print one hit. Addresses inside the game are printed as RVAs ("+37d280") so they line up with Ghidra.
inline void print(const Hit& h) {
    State& s = st();
    auto where = [&](std::uint64_t a) {
        return (a >= s.base && a < s.base + s.size) ? "+" + hex(a - s.base) : hex(a);
    };
    oxc::logf("[probe] t=%llu bp%d %s  rcx=%s rdx=%s r8=%s r9=%s  stack=%s %s %s %s %s %s\n",
              static_cast<unsigned long long>(h.tick), h.slot, where(h.rip).c_str(), hex(h.rcx).c_str(),
              hex(h.rdx).c_str(), hex(h.r8).c_str(), hex(h.r9).c_str(), hex(h.stack[0]).c_str(),
              hex(h.stack[1]).c_str(), hex(h.stack[2]).c_str(), hex(h.stack[3]).c_str(),
              hex(h.stack[4]).c_str(), hex(h.stack[5]).c_str());
    std::string bt;
    for (int k = 0; k < h.nFrames; ++k) bt += " " + where(h.frames[k]);
    oxc::logf("[probe]   backtrace:%s\n", bt.c_str());
    if (h.selOk) oxc::logf("[probe]   selection: +0x%llx=%d (as widget 0x%x)  +0x%llx=%d  +0x%llx=%d\n",
                           static_cast<unsigned long long>(off::SEL_WIDGET), h.sel[0], static_cast<unsigned>(h.sel[0]),
                           static_cast<unsigned long long>(off::SEL_SLOT), h.sel[1],
                           static_cast<unsigned long long>(off::SEL_ITEM), h.sel[2]);
    if (h.dumpLen) {
        std::string d;
        const std::int32_t* w = reinterpret_cast<const std::int32_t*>(h.dump);
        for (int k = 0; k < h.dumpLen / 4; ++k) {
            char b[16];
            std::snprintf(b, sizeof b, "%s%d", k % 8 ? " " : (k ? " | " : ""), w[k]);
            d += b;
        }
        oxc::logf("[probe]   ints at %s: %s\n", where(h.dumpFrom).c_str(), d.c_str());
    }
}

class Probe {
public:
    void tick() {
        if (!enabled_) {
            if (checked_) return;
            checked_ = true;
            const char* e = ::getenv("OXC_ACTIONPROBE");
            if (!(e && e[0] == '1')) return;
            if (!setup()) return;
            enabled_ = true;
        }
        State& s = st();
        if (!loggedArm_ && oxc::hwbp::st().lastArmed > 0) {
            loggedArm_ = true;
            oxc::logf("[probe] armed on %d threads: %s %s %s %s -- click menu options in game now\n", oxc::hwbp::st().lastArmed,
                      hex(s.rva[0]).c_str(), hex(s.rva[1]).c_str(), hex(s.rva[2]).c_str(), hex(s.rva[3]).c_str());
        }
        for (int k = 0; k < 64 && s.tail != s.head.load(); ++k, ++s.tail) print(s.ring[s.tail % kRing]);
        if (const std::uint32_t d = s.dropped.exchange(0)) oxc::logf("[probe] %u hits dropped (ring full)\n", d);
    }

private:
    bool setup() {
        State& s = st();
        if (!oxc::hwbp::init()) { oxc::logf("[probe] could not install the exception handler; nothing armed\n"); return false; }
        s.base = oxc::hwbp::st().base;
        s.size = oxc::hwbp::st().size;
        if (const char* list = ::getenv("OXC_ACTIONPROBE_RVAS")) {
            std::string l(list);
            std::size_t p = 0;
            for (int k = 0; k < 4 && p < l.size(); ++k) {
                std::size_t q = l.find(',', p);
                s.rva[k] = std::strtoull(l.substr(p, q == std::string::npos ? std::string::npos : q - p).c_str(), nullptr, 16);
                if (q == std::string::npos) break;
                p = q + 1;
            }
        } else if (off::BUILD_VERSION == L"241-3") {
            // the action handlers' entries: RDX is each one's argument block, dumped in full
            // three, not four: the fourth debug register is left for actions.hpp's game-tick pump, so a
            // real click's arguments and doAction's can be compared in the same session
            s.rva[0] = 0x387620;   // walk ("Walk here"):            {level, x, y}
            s.rva[1] = 0x388870;   // NPC option:                    {uid, option, flag}
            s.rva[2] = 0x387cd0;   // object option:                 {level, x, y, id, option, flag}
        } else {
            oxc::logf("[probe] OXC_ACTIONPROBE=1 but no OXC_ACTIONPROBE_RVAS and no defaults for this build; nothing armed\n");
            return false;
        }
        // Roles by address (only meaningful when a custom list includes them).
        s.entryExecRva = 0x37d280;
        s.entryCopyRva = 0x37d090;
        s.packetRva = 0x6795c0;
        int armed = 0;
        for (std::uint64_t r : s.rva) {
            if (!r) continue;
            if (oxc::hwbp::add(r, record) < 0) oxc::logf("[probe] no free debug register (or bad rva) for %s; skipped\n", hex(r).c_str());
            else ++armed;
        }
        return armed > 0;
    }

    bool checked_ = false, enabled_ = false, loggedArm_ = false;
};

}  // namespace oxc::probe
