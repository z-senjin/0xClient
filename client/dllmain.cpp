// dllmain.cpp -- 0xClient's native half.
//
// Injected into the game, it does four things and then gets out of the way:
//   1. starts a Java VM and registers the natives (jvm.hpp),
//   2. finds the game window,
//   3. puts a transparent always-on-top window over it (overlay.hpp),
//   4. calls oxclient.OxClient.tick() about thirty times a second.
//
// Notice what is NOT here any more: drawing. Java renders the whole overlay into an image and hands it
// back through present(). Everything you would actually want to CHANGE lives in java/.
//
// The one exception is the error path at the bottom. If Java never came up, Java cannot tell you why --
// so a few lines of GDI put the reason on screen. That is worth the duplication; a client that fails
// silently is a client nobody can fix.
//
// There are two ways this DLL ends up running, and it decides which at startup:
//
//   DIRECT INJECT  -- osclient.exe was started on its own and the DLL was injected into it (wine_inject).
//                     Everything here builds its own host window and its own Java panel, exactly as it
//                     always did. This path must not change behaviour.
//   LAUNCHER MODE  -- 0xClient.exe spawned this game and wants the game as a child of ITS window, with
//                     an ImGui panel it draws itself. The DLL then creates no host and no panel window:
//                     the launcher IS the host, the panel's data crosses the shared-memory bridge
//                     (bridge.hpp), and this DLL keeps the parts that must live in the game process --
//                     the JVM, the overlay, the natives, the cursor and focus work.
#include <windows.h>
#include <windowsx.h>
#include <tlhelp32.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cwchar>
#include <string>
#include <vector>
#include "game.hpp"
#include "overlay.hpp"
#include "panel.hpp"
#include "jvm.hpp"
#include "bridge.hpp"
#include "offsets_json.hpp"
#include "selfcheck.hpp"
#include "actionprobe.hpp"

namespace {

HWND        g_game = nullptr;
HWND        g_host = nullptr;     // the one top-level window: game and panel are children of it.
                                  // In launcher mode this is the LAUNCHER's window, never created here.
HWND        g_renderView = nullptr;   // NXT's GL child -- the window that must hold keyboard focus
std::string g_javaError;      // shown natively if the VM never started
bool        g_launcherMode = false;

// SidePanel's width is TAB_W(36) + BODY_W(250) in Java -- keep the two in step.
constexpr int PANEL_W = 286;

// One top-level window, everything inside it: the game is reparented in as a child and the panel
// docks into the right strip. That is the whole fix for "the panel floats over my screen and I can't
// minimize it" -- a child window moves, minimizes, restores and closes with its parent by definition.
// It is also how RuneLite's sidebar actually behaves, because there the sidebar and canvas share one
// window. The game keeps rendering into its own window the whole time; we only decide where it lives.
static int g_setGameW = -1, g_setGameH = -1;   // what layoutEmbed last told the game to be

void layoutEmbed() {
    if (!g_host || !g_game || !IsWindow(g_host) || !IsWindow(g_game)) return;
    RECT cc{};
    GetClientRect(g_host, &cc);
    int cw = cc.right - cc.left, ch = cc.bottom - cc.top;
    int gameW = cw - PANEL_W;
    if (gameW < 800) return;                       // never squeeze the game to nothing
    SetWindowPos(g_game, nullptr, 0, 0, gameW, ch, SWP_NOZORDER | SWP_NOACTIVATE);
    g_setGameW = gameW;
    g_setGameH = ch;
    // The panel is a top-level window owned by the host (see the creation site for why), so its dock
    // position is in screen coordinates, not host-client coordinates.
    if (oxc::panel::g_panel.hwnd && IsWindow(oxc::panel::g_panel.hwnd)) {
        POINT org{ 0, 0 };
        ClientToScreen(g_host, &org);
        SetWindowPos(oxc::panel::g_panel.hwnd, nullptr, org.x + gameW, org.y, PANEL_W, ch,
                     SWP_NOZORDER | SWP_NOACTIVATE);
    }
}

// Defined below; the host's WM_ACTIVATE needs it.
void giveGameFocus();

// The host's close button must not DestroyWindow the game out from under its own thread: ask the game
// to close itself, the way a real close would. NXT tears its process down, our loop sees the window
// go, and everything unwinds cleanly.
LRESULT CALLBACK hostProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_CLOSE && g_game && IsWindow(g_game)) {
        PostMessageW(g_game, WM_CLOSE, 0, 0);
        return 0;
    }
    if (m == WM_SIZE) layoutEmbed();
    // Client-area cursor on the host itself: the class-cursor path leaves a blank under Wine (same
    // trace as the panel). Non-client areas -- borders, caption -- still get DefWindowProc's resize
    // and size-all cursors, which work fine.
    if (m == WM_SETCURSOR && LOWORD(l) == HTCLIENT) {
        SetCursor(LoadCursorW(nullptr, MAKEINTRESOURCEW(32512)));    // IDC_ARROW
        return TRUE;
    }
    if (m == WM_ACTIVATE && LOWORD(w) != WA_INACTIVE) {
        // DefWindowProc FIRST: its WM_ACTIVATE handling sets focus to the host, silently undoing
        // anything set before it (traced live: our SetFocus, then DefWindowProc's SETFOCUS-to-host,
        // keys landing on the host ever after).
        LRESULT r = DefWindowProcW(h, m, w, l);
        giveGameFocus();
        return r;
    }
    return DefWindowProcW(h, m, w, l);
}

// Why the queues are joined: keyboard input goes to the FOREGROUND queue's focus window. Clicking the
// game child activates the host (our thread's queue becomes the foreground one), so focus on a window
// of the game's separate queue could never receive a keystroke -- traced live: keys landed on the host
// as WM_KEYDOWN while the game held focus in its own queue. Attaching the two queues permanently merges
// them into one, so focus is one shared value and NXT's own focus handling (it keeps focus on its
// JagRenderView child) works as it always did. A temporary attach was tried and is actively harmful:
// detaching handed focus straight back to the host.
void giveGameFocus() {
    if (g_renderView && IsWindow(g_renderView)) SetFocus(g_renderView);
}

// ------------------------------------------------------------------------------------------------
// Find the game's window: the biggest visible top-level window this process owns.
// ------------------------------------------------------------------------------------------------
BOOL CALLBACK pickWindow(HWND h, LPARAM lp) {
    DWORD pid = 0;
    GetWindowThreadProcessId(h, &pid);
    if (pid != GetCurrentProcessId() || !IsWindowVisible(h) || GetWindow(h, GW_OWNER)) return TRUE;
    // Our OWN top-levels are in this process too. Nothing looked at them before, because the only
    // caller ran before either existed -- but the frame loop re-runs this search when the game's
    // window is recreated (review 2026-09-06), and by then the direct-inject host is the biggest
    // window we own (game + panel strip). Adopting it as "the game" would SetParent it into itself.
    // The panel is an OWNED popup and the bridge window is hidden, so the two below are enough.
    if (h == g_host || h == oxc::g_overlay.hwnd) return TRUE;
    RECT r{};
    GetClientRect(h, &r);
    long area = (r.right - r.left) * (r.bottom - r.top);
    auto* best = reinterpret_cast<std::pair<HWND, long>*>(lp);
    if (area > best->second) *best = { h, area };
    return TRUE;
}

HWND findGameWindow() {
    std::pair<HWND, long> best{ nullptr, 0 };
    EnumWindows(pickWindow, reinterpret_cast<LPARAM>(&best));
    return best.first;
}

// EnumWindows only enumerates TOP-LEVEL windows, so a game the launcher already embedded is invisible
// to findGameWindow -- which would be exactly the situation launcher mode cares about, if the launcher
// embedded before this DLL's find loop ever ran. This fallback looks one level down: a window of ours
// that has NXT's JagRenderView child is the game no matter whose child it is. Only consulted after the
// top-level search has come up empty for a couple of seconds, so the normal path pays nothing for it.
BOOL CALLBACK pickEmbedded(HWND h, LPARAM lp) {
    DWORD pid = 0;
    GetWindowThreadProcessId(h, &pid);
    if (pid != GetCurrentProcessId()) return TRUE;
    if (!GetAncestor(h, GA_ROOT) || GetAncestor(h, GA_ROOT) == h) return TRUE;   // not embedded
    wchar_t cls[64] = L"";
    GetClassNameW(h, cls, 64);
    if (wcsncmp(cls, L"Jag", 3) != 0 && !FindWindowExW(h, nullptr, L"JagRenderView", nullptr)) return TRUE;
    *reinterpret_cast<HWND*>(lp) = h;
    return FALSE;                                        // found it: stop the walk
}

HWND findEmbeddedGameWindow() {
    HWND found = nullptr;
    // Walk EVERY top-level window's children, whoever owns the top-level: once the launcher has
    // SetParent'ed the game into its own window, the game window's root belongs to the LAUNCHER's
    // pid, so a walk restricted to our own pid's top-levels can never reach it. (Seen live on
    // Windows 2026-09-05: the launcher embeds within milliseconds of injecting, this thread found
    // nothing for 60 s and gave up, and the strip sat on "waiting for the DLL bridge" forever.)
    // pickEmbedded does the pid filtering on the children themselves.
    EnumWindows([](HWND top, LPARAM lp) -> BOOL {
        EnumChildWindows(top, pickEmbedded, lp);
        return *reinterpret_cast<HWND*>(lp) == nullptr;    // stop once found
    }, reinterpret_cast<LPARAM>(&found));
    return found;
}

// ------------------------------------------------------------------------------------------------
// Launcher-mode detection.
//
// The launcher spawns osclient.exe, injects this DLL, and then embeds the game's top-level window
// into its own. It signals its intent BEFORE the SetParent (afterwards the game window is a child,
// and EnumWindows never enumerates children), in two ways, either of which is accepted:
//
//   1. PostMessageW of the registered "0xClientEmbed" message to the game's top-level window,
//      carrying the launcher's hwnd. To hear it we hold a temporary-looking subclass on that window
//      (gameTopProc) which forwards everything else untouched -- the same shape as the render-view
//      subclass, and for the same reason: it is the only way to see a message the game's own proc
//      would drop. Because a posted message can beat the subclass into place by a few milliseconds
//      (both sides are polling for the same window at 100 ms), the launcher is expected to post the
//      message a few times over its first couple of seconds.
//   2. SetPropW(gameHwnd, L"0xClientLauncherHwnd", launcherHwnd), read here with GetPropW.
//
// A third signal needs no cooperation at all: if the game window is ALREADY a child when we look at
// it, the launcher won the race and its root window is the host.
// ------------------------------------------------------------------------------------------------

UINT    g_msgEmbed        = 0;   // registered once, used by gameTopProc
HWND    g_launcherHwnd    = nullptr;   // set by any of the three signals
WNDPROC g_gameProcOriginal = nullptr;

LRESULT CALLBACK gameTopProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == g_msgEmbed && g_msgEmbed) {
        // The launcher's hwnd. wParam is where a message like this naturally carries it, but the
        // contract says only "carrying its hwnd" -- so accept it from lParam too. IsWindow is the
        // filter that makes accepting either safe: a random other message with a coincidental value
        // fails the check, and a genuine hwnd from the launcher passes.
        HWND from = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(w));
        if (!from || !IsWindow(from)) from = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(l));
        if (from && IsWindow(from)) g_launcherHwnd = from;
        return 0;
    }
    return CallWindowProcW(g_gameProcOriginal, h, m, w, l);
}

/// Start listening for the embed message. Installed on the game's top-level window, whose proc is
/// NXT's; the subclass only ever touches our own registered message and hands the rest straight back,
/// so it is inert from the game's point of view. Kept for the life of the session -- removing it
/// would race messages already in flight, and it costs one comparison per message.
void watchForLauncher(HWND game) {
    if (g_msgEmbed == 0) g_msgEmbed = RegisterWindowMessageW(L"0xClientEmbed");
    if (g_gameProcOriginal) return;
    g_gameProcOriginal = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(game, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(gameTopProc)));
}

/// Re-resolve the game window after NXT destroyed and recreated it, and re-arm everything that was
/// bound to the OLD handle. NXT replaces its main window during boot (live 2026-09-05: a few hundred
/// ms after injection), and every path here used to treat that as "the game closed" -- the wait loop
/// polled a dead handle for 30 s, the frame loop returned and tore the overlay, the bridge and the
/// JVM tick down while the game ran on, and the launcher happily re-embedded a window whose DLL was
/// already gone (review 2026-09-06). Returns true when a different, live window was adopted.
bool reacquireGameWindow() {
    HWND w = findGameWindow();
    if (!w) w = findEmbeddedGameWindow();               // the launcher may have embedded it already
    if (!w || w == g_game) return false;
    g_game = w;
    oxc::g_gameWindow = g_game;
    g_gameProcOriginal = nullptr;   // the embed-message subclass died with the old window
    watchForLauncher(g_game);       // ... so put it back on the new one
    return true;
}

/// True when the game window is already somebody's child -- i.e. the launcher has embedded it. `root`
/// receives that somebody's top-level window.
bool alreadyEmbedded(HWND game, HWND& root) {
    HWND r = GetAncestor(game, GA_ROOT);
    if (r == game || !r) return false;
    root = r;
    return true;
}

/// The launcher's hwnd, if it staked a claim on the game window with the property fallback.
HWND launcherProp(HWND game) {
    HANDLE p = GetPropW(game, L"0xClientLauncherHwnd");
    return p ? reinterpret_cast<HWND>(p) : nullptr;
}

/// Were we spawned by 0xClient.exe? Decides how long we are willing to WAIT for a signal.
///
/// Direct injection must behave exactly as it always has, which means no startup delay: inject, find
/// the window, build the host. But a launcher-spawned game must give the launcher time to signal, and
/// guessing wrong in THAT direction is far worse -- a host window built here would sit between the
/// launcher's SetParent and everything that follows. So: when the parent process is 0xClient.exe,
/// wait up to thirty seconds for a signal; otherwise do not wait at all. Toolhelp is the cheapest way
/// to a parent pid that works under Wine.
bool launcherSpawnedUs() {
    DWORD myPid = GetCurrentProcessId();
    DWORD parentPid = 0;
    wchar_t parentExe[MAX_PATH] = L"";

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return false;
    PROCESSENTRY32W pe{ sizeof pe };
    // Two passes over one snapshot: find our parent pid, then our parent's exe name. One pass cannot
    // do both, because the parent entry may sit anywhere in the list.
    for (BOOL ok = Process32FirstW(snap, &pe); ok; ok = Process32NextW(snap, &pe)) {
        if (pe.th32ProcessID == myPid) { parentPid = pe.th32ParentProcessID; break; }
    }
    if (parentPid) {
        for (BOOL ok = Process32FirstW(snap, &pe); ok; ok = Process32NextW(snap, &pe)) {
            if (pe.th32ProcessID == parentPid) {
                lstrcpynW(parentExe, pe.szExeFile, MAX_PATH);
                break;
            }
        }
    }
    CloseHandle(snap);

    // Case-insensitive basename compare: the launcher ships as 0xClient.exe, and a renamed copy is
    // still recognisable by its base name. A parent that has already exited (pid reuse aside) simply
    // will not be found, and we then behave as direct inject -- which is the safe default.
    wchar_t* base = parentExe;
    for (wchar_t* c = parentExe; *c; ++c) if (*c == L'\\') base = c + 1;
    return lstrcmpiW(base, L"0xClient.exe") == 0;
}

/// Decide the mode. Returns true (and sets g_host) only when a launcher signalled. Sets g_game first
/// if the window turned out to have been embedded before we could enumerate it as a top-level.
bool detectLauncherMode() {
    HWND root = nullptr;
    if (alreadyEmbedded(g_game, root)) { g_host = root; return true; }
    HWND prop = launcherProp(g_game);
    if (prop && IsWindow(prop)) { g_host = prop; return true; }

    watchForLauncher(g_game);
    if (g_launcherHwnd && IsWindow(g_launcherHwnd)) { g_host = g_launcherHwnd; return true; }

    // No signal yet. Without a launcher in our ancestry this is direct inject -- the common case, and
    // the one that must not grow a startup delay. With one, wait: the launcher is polling for the
    // same window we just found and will signal within milliseconds of seeing it.
    if (!launcherSpawnedUs()) return false;
    // 30 s of patience. A host window built here while the launcher is still coming would end up
    // between the launcher's SetParent and everything that follows, which is the one outcome worse
    // than a slow start; a launcher that signalled and then died within thirty seconds is not a
    // launcher worth second-guessing.
    for (int i = 0; i < 300; ++i) {
        // The window we are waiting on can be replaced under us mid-boot. Polling the dead handle for
        // the rest of the 30 s meant every signal (embed message, property, already-a-child) was
        // being read off a window that no longer existed, and the fallback then built a host around a
        // zero rect and SetParent'ed a dead hwnd. Re-resolve first, then test the signals on the
        // window that actually exists (review 2026-09-06).
        if (!IsWindow(g_game) && !reacquireGameWindow()) { Sleep(100); continue; }
        if (alreadyEmbedded(g_game, root)) { g_host = root; return true; }
        prop = launcherProp(g_game);
        if (prop && IsWindow(prop)) { g_host = prop; return true; }
        if (g_launcherHwnd && IsWindow(g_launcherHwnd)) { g_host = g_launcherHwnd; return true; }
        MSG m;                                           // our own queue, if a signal ever lands there
        while (PeekMessageW(&m, nullptr, 0, 0, PM_REMOVE)) DispatchMessageW(&m);
        Sleep(100);
    }
    return false;
}

// ------------------------------------------------------------------------------------------------
// The only drawing C++ still does: telling you why Java is not drawing.
// ------------------------------------------------------------------------------------------------
void renderJavaError(int w, int h) {
    if (!oxc::g_overlay.ensure(w, h)) return;

    // Wrap the message: it carries a full filesystem path now, which is the whole point of it, and a
    // path clipped at the panel edge hides exactly the character that is wrong.
    const std::size_t kWrap = 74;
    std::vector<std::string> body;
    for (std::size_t i = 0; i < g_javaError.size(); i += kWrap) {
        body.push_back(g_javaError.substr(i, kWrap));
    }
    if (body.empty()) body.push_back("(no reason given)");
    body.push_back("");
    body.push_back("Fix java= in 0xclient.ini, next to this DLL, then restart the game.");

    const int lineH = 18;
    int panelH = 34 + lineH * static_cast<int>(body.size()) + 10;

    // Start from fully transparent, then paint an opaque panel. Every pixel we touch needs alpha 255
    // or UpdateLayeredWindow will treat it as invisible.
    std::memset(oxc::g_overlay.pixels, 0, static_cast<std::size_t>(w) * h * 4);

    HDC dc = oxc::g_overlay.memDc;
    RECT panel{ 10, 10, 640, 10 + panelH };
    HBRUSH bg = CreateSolidBrush(RGB(24, 24, 28));
    FillRect(dc, &panel, bg);
    DeleteObject(bg);

    SetBkMode(dc, TRANSPARENT);
    HFONT font = CreateFontA(15, 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET,
                             OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                             FF_DONTCARE, "Consolas");
    HGDIOBJ oldFont = SelectObject(dc, font);

    SetTextColor(dc, RGB(255, 120, 120));
    TextOutA(dc, 22, 20, "0xClient: Java did not start", 30);

    int y = 44;
    for (std::size_t i = 0; i < body.size(); ++i) {
        SetTextColor(dc, i + 1 == body.size() ? RGB(140, 140, 150) : RGB(220, 220, 226));
        TextOutA(dc, 22, y, body[i].c_str(), static_cast<int>(body[i].size()));
        y += lineH;
    }

    SelectObject(dc, oldFont);
    DeleteObject(font);

    // GDI text leaves the alpha byte at zero, which would make everything we just drew invisible.
    // Force the panel opaque.
    auto* px = static_cast<std::uint32_t*>(oxc::g_overlay.pixels);
    for (int y = panel.top; y < panel.bottom && y < h; ++y) {
        for (int x = panel.left; x < panel.right && x < w; ++x) {
            px[static_cast<std::size_t>(y) * w + x] |= 0xFF000000u;
        }
    }

    POINT         srcPt{ 0, 0 };
    SIZE          size{ w, h };
    BLENDFUNCTION blend{ AC_SRC_OVER, 0, 255, AC_SRC_ALPHA };
    HDC screen = GetDC(nullptr);
    UpdateLayeredWindow(oxc::g_overlay.hwnd, screen, nullptr, &size, dc, &srcPt, 0, &blend, ULW_ALPHA);
    ReleaseDC(nullptr, screen);
}

// ------------------------------------------------------------------------------------------------
// Keys. Edge-detected, so holding a key toggles once. All of them go to Java as a bitmask -- the
// native side has no opinion about what F1 means any more, because the plugins decide that.
// ------------------------------------------------------------------------------------------------
int pollKeys() {
    static bool prev[8] = {};
    const int vks[8] = { VK_F1, VK_F2, VK_F3, VK_F4, VK_F5, VK_F6, VK_F7, VK_F8 };
    int mask = 0;
    for (int i = 0; i < 8; ++i) {
        bool down = (GetAsyncKeyState(vks[i]) & 0x8000) != 0;
        if (down && !prev[i]) mask |= 1 << i;      // F1 -> bit 0
        prev[i] = down;
    }
    return mask;
}

LRESULT CALLBACK wndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }

    // No WM_SETCURSOR handler here on purpose. The old one called SetCursor(nullptr) over the panel
    // (the software-cursor workaround), which is what actually blanked the pointer over the panel --
    // probed live with an XFixes oracle: with the handler gone and DefWindowProc applying the class
    // cursor (0xClientOverlay registers hCursor = IDC_ARROW), the panel shows a real 48x48 left_ptr
    // and the game area keeps its cursor too. Java's SidePanel.drawCursor arrow and the leave-detect
    // poll in run() are now redundant and should be deleted.

    // The panel is an opaque child window now, so it gets real WM_PAINTs (uncovered, restored,
    // resized). Re-blit the last frame Java gave us rather than showing garbage until the next
    // tick -- the DIB back buffer holds it.
    if (h == oxc::panel::g_panel.hwnd && m == WM_PAINT) {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(h, &ps);
        if (oxc::panel::g_panel.memDc && oxc::panel::g_panel.width > 0)
            BitBlt(dc, 0, 0, oxc::panel::g_panel.width, oxc::panel::g_panel.height,
                   oxc::panel::g_panel.memDc, 0, 0, SRCCOPY);
        EndPaint(h, &ps);
        return 0;
    }

    // The panel window forwards its mouse events to Java. The game overlay never gets here with a
    // mouse message -- it is WS_EX_TRANSPARENT, so Windows routes clicks around it entirely.
    if (h == oxc::panel::g_panel.hwnd) {
        // Clicking the panel must not move keyboard focus into THIS thread's queue: focus here is
        // dead for the game (its input lives in the game thread's queue), and a user who clicks the
        // panel and then tries to type at the login screen would get nothing. Keep focus on the game.
        if (m == WM_MOUSEACTIVATE) {
            giveGameFocus();
            return MA_NOACTIVATE;
        }
        POINT p{ GET_X_LPARAM(l), GET_Y_LPARAM(l) };
        if (m == WM_MOUSEWHEEL) {                       // wheel coords arrive in screen space
            p = POINT{ GET_X_LPARAM(l), GET_Y_LPARAM(l) };
            ScreenToClient(h, &p);
            oxc::panelMouse(p.x, p.y, 4, GET_WHEEL_DELTA_WPARAM(w) > 0);
            return 0;
        }
        switch (m) {
            case WM_MOUSEMOVE:   oxc::panelMouse(p.x, p.y, 0, false); return 0;
            case WM_LBUTTONDOWN: oxc::panelMouse(p.x, p.y, 1, true);  return 0;
            case WM_LBUTTONUP:   oxc::panelMouse(p.x, p.y, 1, false); return 0;
            case WM_MBUTTONDOWN: oxc::panelMouse(p.x, p.y, 2, true);  return 0;
            case WM_RBUTTONDOWN: oxc::panelMouse(p.x, p.y, 3, true);  return 0;
            default: break;
        }
    }
    return DefWindowProcW(h, m, w, l);
}

std::wstring iniString(const std::wstring& ini, const wchar_t* key, const wchar_t* fallback) {
    wchar_t buf[MAX_PATH]{};
    GetPrivateProfileStringW(L"0xclient", key, fallback, buf, MAX_PATH, ini.c_str());
    return buf;
}

// ------------------------------------------------------------------------------------------------
// The cursor over the game. NXT's render-view window proc sets its own cursor on WM_SETCURSOR --
// the SetCursor import has exactly two call sites, both in that handler, feeding it either a
// pending custom handle or a cached current one. Under Wine one of those comes back blank, and
// the mouse disappears over the game. OSRS's cursor is the arrow everywhere inside the canvas, so
// we take the message first and force it; NXT's handler never runs, which it does not miss.
// ------------------------------------------------------------------------------------------------
WNDPROC g_rvProcOriginal = nullptr;

LRESULT CALLBACK renderViewProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_SETCURSOR) {
        SetCursor(LoadCursorW(nullptr, MAKEINTRESOURCEW(32512)));    // IDC_ARROW
        return TRUE;
    }
    return CallWindowProcW(g_rvProcOriginal, h, m, w, l);
}

// Join the game's input queues with ours, permanently, so keyboard focus is one shared value (see
// giveGameFocus for why). NXT uses TWO threads here: JagWindow lives on one, its JagRenderView child
// on another (traced live), and both must be attached or SetFocus on the render view fails against
// the un-merged third queue. Then hand focus to the render child right away: the login screen must
// accept typing before the user has any reason to click.
//
// Shared by both modes -- the launcher does not create our input for us, and a game sitting in the
// launcher's window needs exactly the same queue-joining and cursor fix as one sitting in ours.
void attachInput() {
    g_renderView = FindWindowExW(g_game, nullptr, L"JagRenderView", nullptr);
    DWORD tidTop = GetWindowThreadProcessId(g_game, nullptr);
    DWORD tidRv  = g_renderView ? GetWindowThreadProcessId(g_renderView, nullptr) : 0;
    DWORD myTid  = GetCurrentThreadId();
    if (tidTop) AttachThreadInput(myTid, tidTop, TRUE);
    if (tidRv && tidRv != tidTop) AttachThreadInput(myTid, tidRv, TRUE);
    giveGameFocus();
    // Button-press latch for nInput (jvm.hpp): the render view's thread is the one that receives
    // the clicks, so hook that one (falls back to the top-level's when there is no render view).
    oxc::installMouseLatch(g_renderView ? g_renderView : g_game);

    // Subclass the render view so the cursor over the game is always a real arrow (see
    // renderViewProc). After the input-queue attaches, so the subclass cannot disturb anything
    // NXT's startup does with the window. The "not already ours" test matters since this function
    // runs again after a window recreation (review 2026-09-06): subclassing the SAME window twice
    // would store renderViewProc as its own original and CallWindowProcW would recurse forever.
    if (g_renderView && IsWindow(g_renderView) &&
        reinterpret_cast<WNDPROC>(GetWindowLongPtrW(g_renderView, GWLP_WNDPROC)) != renderViewProc) {
        g_rvProcOriginal = reinterpret_cast<WNDPROC>(
            SetWindowLongPtrW(g_renderView, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(renderViewProc)));
    }
}

// The host exe's FileVersion string ("240-6"), read off its own PE version resource. This is the one
// thing about the build we can check WITHOUT trusting offsets.hpp first, so it is what the refusal
// below keys on. Empty when the exe carries no version resource at all.
std::wstring hostFileVersion() {
    wchar_t exe[MAX_PATH]{};
    if (!GetModuleFileNameW(nullptr, exe, MAX_PATH)) return L"";
    DWORD size = GetFileVersionInfoSizeW(exe, nullptr);
    if (!size) return L"";
    std::vector<unsigned char> buf(size);
    if (!GetFileVersionInfoW(exe, 0, size, buf.data())) return L"";
    // The string table is keyed by language+codepage; ask the translation table which one exists
    // rather than guessing 040904B0.
    struct Lang { WORD lang, cp; };
    Lang* langs = nullptr; UINT langBytes = 0;
    if (!VerQueryValueW(buf.data(), L"\\VarFileInfo\\Translation", reinterpret_cast<void**>(&langs), &langBytes)
        || langBytes < sizeof(Lang)) return L"";
    for (UINT i = 0; i < langBytes / sizeof(Lang); ++i) {
        wchar_t key[64];
        std::swprintf(key, 64, L"\\StringFileInfo\\%04x%04x\\FileVersion", langs[i].lang, langs[i].cp);
        wchar_t* val = nullptr; UINT valLen = 0;
        if (VerQueryValueW(buf.data(), key, reinterpret_cast<void**>(&val), &valLen) && val && valLen)
            return std::wstring(val, wcsnlen(val, valLen));
    }
    return L"";
}

// Refuse a client this DLL was not measured on. Every number in offsets.hpp is for one build; against
// any other, a struct offset reads a plausible wrong value and an RVA call crashes the game. Returns
// the message to show, or "" when the build matches. OXC_SKIP_BUILD_CHECK=1 overrides -- for the
// deob workflow, where running the DLL against a NEW build to hook-and-log is the whole point -- and
// says so in the log, so a wrong number can never masquerade as a logic bug quietly.
std::string checkBuild(const std::wstring& dllDir) {
    const std::wstring have = hostFileVersion();
    const std::string shown = have.empty() ? "(no version resource)" : oxc::narrow(have);

    // First choice: a per-build offsets file for exactly this exe. It replaces every compiled
    // default, so the DLL does not have to be rebuilt when Jagex ships a new client -- only the
    // file has to exist (offsets/client-<version>.json beside the DLL, or in the repository).
    if (!have.empty()) {
        const oxc::offjson::Report rep = oxc::offjson::load(dllDir, have);
        if (rep.loaded) {
            oxc::logf("[build] osclient.exe %s: offsets/client-%s.json loaded from %s "
                      "(%d applied, %d of them unmeasured; %d refused, %d missing, %d unknown)\n",
                      shown.c_str(), rep.build.c_str(), rep.source.c_str(), rep.applied, rep.unmeasured,
                      rep.refused, rep.missing, rep.unknown);
            for (const std::string& n : rep.missingNames)
                oxc::logf("[build]   missing in the offsets file, compiled default kept: %s\n", n.c_str());
            for (const std::string& n : rep.refusedNames)
                oxc::logf("[build]   refused (not measured on this build; features needing it stay off): %s\n", n.c_str());
            for (const std::string& n : rep.unmeasuredNames)
                oxc::logf("[build]   applied but unmeasured on this build: %s\n", n.c_str());
            return "";
        }
        oxc::logf("[build] %s\n", rep.error.c_str());
    }

    // Second choice: the compiled defaults, when this exe is the build they were measured on.
    const std::wstring want = oxc::off::BUILD_VERSION;
    if (have == want) {
        oxc::logf("[build] osclient.exe %s matches the compiled defaults (client-%s)\n",
                    oxc::narrow(have).c_str(), oxc::narrow(want).c_str());
        return "";
    }
    if (::getenv("OXC_SKIP_BUILD_CHECK")) {
        oxc::logf("[build] WARNING: osclient.exe %s but the compiled defaults are for client-%s -- "
                    "OXC_SKIP_BUILD_CHECK set, every offset is now suspect\n",
                    shown.c_str(), oxc::narrow(want).c_str());
        return "";
    }
    oxc::logf("[build] REFUSED: osclient.exe %s, no offsets file for it, compiled defaults are for client-%s\n",
                shown.c_str(), oxc::narrow(want).c_str());
    return "this is osclient.exe " + shown + ", and there is no offsets/client-" + shown +
           ".json for it (the compiled defaults are for client-" + oxc::narrow(want) + "). "
           "Not reading its memory: every offset was measured on one build. Run "
           "`python tools/update/update.py --build " + shown + "` to derive the file, or wait for "
           "the repository's updater to publish it, then start again.";
}

DWORD WINAPI run(LPVOID module) {
    wchar_t path[MAX_PATH]{};
    GetModuleFileNameW(static_cast<HMODULE>(module), path, MAX_PATH);
    std::wstring dir(path);
    dir.resize(dir.find_last_of(L'\\'));
    std::wstring ini = dir + L"\\0xclient.ini";

    // Find the game window BEFORE starting Java: the Java side asks for the viewport as soon as it
    // starts, and a null window there would have it build a zero-sized image.
    for (int i = 0; i < 600 && !g_game; ++i) {
        g_game = findGameWindow();
        // The launcher may have embedded the game before we got here, in which case it is not a
        // top-level window any more and only the fallback can see it.
        if (!g_game && i > 20) g_game = findEmbeddedGameWindow();
        if (!g_game) Sleep(100);
    }
    if (!g_game) {
        // Say so: a silent return here is indistinguishable, from the launcher's side, from a DLL
        // that never loaded.
        oxc::logf("[dll] no game window found in 60 s (top-level or embedded) -- giving up\n");
        return 0;
    }
    oxc::logf("[dll] game window %p (%s)\n", (void*)g_game,
                GetAncestor(g_game, GA_ROOT) == g_game ? "top-level" : "embedded");
    oxc::g_gameWindow = g_game;

    // Which mode are we in? Decided once, before anything is built: launcher mode skips the host and
    // the panel below, direct inject keeps them, and neither path may disturb the other.
    const bool launcherMode = detectLauncherMode();
    g_launcherMode = launcherMode;

    // Build the host window and embed the game BEFORE Java starts: the Java side asks for the
    // viewport as soon as it comes up, and it should measure the game's final, embedded size -- not
    // the pre-embed one. The host takes the game's place on screen at the game's current size plus a
    // PANEL_W strip on the right for the sidebar, so the game's view does not shrink or jump.
    //
    // Launcher mode skips ALL of this: the launcher's window is the host and the launcher does the
    // embedding, so anything we did here would fight it. The input work below happens in both modes
    // and in the same place in both -- AFTER the game is where it belongs, so giveGameFocus lands on
    // a window that is already embedded.
    if (!launcherMode) {
        WNDCLASSEXW hc{ sizeof hc };
        hc.lpfnWndProc   = hostProc;
        hc.hInstance     = static_cast<HMODULE>(module);
        hc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
        hc.lpszClassName = L"0xClientHost";
        RegisterClassExW(&hc);

        RECT cr{}, wr{};
        GetClientRect(g_game, &cr);
        GetWindowRect(g_game, &wr);
        RECT fr{ 0, 0, (cr.right - cr.left) + PANEL_W, cr.bottom - cr.top };
        AdjustWindowRect(&fr, WS_OVERLAPPEDWINDOW, FALSE);

        g_host = CreateWindowExW(0, hc.lpszClassName, L"0xClient", WS_OVERLAPPEDWINDOW,
                                 wr.left, wr.top, fr.right - fr.left, fr.bottom - fr.top,
                                 nullptr, nullptr, hc.hInstance, nullptr);
        if (!g_host) return 0;
        ShowWindow(g_host, SW_SHOWNOACTIVATE);

        // The game becomes a child: it loses its own caption (the host now provides it) and gains
        // the host's minimize/restore/taskbar behaviour. Blunt style replacement on purpose -- any
        // POPUP/CAPTION/THICKFRAME bits left behind would draw a second frame inside ours.
        SetWindowLongPtrW(g_game, GWL_STYLE, WS_CHILD | WS_VISIBLE);
        SetParent(g_game, g_host);
        layoutEmbed();
        attachInput();
    } else {
        // The launcher embeds the game itself, on its own schedule; there may be nothing to attach to
        // yet, and NXT may rebuild its render view later (the loop re-resolves that child every frame,
        // and AttachThreadInput is per-thread, not per-window, so it survives the embed either way).
        attachInput();
    }

    std::wstring javaHome = iniString(ini, L"java", L"");
    std::wstring jar      = dir + L"\\0xclient.jar";
    // The build check comes first: with g_javaError set the loop below never ticks the JVM, so no
    // native ever reads game memory. The message renders natively over the game like any other
    // start-up failure.
    g_javaError = checkBuild(dir);
    if (!g_javaError.empty()) {
        // said above
    } else if (javaHome.empty()) {
        g_javaError = "java= is not set in 0xclient.ini";
    } else if (!oxc::startJvm(javaHome, jar, g_javaError)) {
        // g_javaError already says what went wrong.
    }

    // Say which process owns the panel before the first tick draws anything: in launcher mode Java
    // must not draw SidePanel into a window that does not exist. No-op on an older jar.
    oxc::notifyPanelMode(launcherMode);

    // Launcher mode's data half: the mapping, the mutex and the hidden window the launcher posts edit
    // notifications to. From here until the game window goes, the loop below publishes the model and
    // drains the edits.
    if (launcherMode && !oxc::bridge::start(static_cast<HMODULE>(module), g_host)) {
        // The bridge is the panel's whole lifeline in this mode, but a refusal here must not take the
        // game down: the panel just stays empty while the overlays keep working.
        oxc::logf("[bridge] could not create the shared mapping -- panel data unavailable (GetLastError=%lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        std::fflush(stdout);
    }

    WNDCLASSEXW wc{ sizeof wc };
    wc.lpfnWndProc   = wndProc;
    wc.hInstance     = static_cast<HMODULE>(module);
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);   // the panel is a real child window now:
    wc.lpszClassName = L"0xClientOverlay";             // no cursor here means an INVISIBLE one
    RegisterClassExW(&wc);

    // WS_EX_TRANSPARENT is what makes clicks fall through to the game underneath. Without it the
    // overlay eats every click and the game becomes unplayable, which is a memorable ten minutes.
    // Deliberately NOT topmost: the loop pins it directly above the host window every frame, so it
    // hides with the client instead of hovering over whatever app you switched to.
    oxc::g_overlay.hwnd = CreateWindowExW(
        WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        wc.lpszClassName, L"", WS_POPUP, 0, 0, 100, 100, nullptr, nullptr, wc.hInstance, nullptr);
    if (!oxc::g_overlay.hwnd) return 0;
    ShowWindow(oxc::g_overlay.hwnd, SW_SHOWNOACTIVATE);

    // The panel is the ONE surface that may keep a click. It is an OWNED POPUP top-level, docked into
    // the right strip by layoutEmbed(), not a child window -- and that is a Wine cursor fix, not a
    // style preference. A child panel has no X window of its own, so the pointer over the panel sits
    // on the HOST's X window, and Wine's cursor application to that window is unreliable: it works on
    // some boots and is a 1x1 blank on others (traced live across four boots: WM_SETCURSOR arrives,
    // DefWindowProc forwards it to the host, NtUserSetCursor(IDC_ARROW) runs, and an XFixes read still
    // reports blank over the panel on the failing boots -- the same call over the game always shows).
    // A top-level window gets its own X window, and Wine applies its class cursor (IDC_ARROW) there
    // reliably -- the game-area overlay has worked this way from day one. Ownership (parent = host on
    // a WS_POPUP) keeps it above the host and ties its lifetime to the client; the loop below also
    // hides it when the host is minimized. It paints with BitBlt instead of UpdateLayeredWindow
    // (see panel.hpp), so no layered styles here.
    //
    // Launcher mode creates none of this: the panel there is ImGui, drawn by the launcher process
    // from the bridge's model. No panel window means no panel mouse messages either, so the wndProc
    // panel branch above and the leave-poll below are naturally dead code in that mode.
    if (!launcherMode) {
        oxc::panel::g_panel.hwnd = CreateWindowExW(
            WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, wc.lpszClassName, L"", WS_POPUP,
            0, 0, PANEL_W, 100, g_host, nullptr, wc.hInstance, nullptr);
        if (!oxc::panel::g_panel.hwnd) return 0;
        ShowWindow(oxc::panel::g_panel.hwnd, SW_SHOWNOACTIVATE);
    }

    MSG msg{};
    int lastHostW = -1, lastHostH = -1;
    // When the game's window went away, in ticks. 0 while it is alive. The loop below ends when this
    // has stood for five seconds -- i.e. the process really has no window any more, which is what a
    // close looks like -- and NOT the instant one handle turns invalid.
    std::uint64_t gameGoneSince = 0;
    for (;;) {
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) DispatchMessageW(&msg);

        // The window is not the session. NXT destroys and recreates its main window during boot, and
        // this loop used to return the moment that happened: overlay gone, bridge stopped, JVM never
        // ticked again, while the game itself ran on and the launcher re-embedded the new window
        // around a DLL that had already exited (review 2026-09-06). Re-resolve instead; only a
        // process with no candidate window at all for five seconds ends the loop.
        if (!IsWindow(g_game)) {
            if (!reacquireGameWindow()) {
                std::uint64_t now = GetTickCount64();
                if (!gameGoneSince) gameGoneSince = now;
                if (now - gameGoneSince >= 5000) break;
                // Nothing to draw over: on a real close this is the game's last second and an overlay
                // still floating above a dying window is the one visible cost of waiting at all.
                if (IsWindowVisible(oxc::g_overlay.hwnd)) ShowWindow(oxc::g_overlay.hwnd, SW_HIDE);
                Sleep(100);
                continue;                              // do not tick Java against a dead window
            }
            gameGoneSince = 0;
            oxc::logf("[dll] game window was recreated -- now %p (%s)\n", (void*)g_game,
                     GetAncestor(g_game, GA_ROOT) == g_game ? "top-level" : "embedded");
            attachInput();                             // new render view: queues, latch, cursor subclass
            if (!launcherMode) {
                // Direct inject owns the embedding, so the new window has to be put back into our
                // host exactly the way the startup path put the first one there. In launcher mode the
                // launcher does this on its own schedule and anything here would fight it.
                SetWindowLongPtrW(g_game, GWL_STYLE, WS_CHILD | WS_VISIBLE);
                SetParent(g_game, g_host);
                lastHostW = lastHostH = -1;            // force a fresh layout pass below
                layoutEmbed();
            }
        }

        // Launcher mode owns none of the window sizing: the launcher lays the game child out and
        // adapts to NXT's snap-back itself (the same self-heal this loop does in direct inject). All
        // the DLL does here is note the game's size, so nothing else mistakes a stale value for the
        // truth. The canvas re-resolve below is what actually keeps the overlay honest.
        if (launcherMode) {
            // Launcher mode owns none of the window sizing: the launcher lays the game child out and
            // adapts to NXT's snap-back itself (the same self-heal this loop does in direct inject).
            // All the DLL does here is note the game's size, so nothing else mistakes a stale value
            // for the truth. The canvas re-resolve below is what keeps the overlay honest.
            RECT gr{};
            GetClientRect(g_game, &gr);
            g_setGameW = gr.right - gr.left;
            g_setGameH = gr.bottom - gr.top;
        } else {
            // Re-run the layout only when the host's size actually changed (user resize, maximize) --
            // SetWindowPos on the game every frame would make NXT relayout its swapchain for nothing.
            RECT hc{};
            GetClientRect(g_host, &hc);
            int hw = hc.right - hc.left, hh = hc.bottom - hc.top;
            if (hw != lastHostW || hh != lastHostH) {
                lastHostW = hw; lastHostH = hh;
                layoutEmbed();
            } else {
                // The host did not change, so if the game's size is not the one we set, the game changed
                // ITSELF: NXT re-applies its saved client size once at login (traced live -- the child
                // came back 2122 wide after layoutEmbed set 2080, and the panel kept the old dock).
                // Fighting it would ping-pong; adapting cannot. The host grows or shrinks around the
                // game's chosen size and the panel re-docks at the game's new edge. A user drag still
                // lands in the branch above, which sets the game to match the host, not the reverse.
                // Exception: a maximized host is the user's choice -- there NXT's snap-back loses to a
                // re-enforced layout, because adapting would resize the host out of its maximize.
                RECT gr{};
                GetClientRect(g_game, &gr);
                int gw = gr.right - gr.left, gh = gr.bottom - gr.top;
                if (gw != g_setGameW || gh != g_setGameH) {
                    if (IsZoomed(g_host)) {
                        // Maximized: DO the re-enforcing this comment has always promised. Skipping
                        // the branch entirely (the old `&& !IsZoomed`) left the game child at NXT's
                        // saved size inside a maximized host -- wider than the client, painting over
                        // the panel strip, until the user un-maximized and re-maximized
                        // (review 2026-09-06). layoutEmbed re-sets g_setGameW/H, so this settles as
                        // soon as NXT stops re-applying -- except below the 800px floor, where
                        // layoutEmbed returns without touching g_setGameW/H, so record it here too
                        // (review 2026-09-06).
                        layoutEmbed();
                        g_setGameW = gw;
                        g_setGameH = gh;
                    } else {
                        g_setGameW = gw;                 // record even when refused, so a game size we
                        g_setGameH = gh;                 // won't host is not re-detected every frame
                        if (gw >= 800) {
                            RECT fr{ 0, 0, gw + PANEL_W, gh };
                            AdjustWindowRect(&fr, WS_OVERLAPPEDWINDOW, FALSE);
                            SetWindowPos(g_host, nullptr, 0, 0, fr.right - fr.left, fr.bottom - fr.top,
                                         SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
                            // The host's own WM_SIZE runs layoutEmbed and re-docks the panel.
                        }
                    }
                }
            }
        }

        // Minimized means minimized: the game is hidden, so the overlay must not keep floating over
        // the desktop -- and the panel is a top-level now, so it must hide with the client too.
        // And when another app is focused, the overlay sits directly above the host in the z-order
        // instead of above everything -- entity boxes have no business showing over a browser window.
        if (IsIconic(g_host) || !IsWindowVisible(g_game)) {
            ShowWindow(oxc::g_overlay.hwnd, SW_HIDE);
            if (IsWindowVisible(oxc::panel::g_panel.hwnd)) ShowWindow(oxc::panel::g_panel.hwnd, SW_HIDE);
        } else {
            if (!IsWindowVisible(oxc::g_overlay.hwnd)) ShowWindow(oxc::g_overlay.hwnd, SW_SHOWNOACTIVATE);
            if (!IsWindowVisible(oxc::panel::g_panel.hwnd)) ShowWindow(oxc::panel::g_panel.hwnd, SW_SHOWNOACTIVATE);
            // Pin the overlay immediately ABOVE the host (insert the host after it in z-order --
            // hWndInsertAfter means "this window goes behind that one"), not above every window
            // there is: another app the user focuses covers the overlay like it covers the game.
            SetWindowPos(g_host, oxc::g_overlay.hwnd, 0, 0, 0, 0,
                         SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            // The overlay must sit on the game's RENDER CANVAS, not on the frame around it: NXT
            // draws the scene into its JagRenderView child, the projection answers in that child's
            // coordinates, and the frame drifts inside the host (the child was seen at ~(4,30) after
            // NXT re-applied its own layout). Re-resolve every frame rather than once -- NXT can
            // recreate the child, and a stale HWND here reads as a 0x0 rect that hides the overlay.
            g_renderView = FindWindowExW(g_game, nullptr, L"JagRenderView", nullptr);
            oxc::g_canvasWindow = g_renderView ? g_renderView : g_game;
            oxc::overlay::followWindow(oxc::g_canvasWindow);
        }

        // Tell Java when the pointer has left the panel: a window only receives WM_MOUSEMOVE while
        // the pointer is over it, so without this the last hovered row would stay highlighted
        // forever. Polled here rather than via TrackMouseEvent because this loop is already where
        // every frame's window state gets reconciled. WindowFromPoint sees through the transparent
        // overlay, and since the panel is a top-level window now, "over the panel" is simply
        // WindowFromPoint naming the panel. Direct inject only: launcher mode has no panel window,
        // and the launcher's ImGui state tracks its own hover.
        if (!launcherMode) {
            POINT cp{};
            if (GetCursorPos(&cp)) {
                static bool wasOverPanel = false;
                bool overPanel = WindowFromPoint(cp) == oxc::panel::g_panel.hwnd;
                if (wasOverPanel && !overPanel) oxc::panelMouse(-1, -1, 0, false);
                wasOverPanel = overPanel;
            }
        }

        if (g_javaError.empty()) {
            // Once per session, after login: test the loaded offsets against the running game and
            // write offsets\selfcheck-<build>.json (client/selfcheck.hpp). Read-only; OXC_SELFCHECK=0 skips.
            static oxc::selfcheck::Runner selfCheck;
            selfCheck.tick(dir, oxc::g_canvasWindow);
            // OXC_ACTIONPROBE=1 only: hardware-breakpoint hook-and-log of the menu-action path
            // (client/actionprobe.hpp). Read-only; prints [probe] lines to the OXC_LOG file.
            static oxc::probe::Probe actionProbe;
            actionProbe.tick();
            // Queued menu actions run on the game thread from the tick breakpoint (client/actions.hpp);
            // this arms it once and logs what was sent. hwbp::tick re-arms new game threads.
            oxc::actions::tick();
            oxc::hwbp::tick();
            // Launcher mode: move the bridge along -- publish the model if Java's revision moved,
            // apply whatever edits the launcher queued. One JNI poll per frame, nothing more.
            if (launcherMode) oxc::bridge::tick();
            // Java draws and presents. We do nothing but ask.
            oxc::tickJvm(pollKeys());
        } else {
            RECT r{};
            GetClientRect(g_game, &r);
            renderJavaError(r.right - r.left, r.bottom - r.top);
        }

        Sleep(33);                                     // ~30 fps is plenty for an overlay
    }

    oxc::g_overlay.release();
    if (oxc::g_overlay.hwnd && IsWindow(oxc::g_overlay.hwnd)) DestroyWindow(oxc::g_overlay.hwnd);
    if (oxc::panel::g_panel.hwnd) {
        oxc::panel::g_panel.release();
        DestroyWindow(oxc::panel::g_panel.hwnd);
    }
    if (launcherMode) {
        oxc::bridge::stop();
    } else if (g_host && IsWindow(g_host)) {
        DestroyWindow(g_host);          // ours to destroy. The launcher's window never was.
    }
    return 0;
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
        // Every printf in the DLL goes to stdout, which is fine when the game was started from a
        // shell (tools/wine-setup.sh's instructions) -- but the LAUNCHER is a GUI-subsystem process
        // with no console, so a game it spawns inherits no stdout and every diagnostic vanishes.
        // OXC_LOG=<path> redirects stdout to a file instead; the env var reaches this process
        // through the launcher, which inherits it from the shell that started it.
        // Appending, shared with the launcher (which inherited this env var and opened the same file
        // first): its [input] trace and our lines interleave in one file. See log.hpp for why this is
        // a kernel handle and not freopen(stdout) -- and why it must happen before the JVM starts.
        if (const char* log = ::getenv("OXC_LOG")) oxc::logOpen(log);
        oxc::logf("[dll] attached to pid %lu\n", static_cast<unsigned long>(GetCurrentProcessId()));
        CreateThread(nullptr, 0, run, module, 0, nullptr);
    }
    return TRUE;
}

