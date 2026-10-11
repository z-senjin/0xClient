#pragma once
// Bringing our window to the front when the game is embedded into it.
//
// WHY: the game window is the one Windows lets come to the front -- it is the new process's first
// window. Then it is reparented into our window as a child, and a child cannot be the foreground
// window. Nothing activates our top-level window, so it can be left behind whatever had the front
// before. The user saw it on 2026-10-10: open the client, and it sits behind other applications.
// The two places that embed the game (the launcher's embedGame and the DLL's own host window) call
// bringToFront right after the reparent.
//
// HOW: Windows only lets the thread that owns the foreground window give it away, so we join that
// thread's input queue for the call (the usual AttachThreadInput pattern), raise and activate the
// window, and leave the queue again -- unless it is a queue we had already joined on purpose (the
// game's, for keyboard focus), which must stay joined.
#include <windows.h>
#include <cstdio>

namespace oxc {

/// Raise and activate a top-level window. `keepAttachedTid` is a thread whose input queue the caller
/// has joined on purpose (0 for none); it is never detached here. Returns whether it ended up in front.
inline bool bringToFront(HWND h, DWORD keepAttachedTid = 0) {
    if (!h || !IsWindow(h)) return false;
    if (IsIconic(h)) ShowWindow(h, SW_RESTORE);
    const DWORD me = GetCurrentThreadId();
    HWND fg = GetForegroundWindow();
    const DWORD fgTid = fg ? GetWindowThreadProcessId(fg, nullptr) : 0;
    const bool join = fgTid && fgTid != me && fgTid != keepAttachedTid;
    const bool joined = join && AttachThreadInput(me, fgTid, TRUE);
    SetWindowPos(h, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
    BringWindowToTop(h);
    const bool ok = SetForegroundWindow(h) != FALSE;
    SetActiveWindow(h);
    if (joined) AttachThreadInput(me, fgTid, FALSE);
    if (::getenv("OXC_LOG"))
        std::printf("[window] bring to front: %s (foreground was %p, %s)\n", ok ? "done" : "refused by Windows",
                    (void*)fg, joined ? "joined its queue" : "no join needed");
    return ok || GetForegroundWindow() == h;
}

}  // namespace oxc
