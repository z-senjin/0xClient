// launcher/main.cpp -- 0xClient.exe: an ImGui launcher that owns the game window.
//
// One window, two states:
//   HOME      a title, a big "+ client" button, a status line.
//   EMBEDDED  the game's top-level window has been reparented in as a WS_CHILD on the left and the
//             launcher draws a 286px ImGui panel strip on the right -- all inside THIS window.
//
// Why the panel is drawn here and not by the DLL: the game process owns the one OpenGL context NXT
// is allowed to have, and a second GL context there is forbidden by design. The launcher is a
// separate process with no GL at all, so it renders Dear ImGui with the CPU (client/imgui_sw.hpp,
// the verified /tmp/imgui-swtest rasterizer) straight into a DIB section backing this window -- no
// GPU, no second context, nothing for Wine to disagree about.
//
// Data crosses processes through the shared-memory bridge described in client/bridge.hpp: the DLL
// publishes Java's plugin model, the launcher reads it and writes edits back, one registered
// message per edit batch so the DLL drains the ring instead of polling.
//
// Direct injection still works exactly as before: run osclient.exe and inject 0xclient.dll
// yourself and the DLL builds its own host window + Java panel. Nothing here is involved. Launcher
// mode only happens when THIS program spawns the game and posts it the embed message.
#include <windows.h>
#include <windowsx.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cfloat>
#include <algorithm>
#include <filesystem>
#include <memory>
#include <mutex>
#include <thread>
#include <optional>
#include <map>
#include <sstream>
#include <io.h>          // _open_osfhandle / _dup2: the OXC_LOG redirect in WinMain
#include <fcntl.h>

#include "imgui.h"
#include "imgui_sw.hpp"
#include "bridge_layout.hpp"
#include "panel_ui.hpp"
#include "accounts/account_store.hpp"
#include "accounts/jagex_auth.hpp"
#include "accounts/jagex_auth_window.hpp"
#include "accounts/osclient_launcher.hpp"
#include "winfront.hpp"     // client/: shared with the DLL's own host window

namespace {

// The 286px strip: launcher/panel_ui.hpp owns the BODY_W(250) + RAIL_W(36) pair so the strip's
// windows and the game-child layout cannot drift apart. Layout code uses effectivePanelW() rather
// than this constant -- collapsed, the strip is the 36px rail alone -- but the open width is still
// what the strip is designed around, so the alias stays.
constexpr int PANEL_W = oxc_panel::PANEL_W;

// Never squeeze the game to nothing (same floor dllmain.cpp's layoutEmbed uses).
constexpr int MIN_GAME_W = 800;

// Registered messages. RegisterWindowMessageW returns the same value process-wide for the same
// string, which is the only way two processes can agree on a message id without a shared header.
UINT g_msgEmbed     = 0;      // launcher -> game window: "you are being embedded", wParam = launcher hwnd
UINT g_msgEdit      = 0;      // launcher -> DLL message window: "drain the edit ring"
bool g_ringFullLogged = false; // set while the edit ring is refusing writes; see writeEdit
UINT g_msgActivate  = 0;      // launcher -> DLL message window: wParam 1 = activated, 0 = deactivated
constexpr UINT WM_OXC_AUTH_NAVIGATE = WM_APP + 41;

HWND g_main = nullptr;
int  g_clientW = 1600, g_clientH = 900;
bool g_quit = false;
bool g_kbMsgSeen = false;   // a keyboard message arrived since the last frame. Once this has ever been
                            // true the poll fallback in frame() retires for good -- delivery works.

bool gamePumps();           // defined by the keyboard-handoff code below; guards every SetFocus

// The embed marker. Set on the game window BEFORE SetParent so the DLL can detect launcher mode
// even if the registered message is lost (a registered message that arrives before the DLL has
// installed its window proc is simply gone; a property sits on the window until it is removed).
constexpr wchar_t kLauncherProp[] = L"0xClientLauncherHwnd";

// Defined further down; the UI and the frame loop both reach for these.
void layoutEmbed();
void startLaunch(const std::wstring& environmentBlock = {});

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(n >= 0 ? n : 0, '\0');
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring wide(const std::string& u8) {
    if (u8.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, u8.data(), (int)u8.size(), nullptr, 0);
    std::wstring w(n >= 0 ? n : 0, L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, u8.data(), (int)u8.size(), w.data(), n);
    return w;
}

std::wstring dirOf(const std::wstring& path) {
    std::size_t slash = path.find_last_of(L'\\');
    return slash == std::wstring::npos ? std::wstring() : path.substr(0, slash + 1);
}

std::wstring iniString(const std::wstring& ini, const wchar_t* key, const wchar_t* fallback) {
    wchar_t buf[MAX_PATH]{};
    // Query each section with an EMPTY fallback: GetPrivateProfileStringW copies the fallback into
    // the buffer when the key is missing, so passing `fallback` to the first call would make buf
    // non-empty and the second call (and with it the whole point of accepting two sections) dead.
    GetPrivateProfileStringW(L"oxclient", key, L"", buf, MAX_PATH, ini.c_str());
    if (buf[0]) return buf;
    // The ini the DLL reads uses [0xclient]; accept either section so one file drives both.
    GetPrivateProfileStringW(L"0xclient", key, L"", buf, MAX_PATH, ini.c_str());
    if (buf[0]) return buf;
    return fallback;
}

// Resolve a possibly-relative ini value against the launcher's own directory, the way "osclient.exe
// next to the launcher" wants. An absolute path (C:\... or \\server\...) is taken as-is.
std::wstring resolveAgainst(const std::wstring& dir, const std::wstring& path) {
    if (path.size() >= 2 && (path[1] == L':' || (path[0] == L'\\' && path[1] == L'\\'))) return path;
    if (!path.empty() && path[0] == L'\\') return path;             // drive-relative: leave it alone
    return dir + path;
}

// ---------------------------------------------------------------------------
// The DIB this window paints out of. Top-down 32bpp BGRA, which is byte-identical to IM_COL32
// little-endian -- the format contract imgui_sw.hpp's header note spells out.
// ---------------------------------------------------------------------------
struct Dib {
    HDC      dc  = nullptr;
    HBITMAP  bmp = nullptr;
    HBITMAP  old = nullptr;
    unsigned* px = nullptr;
    int      w = 0, h = 0;
};
Dib g_dib;

void releaseDib() {
    if (g_dib.dc && g_dib.old) SelectObject(g_dib.dc, g_dib.old);
    if (g_dib.dc) DeleteDC(g_dib.dc);
    if (g_dib.bmp) DeleteObject(g_dib.bmp);
    g_dib = {};
}

bool ensureDib(int w, int h) {
    if (g_dib.dc && g_dib.w == w && g_dib.h == h) return true;
    if (w <= 0 || h <= 0) return false;
    releaseDib();
    BITMAPINFO bi{};
    bi.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth       = w;
    bi.bmiHeader.biHeight      = -h;      // negative: top-down, first row is the top of the window
    bi.bmiHeader.biPlanes      = 1;
    bi.bmiHeader.biBitCount    = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    HDC screen = GetDC(nullptr);
    g_dib.bmp = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, (void**)&g_dib.px, nullptr, 0);
    ReleaseDC(nullptr, screen);
    if (!g_dib.bmp || !g_dib.px) { releaseDib(); return false; }
    g_dib.dc = CreateCompatibleDC(nullptr);
    g_dib.old = (HBITMAP)SelectObject(g_dib.dc, g_dib.bmp);
    if (!g_dib.dc || !g_dib.old) { releaseDib(); return false; }
    g_dib.w = w; g_dib.h = h;
    return true;
}

// ---------------------------------------------------------------------------
// ImGui, created once and driven by the PeekMessage loop below.
// ---------------------------------------------------------------------------
double nowSeconds() {
    static LARGE_INTEGER freq = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
    LARGE_INTEGER c; QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)freq.QuadPart;
}

// Offline verification probe, in the spirit of the project's "verify pixels via GetDIBits dumps,
// never screenshots" rule: set OXC_DUMP_FRAME to a file path and, thirty frames in, the DIB is
// written there as a PAM (P7 RGB_ALPHA -- the format the /tmp/imgui-swtest spike verified with, and
// readable by ImageMagick). Nothing else about the run changes. A window that paints garbage into
// its own back buffer looks identical to a working one from outside, so this is how the launcher's
// raster output gets checked without a human staring at a screen.
void maybeDumpFrame() {
    static const char* path = ::getenv("OXC_DUMP_FRAME");
    static int frames = 0;
    if (!path) return;
    // OXC_DUMP_EVERY=<seconds> keeps dumping to <path>-N every N seconds, for probes that need to
    // see a LATER state than frame 30 (e.g. the status line after an injected click).
    static const int every = ::getenv("OXC_DUMP_EVERY") ? ::atoi(::getenv("OXC_DUMP_EVERY")) : 0;
    ++frames;
    if (frames != 30 && !(every > 0 && frames % (every * 30) == 0)) return;   // ~30 fps
    char suffixPath[512];
    if (frames == 30) std::snprintf(suffixPath, sizeof suffixPath, "%s", path);
    else std::snprintf(suffixPath, sizeof suffixPath, "%s-%d", path, frames);
    FILE* f = std::fopen(suffixPath, "wb");
    if (!f) return;
    std::fprintf(f, "P7\nWIDTH %d\nHEIGHT %d\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n",
                 g_dib.w, g_dib.h);
    for (int y = 0; y < g_dib.h; ++y) {
        for (int x = 0; x < g_dib.w; ++x) {
            unsigned p = g_dib.px[(size_t)y * g_dib.w + x];
            unsigned char bgra[4] = { (unsigned char)(p & 0xFF), (unsigned char)((p >> 8) & 0xFF),
                                      (unsigned char)((p >> 16) & 0xFF), 255 };
            std::fwrite(bgra, 1, 4, f);
        }
    }
    std::fclose(f);
}

void initImGui() {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    // No imgui.ini: a launcher that scribbles a config file next to the game is a launcher that
    // surprises somebody. Every layout decision here is made in code.
    io.IniFilename = nullptr;
    io.BackendFlags |= ImGuiBackendFlags_RendererHasTextures | ImGuiBackendFlags_RendererHasVtxOffset;
    // 1.92 moved the font API onto the atlas. The bitmap default is the classic 13px pixel-clean
    // face: at a software-rasterized 30fps on a DIB, its crisper edges beat the vector font.
    io.Fonts->AddFontDefaultBitmap();
    // Wine's cursor over child windows cost this project a week (see the memory notes on Wine
    // cursor debugging) -- but the root cause there was that plain WS_CHILD windows get no X
    // window for Wine to define a cursor on. This launcher window is a top-level with its own X
    // window, the same case the side panel was converted to a top-level for, and that fix was
    // verified deterministic (XFixes reads left_ptr over our top-levels 3/3 boots). So the real
    // hardware cursor shows here; drawing ImGui's arrow too would give two cursors, and a drawn
    // cursor was explicitly rejected for the side panel for exactly that reason.
    io.MouseDrawCursor = false;

    // The panel's palette and metrics (Theme.java, tuned for a 250px body). Once: the style is
    // context-global and nothing in the frame loop is allowed to touch it.
    oxc_panel::applyStyle();
}

// ---------------------------------------------------------------------------
// The shared-memory bridge, client side. See client/bridge.hpp for the layout and who owns what.
// The parsed model lives in launcher/panel_ui.hpp (oxc_panel::PluginModel) -- the panel draws from
// it, so the panel owns its shape and this file only fills it in.
// ---------------------------------------------------------------------------
using oxc_panel::PluginModel;

struct Bridge {
    HANDLE   map  = nullptr;
    HANDLE   mtx  = nullptr;
    unsigned char* base = nullptr;      // whole mapping view
    size_t   size = 0;
    oxc_bridge::Header* hdr = nullptr;

    // Why the last open() failed, for the strip's note: "the DLL never created it" was the only
    // message, and it was wrong on the day the mapping WAS there and only its mutex was misnamed
    // (2026-09-05). Each step names itself so the next such failure is a one-line diagnosis.
    std::wstring why;

    bool open(DWORD pid) {
        close();
        std::wstring name = L"Local\\0xClientBridge-" + std::to_wstring(pid);
        map = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, name.c_str());
        if (!map) { why = L"no mapping " + name + L" (did launcher mode engage in the DLL?)"; return false; }
        mtx = OpenMutexW(SYNCHRONIZE, FALSE, (name + L"-mtx").c_str());
        if (!mtx) {                                  // mapping without its mutex is a half-built bridge
            why = L"mapping found but its mutex " + name + L"-mtx is missing (GetLastError=" +
                  std::to_wstring(GetLastError()) + L")";
            close(); return false;
        }
        // Size probe first: MapViewOfFile with 0 maps the whole thing, which also tells us how much
        // the DLL created -- the model region's only real bound.
        base = (unsigned char*)MapViewOfFile(map, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0);
        if (!base) { why = L"MapViewOfFile failed (GetLastError=" + std::to_wstring(GetLastError()) + L")"; close(); return false; }
        MEMORY_BASIC_INFORMATION mbi{};
        if (!VirtualQuery(base, &mbi, sizeof mbi)) { why = L"VirtualQuery failed"; close(); return false; }
        size = mbi.RegionSize;
        if (size < oxc_bridge::MODEL_OFFSET) { why = L"mapping too small (" + std::to_wstring(size) + L" bytes)"; close(); return false; }
        hdr = (oxc_bridge::Header*)base;
        if (hdr->magic != oxc_bridge::MAGIC || hdr->version != oxc_bridge::VERSION) {
            why = L"header magic/version mismatch (DLL and launcher from different builds?)";
            close(); return false;
        }
        why.clear();
        return true;
    }

    void close() {
        if (base) UnmapViewOfFile(base);
        if (mtx) CloseHandle(mtx);
        if (map) CloseHandle(map);
        base = nullptr; hdr = nullptr; mtx = nullptr; map = nullptr; size = 0;
    }

    bool lock(DWORD ms = 100)  { return mtx && WaitForSingleObject(mtx, ms) == WAIT_OBJECT_0; }
    void unlock()              { if (mtx) ReleaseMutex(mtx); }
};
Bridge g_bridge;

std::vector<PluginModel> g_plugins;         // the last parsed model snapshot
std::vector<oxc_panel::ProfileModel> g_profiles;
std::vector<oxc_panel::HubEntry> g_hub;
std::int32_t g_activeProfile = -1;
std::int32_t g_hubState = oxc_bridge::HUB_IDLE;
std::string g_hubError;
std::int64_t g_modelRevision = -1;          // the revision g_plugins was built from
std::wstring g_bridgeNote;                  // one line of bridge state for the panel header

// Bounds-checked stream reader over the model region. Every read advances and can fail; the first
// failure makes the rest no-ops and the caller discards the snapshot. A malformed region must never
// turn into an out-of-bounds read in the launcher -- the DLL writes it, but the launcher does not
// get to trust another process's memory.
struct Reader {
    const unsigned char* p = nullptr;
    const unsigned char* end = nullptr;
    bool ok = true;

    std::int32_t i32() {
        if (!ok || p + 4 > end) { ok = false; return 0; }
        std::int32_t v; std::memcpy(&v, p, 4); p += 4; return v;
    }
    std::string str(size_t width) {
        if (!ok || p + width > end) { ok = false; return {}; }
        size_t len = strnlen((const char*)p, width);
        std::string s((const char*)p, len);
        p += width;
        return s;
    }
};

// Parse the model region into the panel's model. Takes the span explicitly rather than reaching into
// g_bridge so the fake-model probe below can feed it a synthetic region -- which is the only way
// to exercise this parser offline, and the parser is exactly the code most likely to drift from
// client/bridge.hpp's writer. Returns false on a truncated or nonsense region, leaving the last
// good snapshot in place. Format 2: the plugin records (format 1's bytes, unchanged) are followed by
// pinned[], the profile list and the hub block, in the order client/bridge.hpp's layout comment
// pins -- the counts here are rejected, not clamped, for the same reason the option count is.
bool readModel(const unsigned char* p, const unsigned char* end, std::int64_t revision) {
    Reader r;
    r.p = p;
    r.end = end;
    r.ok = true;

    int count = r.i32();
    std::vector<PluginModel> next;
    if (count < 0 || count > oxc_bridge::MAX_PLUGINS) return false;
    next.reserve(count);
    for (int i = 0; i < count && r.ok; ++i) {
        PluginModel pl;
        pl.enabled   = r.i32();
        pl.hasConfig = r.i32();
        pl.hotkey    = r.i32();
        pl.name   = r.str(oxc_bridge::model::PLUGIN_NAME);
        pl.desc   = r.str(oxc_bridge::model::PLUGIN_DESC);
        pl.status = r.str(oxc_bridge::model::PLUGIN_STATUS);
        int sc = r.i32();
        if (sc < 0 || sc > oxc_bridge::MAX_SETTINGS_PER_PLUGIN) return false;
        pl.settings.reserve(sc);
        for (int s = 0; s < sc && r.ok; ++s) {
            oxc_panel::Setting st;
            st.kind        = r.i32();
            st.valueInt    = r.i32();
            st.min         = r.i32();
            st.max         = r.i32();
            st.enumIndex   = r.i32();
            st.optionCount = r.i32();
            st.flags       = r.i32();
            st.key       = r.str(oxc_bridge::model::SET_KEY);
            st.label     = r.str(oxc_bridge::model::SET_LABEL);
            st.desc      = r.str(oxc_bridge::model::SET_DESC);
            st.section   = r.str(oxc_bridge::model::SET_SECTION);
            st.valueText = r.str(oxc_bridge::model::SET_VALUETEXT);
            // The option count must be in contract or the stream desynchronises: silently clamping a
            // corrupt count (as an earlier draft did) would leave this reader consuming the region
            // from the wrong offset and emitting plausible-looking garbage plugins. The writer
            // (client/bridge.hpp buildModel) rejects any snapshot above MAX_OPTIONS, so >8 here means
            // the region is not ours at all.
            if (st.optionCount < 0 || st.optionCount > oxc_bridge::MAX_OPTIONS) return false;
            st.options.reserve(st.optionCount);
            for (int o = 0; o < st.optionCount && r.ok; ++o)
                st.options.push_back(r.str(oxc_bridge::model::SET_OPTION));
            pl.settings.push_back(std::move(st));
        }
        next.push_back(std::move(pl));
    }

    // ---- format 2's appended sections. pinned[] is index-parallel to the plugin records just read;
    // ---- profiles and the hub block follow in the contract's order.
    for (int i = 0; i < count && r.ok; ++i)
        next[i].pinned = r.i32() ? 1 : 0;

    int activeProfile = r.i32();
    int profileCount = r.i32();
    if (profileCount < 0 || profileCount > oxc_bridge::MAX_PROFILES) return false;
    std::vector<oxc_panel::ProfileModel> profiles;
    profiles.reserve(profileCount);
    for (int i = 0; i < profileCount && r.ok; ++i) {
        oxc_panel::ProfileModel pf;
        pf.name = r.str(oxc_bridge::model::PROFILE_NAME);
        pf.id   = r.str(oxc_bridge::model::PROFILE_ID);
        profiles.push_back(std::move(pf));
    }

    int hubState = r.i32();
    std::string hubError = r.str(oxc_bridge::model::HUB_ERROR);   // a fixed char[160] field here,
                                                                   // not Java's length-prefixed form
    int hubCount = r.i32();
    if (hubCount < 0 || hubCount > oxc_bridge::MAX_HUB) return false;
    std::vector<oxc_panel::HubEntry> hub;
    hub.reserve(hubCount);
    for (int i = 0; i < hubCount && r.ok; ++i) {
        oxc_panel::HubEntry he;
        he.id      = r.str(oxc_bridge::model::HUB_ID);
        he.name    = r.str(oxc_bridge::model::HUB_NAME);
        he.version = r.str(oxc_bridge::model::HUB_VERSION);
        he.author  = r.str(oxc_bridge::model::HUB_AUTHOR);
        he.desc    = r.str(oxc_bridge::model::HUB_DESC);
        he.flags   = r.i32();
        he.installedPluginIdx = r.i32();
        hub.push_back(std::move(he));
    }

    if (!r.ok) return false;                        // truncated region: keep the previous snapshot
    // An active index outside the list it indexes is not a model we understand either: everything
    // after it parsed, but the profiles block is already lying, and the profiles view would trust it.
    if (activeProfile < -1 || activeProfile >= profileCount) return false;

    g_plugins = std::move(next);
    g_profiles = std::move(profiles);
    g_hub = std::move(hub);
    g_activeProfile = activeProfile;
    g_hubState = hubState;
    g_hubError = std::move(hubError);
    g_modelRevision = revision;
    return true;
}

// Second offline probe: set OXC_FAKE_PANEL and the launcher skips the launch machinery entirely
// and draws the embedded-state panel from a synthetic model region built here, bytes laid out
// exactly the way client/bridge.hpp::buildModel writes them. This exercises the real parser and
// the real panel layout with no game, no DLL and no injection -- the strip can be verified in
// isolation, and a layout change on either side shows up here as visibly wrong data instead of as
// a crash report from somebody's live session. Edits written in this mode go nowhere: there is no
// mapping behind them (writeEdit no-ops without one).
//
// The synthetic model carries every format-2 section -- pinned flags, three profiles, a READY hub
// with one entry per button state -- so the profiles and hub views are dumpable offline too, not
// only the plugin list and the config view that the v1 fake model could reach.
void loadFakePanelModel() {
    std::vector<unsigned char> region;
    auto putI32 = [&region](std::int32_t v) {
        region.push_back((unsigned char)v); region.push_back((unsigned char)(v >> 8));
        region.push_back((unsigned char)(v >> 16)); region.push_back((unsigned char)(v >> 24));
    };
    auto putField = [&region](const char* s, size_t field) {
        size_t n = std::strlen(s);
        if (n > field - 1) n = field - 1;            // a field always keeps its terminator
        region.insert(region.end(), s, s + n);
        region.insert(region.end(), field - n, 0);
    };
    struct FakeSetting { const char* key, *label, *valueText, *section; std::int32_t kind, valueInt, min, max, flags; int options; };
    struct FakePlugin { const char *name, *desc, *status; std::int32_t enabled, hotkey, pinned; std::vector<FakeSetting> settings; };
    // One setting per widget shape the config view knows about, plus sections -- the fake model is
    // the only thing the offline dump can draw, so it should exercise every branch a real jar can
    // send, not just the two kinds the first probe happened to need.
    const FakeSetting pathSettings[] = {
        { "plan",   "Recalculate every tick", "off",      "",        oxc_bridge::SET_BOOL,    0,   0,  0, 0, 0 },
        { "style",  "Route style",            "dotted",   "render",  oxc_bridge::SET_ENUM,    1,   0,  0, 0, 3 },
        { "radius", "Search radius",          "14 tiles", "general", oxc_bridge::SET_INT,    14,   1, 50, oxc_bridge::FLAG_HASUNITS, 0 },
        { "delay",  "Redraw delay",           "250",      "general", oxc_bridge::SET_INT,   250,   0,  0, 0, 0 },
        { "hot",    "Toggle path",            "F3",       "general", oxc_bridge::SET_KEYBIND, 3,   0,  0, oxc_bridge::FLAG_KEYBIND, 0 },
        { "tint",   "Path colour",            "#5adc78",  "render",  oxc_bridge::SET_COLOR, 0x5adc78, 0, 0, 0, 0 },
        { "note",   "Annotation",             "stairs",   "render",  oxc_bridge::SET_TEXT,    0,   0,  0, 0, 0 },
    };
    const FakePlugin plugins[] = {
        { "Shortest path", "Draws the route the pathfinder settled on, tile by tile.",
          "3 routes drawn this session", 1, 4, 1, { pathSettings, pathSettings + 7 } },
        { "Woodcutter", "Fells trees within the radius and banks the logs.", "idle", 0, 5, 0, {} },
        { "Entity ESP", "Boxes entities the plugin model says are visible.", "",     1, -1, 0, {} },
        { "Agility", "Highlights laps and marks the next obstacle.", "lap 4/8", 0, -1, 1, {} },
    };
    const int pluginCount = (std::int32_t)(sizeof plugins / sizeof plugins[0]);
    putI32(pluginCount);
    for (const FakePlugin& fp : plugins) {
        putI32(fp.enabled); putI32(1); putI32(fp.hotkey);
        putField(fp.name,   oxc_bridge::model::PLUGIN_NAME);
        putField(fp.desc,   oxc_bridge::model::PLUGIN_DESC);
        putField(fp.status, oxc_bridge::model::PLUGIN_STATUS);
        putI32((std::int32_t)fp.settings.size());
        for (const FakeSetting& fs : fp.settings) {
            putI32(fs.kind); putI32(fs.valueInt); putI32(fs.min); putI32(fs.max); putI32(fs.valueInt);
            putI32(fs.options); putI32(fs.flags);
            putField(fs.key,       oxc_bridge::model::SET_KEY);
            putField(fs.label,     oxc_bridge::model::SET_LABEL);
            putField("what this setting does", oxc_bridge::model::SET_DESC);
            putField(fs.section,   oxc_bridge::model::SET_SECTION);
            putField(fs.valueText, oxc_bridge::model::SET_VALUETEXT);
            for (int o = 0; o < fs.options; ++o)
                putField(o == 0 ? "solid" : o == 1 ? "dotted" : "hidden",
                         oxc_bridge::model::SET_OPTION);
        }
    }
    // pinned[pluginCount]: two of the four, so the "pinned first, then alphabetical" ordering and
    // both star states are visible in one dump.
    for (const FakePlugin& fp : plugins) putI32(fp.pinned);

    // Profiles: the default active, two more. ids differ from names on purpose -- the view keys on
    // the index, but Java owns the id and the fake data should not suggest otherwise.
    putI32(0);                                     // activeProfileIndex
    const char* profileNames[] = { "default", "pvm", "skilling" };
    putI32((std::int32_t)(sizeof profileNames / sizeof profileNames[0]));
    for (const char* pn : profileNames) {
        putField(pn, oxc_bridge::model::PROFILE_NAME);
        putField((std::string("profile-") + pn).c_str(), oxc_bridge::model::PROFILE_ID);
    }

    // Hub: READY with one entry per action state -- not installed, installed with an update
    // pending, mid-install (busy), and installed and current.
    struct FakeHub { const char *id, *name, *version, *author, *desc; std::int32_t flags, installedIdx; };
    const FakeHub hubEntries[] = {
        { "quick-pray", "Quick Pray", "1.4.0", "somebody",
          "Prayer flicking with an optional delay.", 0, -1 },
        { "chat-logger", "Chat Logger", "0.9.1", "someone else",
          "Writes every public message to a rolling log under the profile directory.",
          oxc_bridge::HUB_FLAG_INSTALLED | oxc_bridge::HUB_FLAG_HAS_UPDATE, 1 },
        { "tile-timer", "Tile Timer", "2.0.0", "a third party",
          "Times how long you have stood on each tile.", oxc_bridge::HUB_FLAG_BUSY, -1 },
        { "loot-tracker", "Loot Tracker", "3.1.2", "yet another",
          "Records drops per monster for this session.",
          oxc_bridge::HUB_FLAG_INSTALLED, 2 },
    };
    putI32(oxc_bridge::HUB_READY);
    putField("", oxc_bridge::model::HUB_ERROR);
    putI32((std::int32_t)(sizeof hubEntries / sizeof hubEntries[0]));
    for (const FakeHub& fh : hubEntries) {
        putField(fh.id,      oxc_bridge::model::HUB_ID);
        putField(fh.name,    oxc_bridge::model::HUB_NAME);
        putField(fh.version, oxc_bridge::model::HUB_VERSION);
        putField(fh.author,  oxc_bridge::model::HUB_AUTHOR);
        putField(fh.desc,    oxc_bridge::model::HUB_DESC);
        putI32(fh.flags);
        putI32(fh.installedIdx);
    }

    // Read back from an offset that matches the real region's position in the mapping.
    std::vector<unsigned char> buf(oxc_bridge::MODEL_OFFSET + region.size(), 0);
    std::memcpy(buf.data() + oxc_bridge::MODEL_OFFSET, region.data(), region.size());
    if (readModel(buf.data() + oxc_bridge::MODEL_OFFSET, buf.data() + buf.size(), 1)) {
        g_bridgeNote = L"bridge: FAKE MODEL (OXC_FAKE_PANEL); edits go nowhere";
    } else {
        g_bridgeNote = L"bridge: FAKE MODEL FAILED TO PARSE -- writer and reader disagree";
    }
}

// Enqueue one edit and poke the DLL. No mutex here on purpose: client/bridge.hpp guards the MODEL
// REGION with the mutex, and the edit ring is deliberately single-producer/single-consumer -- we
// are the only producer, the DLL the only consumer. The ordering that makes that safe is: write
// the record FIRST, then publish head, so the DLL's plain read of head never names a slot whose
// record is not fully written yet. InterlockedExchange keeps that store a single ordered store the
// other process cannot see torn.
//
// The one flow control this side has is the full-ring check below. The ring is 64 slots and the
// DLL's lap guard drops the backlog only once we have lapped it, so a record written at
// head - tail == RING_SLOTS would overwrite the very slot the DLL is about to memcpy -- a torn
// edit, applied as garbage. Refusing the write (and saying so once per stall, not per frame: a
// slider drag writes an edit a frame) is what closes that; the next model publish re-syncs every
// widget to what Java actually has, so the optimistic echo of a dropped edit corrects itself.
void writeEdit(std::int32_t kind, std::int32_t pluginIdx, const char* key,
               std::int64_t intVal, const char* text) {
    if (!g_bridge.hdr) return;
    std::int32_t head = g_bridge.hdr->head;         // our own index; the DLL never writes it
    std::int32_t tail = g_bridge.hdr->tail;
    // RING_SLOTS - 1, not RING_SLOTS: the DLL's drain treats head - tail == RING_SLOTS as a LAPPED
    // ring and throws the whole backlog away, so filling the ring to exactly 64 records did not
    // deliver 64 edits, it delivered none -- a 2.2 s DLL stall during a slider drag dropped every
    // frame's edit including the final value, and the next publish snapped the slider back with no
    // log line (review 2026-09-06). Refusing at 63 keeps the ring inside the invariant the DLL's
    // guard documents: a full ring is 63 pending records, all of which get drained.
    if (head - tail >= oxc_bridge::RING_SLOTS - 1) {
        if (!g_ringFullLogged) {
            g_ringFullLogged = true;
            std::printf("[bridge] edit ring full (the client has not drained %d edits) -- dropping "
                        "kind %d until it catches up\n", oxc_bridge::RING_SLOTS - 1, kind);
            std::fflush(stdout);
        }
        return;
    }
    g_ringFullLogged = false;

    oxc_bridge::EditRecord rec{};
    rec.kind = kind;
    rec.pluginIdx = pluginIdx;
    std::snprintf(rec.key, sizeof rec.key, "%s", key ? key : "");
    rec.intVal = intVal;
    if (text) std::snprintf(rec.text, sizeof rec.text, "%s", text);

    g_bridge.hdr->edits[static_cast<std::size_t>(head) % oxc_bridge::RING_SLOTS] = rec;
    InterlockedExchange(reinterpret_cast<volatile LONG*>(&g_bridge.hdr->head), head + 1);
    InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&g_bridge.hdr->editSeq),
                          g_bridge.hdr->editSeq + 1);
    // An EDIT_TEXT record can carry a password (AutoLogin's), so this stack copy does not outlive the
    // write. The DLL wipes the shared slot the same way once it has applied the record
    // (review 2026-09-06); the model region's own valueText copy is the format's, not ours to drop.
    SecureZeroMemory(&rec, sizeof rec);

    // The message is what makes this arrive within a frame instead of whenever the DLL next polls
    // (it only sets a flag on the DLL side; the drain itself happens on the DLL's tick loop).
    if (g_bridge.hdr->dllMsgHwnd)
        PostMessageW(reinterpret_cast<HWND>(static_cast<uintptr_t>(g_bridge.hdr->dllMsgHwnd)),
                     g_msgEdit, 0, 0);
}

// ---------------------------------------------------------------------------
// Launch / inject / embed
// ---------------------------------------------------------------------------
enum class Phase { Home, WaitWindow, Inject, Embedded };
Phase g_phase = Phase::Home;
std::wstring g_status = L"Spawn the game, inject the DLL, embed it here.";
std::wstring g_gamePath, g_dllPath, g_gameDir;
std::wstring g_iniPath;                 // 0xclient.ini next to this exe: paths in, sidebar state out
std::unique_ptr<AccountStore> g_accounts;
std::unique_ptr<JagexAuthWindow> g_authWindow;
std::string g_accountError;
std::wstring g_launchEnvironment;
char g_legacyUsername[256]{};
char g_legacyLabel[256]{};

enum class AuthStage { Idle, LauncherBrowser, Exchanging, ConsentBrowser, CreatingSession, ImportReady, Failed };
struct AuthRuntime {
    std::mutex mutex;
    AuthStage stage = AuthStage::Idle;
    jagex_auth::LauncherOAuthRequest launcher;
    jagex_auth::ConsentOAuthRequest consent;
    std::string firstIdToken;
    std::vector<jagex_auth::Character> characters;
    std::string sessionId;
    std::string error;
    bool importOpen = false;
};
AuthRuntime g_auth;
bool g_collapsed = false;               // the value persistCollapse last saw (and the ini holds)
bool g_reducedMotion = false;           // ditto for the reduced-motion preference (persistUiPrefs)
DWORD g_gamePid = 0;
HWND  g_game = nullptr;                 // the game's window, a WS_CHILD of ours once embedded
HANDLE g_gameProc = nullptr;            // to notice the game dying before it opens a window
double g_phaseStart = 0;
bool g_quitWhenGameGone = false;        // set by WM_CLOSE once the game has been asked to close

// What layoutEmbed last told the game to be -- the guard that keeps the self-heal loop below from
// re-detecting our own SetWindowPos as "the game changed itself".
int g_setGameW = -1, g_setGameH = -1;

void loadPaths() {
    wchar_t exe[MAX_PATH]{};
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    std::wstring dir = dirOf(exe);
    g_iniPath = dir + L"\\0xclient.ini";
    g_gameDir  = dir;
    wchar_t localAppData[MAX_PATH]{};
    GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, MAX_PATH);
    if (localAppData[0]) {
        g_accounts = std::make_unique<AccountStore>(std::filesystem::path(localAppData) / L"0xClient");
        g_accounts->Load(g_accountError);
    }
    g_gamePath = resolveAgainst(dir, iniString(g_iniPath, L"game", L"osclient.exe"));
    g_dllPath  = resolveAgainst(dir, iniString(g_iniPath, L"dll", L"0xclient.dll"));
    // The sidebar's open/closed state is the one thing this process persists between boots (the
    // spec's "preserve sidebar open/closed state"). It lives in the ini the DLL already reads --
    // same file, its own key -- rather than in a second config file this launcher would own alone.
    oxc_panel::uiCollapsed() = iniString(g_iniPath, L"sidebar", L"open") == L"collapsed";
    g_collapsed = oxc_panel::uiCollapsed();
    // Reduced motion is the same kind of state and MUST survive a restart for the same reason the
    // OS-level setting does: someone who turns animation off did not turn it off for one session.
    // Default "on" (springs run) because that is what the panel was designed against; the key is
    // only ever written once the user has moved the switch.
    oxc_panel::uiReducedMotion() = iniString(g_iniPath, L"motion", L"full") == L"reduced";
    g_reducedMotion = oxc_panel::uiReducedMotion();
}

// Write the sidebar key when the panel's collapse toggle moved this frame, and re-layout so the
// game takes the width the body just gave back (or gives it back when the sidebar reopens). Polled
// from the frame loop rather than called from the click: the click lives in panel_ui.hpp, which
// owns no file I/O and no ini path.
void persistCollapse() {
    // The reduced-motion switch rides the same poll: it is set in panel_ui.hpp's debug view, which
    // owns no file I/O and no ini path, and it changes nothing about the layout -- so it writes its
    // key and stops, where the collapse toggle also has to re-layout the embedded game.
    if (oxc_panel::uiReducedMotion() != g_reducedMotion) {
        g_reducedMotion = oxc_panel::uiReducedMotion();
        WritePrivateProfileStringW(L"oxclient", L"motion", g_reducedMotion ? L"reduced" : L"full",
                                   g_iniPath.c_str());
    }
    if (oxc_panel::uiCollapsed() == g_collapsed) return;
    g_collapsed = oxc_panel::uiCollapsed();
    WritePrivateProfileStringW(L"oxclient", L"sidebar", g_collapsed ? L"collapsed" : L"open",
                               g_iniPath.c_str());
    layoutEmbed();
}

void setPhase(Phase p, const std::wstring& note) {
    g_phase = p;
    g_phaseStart = nowSeconds();
    if (!note.empty()) g_status = note;
}

// The game's main top-level window: the largest visible window this pid owns that has no owner.
// Same pick dllmain.cpp uses on itself -- NXT has splash-sized windows early on, so "biggest" is
// the right rule and "first" is not.
BOOL CALLBACK pickGameWindow(HWND h, LPARAM lp) {
    DWORD pid = 0;
    GetWindowThreadProcessId(h, &pid);
    if (pid != g_gamePid || !IsWindowVisible(h) || GetWindow(h, GW_OWNER)) return TRUE;
    RECT r{};
    GetClientRect(h, &r);
    long area = (r.right - r.left) * (r.bottom - r.top);
    auto* best = reinterpret_cast<std::pair<HWND, long>*>(lp);
    if (area > best->second) *best = { h, area };
    return TRUE;
}

HWND findGameWindow() {
    std::pair<HWND, long> best{ nullptr, 0 };
    EnumWindows(pickGameWindow, reinterpret_cast<LPARAM>(&best));
    return best.first;
}

// (The old launcher's alreadyLoaded() check is gone on purpose: it guarded against re-injecting a
// game YOU had started by hand, where a second injection would silently keep the stale DLL. Here
// every press of "+ client" spawns a fresh process, so there is nothing to be stale.)

// CreateRemoteThread + LoadLibraryW: the oldest and most boring injection there is, ported from
// tools/wine_inject.cpp. W rather than A this time because the launcher has the path as wide text
// and there is no reason to round-trip it through the ANSI codepage to please LoadLibraryA.
bool injectDll(DWORD pid, const std::wstring& dllPath, std::wstring& err) {
    HANDLE proc = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
                              PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, pid);
    if (!proc) { err = L"OpenProcess failed -- try running this as administrator"; return false; }

    SIZE_T bytes = (dllPath.size() + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(proc, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote) { err = L"VirtualAllocEx failed"; CloseHandle(proc); return false; }
    if (!WriteProcessMemory(proc, remote, dllPath.c_str(), bytes, nullptr)) {
        err = L"WriteProcessMemory failed";
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return false;
    }

    // kernel32 sits at the same address in every process on a given boot, so our LoadLibraryW is
    // theirs.
    auto loadLib = reinterpret_cast<LPTHREAD_START_ROUTINE>(
        GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW"));
    if (!loadLib) { err = L"no LoadLibraryW in kernel32?"; CloseHandle(proc); return false; }

    HANDLE th = CreateRemoteThread(proc, nullptr, 0, loadLib, remote, 0, nullptr);
    if (!th) {
        err = L"CreateRemoteThread failed";
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return false;
    }
    WaitForSingleObject(th, 10000);
    // The thread exit code is the low 32 bits of the returned HMODULE. USER handles are 32-bit
    // significant even on Win64, so zero really does mean LoadLibrary returned null, and non-zero
    // really does mean it came up.
    DWORD loaded = 0;
    GetExitCodeThread(th, &loaded);
    CloseHandle(th);
    VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
    CloseHandle(proc);

    if (!loaded) { err = L"the game refused the DLL -- is it the same 64-bit build?"; return false; }
    return true;
}

// One window again: the game becomes a child on the left, the panel strip is the rest. The style
// and parent changes are the same blunt ones dllmain.cpp makes -- any POPUP/CAPTION/THICKFRAME bit
// left behind would draw a second frame inside ours.
void embedGame(HWND game) {
    SetPropW(game, kLauncherProp, (HANDLE)g_main);
    // Tell the DLL launcher mode is on BEFORE the reparent: the DLL is mid-startup (injection only
    // just returned) and must not build its own host window around the game while we are about to
    // steal the game into ours. If the message is lost -- the DLL may not have its window proc
    // sorted yet -- the property below is the fallback it can poll for.
    PostMessageW(game, g_msgEmbed, (WPARAM)g_main, 0);

    SetWindowLongPtrW(game, GWL_STYLE, WS_CHILD | WS_VISIBLE);
    SetParent(game, g_main);
    g_game = game;
    // Join our input queue with the game's, the way dllmain.cpp's attachInput does for the DLL's
    // thread. Keyboard goes to the FOREGROUND queue's focus window, and the game -- spawned with
    // CreateProcess, foreground since it appeared -- owns that queue. Without the join, SetFocus
    // below and uiKeyboardRequested's keepKeyboard loop set focus inside OUR queue while the shared
    // foreground queue keeps its focus on JagRenderView, so every keystroke still lands in the game
    // (traced live 2026-09-05: the search field showed active, zero WM_CHAR ever arrived). With the
    // queues joined, focus is one shared value the keepKeyboard loop can actually hold.
    DWORD gameTid = GetWindowThreadProcessId(game, nullptr);
    if (gameTid && !AttachThreadInput(GetCurrentThreadId(), gameTid, TRUE))
        std::printf("[input] AttachThreadInput(launcher,game) failed (GetLastError=%lu) -- "
                    "keyboard focus stays with the game's queue\n", GetLastError());
    layoutEmbed();
    ShowWindow(game, SW_SHOW);
    // The game, now our child, held the foreground; a child cannot keep it, so raise our window or it
    // can end up behind other applications (client/winfront.hpp). The game's queue stays joined.
    oxc::bringToFront(g_main, gameTid);
    setPhase(Phase::Embedded, L"");
}

// Put the game at (0,0) sized to the client minus the panel strip. Mirrors dllmain.cpp's
// layoutEmbed, minus the panel re-dock: the strip here is pixels of OUR OWN DIB, not a window that
// needs moving. The strip's width is the effective one -- 286px open, 36px collapsed -- so
// collapsing the sidebar widens the game the same frame, which is the whole point of the toggle.
void layoutEmbed() {
    if (!g_game || !IsWindow(g_game)) return;
    RECT cc{};
    GetClientRect(g_main, &cc);
    int cw = cc.right - cc.left, ch = cc.bottom - cc.top;
    int gameW = cw - oxc_panel::effectivePanelW();
    if (gameW < MIN_GAME_W) return;                 // never squeeze the game to nothing
    SetWindowPos(g_game, nullptr, 0, 0, gameW, ch, SWP_NOZORDER | SWP_NOACTIVATE);
    g_setGameW = gameW;
    g_setGameH = ch;
}

// The self-heal half of dllmain.cpp's loop, verbatim in spirit: if the game's size is not the one
// we set, the game changed ITSELF -- NXT re-applies its saved client size once at login -- so the
// launcher grows around the game's chosen size instead of fighting it. A user drag lands in
// WM_SIZE, which sizes the game to fit, not the reverse; a maximized window is the user's choice
// and adapting would resize us out of it.
void selfHeal() {
    if (!g_game || !IsWindow(g_game)) return;    static int lastHostW = -1, lastHostH = -1;
    RECT cc{};
    GetClientRect(g_main, &cc);
    int hw = cc.right - cc.left, hh = cc.bottom - cc.top;
    if (hw != lastHostW || hh != lastHostH) {
        lastHostW = hw; lastHostH = hh;
        layoutEmbed();
        return;
    }
    RECT gr{};
    GetClientRect(g_game, &gr);
    int gw = gr.right - gr.left, gh = gr.bottom - gr.top;
    if (gw != g_setGameW || gh != g_setGameH) {
        if (IsZoomed(g_main)) {
            // Maximized: the window size is the user's choice, so NXT's login-time snap-back has to
            // LOSE here -- re-enforce the layout instead of adapting (adapting would resize us out of
            // the maximize). Doing nothing, which is what this branch used to do, left the game child
            // at NXT's saved size: 2122 wide inside a 1920 client (traced live), and a child paints
            // over the parent's client area, so the 286px strip was covered and unreachable until the
            // user un-maximized and re-maximized (review 2026-09-06).
            layoutEmbed();
            // layoutEmbed refuses below MIN_GAME_W without touching g_setGameW/H, so record the size
            // here too: otherwise the condition above stays true and this branch re-fires every frame
            // (a no-op loop, but the one asymmetry with the non-maximized path -- review 2026-09-06).
            g_setGameW = gw;
            g_setGameH = gh;
        } else {
            g_setGameW = gw;                        // record even when refused, so a game size we
            g_setGameH = gh;                        // won't host is not re-detected every frame
            if (gw >= MIN_GAME_W) {
                RECT fr{ 0, 0, gw + oxc_panel::effectivePanelW(), gh };
                AdjustWindowRect(&fr, WS_OVERLAPPEDWINDOW, FALSE);
                SetWindowPos(g_main, nullptr, 0, 0, fr.right - fr.left, fr.bottom - fr.top,
                             SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
                // our own WM_SIZE runs layoutEmbed, which re-sizes the game to fit
            }
        }
    }
}

// The embed signal, REPEATED. embedGame posts it once before SetParent, but a posted message that
// lands before the DLL has installed its forwarding subclass on the game window (dllmain.cpp's
// gameTopProc, put up by watchForLauncher) is simply gone -- both sides poll for the same window at
// 100 ms, so the two startups race and a single post can lose. The DLL's detection note asks for a
// few posts over the first couple of seconds; the SetPropW marker in embedGame and the
// already-embedded check there are the other two signals, and this closes the last gap. After
// SetParent the game window is a child of ours, but PostMessageW to a child hwnd reaches its window
// proc all the same, and the subclass stays installed for the life of the session.
void repostEmbed() {
    static double lastPost = 0;
    static DWORD pid = 0;
    if (pid != g_gamePid) { pid = g_gamePid; lastPost = 0; }   // a fresh game: start the window over
    if (nowSeconds() - g_phaseStart > 2.5) return;             // long past any race that matters
    if (nowSeconds() - lastPost < 0.1) return;
    lastPost = nowSeconds();
    if (g_game && IsWindow(g_game)) PostMessageW(g_game, g_msgEmbed, (WPARAM)g_main, 0);
}

// Bridge upkeep for the embedded state: find the mapping after injection (the DLL creates it only
// once the JVM is up -- JNI_CreateJavaVM alone takes seconds, and the bridge is built after that),
// then pull the model when Java's revision moved. Polled here rather than awaited: the frame loop
// must never block. The first 30 s -- dllmain.cpp's launcher-detection patience -- are polled three
// times a second and reported as "opening..."; an old-and-slow boot that gave up here at 5 s looked
// exactly like "the DLL never created it" while the game's own log showed the bridge coming up three
// seconds later. After that the polling continues at 2 s forever, because "slower than 30 s" is a
// slow boot, not a failure (review 2026-09-06).
void bridgeTick() {
    if (!g_bridge.hdr) {
        // Never STOP retrying, only slow down. The 30 s budget used to end the attempts as well as
        // the optimistic note, so a JVM that took longer than that to come up (cold disk, an
        // antivirus scan of 0xclient.jar, first-run JIT -- the mapping appeared at 35 s) left the
        // strip dead for the whole session while the DLL's own log showed the bridge up
        // (review 2026-09-06). After the budget the note names the real failure, and says we are
        // still trying, which is now true.
        const double waited = nowSeconds() - g_phaseStart;
        const double interval = waited < 30.0 ? 0.3 : 2.0;
        static double lastTry = 0;
        if (nowSeconds() - lastTry > interval) { lastTry = nowSeconds(); g_bridge.open(g_gamePid); }
        if (g_bridge.hdr) { g_bridgeNote.clear(); return; }    // opened just now: read it next frame
        if (waited < 30.0) {
            g_bridgeNote = L"bridge: opening...";
        } else {
            g_bridgeNote = L"bridge: " + (g_bridge.why.empty()
                               ? std::wstring(L"the DLL never created it (did launcher mode engage?)")
                               : g_bridge.why) + L" -- still retrying";
        }
        return;
    }
    if (!g_bridgeNote.empty()) g_bridgeNote.clear();   // the mapping opened -- the "opening..." note
                                                       // would otherwise sit there forever (seen live)
    if (!g_bridge.lock()) return;                   // DLL mid-publish: next frame
    std::int64_t rev = g_bridge.hdr->modelRevision;
    bool fresh = (rev != g_modelRevision);
    if (fresh) readModel(g_bridge.base + oxc_bridge::MODEL_OFFSET, g_bridge.base + g_bridge.size, rev);
    g_bridge.unlock();
}

// ---------------------------------------------------------------------------
// Accounts and UI
// ---------------------------------------------------------------------------
bool launchJagexCharacter(const JagexCharacter& character) {
    if (!g_accounts) { g_status = L"account store is unavailable"; return false; }
    auto* identity = g_accounts->FindIdentity(character.identityId);
    if (!identity) { g_status = L"Jagex identity not found"; return false; }
    std::string error; auto session = g_accounts->credentials().LoadSecret(identity->credentialReference, error);
    if (!session) { identity->authState = AuthState::ReauthenticationRequired; g_accounts->Save(error); g_status = wide(error); return false; }
    std::wstring environment;
    if (!OsClientLauncher::BuildJagexEnvironment(*session, character, environment, error)) { SecureZeroMemory(session->data(), session->size()); g_status = wide(error); return false; }
    SecureZeroMemory(session->data(), session->size());
    const_cast<JagexCharacter&>(character).lastUsed = AccountStore::Now();
    g_accounts->Save(error); startLaunch(environment); return true;
}

bool launchLegacyAccount(const LegacyAccount& account) {
    std::string error; std::wstring environment;
    if (!OsClientLauncher::BuildLegacyEnvironment(environment, error)) { g_status = wide(error); return false; }
    const_cast<LegacyAccount&>(account).lastUsed = AccountStore::Now();
    if (g_accounts) g_accounts->Save(error); startLaunch(environment); return true;
}

std::map<std::string, std::string> parseUrlValues(const std::string& text, char separator) {
    std::map<std::string, std::string> result; std::stringstream stream(text); std::string item;
    while (std::getline(stream, item, separator)) { auto eq = item.find('='); if (eq != std::string::npos) result[item.substr(0, eq)] = item.substr(eq + 1); }
    return result;
}
void scheduleAuthNavigation(const std::string& url) { auto* value = new std::string(url); PostMessageW(g_main, WM_OXC_AUTH_NAVIGATE, 0, reinterpret_cast<LPARAM>(value)); }
void beginJagexLogin() {
    if (g_authWindow && g_authWindow->IsOpen()) { g_status = L"Jagex sign-in is already open"; return; }
    wchar_t local[MAX_PATH]{}; GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    if (!local[0]) { g_status = L"LOCALAPPDATA is unavailable"; return; }
    const auto temp = std::filesystem::path(local) / L"0xClient" / L"AuthTemp" / AccountStore::NewId();
    auto request = jagex_auth::BeginLauncherOAuth(); if (request.url.empty()) { g_status = L"could not generate secure OAuth state"; return; }
    { std::lock_guard lock(g_auth.mutex); g_auth.stage = AuthStage::LauncherBrowser; g_auth.launcher = request; g_auth.consent = {}; g_auth.firstIdToken.clear(); g_auth.characters.clear(); g_auth.error.clear(); g_auth.importOpen = false; }
    g_authWindow = std::make_unique<JagexAuthWindow>(); std::string error;
    auto callback = [](const std::wstring& uri) -> bool {
        const std::string text = utf8(uri);
        if (text.rfind(jagex_auth::LAUNCHER_REDIRECT, 0) == 0) {
            auto q = text.find('?'); auto values = q == std::string::npos ? std::map<std::string,std::string>() : parseUrlValues(text.substr(q + 1), '&');
            std::lock_guard lock(g_auth.mutex);
            if (values["state"] != g_auth.launcher.state || values["code"].empty()) { g_auth.stage = AuthStage::Failed; g_auth.error = "OAuth state mismatch or authorization code missing"; g_status = L"Jagex sign-in failed: invalid OAuth callback"; return true; }
            const std::string code = values["code"], verifier = g_auth.launcher.verifier; g_auth.stage = AuthStage::Exchanging;
            std::thread([code, verifier] { jagex_auth::Service service; std::string token, error; if (!service.ExchangeCode(code, verifier, token, error)) { std::lock_guard lock(g_auth.mutex); g_auth.stage=AuthStage::Failed; g_auth.error=error; return; } auto consent=jagex_auth::BeginConsent(token); { std::lock_guard lock(g_auth.mutex); g_auth.firstIdToken=std::move(token); g_auth.consent=consent; g_auth.stage=AuthStage::ConsentBrowser; } scheduleAuthNavigation(consent.url); }).detach();
            return true;
        }
        if (text.rfind("http://localhost", 0) == 0) {
            auto hash = text.find('#'); auto values = hash == std::string::npos ? std::map<std::string,std::string>() : parseUrlValues(text.substr(hash + 1), '&');
            std::lock_guard lock(g_auth.mutex);
            if (g_auth.stage != AuthStage::ConsentBrowser || values["state"] != g_auth.consent.state || values["id_token"].empty()) { g_auth.stage=AuthStage::Failed; g_auth.error="consent state mismatch or id_token missing"; return true; }
            const std::string token=values["id_token"]; g_auth.stage=AuthStage::CreatingSession;
            std::thread([token] { jagex_auth::Service service; std::string session,error; std::vector<jagex_auth::Character> chars; if(!service.CreateGameSession(token,session,error)||!service.FetchCharacters(session,chars,error)){std::lock_guard lock(g_auth.mutex);g_auth.stage=AuthStage::Failed;g_auth.error=error;return;} {std::lock_guard lock(g_auth.mutex);g_auth.sessionId=std::move(session);g_auth.characters=std::move(chars);g_auth.stage=AuthStage::ImportReady;} }).detach();
            return true;
        }
        return false;
    };
    if (!g_authWindow->Open(temp.wstring(), callback, [] {}, error) || !g_authWindow->Navigate(request.url, error)) { g_authWindow.reset(); g_status = wide(error); return; }
    g_status = L"Jagex sign-in window opened; complete authentication there.";
}

void importJagexCharacters() {
    std::lock_guard lock(g_auth.mutex);
    if (!g_accounts || g_auth.sessionId.empty() || g_auth.characters.empty()) return;
    const auto now = AccountStore::Now();
    JagexIdentity created; created.id = AccountStore::NewId(); created.credentialReference = AccountStore::NewId(); created.createdAt = now; created.lastAuthenticatedAt = now; created.authState = AuthState::Ready;
    std::string error;
    if (!g_accounts->credentials().StoreSecret(created.credentialReference, g_auth.sessionId, error)) { g_auth.stage=AuthStage::Failed; g_auth.error=error; return; }
    g_accounts->UpsertIdentity(created);
    for (const auto& remote : g_auth.characters) { JagexCharacter c; c.id=AccountStore::NewId(); c.identityId=created.id; c.accountId=remote.accountId; c.displayName=remote.displayName; c.createdAt=now; c.lastUsed=0; c.available=true; g_accounts->UpsertCharacter(std::move(c)); }
    if (!g_accounts->Save(error)) { g_auth.stage=AuthStage::Failed; g_auth.error=error; return; }
    SecureZeroMemory(g_auth.sessionId.data(), g_auth.sessionId.size()); g_auth.sessionId.clear(); g_auth.stage=AuthStage::Idle; g_auth.characters.clear(); g_authWindow.reset(); g_status = L"Jagex characters imported.";
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------
void drawHome() {
    ImGuiIO& io = ImGui::GetIO();
    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(io.DisplaySize);
    ImGuiWindowFlags flags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                             ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoSavedSettings |
                             ImGuiWindowFlags_NoBringToFrontOnFocus | ImGuiWindowFlags_NoScrollWithMouse;
    ImGui::Begin("##home", nullptr, flags);

    // The wordmark, two-tone like the logo: "0x" in the brand green, "Client" in the text colour.
    ImGui::SetCursorPos(ImVec2(40, 40));
    ImGui::TextColored(ImGui::ColorConvertU32ToFloat4(oxc_panel::theme::BRAND), "0x");
    ImGui::SameLine(0.0f, 0.0f);
    ImGui::TextUnformatted("Client");
    ImGui::SetCursorPos(ImVec2(40, 70));
    ImGui::TextDisabled("ImGui launcher -- the game is embedded into this window.");

    ImGui::SetCursorPos(ImVec2(40, 110));
    ImGui::BeginChild("launchbox", ImVec2(420, 120), true);
    if (ImGui::Button("+ client", ImVec2(390, 52)) && g_phase == Phase::Home) {
        startLaunch();
    }
    ImGui::Spacing();
    ImGui::TextWrapped("%s", utf8(g_status).c_str());
    ImGui::EndChild();

    ImGui::SetCursorPos(ImVec2(40, 250));
    ImGui::BeginChild("accounts", ImVec2(600, 360), true);
    ImGui::TextUnformatted("Accounts");
    { std::lock_guard lock(g_auth.mutex); if (g_auth.stage == AuthStage::ImportReady) { ImGui::OpenPopup("Jagex Account Connected"); } if (g_auth.stage == AuthStage::Failed && !g_auth.error.empty()) { ImGui::TextColored(ImVec4(1,0.4f,0.4f,1), "%s", g_auth.error.c_str()); } }
    if (ImGui::Button("+ Login with Jagex") ) beginJagexLogin();
    if (ImGui::BeginPopupModal("Jagex Account Connected", nullptr, ImGuiWindowFlags_AlwaysAutoResize)) { ImGui::TextUnformatted("Characters found:"); std::vector<std::string> names; { std::lock_guard lock(g_auth.mutex); for (const auto& c : g_auth.characters) names.push_back(c.displayName); } for (const auto& name : names) ImGui::Text("[x] %s", name.c_str()); if (ImGui::Button("Import All")) { ImGui::CloseCurrentPopup(); importJagexCharacters(); } ImGui::SameLine(); if (ImGui::Button("Cancel")) { std::lock_guard lock(g_auth.mutex); SecureZeroMemory(g_auth.sessionId.data(), g_auth.sessionId.size()); g_auth.sessionId.clear(); g_auth.characters.clear(); g_auth.stage=AuthStage::Idle; ImGui::CloseCurrentPopup(); } ImGui::EndPopup(); }

    ImGui::SameLine();
    if (ImGui::Button("+ Add Legacy Account")) ImGui::OpenPopup("Add Legacy Account");
    if (ImGui::BeginPopupModal("Add Legacy Account", nullptr, ImGuiWindowFlags_AlwaysAutoResize)) {
        ImGui::InputText("Username", g_legacyUsername, sizeof g_legacyUsername);
        ImGui::InputText("Label", g_legacyLabel, sizeof g_legacyLabel);
        ImGui::TextWrapped("The existing AutoLogin plugin remains responsible for the password and native login screen.");
        if (ImGui::Button("Save")) { if (g_accounts && g_legacyUsername[0]) { LegacyAccount a; a.id=AccountStore::NewId(); a.username=g_legacyUsername; a.label=g_legacyLabel; a.createdAt=AccountStore::Now(); std::string e; g_accounts->data().legacyAccounts.push_back(std::move(a)); if (!g_accounts->Save(e)) g_status=wide(e); else g_status=L"Legacy account saved; configure its AutoLogin credentials before launching."; g_legacyUsername[0]=g_legacyLabel[0]=0; } ImGui::CloseCurrentPopup(); }
        ImGui::SameLine(); if (ImGui::Button("Cancel")) ImGui::CloseCurrentPopup();
        ImGui::EndPopup();
    }
    if (g_accounts) {
        ImGui::Separator(); ImGui::TextUnformatted("Jagex characters");
        for (const auto& c : g_accounts->data().jagexCharacters) {
            ImGui::Text("%s%s", c.displayName.c_str(), c.available ? "" : " (unavailable)"); ImGui::SameLine();
            if (ImGui::Button((std::string("Launch##jagex-") + c.id).c_str()) && c.available) launchJagexCharacter(c);
        }
        ImGui::Separator(); ImGui::TextUnformatted("Legacy accounts");
        for (const auto& a : g_accounts->data().legacyAccounts) {
            ImGui::Text("%s", a.label.empty() ? a.username.c_str() : a.label.c_str()); ImGui::SameLine();
            if (ImGui::Button((std::string("Launch##legacy-") + a.id).c_str())) launchLegacyAccount(a);
        }
    } else ImGui::TextDisabled("Account storage is unavailable.");
    ImGui::EndChild();

    ImGui::SetCursorPos(ImVec2(40, 650));
    ImGui::BeginChild("paths", ImVec2(420, 120), true);
    ImGui::TextWrapped("game: %s", utf8(g_gamePath).c_str());
    ImGui::TextWrapped("dll:  %s", utf8(g_dllPath).c_str());
    ImGui::TextDisabled("edit [oxclient] game= in 0xclient.ini, next to this exe.");
    ImGui::EndChild();

    ImGui::End();
}

// The 286px strip: the root window covers the whole client (NoMouseInputs, so clicks over the game
// child stay the game's), and launcher/panel_ui.hpp draws the body + rail at the right edge. The
// panel UI -- tabs, plugin rows, config views, the theme -- is all over there; this only hands it
// the parsed model and the edit sink.
void drawPanel() {
    ImGuiIO& io = ImGui::GetIO();
    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(io.DisplaySize);
    ImGuiWindowFlags rootFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                                 ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoSavedSettings |
                                 ImGuiWindowFlags_NoBringToFrontOnFocus |
                                 ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoMouseInputs;
    ImGui::Begin("##root", nullptr, rootFlags);
    ImGui::End();

    oxc_panel::Model m;
    m.plugins       = &g_plugins;
    m.profiles      = &g_profiles;
    m.hub           = &g_hub;
    m.activeProfile = &g_activeProfile;
    m.hubState      = &g_hubState;
    m.hubError      = &g_hubError;
    m.modelRevision = g_modelRevision;
    m.editSeq       = g_bridge.hdr ? g_bridge.hdr->editSeq : 0;
    m.bridgeUp      = g_bridge.hdr != nullptr;
    m.note          = utf8(g_bridgeNote);
    m.gameStatus    = utf8(g_status);
    oxc_panel::draw(m, [](std::int32_t kind, std::int32_t pluginIdx, const char* key,
                           std::int64_t intVal, const char* text) {
        // Plugin enable travels as key "enabled" (oxclient.panel.PanelBridge.ENABLE_KEY): the DLL's
        // bridgeApply routes it to Plugin.setEnabled rather than Setting.set, because there is no
        // Setting behind the switch. Everything else -- including the format-2 command kinds, which
        // ignore the key -- is routed by the DLL through the owning manager.
        writeEdit(kind, pluginIdx, key, intVal, text);
    });

    // Keyboard handoff for the panel's text fields. The game child owns focus (the DLL holds it on
    // JagRenderView), so a field that never asked would silently type into the game -- and the DLL
    // re-focuses the render view on every WM_ACTIVATE, including the one our own click just caused,
    // so a one-shot SetFocus would lose that race. Hence: take focus when a field activates, keep
    // re-asserting it every frame while one is live, and hand it back through the DLL's own
    // activate message (wParam 1 = "you are active again, re-focus the render view") when the last
    // field closes.
    if (oxc_panel::uiKeyboardRequested() && gamePumps()) SetFocus(g_main);
    static bool wasActive = false;
    bool active = oxc_panel::uiKeyboardActive();
    if (wasActive && !active) {
        if (GetFocus() == g_main && g_bridge.hdr && g_bridge.hdr->dllMsgHwnd)
            PostMessageW(reinterpret_cast<HWND>(static_cast<uintptr_t>(g_bridge.hdr->dllMsgHwnd)),
                         g_msgActivate, 1, 0);
    }
    wasActive = active;
}

// A cross-thread SetFocus synchronizes with the joined input queue (AttachThreadInput above), and
// NXT's boot spends long stretches not pumping messages -- during one of those the sync never
// returns and this window's whole loop hangs (seen live 2026-09-05: the strip froze on the first
// field click until the game came back, minutes later). Probe the game's pump with a bounded
// WM_NULL and skip focus changes while it would block. The verdict is cached briefly: this runs
// every frame while a field is live, and each uncached probe can cost up to 50ms.
double g_lastPumpProbe = 0.0;
bool   g_gamePumps     = true;
bool gamePumps() {
    double now = nowSeconds();
    if (now - g_lastPumpProbe > 0.25) {
        g_lastPumpProbe = now;
        DWORD_PTR ignored = 0;
        g_gamePumps = g_game && IsWindow(g_game) &&
            SendMessageTimeoutW(g_game, WM_NULL, 0, 0, SMTO_ABORTIFHUNG, 50, &ignored) != 0;
    }
    return g_gamePumps;
}

// The half of the handoff above that must run even when the panel did not just draw -- focus can be
// stolen between frames by the DLL's WM_ACTIVATE path. Called from frame() every frame.
void keepKeyboard() {
    if (!oxc_panel::uiKeyboardActive()) return;
    if (GetFocus() == g_main) return;
    // Focus moved off our window while a field is live. Two very different causes. The DLL's
    // WM_ACTIVATE path re-focusing JagRenderView after OUR OWN strip click: fight it -- the user is
    // typing in the field. The user clicking back into the game (the OSRS login fields): surrender,
    // because that click lands on the game child and is invisible to ImGui, so the field would stay
    // live forever -- and the old SetFocus-every-frame loop starved the login box of every
    // keystroke (traced 2026-09-05: WantTextInput=1 with the pointer parked over the game). The
    // pointer disambiguates: over the game area means the user went back to the game.
    HWND fg = GetForegroundWindow();
    if (!fg || GetAncestor(fg, GA_ROOT) != g_main) return;   // whole process backgrounded: not ours to fight
    POINT p{};
    if (GetCursorPos(&p) && ScreenToClient(g_main, &p) &&
        p.x >= 0 && p.y >= 0 && p.x < g_clientW - oxc_panel::effectivePanelW()) {
        oxc_panel::kbSurrenderFlag() = true;   // draw() closes the field; no g_msgActivate handback
        return;                                 // needed -- the DLL already holds the focus it wants
    }
    if (gamePumps()) SetFocus(g_main);
}

// ---------------------------------------------------------------------------
// Input. ONE path: the window messages this window's wndProc receives. A message only arrives when
// the pointer or the keyboard focus is genuinely on the LAUNCHER -- the game is a child window of
// ours, so anything aimed at IT is delivered to it and never shows up here. That discrimination is
// the whole point, and it is exactly what the old "physical mouse as a safety net" block lacked: it
// fed ImGui GetCursorPos + GetAsyncKeyState(VK_LBUTTON/RBUTTON/MBUTTON) every frame, and
// GetAsyncKeyState is GLOBAL. A click aimed at the game passed the old position test (the game child
// lives INSIDE our client rect, so the point was "over the launcher") and was handed to ImGui as a
// click on the strip. The keyboard had the same two-path shape, and its poll read keys typed into
// the game straight into the strip's text fields -- the password one included.
//
// What is left of the net is deliberately release-only (see reconcileMouse). Nothing below ever
// synthesises a mouse-down, a key-down, or a position ONTO the strip out of physical state.
// Coordinates are launcher-client coordinates, the space io.DisplaySize describes.
// ---------------------------------------------------------------------------

// One line per PATH change -- never per event, and never a key or a character value (a OXC_LOG that
// traced every WM_CHAR once logged the password field byte for byte, review 2026-09-06).
enum { LOG_KB = 0, LOG_MOUSE = 1, LOG_SLOTS = 2 };
void logInputPath(int slot, const char* what) {
    static const char* last[LOG_SLOTS] = {};
    if (last[slot] && std::strcmp(last[slot], what) == 0) return;
    last[slot] = what;
    std::printf("[input] %s\n", what);
    std::fflush(stdout);
}

int  g_btnDownMask  = 0;      // buttons ImGui was told are down, by a MESSAGE (bit per ImGui button)
bool g_mouseInside  = false;  // ImGui currently holds a valid position from us
bool g_mouseTracked = false;  // TrackMouseEvent(TME_LEAVE) armed for the current hover
bool g_captureHeld  = false;  // we called SetCapture and it stuck

// Is this launcher-client point over the embedded game child? A rect test rather than
// WindowFromPoint: the game belongs to another process and WindowFromPoint would also answer for
// tooltips and menus floating over it, while the child's rect is precisely the region layoutEmbed
// handed the game.
bool overGameChild(POINT clientPt) {
    if (!g_game || !IsWindow(g_game)) return false;
    RECT r{};
    if (!GetWindowRect(g_game, &r)) return false;        // child window rects come back in screen space
    POINT screen = clientPt;
    if (!ClientToScreen(g_main, &screen)) return false;
    return PtInRect(&r, screen) != FALSE;
}

// The strip: our client area minus whatever the game child covers. The only region ImGui may ever be
// told the pointer is in. In the home and fake-panel states there is no child, so it is the whole
// client area and the "+ client" button works exactly as before.
bool overStrip(POINT clientPt) {
    return clientPt.x >= 0 && clientPt.y >= 0 && clientPt.x < g_clientW && clientPt.y < g_clientH &&
           !overGameChild(clientPt);
}

void clearMousePos() {
    if (!g_mouseInside) return;
    ImGui::GetIO().AddMousePosEvent(-FLT_MAX, -FLT_MAX);   // "the mouse left", in ImGui's own words
    g_mouseInside = false;
}

void feedMousePos(POINT clientPt) {
    // While a drag WE captured is in flight the pointer is allowed to be anywhere: those moves are
    // ours until the release (the game is not seeing them either way), and pinning the position at
    // the strip's edge would make a slider impossible to drag to its minimum -- the grab leaves the
    // strip on the way there.
    if (g_btnDownMask || overStrip(clientPt)) {
        ImGui::GetIO().AddMousePosEvent((float)clientPt.x, (float)clientPt.y);
        g_mouseInside = true;
    } else {
        clearMousePos();
    }
}

void armMouseLeave() {
    if (g_mouseTracked) return;
    TRACKMOUSEEVENT tme{ sizeof tme, TME_LEAVE, g_main, 0 };
    g_mouseTracked = TrackMouseEvent(&tme) != FALSE;
}

// SetCapture on button-down over the strip is the standard fix for the one real problem the old poll
// was there to solve: press on the strip, drag off it, release over the game child -- the release
// goes to whatever window is under the cursor and ImGui would hold the button down forever. With
// capture the release always comes back to us as a message.
//
// One hazard worth naming: embedGame calls AttachThreadInput, and attached queues SHARE the mouse
// capture, so while we hold it the game receives no mouse input at all. That is correct for the
// length of a drag that started on the strip and wrong for one microsecond longer -- which is why
// dropCapture is reachable from the button-up path, from WM_CAPTURECHANGED, and from
// reconcileMouse's idle check, rather than from a single place that a lost message could skip.
void takeCapture() {
    if (g_captureHeld) return;
    SetCapture(g_main);
    g_captureHeld = (GetCapture() == g_main);            // SetCapture's return is the PREVIOUS owner
    logInputPath(LOG_MOUSE, g_captureHeld
        ? "mouse: window messages, capture held across drags"
        : "mouse: window messages, SetCapture REFUSED -- release reconciliation covers drags");
}

void dropCapture() {
    if (!g_captureHeld) return;
    g_captureHeld = false;
    ReleaseCapture();
}

void feedMouseButton(int btn, bool down, POINT clientPt) {
    // Position first, and while g_btnDownMask still describes the drag in flight: a click's own
    // coordinates decide which widget it lands on, and a button-down can be the first message after
    // the pointer entered (the WM_MOUSEMOVE before it can be coalesced away).
    feedMousePos(clientPt);
    if (down) {
        bool first = (g_btnDownMask == 0);
        g_btnDownMask |= 1 << btn;
        if (first) takeCapture();
    } else {
        g_btnDownMask &= ~(1 << btn);
        if (!g_btnDownMask) dropCapture();
    }
    ImGui::GetIO().AddMouseButtonEvent(btn, down);
}

// The narrow safety net, run once per frame AFTER the pump has drained every pending message. Two
// things the message path cannot see on its own:
//   * a button pressed on the strip and released elsewhere while we do NOT hold capture (Wine can
//     refuse SetCapture, and another window can steal it -- see WM_CAPTURECHANGED);
//   * the pointer leaving our window without a WM_MOUSELEAVE ever arriving.
// Only releases and leaves are synthesised. A DOWN is never synthesised from physical state, and a
// position is never synthesised ONTO the strip -- that asymmetry IS the fix.
void reconcileMouse() {
    ImGuiIO& io = ImGui::GetIO();
    POINT p{};
    const bool haveCursor = GetCursorPos(&p) && ScreenToClient(g_main, &p);
    const bool onStrip = haveCursor && overStrip(p);

    if (GetCapture() != g_main) {
        static const int vkBtn[3] = { VK_LBUTTON, VK_RBUTTON, VK_MBUTTON };
        for (int b = 0; b < 3; ++b) {
            if (!io.MouseDown[b]) continue;
            // Over the strip the release is delivered to US, so a disagreement here is a race with a
            // message not yet dispatched, not a lost release. Off the strip this check runs every
            // frame, so a button released over the game clears on the very next one -- long before
            // the pointer could wander back onto the strip carrying a stale down.
            if (onStrip) continue;
            if (GetAsyncKeyState(vkBtn[b]) & 0x8000) continue;    // still physically held: a live drag
            g_btnDownMask &= ~(1 << b);
            io.AddMouseButtonEvent(b, false);
            logInputPath(LOG_MOUSE, "mouse: synthesised a button release (no capture; the real one "
                                    "went to another window)");
        }
    }

    if (g_captureHeld && !g_btnDownMask) dropCapture();  // never hold the shared queue's capture idle
    if (!g_btnDownMask && !onStrip) clearMousePos();
}

ImGuiKey vkToImGuiKey(WPARAM vk) {
    switch (vk) {
        case VK_TAB:      return ImGuiKey_Tab;
        case VK_LEFT:     return ImGuiKey_LeftArrow;
        case VK_RIGHT:    return ImGuiKey_RightArrow;
        case VK_UP:       return ImGuiKey_UpArrow;
        case VK_DOWN:     return ImGuiKey_DownArrow;
        case VK_PRIOR:    return ImGuiKey_PageUp;
        case VK_NEXT:     return ImGuiKey_PageDown;
        case VK_HOME:     return ImGuiKey_Home;
        case VK_END:      return ImGuiKey_End;
        case VK_INSERT:   return ImGuiKey_Insert;
        case VK_DELETE:   return ImGuiKey_Delete;
        case VK_BACK:     return ImGuiKey_Backspace;
        case VK_SPACE:    return ImGuiKey_Space;
        case VK_RETURN:   return ImGuiKey_Enter;
        case VK_ESCAPE:   return ImGuiKey_Escape;
        // Windows names the punctuation keys OEM_n; the mapping below is the US-layout assignment
        // from WinUser.h, which is the layout NXT assumes everywhere else too.
        case VK_OEM_7:    return ImGuiKey_Apostrophe;   // '
        case VK_OEM_COMMA:return ImGuiKey_Comma;        // ,
        case VK_OEM_MINUS:return ImGuiKey_Minus;        // -
        case VK_OEM_PERIOD: return ImGuiKey_Period;     // .
        case VK_OEM_2:    return ImGuiKey_Slash;        // /
        case VK_OEM_1:    return ImGuiKey_Semicolon;    // ;
        case VK_OEM_PLUS: return ImGuiKey_Equal;        // =
        case VK_OEM_4:    return ImGuiKey_LeftBracket;  // [
        case VK_OEM_5:    return ImGuiKey_Backslash;    // backslash
        case VK_OEM_6:    return ImGuiKey_RightBracket; // ]
        case VK_OEM_102:  return ImGuiKey_Backslash;    // the ISO 102nd key
        case VK_OEM_3:    return ImGuiKey_GraveAccent;  // `
        case VK_CAPITAL:  return ImGuiKey_CapsLock;
        case VK_SCROLL:   return ImGuiKey_ScrollLock;
        case VK_NUMLOCK:  return ImGuiKey_NumLock;
        case VK_SNAPSHOT: return ImGuiKey_PrintScreen;
        case VK_PAUSE:    return ImGuiKey_Pause;
        case VK_NUMPAD0:  return ImGuiKey_Keypad0;
        case VK_NUMPAD1:  return ImGuiKey_Keypad1;
        case VK_NUMPAD2:  return ImGuiKey_Keypad2;
        case VK_NUMPAD3:  return ImGuiKey_Keypad3;
        case VK_NUMPAD4:  return ImGuiKey_Keypad4;
        case VK_NUMPAD5:  return ImGuiKey_Keypad5;
        case VK_NUMPAD6:  return ImGuiKey_Keypad6;
        case VK_NUMPAD7:  return ImGuiKey_Keypad7;
        case VK_NUMPAD8:  return ImGuiKey_Keypad8;
        case VK_NUMPAD9:  return ImGuiKey_Keypad9;
        case VK_DECIMAL:  return ImGuiKey_KeypadDecimal;
        case VK_DIVIDE:   return ImGuiKey_KeypadDivide;
        case VK_MULTIPLY: return ImGuiKey_KeypadMultiply;
        case VK_SUBTRACT: return ImGuiKey_KeypadSubtract;
        case VK_ADD:      return ImGuiKey_KeypadAdd;
        default: break;
    }
    if (vk >= '0' && vk <= '9') return (ImGuiKey)(ImGuiKey_0 + (vk - '0'));
    if (vk >= 'A' && vk <= 'Z') return (ImGuiKey)(ImGuiKey_A + (vk - 'A'));
    if (vk >= VK_F1 && vk <= VK_F12) return (ImGuiKey)(ImGuiKey_F1 + (vk - VK_F1));
    return ImGuiKey_None;
}

void feedKey(WPARAM vk, bool down) {
    ImGuiIO& io = ImGui::GetIO();
    // Mods first: ImGui wants them as their own events, and the diff between the event and the
    // real modifier state is what its key routing cares about.
    if (vk == VK_SHIFT)    { io.AddKeyEvent(ImGuiMod_Shift, down);   return; }
    if (vk == VK_CONTROL)  { io.AddKeyEvent(ImGuiMod_Ctrl, down);    return; }
    if (vk == VK_MENU)     { io.AddKeyEvent(ImGuiMod_Alt, down);     return; }
    if (vk == VK_LWIN || vk == VK_RWIN) { io.AddKeyEvent(ImGuiMod_Super, down); return; }
    ImGuiKey k = vkToImGuiKey(vk);
    if (k != ImGuiKey_None) io.AddKeyEvent(k, down);
}

// One ImGui frame. Returns false once the app should exit.
bool frame() {
    // The game dying unwinds everything back to the home state -- the bridge closes with it, and
    // the next press of "+ client" starts a fresh game.
    // OXC_FAKE_PANEL (see loadFakePanelModel) bypasses the launch machinery entirely: the panel
    // draws from synthetic data over the whole window and nothing here touches a game process.
    static bool fakePanel = ::getenv("OXC_FAKE_PANEL") != nullptr;
    if (fakePanel) {
        if (g_plugins.empty()) loadFakePanelModel();
        // OXC_FAKE_CONFIG: push the first plugin that has settings, so the config view is dumpable
        // without a mouse to click a gear with (see oxc_panel::debugPushConfig).
        static bool fakeConfig = ::getenv("OXC_FAKE_CONFIG") != nullptr;
        if (fakeConfig) {
            for (int i = 0; i < (int)g_plugins.size(); ++i)
                // configurable(), not the raw field: that int is the plugin FLAGS word now
                // (PLUGIN_FLAG_CONFIG | PLUGIN_FLAG_DEV), so testing it for non-zero would also
                // answer yes for a developer-marked plugin with no settings at all.
                if (g_plugins[i].configurable() && !g_plugins[i].settings.empty()) {
                    oxc_panel::debugPushConfig(i);
                    break;
                }
            fakeConfig = false;
        }
        // OXC_FAKE_TAB=plugins|profiles|hub|debug: pick the starting tab, so a view with no offline
        // click path to it (the rail needs a mouse) still gets dumped. Unknown values are ignored.
        if (const char* tab = ::getenv("OXC_FAKE_TAB")) {
            if (!std::strcmp(tab, "debug"))    oxc_panel::uiTab() = oxc_panel::TAB_DEBUG;
            if (!std::strcmp(tab, "profiles")) oxc_panel::uiTab() = oxc_panel::TAB_PROFILES;
            if (!std::strcmp(tab, "hub"))      oxc_panel::uiTab() = oxc_panel::TAB_HUB;
        }
        g_phase = Phase::Embedded;
    } else if (g_phase == Phase::Embedded && (!g_game || !IsWindow(g_game))) {
        // NXT can destroy and recreate its main window during boot (live 2026-09-05: the embed
        // landed on a window the game replaced moments later; the DLL -- still inside
        // detectLauncherMode's 30s patience loop -- never saw a marked window and fell back to
        // direct inject, while this side declared "the game closed." to a process that was alive
        // and well). The PROCESS is the thing we own: while it lives, go back to waiting and
        // re-embed on its current window. The second injectDll is a refcount-only LoadLibrary on
        // an already-loaded module -- DllMain does not run twice.
        // ... but a window that went away because WE asked it to is not a recreation. The game's
        // window always dies before its process does, so this branch swallowed every close: the
        // launcher flipped to "re-embedding...", waited 30 s for a window from a process that was
        // shutting down, and landed back on the home screen with "osclient.exe exited before it
        // opened a window" -- the buttonless-husk outcome the WM_CLOSE handler exists to avoid
        // (review 2026-09-06). g_quitWhenGameGone is the difference and it is checked first.
        if (!g_quitWhenGameGone && g_gameProc && WaitForSingleObject(g_gameProc, 0) != WAIT_OBJECT_0) {
            g_game = nullptr;
            setPhase(Phase::WaitWindow,
                     L"the game's window was recreated during boot -- re-embedding...");
        } else {
        g_bridge.close();
        g_plugins.clear();
        g_profiles.clear();
        g_hub.clear();
        g_activeProfile = -1;
        g_hubState = oxc_bridge::HUB_IDLE;
        g_hubError.clear();
        g_modelRevision = -1;
        g_game = nullptr;
        if (g_gameProc) { CloseHandle(g_gameProc); g_gameProc = nullptr; }
        g_gamePid = 0;
        setPhase(Phase::Home, L"the game closed.");
        // If the user closed the WINDOW (WM_CLOSE asked the game to go first), that is the end of
        // the launcher too -- returning to the home screen here would leave a buttonless husk.
        if (g_quitWhenGameGone) { g_quit = true; return false; }
        }
    }

    switch (g_phase) {
        case Phase::WaitWindow: {
            if (g_gameProc && WaitForSingleObject(g_gameProc, 0) == WAIT_OBJECT_0) {
                setPhase(Phase::Home, L"osclient.exe exited before it opened a window.");
                CloseHandle(g_gameProc); g_gameProc = nullptr; g_gamePid = 0;
                // A close request that got this far still ends the launcher rather than parking it
                // on the home screen (review 2026-09-06) -- same rule as the embedded branch above.
                if (g_quitWhenGameGone) { g_quit = true; return false; }
                break;
            }
            HWND w = findGameWindow();
            if (w) {
                g_status = L"injecting 0xclient.dll...";
                setPhase(Phase::Inject, L"");
            } else if (nowSeconds() - g_phaseStart > 30.0) {
                // 30s, not 10: the game's first window is not on a clock -- a cold wineprefix or a
                // cache write from a previous kill can stall NXT for tens of seconds before it
                // shows anything (traced live 2026-09-05: 10s gave up, the window landed at ~14s,
                // and the user is left staring at a dead home screen while the game runs fine).
                setPhase(Phase::Home, L"osclient.exe never opened a window (30s).");
            }
            break;
        }
        case Phase::Inject: {
            std::wstring err;
            if (!injectDll(g_gamePid, g_dllPath, err)) {
                setPhase(Phase::Home, L"injection failed: " + err);
                if (g_gameProc) { CloseHandle(g_gameProc); g_gameProc = nullptr; }
                g_gamePid = 0;
                break;
            }
            embedGame(findGameWindow());
            if (g_phase == Phase::Embedded) {
                g_phaseStart = nowSeconds();     // bridge-open retry budget starts now
                g_status = L"embedded. waiting for the DLL bridge...";
            } else {
                setPhase(Phase::Home, L"the game window vanished during embed.");
            }
            break;
        }
        case Phase::Embedded:
            if (!fakePanel) { repostEmbed(); bridgeTick(); selfHeal(); }
            break;
        case Phase::Home:
        default:
            break;
    }

    // --- render -------------------------------------------------------------
    if (g_clientW <= 0 || g_clientH <= 0) return true;
    if (!ensureDib(g_clientW, g_clientH)) return true;
    if (IsIconic(g_main)) return true;              // minimized: nothing to paint, no WM_SIZE yet

    ImGuiIO& io = ImGui::GetIO();
    static double last = 0;
    double now = nowSeconds();
    io.DeltaTime = last > 0 ? (float)(now - last) : 1.0f / 30.0f;
    if (io.DeltaTime <= 0.0f) io.DeltaTime = 1.0f / 30.0f;
    if (io.DeltaTime > 0.1f) io.DeltaTime = 0.1f;   // a stall must not look like a 10s jump
    last = now;
    io.DisplaySize = ImVec2((float)g_clientW, (float)g_clientH);

    // Mouse: nothing to feed here. Every position, button and wheel event came in as a window
    // message and is already in ImGui's queue. All this does is close the two holes a message can
    // fall through -- a release that went to another window while we did not hold capture, and a
    // pointer that left without a WM_MOUSELEAVE. It never invents a press or a hover.
    reconcileMouse();

    // Keyboard. Window messages (WM_KEYDOWN/WM_SYSKEYDOWN/WM_CHAR in wndProc, which latch
    // g_kbMsgSeen) are THE path: they are exactly right when Wine delivers them, which it does once
    // this window holds Win32 + X focus (the fake-panel trace 2026-09-05 showed every WM_CHAR
    // arriving once X focus sat on our window; the embed path's AttachThreadInput + keepKeyboard
    // reach the same state live).
    //
    // The poll survives only for the sessions where delivery is dead, and it is now gated twice:
    //   * the moment ONE key message has ever arrived, delivery works and the poll is retired for
    //     good. A sticky "recently" window instead of a latch would double every key on the frame
    //     the two paths overlapped;
    //   * it runs only while the LAUNCHER holds focus and is foreground. GetAsyncKeyState reads the
    //     physical keyboard no matter which window owns it, so the ungated poll typed the game's
    //     keystrokes into whatever strip field was live -- the AutoLogin password field included.
    // Arming takes a silent snapshot so a key already held when the gate opens can never arrive as a
    // fresh press, and closing the gate releases whatever the poll had reported down, so ImGui
    // cannot be left holding a key across the handover.
    static bool s_prevPollDown[256] = {};
    static bool s_pollArmed = false;
    static bool s_kbMsgEverSeen = false;
    auto releasePolledKeys = [&]() {
        if (!s_pollArmed) return;
        for (int vk = 8; vk < 256; ++vk) {
            if (!s_prevPollDown[vk]) continue;
            s_prevPollDown[vk] = false;
            feedKey(vk, false);
        }
        s_pollArmed = false;
    };

    const bool kbMsgThisFrame = g_kbMsgSeen;
    g_kbMsgSeen = false;
    if (kbMsgThisFrame) s_kbMsgEverSeen = true;
    // GetFocus answers for the input queue our thread is attached to, which after embedGame is the
    // game's queue as well -- so this is genuinely "the strip has the keyboard", not "our process
    // exists". Both halves are required: focus without foreground is a backgrounded launcher.
    const bool focusHere = (GetFocus() == g_main) && (GetForegroundWindow() == g_main);
    if (s_kbMsgEverSeen) {
        logInputPath(LOG_KB, "keyboard: window messages");
        releasePolledKeys();
    } else if (!focusHere) {
        releasePolledKeys();                        // the game has the keyboard: feed ImGui nothing
    } else {
        logInputPath(LOG_KB, "keyboard: polling (no key messages ever arrived; launcher has focus)");
        if (!s_pollArmed) {
            for (int vk = 8; vk < 256; ++vk)        // vk 8 up: skip VK_LBUTTON/RBUTTON/MBUTTON --
                s_prevPollDown[vk] = (GetAsyncKeyState(vk) & 0x8000) != 0;   // mouse state is the
            s_pollArmed = true;                     // mouse path's business, never the keyboard's
        } else {
            for (int vk = 8; vk < 256; ++vk) {
                bool down = (GetAsyncKeyState(vk) & 0x8000) != 0;
                if (down == s_prevPollDown[vk]) continue;
                s_prevPollDown[vk] = down;
                feedKey(vk, down);
                if (down) {
                    BYTE kb[256] = {};
                    GetKeyboardState(kb);
                    WCHAR chars[8] = {};
                    int n = ToUnicode(vk, MapVirtualKeyW(vk, MAPVK_VK_TO_VSC), kb, chars, 8, 0);
                    for (int i = 0; i < n; ++i) io.AddInputCharacter(chars[i]);
                }
            }
        }
    }

    ImGui::NewFrame();
    if (g_phase == Phase::Embedded) drawPanel(); else drawHome();
    ImGui::Render();
    if (g_phase == Phase::Embedded) { keepKeyboard(); persistCollapse(); }

    // Clear then raster: ImGui paints windows, not the void behind them, and yesterday's frame must
    // not show through where nothing was drawn this time.
    const size_t pixels = (size_t)g_dib.w * g_dib.h;
    for (size_t i = 0; i < pixels; ++i) g_dib.px[i] = 0xFF0A0C0Au;   // opaque near-black (theme::CANVAS)
    oxc_sw::renderDrawData(ImGui::GetDrawData(), g_dib.px, g_dib.w, g_dib.h);

    // WS_CLIPCHILDREN on our class is what makes this safe in embedded mode: the DC excludes the
    // game child, so repainting the whole client cannot smear the game's frame.
    HDC wdc = GetDC(g_main);
    if (wdc) {
        BitBlt(wdc, 0, 0, g_dib.w, g_dib.h, g_dib.dc, 0, 0, SRCCOPY);
        ReleaseDC(g_main, wdc);
    }
    maybeDumpFrame();
    // One line a second: did keyboard input reach ImGui, and is a text field active? Diagnoses
    // the "typing into the search box does nothing" class of failure (seen live 2026-09-05).
    static unsigned s_inputLogFrames = 0;
    if (::getenv("OXC_LOG") && (s_inputLogFrames++ % 30) == 1) {
        const ImGuiIO& io = ImGui::GetIO();
        std::printf("[input] WantTextInput=%d chars=%d anyActive=%d focusHere=%d fg=self:%d game:%p mouse=(%.0f,%.0f) cap=%d\n",
                    io.WantTextInput ? 1 : 0, io.InputQueueCharacters.Size,
                    ImGui::IsAnyItemActive() ? 1 : 0,
                    GetFocus() == g_main ? 1 : 0,
                    GetForegroundWindow() == g_main ? 1 : 0, (void*)g_game,
                    io.MousePos.x, io.MousePos.y, io.WantCaptureMouse ? 1 : 0);
    }
    return true;
}

void startLaunch(const std::wstring& environmentBlock) {
    loadPaths();
    if (GetFileAttributesW(g_gamePath.c_str()) == INVALID_FILE_ATTRIBUTES) {
        g_status = L"cannot find the game at: " + g_gamePath;
        return;
    }
    if (GetFileAttributesW(g_dllPath.c_str()) == INVALID_FILE_ATTRIBUTES) {
        g_status = L"0xclient.dll is not next to this exe (or set [oxclient] dll=).";
        return;
    }
    STARTUPINFOW si{ sizeof si };
    PROCESS_INFORMATION pi{};
    std::wstring cmd = L"\"" + g_gamePath + L"\"";
    // The game's own directory as its working directory: NXT resolves its config and cache relative
    // to the cwd, and a launcher that starts it from elsewhere would send it digging in the wrong
    // place.
    const DWORD flags = environmentBlock.empty() ? 0 : CREATE_UNICODE_ENVIRONMENT;
    void* environment = environmentBlock.empty() ? nullptr : const_cast<wchar_t*>(environmentBlock.c_str());
    if (!CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, flags, environment,
                        g_gameDir.c_str(), &si, &pi)) {
        g_status = L"CreateProcess failed (error " + std::to_wstring(GetLastError()) + L").";
        return;
    }
    CloseHandle(pi.hThread);
    g_gameProc = pi.hProcess;
    g_gamePid = pi.dwProcessId;
    setPhase(Phase::WaitWindow,
             L"osclient.exe started (pid " + std::to_wstring(g_gamePid) + L") -- waiting for its window...");
}

LRESULT CALLBACK wndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    switch (m) {
    case WM_OXC_AUTH_NAVIGATE: {
        std::unique_ptr<std::string> url(reinterpret_cast<std::string*>(l));
        if (g_authWindow) { std::string error; if (!g_authWindow->Navigate(*url, error)) g_status = wide(error); }
        return 0;
    }
    case WM_ERASEBKGND:
        return 1;                                   // the DIB covers every pixel; erasing is flicker
    case WM_PAINT: {
        PAINTSTRUCT ps;
        BeginPaint(h, &ps);                         // validate only: the loop blits the real pixels
        EndPaint(h, &ps);
        return 0;
    }
    case WM_SIZE:
        g_clientW = LOWORD(l);
        g_clientH = HIWORD(l);
        if (g_phase == Phase::Embedded) layoutEmbed();
        return 0;
    case WM_CLOSE:
        // Ask the game to close itself first, the way dllmain.cpp's host does: destroying our
        // window would take the game's window down with it mid-swapchain, and NXT never gets to
        // tear down cleanly. frame() notices the game's window go, tidies up, and quits (see
        // g_quitWhenGameGone); with no game embedded, closing is just closing.
        if (g_game && IsWindow(g_game)) {
            g_quitWhenGameGone = true;
            PostMessageW(g_game, WM_CLOSE, 0, 0);
            return 0;
        }
        DestroyWindow(h);
        return 0;
    case WM_DESTROY:
        if (g_gameProc) CloseHandle(g_gameProc);
        PostQuitMessage(0);
        return 0;

    // ---- input -> ImGui ----------------------------------------------------
    // These messages ARE the input path. They reach this proc only when the pointer is over OUR
    // client area (or while we hold capture, which we only take for a drag that began on the strip),
    // so a click aimed at the game child is delivered to the game and is invisible here -- exactly
    // the discrimination the old GetAsyncKeyState poll could not make.
    case WM_MOUSEMOVE: {
        armMouseLeave();                            // so a pointer that leaves is reported, not guessed
        POINT p{ GET_X_LPARAM(l), GET_Y_LPARAM(l) };
        feedMousePos(p);
        return 0;
    }
    case WM_MOUSELEAVE:
        g_mouseTracked = false;
        if (!g_btnDownMask) clearMousePos();        // mid-drag the pointer is allowed to be outside
        return 0;
    case WM_CAPTURECHANGED:
        // Someone took the capture off us mid-drag. Note it once and let reconcileMouse's
        // release-only path cover the rest of this drag; do NOT re-take it, which would fight
        // whatever window legitimately wanted it.
        if (g_captureHeld) {
            g_captureHeld = false;
            logInputPath(LOG_MOUSE, "mouse: capture lost mid-drag -- release reconciliation covers it");
        }
        return 0;
    // No per-key trace here, ever: with OXC_LOG set the launcher once logged every WM_CHAR it got --
    // which is the strip's own text fields, the password one included (review, 2026-09-06). The
    // per-second [input] summary above is all the keyboard diagnosis this file offers.
    case WM_LBUTTONDOWN: {
        // A click on the strip is a click on this window -- the game child got WM_LBUTTONDOWN
        // instead if the point was over it. Take keyboard focus back from the game here: the DLL
        // parks focus on the render view (see WM_ACTIVATE below), and without this a text field
        // like the plugin search never sees WM_CHAR. SetFocus, deliberately NOT
        // SetForegroundWindow: traced 2026-09-05, SetFocus makes Wine point X keyboard focus
        // straight at our client window, while SetForegroundWindow routes activation through the
        // window manager, which (mutter) parks X focus on its frame window where keystrokes are
        // swallowed before Wine ever sees them. Clicking back into the game moves focus to the
        // game child's own window procedure, so nothing more is needed in that direction.
        if (gamePumps()) SetFocus(h);   // skipped while the game is not pumping: see gamePumps()
        POINT p{ GET_X_LPARAM(l), GET_Y_LPARAM(l) };
        feedMouseButton(0, true, p);
        return 0;
    }
    case WM_LBUTTONUP:
    case WM_RBUTTONDOWN:
    case WM_RBUTTONUP:
    case WM_MBUTTONDOWN:
    case WM_MBUTTONUP: {
        const int btn  = (m == WM_LBUTTONUP) ? 0 : (m == WM_RBUTTONDOWN || m == WM_RBUTTONUP) ? 1 : 2;
        const bool down = (m == WM_RBUTTONDOWN || m == WM_MBUTTONDOWN);
        POINT p{ GET_X_LPARAM(l), GET_Y_LPARAM(l) };
        feedMouseButton(btn, down, p);
        return 0;
    }
    case WM_MOUSEWHEEL: {
        // Wheel coordinates arrive in SCREEN space (windowsx GET_X_LPARAM on lParam), unlike every
        // other mouse message -- the same trap dllmain.cpp's panel handler documents.
        POINT p{ GET_X_LPARAM(l), GET_Y_LPARAM(l) };
        ScreenToClient(h, &p);
        // The wheel is the one mouse message Windows routes by FOCUS, not by the window under the
        // cursor: with a strip text field live, a scroll over the game arrives here. Feeding it
        // would scroll the plugin list while the user is scrolling the game, so the position test
        // decides -- the same rule every other event obeys.
        if (!g_btnDownMask && !overStrip(p)) return 0;
        feedMousePos(p);
        ImGui::GetIO().AddMouseWheelEvent(0.0f,
            (float)GET_WHEEL_DELTA_WPARAM(w) / (float)WHEEL_DELTA);
        return 0;
    }
    // Keyboard messages: the PRIMARY input path (frame()'s poll block is the fallback and skips any
    // frame that saw one of these). feedKey maps the key, WM_CHAR carries the character. When Wine
    // routes keystrokes here at all, both arrive and the poll never engages.
    case WM_KEYDOWN:
    case WM_SYSKEYDOWN:
        g_kbMsgSeen = true; feedKey(w, true);  return 0;
    case WM_KEYUP:
    case WM_SYSKEYUP:
        g_kbMsgSeen = true; feedKey(w, false); return 0;
    case WM_CHAR:
        g_kbMsgSeen = true; ImGui::GetIO().AddInputCharacter((unsigned int)w); return 0;

    // Keyboard focus lives with the game (the DLL holds it on JagRenderView via the attached input
    // queues), so activation changes are news the DLL wants: it re-focuses the render view the way
    // dllmain.cpp's host WM_ACTIVATE does. Ignorable by the DLL -- additive, not load-bearing.
    case WM_ACTIVATE:
        if (g_bridge.hdr && g_bridge.hdr->dllMsgHwnd)
            PostMessageW((HWND)(uintptr_t)g_bridge.hdr->dllMsgHwnd, g_msgActivate,
                         (w == WA_INACTIVE) ? 0 : 1, 0);
        break;
    }
    // No WM_SETCURSOR handler on purpose: DefWindowProc applies the class cursor (a real arrow,
    // registered below) over our client area, and the non-client cursors keep working. This window
    // is a top-level with its own X window, so Wine's cursor logic reaches it (see the comment at
    // io.MouseDrawCursor); io.MouseDrawCursor is off so there is exactly one cursor.
    return DefWindowProcW(h, m, w, l);
}

}  // namespace

// WinMain, not wWinMain, on purpose: mingw needs -municode for the wide entry point and silently
// fails to link without it. The one argument we take (--launch) is ASCII, so the narrow entry costs
// us nothing.
int WINAPI WinMain(HINSTANCE inst, HINSTANCE, LPSTR cmdLine, int show) {
    g_msgEmbed    = RegisterWindowMessageW(L"0xClientEmbed");
    g_msgEdit     = RegisterWindowMessageW(L"0xClientBridgeEdit");
    g_msgActivate = RegisterWindowMessageW(L"0xClientBridgeActivate");

    // Same redirect DllMain does: a GUI-subsystem process has no console, so without this every
    // printf (ours, and the [input] trace in frame()) vanishes. OXC_LOG=<win path>.
    //
    // Opened exactly the way client/log.hpp opens it in the game process, because both processes
    // write this one file. Two things the old DeleteFileA + freopen(log, "a") pair got wrong
    // (review 2026-09-06):
    //   * msvcrt's "a" stream seeks to the end and then writes, which is not atomic. A [bridge] line
    //     the DLL appended between our seek and our write landed under ours -- one garbled line per
    //     collision, most likely during the busy boot second when both sides log. FILE_APPEND_DATA
    //     makes the kernel place every write at the current end of file instead.
    //   * a previous game process still shutting down holds the log open with FILE_SHARE_DELETE, so
    //     DeleteFileA "succeeds" (delete-pending), the name stays taken, freopen then fails with
    //     access denied and the launcher logs NOTHING for the run with no sign of it. CREATE_ALWAYS
    //     truncates in place, which needs no free name.
    // If any step fails, stdout stays as it was -- no log, but nothing worse than that.
    if (const char* log = ::getenv("OXC_LOG")) {
        HANDLE h = CreateFileA(log, FILE_APPEND_DATA | GENERIC_READ,
                               FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h != INVALID_HANDLE_VALUE) {
            int fd = _open_osfhandle(reinterpret_cast<intptr_t>(h), _O_APPEND | _O_WRONLY);
            if (fd == -1) {
                CloseHandle(h);
            } else {
                // _dup2 duplicates the handle onto stdout's descriptor, so closing fd afterwards
                // leaves stdout owning a live handle of its own.
                if (_dup2(fd, _fileno(stdout)) == 0) std::setvbuf(stdout, nullptr, _IONBF, 0);
                _close(fd);
            }
        }
    }

    loadPaths();
    initImGui();

    WNDCLASSW wc{};
    wc.lpfnWndProc   = wndProc;
    wc.hInstance     = inst;
    wc.lpszClassName = L"0xClientLauncher";
    wc.hIcon         = LoadIconW(inst, MAKEINTRESOURCEW(1));   // the logo, from launcher/0xClient.rc
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);   // the class cursor DefWindowProc applies
    wc.hbrBackground = nullptr;                          // nothing to erase: we own every pixel
    // WS_CLIPCHILDREN is load-bearing once the game is a child: without it a GetDC on this window
    // covers the child's screen area and every 30fps repaint would paint over the game's frame.
    wc.style         = CS_HREDRAW | CS_VREDRAW;
    RegisterClassW(&wc);

    DWORD style = WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN;
    RECT fr{ 0, 0, 1600, 900 };
    AdjustWindowRect(&fr, style, FALSE);
    g_main = CreateWindowW(wc.lpszClassName, L"0xClient", style,
                           CW_USEDEFAULT, CW_USEDEFAULT, fr.right - fr.left, fr.bottom - fr.top,
                           nullptr, nullptr, inst, nullptr);
    ShowWindow(g_main, show);

    // `0xClient.exe --launch` (or OXC_AUTOSTART=1 in the environment) presses "+ client" itself,
    // so `gradlew run` from a terminal, a desktop shortcut, or a test script can go straight to the
    // game without a mouse. Exactly the button's path -- startLaunch() validates the ini paths and
    // puts any failure in the status line the home screen shows -- nothing is bypassed.
    if ((cmdLine && std::strstr(cmdLine, "--launch")) || ::getenv("OXC_AUTOSTART"))
        startLaunch();

    // Frame-paced message pump: ~30fps like the DLL's loop, but the launcher never sleeps past a
    // message -- PeekMessage drains everything pending before each frame.
    double nextFrame = nowSeconds();
    MSG msg{};
    for (;;) {
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) { g_quit = true; break; }
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        if (g_quit) break;
        double t = nowSeconds();
        if (t < nextFrame) {
            double ms = (nextFrame - t) * 1000.0;
            Sleep(ms < 1.0 ? 1.0 : ms);              // Wine's timer resolution makes Sleep(0) a spin
            continue;
        }
        nextFrame = t + 1.0 / 30.0;
        frame();
    }

    g_bridge.close();
    releaseDib();
    ImGui::DestroyContext();
    return 0;
}
