#!/usr/bin/env python3
"""The 0xClient updater: turn a new osclient.exe into offsets/client-<build>.json.

    python tools/update/update.py --latest          # whatever Jagex is shipping right now
    python tools/update/update.py --build 241-3     # one specific build (CDN if current, else archive)
    python tools/update/update.py --exe path\\to\\osclient.exe   # a file you already have

Steps, each of which can be run on its own with the flags below:

  1. fetch      tools/update/fetch_client.py downloads the build into game/client-<build>/
  2. analyse    Ghidra headless imports and analyses the binary (10-20 min the first time, cached
                after) and runs tools/ghidra_scripts/DeriveOffsets.java, which walks from the
                client's own Lua binding names to every number the DLL needs and writes
                build/ghidra-<build>/derived.json with the evidence for each.
  3. merge      the derived values are merged with the previous build's file: anything the script
                could not derive keeps the previous value, marked "carried" so nothing silently
                pretends to be measured; anything new is "derived"; anything the previous file
                marked "verified" that the script re-derived to the SAME value stays "verified".
  4. write      offsets/client-<build>.json, plus a diff against the previous build on stdout.

Ghidra is found from --ghidra, then $GHIDRA_HOME, then the newest ghidra_* folder under common
locations. Java 21+ must be on PATH or in $JAVA_HOME for Ghidra to start.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.dirname(os.path.abspath(__file__))
OFFSETS_DIR = os.path.join(ROOT, "offsets")
SCRIPTS = os.path.join(ROOT, "tools", "ghidra_scripts")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pe_file_version(path):
    """FileVersion from the PE version resource, read by hand so this runs anywhere."""
    data = open(path, "rb").read()
    # VS_VERSION_INFO blocks carry "FileVersion\0" as UTF-16LE followed by the value string.
    key = "FileVersion".encode("utf-16-le")
    for m in re.finditer(re.escape(key), data):
        i = m.end()
        # skip NUL terminator(s) and padding to the next 4-byte boundary
        while i < len(data) and data[i:i + 2] == b"\x00\x00":
            i += 2
        if i % 4:
            i += 4 - (i % 4)
        s = data[i:i + 64].decode("utf-16-le", errors="ignore")
        s = s.split("\x00")[0].strip()
        if re.fullmatch(r"\d+-\d+", s):
            return s
    return None


def find_ghidra(explicit):
    candidates = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("GHIDRA_HOME"):
        candidates.append(os.environ["GHIDRA_HOME"])
    for pat in ("C:/ghidra*", "C:/Program Files/ghidra*", os.path.expanduser("~/ghidra*"),
                os.path.expanduser("~/toolchains/ghidra*/ghidra_*"), os.path.expanduser("~/toolchains/ghidra*"),
                "/opt/ghidra*", "/usr/share/ghidra*", "/usr/local/ghidra*"):
        candidates.extend(sorted(glob.glob(pat), reverse=True))
    for c in candidates:
        for name in ("analyzeHeadless.bat", "analyzeHeadless"):
            p = os.path.join(c, "support", name)
            if os.path.exists(p):
                return p
    return None


def fetch(args):
    cmd = [sys.executable, os.path.join(HERE, "fetch_client.py"), "--out", os.path.join(ROOT, "game")]
    cmd += ["--latest"] if args.latest else ["--build", args.build]
    log("fetch:", " ".join(cmd[1:]))
    out = subprocess.run(cmd, capture_output=True, text=True, check=True)
    build = out.stdout.strip().splitlines()[-1]
    return build, os.path.join(ROOT, "game", f"client-{build}", "osclient.exe")


def analyse(exe, build, headless, project_root):
    proj = os.path.join(project_root, f"ghidra-{build}")
    os.makedirs(proj, exist_ok=True)
    derived = os.path.join(proj, "derived.json")
    if os.path.exists(derived):
        os.remove(derived)
    gpr = os.path.join(proj, "osrs.gpr")
    cmd = [headless, proj, "osrs"]
    # -import creates the project the first time; -process reuses the analysed program after that.
    cmd += ["-process", "osclient.exe"] if os.path.exists(gpr) else ["-import", exe]
    cmd += ["-scriptPath", SCRIPTS, "-postScript", "DeriveOffsets.java", derived,
            "-analysisTimeoutPerFile", "3600"]
    if os.path.exists(gpr):
        cmd += ["-noanalysis"]
    log("ghidra:", " ".join(cmd))
    with open(os.path.join(proj, "headless.log"), "w", encoding="utf-8") as lf:
        r = subprocess.run(cmd, stdout=lf, stderr=subprocess.STDOUT)
    if r.returncode != 0 or not os.path.exists(derived):
        raise SystemExit(f"Ghidra did not produce {derived}; see {os.path.join(proj, 'headless.log')}")
    return json.load(open(derived, encoding="utf-8"))


def previous_file(build):
    """The newest offsets file for a build older than `build` (by numeric order)."""
    def key(b):
        return tuple(int(x) for x in b.split("-"))
    files = []
    for f in glob.glob(os.path.join(OFFSETS_DIR, "client-*.json")):
        b = re.search(r"client-(\d+-\d+)\.json$", f)
        if b and key(b.group(1)) < key(build):
            files.append((key(b.group(1)), f))
    if not files:
        return None, None
    files.sort()
    f = files[-1][1]
    return f, json.load(open(f, encoding="utf-8"))


def table_keys():
    out = subprocess.run([sys.executable, os.path.join(HERE, "gen_offset_table.py"), "--json"],
                         capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def header_notes():
    """Each offset's derivation note from client/offsets.hpp, and the status that note states.
    Used only when there is no previous build file: the compiled defaults are the first baseline,
    and their notes already say which were confirmed against a running game."""
    text = open(os.path.join(ROOT, "client", "offsets.hpp"), encoding="utf-8").read()
    notes, comment = {}, []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("//"):
            comment.append(s[2:].strip())
            continue
        m = re.match(r"^inline\s+(?:std::uintptr_t|std::int32_t|int)\s+(\w+)\s*=\s*[^;]+;\s*(?://\s*(.*))?$", line)
        if m:
            note = " ".join(comment).strip()
            trail = (m.group(2) or "").strip()
            whole = (note + " " + trail).upper()
            live = "VERIFIED LIVE" in whole and "NOT VERIFIED" not in whole and "NOT RE-VERIFIED" not in whole and "NOT (RE-)VERIFIED" not in whole
            if "SUSPECT" in trail.upper() or "NOT DERIVED" in whole:
                status = "suspect"
            elif "REFUTED" in whole and not live:
                status = "refuted"
            elif live:
                status = "verified"
            else:
                status = "default"
            notes[m.group(1)] = (status, (trail or note)[:400])
            comment = []
        elif s:
            comment = []
    return notes


# Struct FAMILIES: displacements that live in the same object. When a build inserts or removes bytes
# in that object, every field past the edit moves by the same amount, so a carried field whose nearest
# DERIVED neighbours in its own family moved by the same delta on BOTH sides is moved by that delta too,
# and labelled "shifted" rather than "carried" -- still not a measurement, but an inference with a
# stated basis that the DLL's self-check then confirms or refutes. Fields of different objects must
# never be mixed: a delta measured on the client object says nothing about an entity.
FAMILIES = {
    "client object": {
        "VIEW_OBJ", "GAME_STATE", "CYCLE", "SKILL_EFFECTIVE", "SKILL_BASE", "SKILL_XP", "RUN_ENERGY",
        "WORLD_MAP", "CAMERA_FINE_X", "CAMERA_FINE_H", "CAMERA_FINE_Y", "REGISTRY_MAP", "REGISTRY_GROUPS",
        "REGISTRY_GROUP_COUNT", "SCENE", "LOCAL_PLAYER_IDX", "REGISTRY_GROUP_SEL", "PLAYER_COUNT", "PLAYER_IDS",
        "IFACE_MANAGER",
    },
    "entity": {
        "ENTITY_FINE_H", "ENTITY_FINE_X", "ENTITY_FINE_Y", "ENTITY_ORIENTATION", "ENTITY_SCENE_X", "ENTITY_SCENE_Y",
        "ENTITY_PLANE", "ENTITY_ANIMATION", "ENTITY_NAME_OVERRIDE", "PLAYER_NAME_PTR", "ENTITY_DEF_PTR",
        "PLAYER_COMBAT_LEVEL", "ENTITY_PLANE_COORD",
    },
    "IfType": {
        "IFTYPE_X", "IFTYPE_Y", "IFTYPE_WIDTH", "IFTYPE_HEIGHT", "IFTYPE_HIDDEN", "IFTYPE_TEXT", "IFTYPE_TEXT_FLAG",
        "IFTYPE_TEXT2", "IFTYPE_TEXT2_FLAG", "IFTYPE_CHILDREN_COUNT", "IFTYPE_CHILDREN_DATA",
    },
}
# kept for anything that imported the old name
CLIENT_OBJECT_FIELDS = FAMILIES["client object"]


def infer_shifts(offsets, prev_offsets):
    for family, members in FAMILIES.items():
        derived = sorted((p["value"], offsets[n]["value"] - p["value"]) for n, p in prev_offsets.items()
                         if n in members and n in offsets and offsets[n]["status"] in ("derived", "verified")
                         and isinstance(p.get("value"), int))
        for name, entry in offsets.items():
            if name not in members or entry["status"] not in ("carried", "suspect"):
                continue
            old = entry["value"]
            below = [(v, d) for v, d in derived if v < old]
            above = [(v, d) for v, d in derived if v > old]
            if not below or not above:
                continue
            lo, hi = below[-1], above[0]
            if lo[1] != hi[1]:
                continue
            if lo[1] == 0:
                entry["inference"] = (f"the nearest derived {family} fields on both sides (0x{lo[0]:x}, 0x{hi[0]:x}) "
                                      f"did not move, so this one most likely did not either")
                continue
            entry["previous"] = old
            entry["value"] = old + lo[1]
            if entry["status"] == "carried":
                entry["status"] = "shifted"   # a suspect value stays suspect: it moved, it did not improve
            entry["inference"] = (f"not derived; the nearest derived {family} fields on both sides (0x{lo[0]:x} and "
                                  f"0x{hi[0]:x}) both moved by {lo[1]:+#x}, so this one was moved with them. Verify before trusting")

# What KIND of number each entry is. The kind decides how dangerous a value that was not measured on
# this build is, and the DLL (client/offsets_json.hpp) applies exactly this rule:
#
#   code      an RVA the DLL CALLS. Moves on every build; a stale one crashes the game. Never applied
#             unless derived or verified on this build.
#   global    an RVA of a data cell the DLL reads. Moves on every build; a stale one reads garbage.
#             Never applied unless derived or verified on this build.
#   field     a displacement inside a struct. Moves rarely; a carried one is applied but labelled, and
#             the DLL's self-check is what confirms or condemns it.
#   opcode    a menu action number. Only a hook-and-log against a real click establishes it.
#   constant  a number 0xClient chooses (a scan span, a depth cap). Not read from the game at all, so
#             "carried" would be a lie: it is labelled "constant" and never reviewed as an offset.
CODE_RVAS = {"BUILD_ID", "DO_ACTION", "WORLD_TO_SCREEN", "GET_VARBIT", "ACT_TICK", "ACT_WALK", "ACT_NPC_OP", "ACT_LOC_OP", "ACT_IF_OP"}
GLOBAL_RVAS = {"CLIENT_OBJ_PTR", "VARP_ARRAY_PTR", "CONTAINER_BUCKETS", "CONTAINER_MASK", "IFACE_EMPTY_SENTINEL", "LOCDEF_CACHE"}
OPCODES = set()   # since client-241-3 the OP_* numbers are 0xClient's own labels (client/actions.hpp)
CONSTANTS = {"IFTYPE_SCAN_SPAN", "IFTYPE_CHAIN_MAX", "OPLOC1", "OPNPC1", "OPNPC2", "OPNPC3", "OPNPC4", "OPNPC5", "OP_WALK"}


def kind_of(name):
    if name in CODE_RVAS:
        return "code"
    if name in GLOBAL_RVAS:
        return "global"
    if name in OPCODES:
        return "opcode"
    if name in CONSTANTS:
        return "constant"
    return "field"


MEASURED = ("derived", "verified")
# statuses a carried value keeps, so a doubt recorded on one build is not laundered into "carried"
DOUBTS = ("suspect", "refuted")
_CARRY_PREFIX = re.compile(r"^(?:(?:not derived on [\w-]+; (?:carried|value last \w+) (?:from|on) [\w-]+: )|(?:(?:carried|not derived); the nearest derived [\w -]*?fields[^.]*\.(?: [^.]*?(?:either|them)\.)?(?: Verify before trusting\.)? ))+")


def origin_evidence(entry):
    """The evidence a value was ORIGINALLY recorded with, without the 'carried from' chain earlier
    builds wrapped around it (old files nest one prefix per build)."""
    return _CARRY_PREFIX.sub("", str(entry.get("evidence", "")))


def keep_verified(build, sha, offsets):
    """Re-deriving a build that already has a file must not throw away what the running game confirmed:
    an entry the existing file (same build, same binary) records as verified -- or as FAILED in game --
    and that came out with the same value now, keeps that status with its date and evidence. A changed
    value is not carried over: it is a different number, and nothing has been seen about it yet."""
    path = os.path.join(OFFSETS_DIR, f"client-{build}.json")
    try:
        old = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return
    if old.get("sha256") != sha:
        return
    for name, e in old.get("offsets", {}).items():
        cur = offsets.get(name)
        if not cur or e.get("value") != cur.get("value"):
            continue
        failed = e.get("status") == "suspect" and str(e.get("verification", "")).startswith("FAILED")
        if e.get("status") != "verified" and not failed:
            continue
        cur["status"] = "suspect" if failed else "verified"
        for k in ("verified_on", "verification"):
            if k in e:
                cur[k] = e[k]
        for k in ("since", "since_status", "inference"):
            cur.pop(k, None)


def _status_in(build, name):
    """The status `name` has in offsets/client-<build>.json, if that file exists."""
    path = os.path.join(OFFSETS_DIR, f"client-{build}.json")
    try:
        return json.load(open(path, encoding="utf-8"))["offsets"][name]["status"]
    except (OSError, KeyError, ValueError):
        return None


def _int(s):
    try:
        return int(s, 0)
    except ValueError:
        return None


def merge(build, sha, derived, prev):
    keys = table_keys()
    notes = header_notes()
    prev_offsets = (prev or {}).get("offsets", {})
    prev_build = (prev or {}).get("build")
    offsets = {}
    for k in keys:
        name = k["name"]
        kind = kind_of(name)
        d = derived.get("offsets", {}).get(name)
        p = prev_offsets.get(name)
        if kind == "constant":
            offsets[name] = {"value": int(k["default"], 0), "status": "constant", "kind": kind,
                             "evidence": "chosen by 0xClient (client/offsets.hpp), not a number read from the game"}
            continue
        if d and d.get("value") is not None:
            status = "derived"
            if p and p.get("value") == d["value"] and p.get("status") == "verified":
                status = "verified"
            offsets[name] = {"value": d["value"], "status": status, "kind": kind, "evidence": d.get("evidence", "")}
            if p and p.get("value") != d["value"]:
                offsets[name]["previous"] = p.get("value")
                if p.get("status") == "verified":
                    offsets[name]["note"] = f"CHANGED from a value verified on {prev_build}: review before merging"
            if status == "verified" and p.get("verified_on"):
                offsets[name]["verified_on"] = p["verified_on"]
        elif p and p.get("value") is not None:
            # Record WHERE the value was last measured, not a growing chain of "carried from" hops.
            if p.get("status") in MEASURED:
                since, since_status = prev_build, p["status"]
            elif p.get("since"):
                since, since_status = p["since"], p.get("since_status", "carried")
            else:
                # an older file: the origin is the last hop of its "carried from" chain
                hops = re.findall(r"carried from ([\w-]+)", str(p.get("evidence", "")))
                since = hops[-1] if hops else prev_build
                since_status = _status_in(since, name) or p.get("status")
            status = p["status"] if p.get("status") in DOUBTS else "carried"
            # A doubt the compiled default's own note records (SUSPECT / REFUTED in offsets.hpp) still
            # applies while the carried value IS that default -- older files lost it on the first carry.
            hn = notes.get(name)
            if hn and hn[0] in DOUBTS and str(p["value"]) == str(_int(k["default"])):
                status = hn[0]
            offsets[name] = {"value": p["value"], "status": status, "kind": kind,
                             "since": since, "since_status": since_status,
                             "evidence": f"not derived on {build}; value last {since_status} on {since}: " + origin_evidence(p)}
        else:
            default = k["default"]
            try:
                val = int(default, 0)
            except ValueError:
                val = None
            offsets[name] = {"value": val, "status": "missing" if val is None else "default", "kind": kind,
                             "evidence": "no derivation and no previous build; compiled default"}
    if not prev:
        # First baseline: the compiled defaults' own notes say what was confirmed against a running
        # game, what is suspect and what was refuted. Carry that knowledge into the file.
        for name, (status, note) in header_notes().items():
            e = offsets.get(name)
            if not e:
                continue
            if e["status"] == "constant":
                continue
            if e["status"] == "default":
                e["status"] = status
                e["evidence"] = "compiled default (client/offsets.hpp): " + note
            elif e["status"] == "derived" and status == "verified":
                e["status"] = "verified"
                e["evidence"] += " | confirmed against a running game on this build (client/offsets.hpp)"
    infer_shifts(offsets, prev_offsets)
    keep_verified(build, sha, offsets)
    return {
        "build": build,
        "sha256": sha,
        "derived": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": derived.get("source", "DeriveOffsets.java"),
        "notes": derived.get("notes", ""),
        "offsets": offsets,
    }


def diff(prev, cur):
    po = (prev or {}).get("offsets", {})
    rows = []
    for name, v in cur["offsets"].items():
        pv = po.get(name, {}).get("value")
        if pv != v["value"]:
            rows.append((name, pv, v["value"], v["status"]))
    return rows


def summary(cur, prev, prev_path, changes):
    """The reviewer's page: what was measured, what was not and why that matters, what moved."""
    o = cur["offsets"]
    counts = {}
    for v in o.values():
        counts[v["status"]] = counts.get(v["status"], 0) + 1
    lines = [f"## Offsets for client-{cur['build']}", "",
             f"`osclient.exe` sha256 `{cur['sha256']}`", "",
             "| status | count |", "|---|---:|"]
    for s in ("verified", "derived", "shifted", "carried", "suspect", "refuted", "default", "missing", "constant"):
        if counts.get(s):
            lines.append(f"| {s} | {counts[s]} |")
    po = (prev or {}).get("offsets", {})
    lost = [n for n, v in o.items() if v["status"] not in MEASURED and po.get(n, {}).get("status") in MEASURED]
    if lost:
        lines += ["", "### REGRESSION: derived on the previous build, not on this one",
                  "A rule in tools/ghidra_scripts/DeriveOffsets.java stopped matching -- the build changed a shape "
                  "it depends on. Read build/ghidra-<build>/derived.json.debug.txt and the notes, fix the rule:", ""]
        lines += [f"- `{n}` (now {o[n]['status']})" for n in lost]
        notes = cur.get("notes")
        if notes:
            lines += ["", f"derivation notes: {notes}"]
    danger = [n for n, v in o.items() if v.get("kind") in ("code", "global") and v["status"] not in MEASURED]
    if danger:
        lines += ["", "### Not applied by the DLL on this build",
                  "Function and global RVAs move on every build, so a value not measured on this one is "
                  "refused rather than called or read. Features that need these stay off until they are derived:", ""]
        lines += [f"- `{n}` ({o[n]['kind']}, {o[n]['status']})" for n in danger]
    review = [n for n, v in o.items() if v.get("note")]
    if review:
        lines += ["", "### Changed from a verified value -- check these first", ""]
        lines += [f"- `{n}`: {o[n].get('previous')} -> {o[n]['value']}" for n in review]
    soft = [n for n, v in o.items() if v.get("kind") == "field" and v["status"] not in MEASURED]
    if soft:
        lines += ["", f"### Struct fields carried or inferred ({len(soft)})",
                  "Applied, labelled, and checked by the DLL's self-check (`OXC_SELFCHECK=1`) on first run:", ""]
        lines += ["- " + ", ".join(f"`{n}`" for n in soft)]
    if prev:
        lines += ["", f"### Diff against {os.path.basename(prev_path)} ({len(changes)} changed)", ""]
        if changes:
            lines += ["| name | old | new | status |", "|---|---|---|---|"]
            for name, pv, nv, st in changes:
                f = lambda x: hex(x) if isinstance(x, int) else str(x)
                lines.append(f"| `{name}` | {f(pv)} | {f(nv)} | {st} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--latest", action="store_true")
    src.add_argument("--build")
    src.add_argument("--exe")
    ap.add_argument("--ghidra", help="Ghidra install folder (the one with support/analyzeHeadless)")
    ap.add_argument("--projects", default=os.path.join(ROOT, "build"), help="where Ghidra projects live")
    ap.add_argument("--no-analyse", action="store_true", help="reuse build/ghidra-<build>/derived.json")
    ap.add_argument("--dry-run", action="store_true", help="print the file instead of writing it")
    ap.add_argument("--summary", help="also write a Markdown review summary here (the CI pull request body)")
    a = ap.parse_args()

    if a.exe:
        exe = os.path.abspath(a.exe)
        build = pe_file_version(exe)
        if not build:
            raise SystemExit(f"{exe}: no FileVersion resource; pass a real osclient.exe")
    else:
        build, exe = fetch(a)
    sha = sha256(exe)
    log(f"build {build}, sha256 {sha}")

    derived_path = os.path.join(a.projects, f"ghidra-{build}", "derived.json")
    if a.no_analyse and os.path.exists(derived_path):
        derived = json.load(open(derived_path, encoding="utf-8"))
    else:
        headless = find_ghidra(a.ghidra)
        if not headless:
            raise SystemExit("Ghidra not found: pass --ghidra <folder> or set GHIDRA_HOME")
        derived = analyse(exe, build, headless, a.projects)
    if derived.get("sha256") and derived["sha256"] != sha:
        raise SystemExit(f"derived.json is for sha256 {derived['sha256']}, not this exe")

    prev_path, prev = previous_file(build)
    cur = merge(build, sha, derived, prev)
    changes = diff(prev, cur)
    counts = {}
    for v in cur["offsets"].values():
        counts[v["status"]] = counts.get(v["status"], 0) + 1
    log(f"status: {counts}")
    if prev:
        log(f"changes against {os.path.basename(prev_path)}: {len(changes)}")
        for name, pv, nv, st in changes:
            log(f"  {name:28s} {hex(pv) if isinstance(pv, int) else pv} -> {hex(nv) if isinstance(nv, int) else nv}  [{st}]")
    if a.summary:
        with open(a.summary, "w", encoding="utf-8", newline="\n") as f:
            f.write(summary(cur, prev, prev_path, changes))
    text = json.dumps(cur, indent=2)
    if a.dry_run:
        print(text)
        return
    os.makedirs(OFFSETS_DIR, exist_ok=True)
    out = os.path.join(OFFSETS_DIR, f"client-{build}.json")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text + "\n")
    log(f"wrote {os.path.relpath(out, ROOT)}")
    print(build)


if __name__ == "__main__":
    main()
