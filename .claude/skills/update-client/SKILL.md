---
name: update-client
description: Bring 0xClient up to a new osclient.exe build. Fetches the client, runs Ghidra headless, derives offsets/client-BUILD.json automatically, merges with the previous build, verifies against a running game, and publishes. Use when Jagex ships an update, when the DLL refuses a build, or when someone asks to "update the client".
---

# Updating 0xClient to a new game build

Jagex rebuilds `osclient.exe` roughly weekly. Every number the DLL reads out of the game (the
function RVAs and the struct offsets in `client/offsets.hpp`) was measured on one build, so each new
build needs its own `offsets/client-<build>.json`. The DLL loads that file at start-up by the exe's
version resource, so a user never needs a rebuild: they need the file to exist.

This skill is the procedure that produces the file. Most of it is automatic. The part that is not is
saying honestly what the automation could not do.

## 0. What you need

- Python 3.9+ on PATH.
- Ghidra 11+ (`GHIDRA_HOME`, `--ghidra <folder>`, or an install under a usual location) and a
  Java 21 JDK for it. Ghidra is free: https://ghidra-sre.org.
- Network access to `jagex.akamaized.net` (the current build) or `archive.lostcity.rs` (any build).
- For verification only: a Windows machine with the game, and an account you are willing to log in with.

## 1. One command does the derivation

```bash
python tools/update/update.py --latest
```

or, for a specific build (the archive keeps every build, including staging ones):

```bash
python tools/update/update.py --build 241-3
```

or, from a client you already have:

```bash
python tools/update/update.py --exe "C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe"
```

What runs, in order (each step is its own script in `tools/update/` if you need just one):

1. `fetch_client.py` downloads the build into `game/client-<build>/` (CDN pieces are sha256-verified
   against the signed metafile; the archive is used for anything but the current build).
2. Ghidra headless imports the exe into `build/ghidra-<build>/` and analyses it (10-20 minutes the
   first time; the project is reused afterwards).
3. `tools/ghidra_scripts/DeriveOffsets.java` walks from the client's own Lua binding names to every
   number in the offset table and writes `build/ghidra-<build>/derived.json` with the evidence for
   each: which anchor string, which registration, which instruction the displacement was read from.
4. `update.py` merges that with the previous build's file. The status of each entry says what it is:

   | status | meaning |
   |---|---|
   | `derived` | the script read it off this build's code |
   | `verified` | derived, and confirmed against a running game (the DLL's self-check, or by hand) |
   | `shifted` | not derived, but its struct's nearest derived neighbours on BOTH sides moved by the same amount, so it was moved with them (`inference` says which) |
   | `carried` | the script could NOT derive it; the previous build's value is kept and labelled, with `since` naming the build it was last measured on |
   | `suspect` | a recorded doubt: a note in offsets.hpp, or a failed self-check. Kept, never trusted |
   | `refuted` | known wrong. The DLL never applies it |
   | `constant` | a tunable 0xClient chooses (a scan span), not a number read from the game |
   | `default` | no previous build either; the compiled default from offsets.hpp |
   | `missing` | nothing at all; the DLL keeps its compiled value and logs it |

   Every entry also has a `kind`, and the DLL applies a rule per kind (client/offsets_json.hpp):
   `code` and `global` RVAs move on EVERY build, so one that is not `derived`/`verified` is
   **refused** -- set to 0, and every feature that needs it stays off (a stale code RVA is a crash,
   a stale global a garbage read). `field` displacements move rarely, so an unmeasured one is
   applied but listed in the `[build]` log and tested by the self-check. `opcode`s need a
   hook-and-log against a real click.

5. `offsets/client-<build>.json` is written and the diff against the previous build is printed.
   `--summary <file>` also writes the reviewer's page (the CI pull request body): counts per status,
   what the DLL will refuse, values that changed from a verified one, and a **REGRESSION** section
   listing anything the previous build derived that this one could not -- a rule that stopped
   matching. Fix that rule before merging; the debug listing beside derived.json shows the new shape.

Read the diff. A struct offset that moved by a round number (`0x10`, `0x8`) beside neighbours that
moved by the same amount is a field inserted upstream and is expected. A function RVA always moves.
An entry that went `carried` is the thing to look at next.

## 2. What to do about `carried` entries

A `carried` value is last build's number wearing this build's name. Sometimes it is still right
(struct offsets move rarely); sometimes it reads a plausible wrong value. Two ways to settle it:

- **Derive it by hand** with the `deob` skill: the anchor for every offset is written in its comment
  in `client/offsets.hpp`. Decompile the anchor on the new build (`tools/ghidra_scripts/
  DecompileAnchors.java <rva>`), read the displacement, put the value into the JSON with
  `"status": "derived"` and the evidence, and teach `DeriveOffsets.java` the pattern so the next
  build does not need you.
- **Verify it live** (next section). A value that behaves correctly in game is `verified`
  regardless of how it was obtained.

Never promote a `carried` entry to `derived` or `verified` without doing one of those two things.

## 3. Verifying against the running game

**The automatic way.** The DLL tests the loaded offsets itself, once per session, a few seconds after
you are in the world (client/selfcheck.hpp; read-only; `OXC_SELFCHECK=0` turns it off). It writes
`offsets\selfcheck-<build>.json` beside the DLL and the same lines to the `OXC_LOG` file. Each offset
it could test gets a verdict:

- `pass` -- a cross-check held: the registry's player table holds exactly the handles `PLAYER_IDS`
  lists; every skill's base level is the level its xp earns; the combat level is what the formula
  makes of your stats; your render position sits on your scene tile; both view scale pairs read the
  canvas size; the projection puts you inside the canvas; a group root is at (0,0) canvas-sized;
  inventory has 28 slots and equipment 14; NPC names read through the definition pointer.
- `plausible` -- the right shape only (an orientation in 0..2047). Recorded, never promoted.
- `fail` -- the prediction did not hold.

Then, in the repository:

```bash
python tools/update/verify.py build/dist/offsets/selfcheck-<build>.json
```

turns every `pass` into `verified` (with the date and what was seen), every `fail` into `suspect`, and
refuses a report taken on a different binary or for a value the file no longer holds. Commit the
JSON. Log in somewhere with NPCs around (a bank is ideal) so the NPC checks have something to test.

**By hand**, for what the self-check cannot see. The checks the DLL's own log makes possible (set
`OXC_LOG=<file>` before launching):
1. `[build]` line: the file loaded, with applied/unmeasured/refused/missing counts; each refused and
   unmeasured name is listed.
2. Log in and stand still. `[proj]` lines print the local player's scene coords, fine coords, plane
   and the projected screen point: the box must sit on your character. That exercises
   `CLIENT_OBJ_PTR`, `SCENE`, `LOCAL_PLAYER_IDX`, the registry walk, `ENTITY_*`, `WORLD_TO_SCREEN`,
   `CAMERA_*`, `VIEW_*`.
3. Open the skills tab: the panel's Skills debug view must match. That is `SKILL_*`.
4. Walk one tile: `CYCLE` advances, `GAME_STATE` reads 30 while logged in.
5. Open the world map and hover: `WM_*` must put the marker where the cursor is.
6. Right-click an NPC: names come through `ENTITY_NAME_OVERRIDE` / `DEF_NAME`; the popup's widget
   rectangles come through `IFACE_*` / `IFTYPE_*`.
7. Open the bank: `CONTAINER_*` (the Inventory debug view lists the containers it can read).

Anything that behaves gets `"status": "verified"` and a one-line note of what was seen, with the
date. Anything that does not stays `carried`/`derived` and gets a note saying what was wrong.

If a live capture is easier for a particular number, a packet proxy such as RSProx
(https://github.com/blurite/rsprox) shows what the client sends when an action fires, which is the
reliable way to confirm menu opcodes and the `DO_ACTION` argument order.

## 4. Publish

```bash
python tools/wiki/build_wiki.py
git add offsets/client-<build>.json wiki/index.html
git commit -m "Offsets for client-<build>"
git push
```

The DLL downloads `offsets/client-<build>.json` from the repository on first start, so a push IS the
release. No binary needs rebuilding unless `client/offsets.hpp` gained a new name. A copy it cached
earlier is re-checked against the repository once it is six hours old and replaced when the remote one
is for the same binary and newer or measures more -- so a later `verify.py` commit reaches everyone
too. `OXC_OFFSETS_REPO=<owner>/<repo>/<branch>` points a fork's DLL at its own files.

## 5. Adding a brand-new offset

1. Declare it in `client/offsets.hpp` with its HOW FOUND note (anchor, build, verified or not).
2. `python tools/update/gen_offset_table.py` regenerates `client/offsets_table.inc`.
3. Teach `DeriveOffsets.java` how to find it from its anchor so the pipeline carries it forward.
4. Add it to every current `offsets/*.json` (the updater can do this: `--no-analyse` re-merges).
5. Rebuild (`gradlew dist`), rebuild the wiki, commit all of it together.

## What this skill does not do

It does not guess. If a value cannot be derived and cannot be verified, the file says so, the DLL
logs it, and the feature that depends on it refuses rather than reads garbage. That is the design.

It also does not find `DO_ACTION` or the menu opcodes. This engine has no single opcode dispatcher
like the Java client's -- menu entries are objects with their own exec virtuals -- so those need a
hook-and-log against a real click. derived.json's notes name the widget-menu action method every build
as the starting point.
