// overlay.hpp -- layered-window back buffers, and the one function that puts pixels in them.
//
// The interesting decision here is that C++ does NOT draw anything. Java hands us a finished image once
// a frame and we put it on the screen. That is the opposite of how most clients do it, and it is the
// reason a plugin can draw its overlay with ordinary Java2D -- shapes, alpha, antialiased text, fonts --
// instead of a handful of primitives someone had to expose one at a time.
//
// The cost is one memcpy of a full-screen image per frame. At 1920x1080 that is 8 MB, which sounds
// alarming and takes well under a millisecond. We were already blitting the same number of pixels when
// the drawing was done in C++.
//
// WHY A SEPARATE WINDOW instead of hooking the game's renderer: hooking needs a detour library, a
// graphics API to get right, and it crashes inside someone else's render loop when you get it wrong. A
// layered window is a page of code you can read in one sitting. The cost is that it will not appear in
// screenshots or recordings, and it can flicker. That trade is deliberate.
//
// There are TWO of these windows now: the game overlay (this file's g_overlay, fully click-through) and
// the control panel (panel.hpp, which must receive clicks). The buffer + present logic is shared, which
// is why it is a struct rather than a pile of globals: a Layered is one window's drawing surface, and
// each window owns one.
#pragma once
#include <windows.h>
#include <cstdint>
#include <cstring>

namespace oxc {

/// One layered window's back buffer: a DIB, its pixels, and the UpdateLayeredWindow call.
///
/// PREMULTIPLIED is not optional. UpdateLayeredWindow interprets the colour channels as already scaled
/// by alpha; hand it straight ARGB and every semi-transparent pixel comes out too bright, with pale
/// halos around text. Java has a pixel format for exactly this -- TYPE_INT_ARGB_PRE -- so the Java side
/// draws into one of those and no conversion happens anywhere.
struct Layered {
    HWND    hwnd    = nullptr;
    HDC     memDc   = nullptr;
    HBITMAP bitmap  = nullptr;
    void*   pixels  = nullptr;      // the DIB's own memory, ARGB, top-down
    int     width = 0, height = 0;

    /// Throw away the current back buffer. Safe to call when there isn't one.
    void release() {
        if (memDc)  { DeleteDC(memDc);   memDc = nullptr; }
        if (bitmap) { DeleteObject(bitmap); bitmap = nullptr; }
        pixels = nullptr;
        width = height = 0;
    }

    /// Make sure we have a back buffer of exactly this size. Recreates it when the size changes.
    bool ensure(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        if (memDc && w == width && h == height) return true;
        release();

        BITMAPINFO bi{};
        bi.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
        bi.bmiHeader.biWidth       = w;
        bi.bmiHeader.biHeight      = -h;          // negative = top-down, matching how Java lays out pixels
        bi.bmiHeader.biPlanes      = 1;
        bi.bmiHeader.biBitCount    = 32;
        bi.bmiHeader.biCompression = BI_RGB;

        HDC screen = GetDC(nullptr);
        memDc  = CreateCompatibleDC(screen);
        bitmap = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, &pixels, nullptr, 0);
        ReleaseDC(nullptr, screen);

        if (!memDc || !bitmap || !pixels) { release(); return false; }
        SelectObject(memDc, bitmap);
        width  = w;
        height = h;
        return true;
    }

    /// Copy `src` (w*h pixels of premultiplied ARGB) into the back buffer, WITHOUT presenting it.
    ///
    /// Split from show() on purpose, and the comment on nPresent in jvm.hpp is the why: the copy runs
    /// inside a GetPrimitiveArrayCritical window, and the JNI critical-region rule forbids anything in
    /// there that can block or allocate. ensure() (CreateDIBSection on a resize) and UpdateLayeredWindow
    /// (an X11 round trip under Wine, easily milliseconds) are both exactly that, so the caller takes
    /// the critical pointer, memcpys into a buffer that already exists, releases it, and only then
    /// calls show().
    bool copyIn(const void* src, int w, int h) {
        if (!hwnd || !src) return false;
        if (!ensure(w, h)) return false;
        std::memcpy(pixels, src, static_cast<std::size_t>(w) * h * 4);
        return true;
    }

    /// Put the last copied frame on the screen, at the window's current position. Callers that need the
    /// window moved (the panel follows the game's right edge) position it before calling.
    void show() {
        if (!hwnd || !pixels) return;

        POINT         srcPt{ 0, 0 };
        SIZE          size{ width, height };
        BLENDFUNCTION blend{ AC_SRC_OVER, 0, 255, AC_SRC_ALPHA };

        HDC screen = GetDC(nullptr);
        UpdateLayeredWindow(hwnd, screen, nullptr, &size, memDc, &srcPt, 0, &blend, ULW_ALPHA);
        ReleaseDC(nullptr, screen);
    }

    /// Copy `src` into the window and show it, at its current position.
    void present(const void* src, int w, int h) {
        if (copyIn(src, w, h)) show();
    }
};

/// The game overlay: covers the game's whole client area, fully click-through (see dllmain.cpp).
inline Layered g_overlay;

/// The window whose client rect IS the game's drawing canvas: NXT's JagRenderView child, with the
/// game frame as the fallback until that child exists. The projection answers in this window's
/// coordinates, the overlay must sit on this rect, and Java must size its image from it -- all four
/// call sites (followWindow, nViewport, nInput, nProject) must measure the SAME window, because the
/// overlay is positioned by one call and sized by another: two different answers make a layered
/// window that starts at the canvas and stretches across the whole host. Set every frame by
/// dllmain.cpp; null until then.
inline HWND g_canvasWindow = nullptr;

}  // namespace oxc

namespace oxc::overlay {

/// Move and resize the overlay to sit exactly on top of `game`'s client area. `game` may be a child
/// window (it is, once the host embeds it) -- ClientToScreen walks the parent chain, so the result is
/// still a screen point. Z-order is NOT touched here: pinAbove (called from dllmain.cpp's loop) keeps the
/// overlay just above the host window.
inline void followWindow(HWND game) {
    if (!g_overlay.hwnd || !game) return;
    RECT r{};
    GetClientRect(game, &r);
    POINT tl{ r.left, r.top };
    ClientToScreen(game, &tl);
    SetWindowPos(g_overlay.hwnd, nullptr, tl.x, tl.y, r.right - r.left, r.bottom - r.top,
                 SWP_NOZORDER | SWP_NOACTIVATE);
}

/// Keep the overlay directly ABOVE `host` in the z-order, moving only the overlay -- never the host.
///
/// Why: the loop used to do the opposite, every frame -- put the HOST directly behind the overlay. The
/// overlay itself never moves in the z-order, so when the user clicked the client to bring it forward,
/// the next frame sent it back down behind whatever the overlay sat under, and at launch the client sank
/// behind other apps (the user, 2026-10-10: "it still is weird about showing in front of other
/// applications"). Now the client is an ordinary window -- Windows decides where it is, as for any other
/// app -- and the overlay just follows it. Only touches the z-order when it is wrong, so nothing churns.
inline void pinAbove(HWND host) {
    if (!g_overlay.hwnd || !host || !IsWindow(host)) return;
    HWND above = GetWindow(host, GW_HWNDPREV);            // the window right above the host
    if (above == g_overlay.hwnd) return;                    // already in place
    // "insert after X" means "just below X": below the window above the host = just above the host
    SetWindowPos(g_overlay.hwnd, above ? above : HWND_TOP, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
}

}  // namespace oxc::overlay
