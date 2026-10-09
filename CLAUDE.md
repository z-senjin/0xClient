# 0xClient — working rules for agents

This file is read by coding agents working in this repository. People should read `README.md`.

## The three rules of this codebase

1. **No packet building.** Actions go through the game's own menu-action function. Never write code
   that constructs or sends a network packet.
2. **Every offset says how it was found.** A number in `client/offsets.hpp` or `offsets/*.json`
   carries the anchor it was derived from and whether it was verified against a running client.
   A number without evidence is a hypothesis and is labelled as one.
3. **Nothing claims to work until it has been seen working.** Say which of these you did: derived
   it from the binary, confirmed it against a running client, or copied it from a note.

## Skills (in `.claude/skills/`)

| Skill | Use it when |
|---|---|
| `wiki` | After any change to Java, C++, offsets, docs, skills or agents: rebuild `wiki/index.html`. Always. |
| `update-client` | Jagex shipped a new `osclient.exe`: fetch it, derive `offsets/client-<build>.json`, verify, publish. |
| `deob` | A single offset broke, or a feature needs memory the client does not expose yet: the manual method. |

## Agents (in `.claude/agents/`)

| Agent | What it does |
|---|---|
| `client-updater` | Runs the whole `update-client` procedure end to end for a given build and reports what it could and could not derive. |

## Layout

- `java/oxclient/` — the client: API, plugins, config, panel, profiles. `java/net/runelite/` is the
  RuneLite API shim; `java/shortestpath/` is the vendored Shortest Path plugin.
- `client/` — the injected DLL (C++). `offsets.hpp` holds the defaults and their derivation notes;
  `offsets_json.hpp` loads `offsets/client-<build>.json` at run time.
- `launcher/` — the ImGui launcher (C++).
- `offsets/` — one JSON per client build, produced by `tools/update/update.py`.
- `tools/update/` — the updater: fetch a client, run Ghidra headless, derive, diff, write.
  `verify.py` folds the DLL's self-check report (`client/selfcheck.hpp`) into a build's file.
- `tools/ghidra_scripts/` — the Ghidra scripts the updater and the deob skill run.
- `wiki/` — the generated wiki. Rebuild it; do not edit it.

## Conventions

- Commit messages describe the change in plain English. No tool attribution lines.
- Documentation lives in the sources (javadoc, header comments, offset notes); the wiki is generated.
- `game/`, `build/` and `tools/decompiled/` are scratch and ignored; never commit a copy of the game.
- Do not reference anything outside this repository in code, comments or docs.
