// hwbp.hpp -- the CPU's four hardware execute breakpoints, shared by everything in the DLL that needs
// to run something at a precise point in the game's own code without patching a byte of it.
//
// Two users today: actionprobe.hpp (hook-and-log, OXC_ACTIONPROBE=1) and actions.hpp (runs queued menu
// actions on the game thread, from the client's per-frame tick). Each registers an RVA and a callback;
// this file owns DR0..DR3 on every game thread and the one vectored exception handler that routes a
// hit to its callback.
//
// The callback runs ON THE GAME THREAD that hit the breakpoint, inside exception dispatch, before the
// instruction at the breakpoint executes. It may read memory and call into the game (that is the
// point of actions.hpp); it must not block, allocate unboundedly or call logf -- leave a record and let
// the DLL loop print it.
#pragma once
#include <windows.h>
#include <tlhelp32.h>
#include <cstdint>

namespace oxc::hwbp {

using Callback = void (*)(CONTEXT*);

struct Slot {
    std::uint64_t rva = 0;
    Callback fn = nullptr;
};

struct State {
    std::uint64_t base = 0, size = 0;
    Slot slots[4];
    PVOID veh = nullptr;
    std::uint64_t nextArm = 0;
    int lastArmed = 0;
};

inline State& st() {
    static State s;
    return s;
}

inline LONG CALLBACK onException(EXCEPTION_POINTERS* ep) {
    if (ep->ExceptionRecord->ExceptionCode != EXCEPTION_SINGLE_STEP) return EXCEPTION_CONTINUE_SEARCH;
    CONTEXT* c = ep->ContextRecord;
    const DWORD64 dr6 = c->Dr6;
    if (!(dr6 & 0xF)) return EXCEPTION_CONTINUE_SEARCH;       // a real single-step, not ours
    const int k = (dr6 & 1) ? 0 : (dr6 & 2) ? 1 : (dr6 & 4) ? 2 : 3;
    State& s = st();
    if (s.slots[k].fn && c->Rip == s.base + s.slots[k].rva) s.slots[k].fn(c);
    c->Dr6 = 0;
    c->EFlags |= 0x10000;   // RF: resume without re-triggering this execute breakpoint
    return EXCEPTION_CONTINUE_EXECUTION;
}

inline bool init() {
    State& s = st();
    if (s.base) return true;
    HMODULE m = GetModuleHandleW(nullptr);
    auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(m);
    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS64*>(reinterpret_cast<unsigned char*>(m) + dos->e_lfanew);
    s.size = nt->OptionalHeader.SizeOfImage;
    s.veh = AddVectoredExceptionHandler(1, onException);
    if (!s.veh) return false;
    s.base = reinterpret_cast<std::uint64_t>(m);
    return true;
}

/// Claim a free debug register for `rva` (an RVA inside the game image). Returns the slot, or -1 when
/// all four are taken, the RVA is 0 / outside the image, or the handler could not be installed.
inline int add(std::uint64_t rva, Callback fn) {
    if (!rva || !init()) return -1;
    State& s = st();
    if (rva >= s.size) return -1;
    for (int k = 0; k < 4; ++k) {
        if (s.slots[k].rva == rva) { s.slots[k].fn = fn; return k; }
    }
    for (int k = 0; k < 4; ++k) {
        if (!s.slots[k].rva) {
            s.slots[k].rva = rva;
            s.slots[k].fn = fn;
            s.nextArm = 0;   // arm on the next tick
            return k;
        }
    }
    return -1;
}

inline int freeSlots() {
    int n = 0;
    for (const Slot& sl : st().slots) n += sl.rva == 0;
    return n;
}

/// Write DR0..DR3/DR7 on every thread of the game except the caller. Threads the game starts later
/// get them on the next call, so tick() repeats this every two seconds.
inline int armAllThreads() {
    State& s = st();
    const DWORD pid = GetCurrentProcessId(), self = GetCurrentThreadId();
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    THREADENTRY32 te{sizeof te};
    int n = 0;
    for (BOOL ok = Thread32First(snap, &te); ok; ok = Thread32Next(snap, &te)) {
        if (te.th32OwnerProcessID != pid || te.th32ThreadID == self) continue;
        HANDLE t = OpenThread(THREAD_GET_CONTEXT | THREAD_SET_CONTEXT | THREAD_SUSPEND_RESUME, FALSE, te.th32ThreadID);
        if (!t) continue;
        if (SuspendThread(t) != static_cast<DWORD>(-1)) {
            alignas(16) CONTEXT c{};
            c.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            if (GetThreadContext(t, &c)) {
                DWORD64 dr7 = 0;
                DWORD64* drs[4] = {&c.Dr0, &c.Dr1, &c.Dr2, &c.Dr3};
                for (int k = 0; k < 4; ++k) {
                    *drs[k] = s.slots[k].rva ? s.base + s.slots[k].rva : 0;
                    if (s.slots[k].rva) dr7 |= 1ull << (k * 2);   // local enable; RW=00 execute, LEN=00
                }
                c.Dr7 = dr7;
                if (SetThreadContext(t, &c)) ++n;
            }
            ResumeThread(t);
        }
        CloseHandle(t);
    }
    CloseHandle(snap);
    return n;
}

/// Call from the DLL loop. Returns the number of threads armed when it re-armed this call, else -1.
inline int tick() {
    State& s = st();
    if (!s.base) return -1;
    bool any = false;
    for (const Slot& sl : s.slots) any = any || sl.rva;
    if (!any) return -1;
    const std::uint64_t now = GetTickCount64();
    if (now < s.nextArm) return -1;
    s.nextArm = now + 2000;
    s.lastArmed = armAllThreads();
    return s.lastArmed;
}

}  // namespace oxc::hwbp
