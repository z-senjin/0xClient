// offsets_json.hpp -- load the per-build offset file into the variables offsets.hpp declares.
//
// offsets.hpp carries one value per number and the note that says how it was found. Those values are
// the DEFAULTS, measured on the build named by BUILD_VERSION. At start-up the DLL reads the host
// osclient.exe's version resource and looks for offsets/client-<version>.json next to itself; if the
// file is there, every number in it replaces the default, and the client runs on a build the DLL was
// never compiled for. If it is not there, the DLL asks the project's GitHub repository for it once
// (the derivation pipeline publishes a file per build) and caches it beside the DLL.
//
// The file format is deliberately flat:
//
//   {
//     "build":   "241-3",
//     "sha256":  "<osclient.exe digest the numbers were derived from>",
//     "offsets": { "CLIENT_OBJ_PTR": { "value": 15290984, "status": "derived", "evidence": "..." }, ... }
//   }
//
// Unknown keys are ignored (a newer pipeline may know more than this DLL); missing keys keep their
// compiled default and are reported, because a default from another build is exactly the silent
// wrong-read this whole arrangement exists to prevent. A key whose status is "missing" leaves the
// default too, and is reported as missing rather than applied.
#pragma once
#include <windows.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "log.hpp"
#include "offsets.hpp"

namespace oxc::offjson {

// ---------------------------------------------------------------------------------------------------
// A JSON reader just big enough for the offsets file: objects, strings, numbers, true/false/null.
// ---------------------------------------------------------------------------------------------------
struct Value {
    enum Kind { Null, Bool, Number, String, Object, Array } kind = Null;
    bool b = false;
    double num = 0;
    std::string str;
    std::map<std::string, Value> obj;
    std::vector<Value> arr;

    const Value* get(const std::string& k) const {
        auto it = obj.find(k);
        return it == obj.end() ? nullptr : &it->second;
    }
};

class Parser {
public:
    explicit Parser(const std::string& s) : s_(s) {}
    bool parse(Value& out, std::string& err) {
        skip();
        if (!value(out, err)) return false;
        skip();
        if (i_ != s_.size()) { err = "trailing characters at " + std::to_string(i_); return false; }
        return true;
    }

private:
    const std::string& s_;
    size_t i_ = 0;

    void skip() { while (i_ < s_.size() && (s_[i_] == ' ' || s_[i_] == '\n' || s_[i_] == '\r' || s_[i_] == '\t')) ++i_; }

    bool value(Value& v, std::string& err) {
        if (i_ >= s_.size()) { err = "unexpected end"; return false; }
        char c = s_[i_];
        if (c == '{') return object(v, err);
        if (c == '[') return array(v, err);
        if (c == '"') { v.kind = Value::String; return string(v.str, err); }
        if (c == 't' && s_.compare(i_, 4, "true") == 0) { v.kind = Value::Bool; v.b = true; i_ += 4; return true; }
        if (c == 'f' && s_.compare(i_, 5, "false") == 0) { v.kind = Value::Bool; v.b = false; i_ += 5; return true; }
        if (c == 'n' && s_.compare(i_, 4, "null") == 0) { v.kind = Value::Null; i_ += 4; return true; }
        if (c == '-' || (c >= '0' && c <= '9')) {
            size_t start = i_;
            while (i_ < s_.size() && (s_[i_] == '-' || s_[i_] == '+' || s_[i_] == '.' || s_[i_] == 'e' || s_[i_] == 'E' || (s_[i_] >= '0' && s_[i_] <= '9'))) ++i_;
            v.kind = Value::Number;
            v.num = std::strtod(s_.c_str() + start, nullptr);
            return true;
        }
        err = "unexpected character '" + std::string(1, c) + "' at " + std::to_string(i_);
        return false;
    }

    bool string(std::string& out, std::string& err) {
        ++i_;  // opening quote
        while (i_ < s_.size()) {
            char c = s_[i_++];
            if (c == '"') return true;
            if (c == '\\') {
                if (i_ >= s_.size()) break;
                char e = s_[i_++];
                switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    if (i_ + 4 > s_.size()) { err = "bad \\u escape"; return false; }
                    unsigned cp = std::strtoul(s_.substr(i_, 4).c_str(), nullptr, 16);
                    i_ += 4;
                    if (cp < 0x80) out += static_cast<char>(cp);
                    else if (cp < 0x800) { out += static_cast<char>(0xC0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
                    else { out += static_cast<char>(0xE0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
                    break;
                }
                default: out += e; break;
                }
            } else {
                out += c;
            }
        }
        err = "unterminated string";
        return false;
    }

    bool object(Value& v, std::string& err) {
        v.kind = Value::Object;
        ++i_;
        skip();
        if (i_ < s_.size() && s_[i_] == '}') { ++i_; return true; }
        while (true) {
            skip();
            if (i_ >= s_.size() || s_[i_] != '"') { err = "expected key at " + std::to_string(i_); return false; }
            std::string key;
            if (!string(key, err)) return false;
            skip();
            if (i_ >= s_.size() || s_[i_] != ':') { err = "expected ':' at " + std::to_string(i_); return false; }
            ++i_;
            skip();
            Value child;
            if (!value(child, err)) return false;
            v.obj[key] = std::move(child);
            skip();
            if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
            if (i_ < s_.size() && s_[i_] == '}') { ++i_; return true; }
            err = "expected ',' or '}' at " + std::to_string(i_);
            return false;
        }
    }

    bool array(Value& v, std::string& err) {
        v.kind = Value::Array;
        ++i_;
        skip();
        if (i_ < s_.size() && s_[i_] == ']') { ++i_; return true; }
        while (true) {
            skip();
            Value child;
            if (!value(child, err)) return false;
            v.arr.push_back(std::move(child));
            skip();
            if (i_ < s_.size() && s_[i_] == ',') { ++i_; continue; }
            if (i_ < s_.size() && s_[i_] == ']') { ++i_; return true; }
            err = "expected ',' or ']' at " + std::to_string(i_);
            return false;
        }
    }
};

// ---------------------------------------------------------------------------------------------------
// The variable table: every name in offsets.hpp, generated into offsets_table.inc.
// ---------------------------------------------------------------------------------------------------
struct Slot {
    const char* name;
    std::uintptr_t* u;   // one of these two is set, by kind
    int* i;
};

inline const std::vector<Slot>& slots() {
    static const std::vector<Slot> table = {
#define OXC_OFFSET_uintptr(NAME) Slot{#NAME, &oxc::off::NAME, nullptr},
#define OXC_OFFSET_int(NAME)     Slot{#NAME, nullptr, &oxc::off::NAME},
#define OXC_OFFSET(KIND, NAME)   OXC_OFFSET_##KIND(NAME)
#include "offsets_table.inc"
#undef OXC_OFFSET
#undef OXC_OFFSET_int
#undef OXC_OFFSET_uintptr
    };
    return table;
}

struct Report {
    bool loaded = false;         // a file for this build was read and applied
    std::string build;           // the build the file names
    std::string source;          // "file", "download", or "refresh" (a newer copy replaced the cache)
    int applied = 0, missing = 0, unknown = 0, refused = 0, unmeasured = 0;
    std::vector<std::string> missingNames;
    std::vector<std::string> refusedNames;     // "NAME (status)": an RVA not measured on this build
    std::vector<std::string> unmeasuredNames;  // "NAME (status)": a field applied without a measurement
    std::string error;           // why loading failed, when it did
};

// ---------------------------------------------------------------------------------------------------
// Trust. Every applied name keeps the status its file gave it, so a feature can ask "was the number I
// am about to use measured on THIS build?" instead of finding out from a wrong read.
//
//   measured   derived (read off this build's code) or verified (and confirmed against a running game)
//   anything else -- carried, shifted, default, suspect, refuted -- is a value from another build
//
// The rule apply() enforces with it, per KIND (update.py writes the kind; older files fall back to
// the name lists below):
//
//   code, global   an RVA. These move on EVERY build, so an unmeasured one is not "probably fine", it
//                  is almost certainly wrong -- and a wrong code RVA is a crash, a wrong global a
//                  garbage read. Refused: the variable is set to 0, which every caller treats as
//                  "not available on this build" (doAction, projectFine, clientObj, varps, widgets).
//   field          a struct displacement. These move rarely, so an unmeasured one is applied -- the
//                  alternative is a client that reads nothing at all after every update -- but it is
//                  listed in the [build] log, and OXC_SELFCHECK=1 tests it against the running game.
//   refuted        never applied, whatever its kind: the note that recorded it says it is wrong.
// ---------------------------------------------------------------------------------------------------
inline std::map<std::string, std::string>& statusTable() {
    static std::map<std::string, std::string> t;
    return t;
}

/// The status the loaded file gave `name` ("derived", "carried", ...), or "compiled" when no file was
/// loaded and the compiled default is in force.
inline std::string statusOf(const std::string& name) {
    auto it = statusTable().find(name);
    return it == statusTable().end() ? "compiled" : it->second;
}

inline bool isMeasuredStatus(const std::string& st) { return st == "derived" || st == "verified"; }

/// True when `name` was derived or verified on the running build (or the compiled defaults are for
/// exactly this build, in which case the header's own notes are the measurement).
inline bool measured(const std::string& name) {
    const std::string st = statusOf(name);
    return st == "compiled" || isMeasuredStatus(st);
}

inline std::string kindFallback(const std::string& name) {
    static const char* code[] = {"BUILD_ID", "DO_ACTION", "WORLD_TO_SCREEN", "GET_VARBIT",
                                 "ACT_TICK", "ACT_WALK", "ACT_NPC_OP", "ACT_LOC_OP", "ACT_IF_OP",
                                 "ACT_OBJ_OP", "ACT_ON_ITEM", "ACT_ON_LOC", "ACT_ON_NPC", "ACT_ON_OBJ"};
    static const char* glob[] = {"CLIENT_OBJ_PTR", "VARP_ARRAY_PTR", "CONTAINER_BUCKETS", "CONTAINER_MASK",
                                 "IFACE_EMPTY_SENTINEL", "LOCDEF_CACHE", "ITEMDEF_CACHE"};
    for (const char* c : code) if (name == c) return "code";
    for (const char* g : glob) if (name == g) return "global";
    return "field";
}

// Where the per-build files live: offsets/ beside the DLL. `dir` is the DLL's directory.
inline std::wstring offsetsPath(const std::wstring& dir, const std::wstring& version) {
    return dir + L"\\offsets\\client-" + version + L".json";
}

inline bool readFile(const std::wstring& path, std::string& out) {
    std::ifstream f(std::filesystem::path(path), std::ios::binary);
    if (!f) return false;
    std::ostringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return true;
}

// One GET over WinHTTP, into `out`. Returns false on any failure with the reason in `err`. Kept small:
// no redirects beyond what WinHTTP follows itself, no auth, no retries -- the caller decides.
// `timeoutMs` > 0 bounds every phase (resolve, connect, send, receive) so an optional fetch at start-up
// cannot hold the game's loading screen hostage; 0 keeps WinHTTP's own defaults.
inline bool httpGet(const std::wstring& host, const std::wstring& path, std::string& out, std::string& err,
                    int timeoutMs = 0) {
    HINTERNET s = WinHttpOpen(L"0xClient/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!s) { err = "WinHttpOpen failed"; return false; }
    if (timeoutMs > 0) WinHttpSetTimeouts(s, timeoutMs, timeoutMs, timeoutMs, timeoutMs);
    HINTERNET c = WinHttpConnect(s, host.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!c) { WinHttpCloseHandle(s); err = "WinHttpConnect failed"; return false; }
    HINTERNET r = WinHttpOpenRequest(c, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    bool ok = false;
    if (r && WinHttpSendRequest(r, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) && WinHttpReceiveResponse(r, nullptr)) {
        DWORD status = 0, len = sizeof status;
        WinHttpQueryHeaders(r, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &status, &len, WINHTTP_NO_HEADER_INDEX);
        if (status == 200) {
            DWORD avail = 0;
            while (WinHttpQueryDataAvailable(r, &avail) && avail > 0) {
                std::string chunk(avail, '\0');
                DWORD got = 0;
                if (!WinHttpReadData(r, chunk.data(), avail, &got)) break;
                out.append(chunk.data(), got);
            }
            ok = true;
        } else {
            err = "HTTP " + std::to_string(status);
        }
    } else {
        err = "request failed (" + std::to_string(GetLastError()) + ")";
    }
    if (r) WinHttpCloseHandle(r);
    WinHttpCloseHandle(c);
    WinHttpCloseHandle(s);
    return ok;
}

// Apply a parsed file to the variables. Reports what was set, what the file did not know, and what
// the file knew that this DLL does not.
inline bool apply(const Value& root, Report& rep) {
    const Value* build = root.get("build");
    const Value* offsets = root.get("offsets");
    if (!build || build->kind != Value::String || !offsets || offsets->kind != Value::Object) {
        rep.error = "offsets file is missing \"build\" or \"offsets\"";
        return false;
    }
    rep.build = build->str;
    statusTable().clear();
    for (const Slot& s : slots()) {
        const Value* entry = offsets->get(s.name);
        if (!entry) { ++rep.missing; rep.missingNames.push_back(s.name); continue; }
        const Value* v = entry->kind == Value::Object ? entry->get("value") : entry;
        const Value* st = entry->kind == Value::Object ? entry->get("status") : nullptr;
        const Value* kd = entry->kind == Value::Object ? entry->get("kind") : nullptr;
        const std::string status = st && st->kind == Value::String ? st->str : "derived";  // bare numbers: old format
        const std::string kind = kd && kd->kind == Value::String ? kd->str : kindFallback(s.name);
        if (status == "missing") { ++rep.missing; rep.missingNames.push_back(s.name); continue; }
        if (!v || v->kind != Value::Number) { ++rep.missing; rep.missingNames.push_back(s.name); continue; }
        statusTable()[s.name] = status;
        const bool rva = kind == "code" || kind == "global";
        if (status == "refuted" || (rva && !isMeasuredStatus(status))) {
            // Refused: a stale RVA is a crash or a garbage read; a refuted value is known wrong. 0 is
            // the "not on this build" sentinel every caller checks. A refuted FIELD keeps its compiled
            // default instead (0 is a real displacement), and is still listed so nothing trusts it.
            if (rva) { if (s.u) *s.u = 0; else if (s.i) *s.i = 0; }
            ++rep.refused;
            rep.refusedNames.push_back(std::string(s.name) + " (" + kind + ", " + status + ")");
            continue;
        }
        if (s.u) *s.u = static_cast<std::uintptr_t>(v->num);
        else if (s.i) *s.i = static_cast<int>(v->num);
        ++rep.applied;
        if (!isMeasuredStatus(status) && status != "constant") {
            ++rep.unmeasured;
            rep.unmeasuredNames.push_back(std::string(s.name) + " (" + status + ")");
        }
    }
    for (const auto& kv : offsets->obj) {
        bool known = false;
        for (const Slot& s : slots()) if (kv.first == s.name) { known = true; break; }
        if (!known) ++rep.unknown;
    }
    oxc::off::BUILD_VERSION = std::wstring(rep.build.begin(), rep.build.end());
    // The one relationship the code relies on across two names: the NxtString flag byte sits 0x17
    // past the string. A file that breaks it is reporting two different structs, so say so.
    if (oxc::off::IFTYPE_TEXT_FLAG != oxc::off::IFTYPE_TEXT + 0x17)
        oxc::logf("[offsets] WARNING: IFTYPE_TEXT_FLAG (0x%llx) is not IFTYPE_TEXT+0x17 (0x%llx) in this file\n",
                  (unsigned long long)oxc::off::IFTYPE_TEXT_FLAG, (unsigned long long)oxc::off::IFTYPE_TEXT);
    return true;
}

// ---------------------------------------------------------------------------------------------------
// Which binary the file was derived from. The file names a build AND the sha256 of the osclient.exe
// its numbers were read off; the version resource alone cannot tell two binaries with the same
// FileVersion apart (a re-signed or hot-fixed exe), and numbers measured on one are not evidence
// about the other. Hashed with the OS's own BCrypt, once, at start-up (16 MB: tens of milliseconds).
// ---------------------------------------------------------------------------------------------------
inline std::string hostSha256() {
    wchar_t exe[MAX_PATH]{};
    if (!GetModuleFileNameW(nullptr, exe, MAX_PATH)) return "";
    std::string data;
    if (!readFile(exe, data)) return "";
    BCRYPT_ALG_HANDLE alg = nullptr;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) return "";
    BCRYPT_HASH_HANDLE h = nullptr;
    unsigned char digest[32]{};
    bool ok = BCryptCreateHash(alg, &h, nullptr, 0, nullptr, 0, 0) == 0
           && BCryptHashData(h, reinterpret_cast<PUCHAR>(data.data()), static_cast<ULONG>(data.size()), 0) == 0
           && BCryptFinishHash(h, digest, sizeof digest, 0) == 0;
    if (h) BCryptDestroyHash(h);
    BCryptCloseAlgorithmProvider(alg, 0);
    if (!ok) return "";
    static const char* hex = "0123456789abcdef";
    std::string out;
    for (unsigned char b : digest) { out += hex[b >> 4]; out += hex[b & 15]; }
    return out;
}

// Where published files come from: "<owner>/<repo>/<branch>" on raw.githubusercontent.com. A fork
// that publishes its own offsets sets OXC_OFFSETS_REPO instead of rebuilding the DLL.
inline std::wstring remotePath(const std::wstring& version) {
    std::wstring repo = L"StoneShorts/0xClient/main";
    if (const char* r = ::getenv("OXC_OFFSETS_REPO")) {
        std::string n(r);
        repo.assign(n.begin(), n.end());
    }
    return L"/" + repo + L"/offsets/client-" + version + L".json";
}

// How many entries a parsed file has measured (derived/verified) -- the "is this copy better" test.
inline int measuredCount(const Value& root) {
    const Value* o = root.get("offsets");
    if (!o || o->kind != Value::Object) return 0;
    int n = 0;
    for (const auto& kv : o->obj) {
        const Value* st = kv.second.kind == Value::Object ? kv.second.get("status") : nullptr;
        if (st && st->kind == Value::String && isMeasuredStatus(st->str)) ++n;
    }
    return n;
}

inline std::string strField(const Value& root, const char* k) {
    const Value* v = root.get(k);
    return v && v->kind == Value::String ? v->str : "";
}

inline void writeCache(const std::wstring& dllDir, const std::wstring& path, const std::string& text) {
    CreateDirectoryW((dllDir + L"\\offsets").c_str(), nullptr);
    std::ofstream f(std::filesystem::path(path), std::ios::binary);
    f << text;
}

// A cached file is a snapshot of the repository on the day it was fetched. The pipeline keeps
// improving a build's file after release (a hand derivation, a live verification), so a cache older
// than this is re-checked against the repository once -- quickly, and only REPLACED when the remote
// copy is for the same binary and is newer or measures more. OXC_OFFSETS_REFRESH=0 turns this off.
constexpr long long kRefreshAfterSeconds = 6 * 60 * 60;

inline long long ageSeconds(const std::wstring& path) {
    WIN32_FILE_ATTRIBUTE_DATA a{};
    if (!GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &a)) return -1;
    FILETIME now{};
    GetSystemTimeAsFileTime(&now);
    auto ticks = [](const FILETIME& f) { return (static_cast<long long>(f.dwHighDateTime) << 32) | f.dwLowDateTime; };
    return (ticks(now) - ticks(a.ftLastWriteTime)) / 10000000LL;
}

// Load offsets for `version` ("241-3"): the file beside the DLL first (refreshed from the repository
// when it is stale), else one download from the repository, cached beside the DLL for next time.
// The file must be for this exact binary (sha256) unless OXC_SKIP_BUILD_CHECK is set. Never throws;
// the report says what happened.
inline Report load(const std::wstring& dllDir, const std::wstring& version) {
    Report rep;
    const std::wstring path = offsetsPath(dllDir, version);
    std::string text;
    Value root;
    std::string err;
    const bool haveCache = readFile(path, text);
    if (haveCache) {
        rep.source = "file";
        if (!Parser(text).parse(root, err)) {
            rep.error = "offsets file does not parse: " + err;
            return rep;
        }
        const char* rf = ::getenv("OXC_OFFSETS_REFRESH");
        if (!(rf && rf[0] == '0') && ageSeconds(path) > kRefreshAfterSeconds) {
            std::string remote, rerr;
            Value r;
            if (httpGet(L"raw.githubusercontent.com", remotePath(version), remote, rerr, 3000) && Parser(remote).parse(r, rerr)
                && strField(r, "build") == strField(root, "build") && strField(r, "sha256") == strField(root, "sha256")
                && (strField(r, "derived") > strField(root, "derived") || measuredCount(r) > measuredCount(root))) {
                oxc::logf("[build] refreshed offsets/client-%s.json from the repository (%d -> %d measured)\n",
                          strField(r, "build").c_str(), measuredCount(root), measuredCount(r));
                text = remote;
                root = std::move(r);
                rep.source = "refresh";
            }
            writeCache(dllDir, path, text);  // also restarts the age clock when nothing newer was found
        }
    } else {
        if (!httpGet(L"raw.githubusercontent.com", remotePath(version), text, err)) {
            std::string ver;
            for (wchar_t ch : version) ver += (ch < 128 ? static_cast<char>(ch) : '?');
            rep.error = "no offsets/client-" + ver + ".json beside the DLL, and the download failed: " + err;
            return rep;
        }
        rep.source = "download";
        if (!Parser(text).parse(root, err)) {
            rep.error = "downloaded offsets file does not parse: " + err;
            return rep;
        }
        writeCache(dllDir, path, text);
    }

    const std::string want = strField(root, "sha256");
    if (!want.empty()) {
        const std::string have = hostSha256();
        if (have.empty()) {
            oxc::logf("[build] could not hash osclient.exe; the file's sha256 is unchecked\n");
        } else if (have != want) {
            if (!::getenv("OXC_SKIP_BUILD_CHECK")) {
                rep.error = "offsets file was derived from osclient.exe sha256 " + want + ", but this one is " + have +
                            " -- same version string, different binary. Re-derive it (update.py --exe), or set "
                            "OXC_SKIP_BUILD_CHECK=1 to use it anyway";
                return rep;
            }
            oxc::logf("[build] WARNING: sha256 mismatch (file %s, exe %s); OXC_SKIP_BUILD_CHECK set\n", want.c_str(), have.c_str());
        }
    }
    if (!apply(root, rep)) return rep;
    rep.loaded = true;
    return rep;
}

}  // namespace oxc::offjson
