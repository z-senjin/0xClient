#!/usr/bin/env python3
"""Bring 0xClient up to the game build Jagex is shipping now -- one command, start to finish.

    python update_client.py              do everything (asks before it pushes)
    python update_client.py --yes        do everything, push without asking
    python update_client.py --no-game    stop after building (no log-in, no verification)
    python update_client.py --no-git     never pull, commit or push
    python update_client.py --build 241-3            a specific build instead of the live one
    python update_client.py --ghidra C:\\path\\to\\ghidra_12.1.4_PUBLIC

What it does, in order (each step says what it is doing, and stops with a plain reason if it cannot):

  1. git pull          picks up offsets the GitHub workflow already derived and you merged
  2. which build?      asks Jagex's CDN for the live build (tools/update/fetch_client.py)
  3. derive            if offsets/client-<build>.json does not exist yet: downloads the build, runs
                       Ghidra and tools/ghidra_scripts/DeriveOffsets.java, merges with the previous
                       build (tools/update/update.py). Stops if the summary reports a REGRESSION --
                       a derivation rule that stopped matching needs a person (or the deob skill).
  4. build             gradlew dist (copies the offsets file next to the DLL)
  5. verify in game    starts the launcher with logging on; you log in somewhere with NPCs around
                       (a bank is ideal) and wait until this script says the self-check is written,
                       then close the game. The DLL's self-check (client/selfcheck.hpp) is folded into
                       the offsets file by tools/update/verify.py: what the running game confirmed
                       becomes "verified", what it contradicted becomes "suspect".
  6. report            what the DLL refused, what failed, whether actions are available
  7. wiki + git        rebuilds the wiki, commits the offsets file and the wiki, and pushes (after
                       asking) so every other copy of the client downloads the new file
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, "build", "dist")
LOG = os.path.join(DIST, "oxc.log")
WIN = os.name == "nt"


# ------------------------------------------------------------------------------------------------
# small helpers
# ------------------------------------------------------------------------------------------------
def say(msg=""):
    print(msg, flush=True)


def step(n, title):
    say()
    say(f"=== {n}. {title} " + "=" * max(0, 60 - len(title)))


def stop(msg, code=1):
    say()
    say("STOPPED: " + msg)
    sys.exit(code)


def run(cmd, check=True, capture=False, env=None):
    """Run a command in the repository root, streaming its output unless captured."""
    say("  > " + " ".join(cmd))
    r = subprocess.run(cmd, cwd=ROOT, env=env, text=True,
                       stdout=subprocess.PIPE if capture else None,
                       stderr=subprocess.STDOUT if capture else None)
    if check and r.returncode != 0:
        if capture and r.stdout:
            say(r.stdout)
        stop(f"`{' '.join(cmd)}` failed (exit {r.returncode}).")
    return r


def ask(question, assume_yes):
    if assume_yes:
        return True
    try:
        return input(f"{question} [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        return False


def gradlew():
    return [os.path.join(ROOT, "gradlew.bat" if WIN else "gradlew")]


def have_git():
    return shutil.which("git") is not None and os.path.isdir(os.path.join(ROOT, ".git"))


def find_ghidra(explicit):
    """The Ghidra install folder (the one with support/analyzeHeadless), or None."""
    cands = []
    if explicit:
        cands.append(explicit)
    if os.environ.get("GHIDRA_HOME"):
        cands.append(os.environ["GHIDRA_HOME"])
    home = os.path.expanduser("~")
    for pat in (os.path.join(home, "Documents", "ghidra", "ghidra_*"), os.path.join(home, "Documents", "ghidra_*"),
                os.path.join(home, "ghidra", "ghidra_*"), os.path.join(home, "ghidra_*"),
                "C:/ghidra/ghidra_*", "C:/ghidra_*", "C:/Program Files/ghidra*", "/opt/ghidra*"):
        cands.extend(sorted(glob.glob(pat), reverse=True))
    for c in cands:
        if any(os.path.exists(os.path.join(c, "support", n)) for n in ("analyzeHeadless.bat", "analyzeHeadless")):
            return c
    return None


def status_counts(path):
    d = json.load(open(path, encoding="utf-8"))
    counts = {}
    for v in d["offsets"].values():
        counts[v["status"]] = counts.get(v["status"], 0) + 1
    return d, counts


# ------------------------------------------------------------------------------------------------
# the steps
# ------------------------------------------------------------------------------------------------
def git_pull(a):
    step(1, "Get the latest from GitHub")
    if a.no_git or not have_git():
        say("  skipped" + (" (--no-git)" if a.no_git else " (no git or not a git checkout)"))
        return
    r = run(["git", "pull", "--ff-only"], check=False, capture=True)
    say("  " + (r.stdout or "").strip().replace("\n", "\n  "))
    if r.returncode != 0:
        say("  (pull did not fast-forward -- carrying on with what is here; sort the branch out later)")


def which_build(a):
    step(2, "Which build is Jagex shipping?")
    if a.build:
        say(f"  using --build {a.build}")
        return a.build
    r = run([sys.executable, os.path.join("tools", "update", "fetch_client.py"), "--version"], check=False, capture=True)
    lines = [l for l in (r.stdout or "").splitlines() if re.fullmatch(r"\d+-\d+", l.strip())]
    if r.returncode != 0 or not lines:
        say((r.stdout or "").strip())
        stop("could not ask Jagex's CDN for the live build. Check your connection, or pass --build <id>.")
    build = lines[-1].strip()
    say(f"  live build: {build}")
    return build


def derive(a, build):
    step(3, f"Offsets for client-{build}")
    path = os.path.join(ROOT, "offsets", f"client-{build}.json")
    summary = os.path.join(ROOT, "build", f"update-summary-{build}.md")
    if os.path.exists(path) and not a.rederive:
        _, counts = status_counts(path)
        say(f"  offsets/client-{build}.json already exists ({counts}) -- not re-deriving (--rederive to force)")
        return path
    ghidra = find_ghidra(a.ghidra)
    if not ghidra:
        stop("Ghidra was not found. Pass --ghidra <folder with support\\analyzeHeadless.bat> or set GHIDRA_HOME.")
    say(f"  Ghidra: {ghidra}")
    say("  downloading the build and analysing it -- 10 to 20 minutes the first time, be patient")
    os.makedirs(os.path.dirname(summary), exist_ok=True)
    run([sys.executable, os.path.join("tools", "update", "update.py"), "--build", build,
         "--ghidra", ghidra, "--summary", summary])
    if not os.path.exists(path):
        stop(f"update.py finished but offsets/client-{build}.json was not written; see its output above.")
    text = open(summary, encoding="utf-8").read() if os.path.exists(summary) else ""
    _, counts = status_counts(path)
    say(f"  written: offsets/client-{build}.json {counts}")
    say(f"  review summary: {os.path.relpath(summary, ROOT)}")
    if "### REGRESSION" in text:
        section = text.split("### REGRESSION", 1)[1].split("\n### ", 1)[0]
        say("\n### REGRESSION" + section)
        stop("a derivation rule that matched the previous build did not match this one (listed above).\n"
             "         The file was written, but those entries are carried, not measured. Fix the rule\n"
             "         (tools/ghidra_scripts/DeriveOffsets.java -- the deob skill walks through it), then run\n"
             "         this again with --rederive. Nothing was committed.")
    return path


def build_dist(a):
    step(4, "Build")
    run(gradlew() + ["dist"])


def verify_in_game(a, build):
    step(5, "Verify against the running game")
    if a.no_game:
        say("  skipped (--no-game). The file is derived but not verified; run again without --no-game when you can.")
        return None
    report = os.path.join(DIST, "offsets", f"selfcheck-{build}.json")
    env = dict(os.environ)
    env["OXC_LOG"] = LOG
    for k in ("OXC_ACTIONPROBE", "OXC_ACTIONTEST", "OXC_SELFCHECK"):
        env.pop(k, None)   # a clean, normal run: the self-check on, the probes off
    try:
        os.remove(LOG)
    except OSError:
        pass
    started = time.time()
    say("  Starting the launcher. In it:")
    say("    1. pick your account and press + client")
    say("    2. log in, and stand somewhere with NPCs around (a bank is ideal)")
    say("    3. wait here until this window says the self-check is written, then close the game AND\n       the 0xClient launcher window (this script carries on when the launcher closes)")
    proc = subprocess.Popen(gradlew() + ["run", "--no-daemon"], cwd=ROOT, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    announced = False
    while proc.poll() is None:
        if not announced and os.path.exists(report) and os.path.getmtime(report) >= started:
            announced = True
            say()
            say("  >>> Self-check written. Close the game and the 0xClient launcher now. <<<")
        time.sleep(2)
    if not (os.path.exists(report) and os.path.getmtime(report) >= started):
        say("  The game closed before the self-check ran (it runs a few seconds after you are in the world).")
        if ask("  Try again?", False):
            return verify_in_game(a, build)
        say("  Skipping verification; the file stays derived-only.")
        return None
    say("  recording what the game confirmed:")
    run([sys.executable, os.path.join("tools", "update", "verify.py"), report])
    return report


def report_findings(build, path, report):
    step(6, "Report")
    d, counts = status_counts(path)
    o = d["offsets"]
    say(f"  client-{build}: {counts}")
    if report:
        r = json.load(open(report, encoding="utf-8"))
        fails = [(k, v.get("detail", "")) for k, v in r.get("offsets", {}).items() if v.get("verdict") == "fail"]
        if fails:
            say("  failed in game (now suspect -- what depends on them may misbehave):")
            for k, why in fails:
                say(f"    {k}: {why}")
        else:
            say("  nothing failed in game")
    if os.path.exists(LOG):
        refused = [l.strip() for l in open(LOG, encoding="utf-8", errors="replace") if "[build]   refused" in l]
        if refused:
            say("  the DLL refused (features needing these stay off):")
            for l in refused:
                say("    " + l.split(":", 1)[-1].strip())
        if any("[actions] armed on the game tick" in l for l in open(LOG, encoding="utf-8", errors="replace")):
            say("  actions: available (armed on the game tick)")
    acts = [n for n in ("ACT_TICK", "ACT_WALK", "ACT_NPC_OP", "ACT_LOC_OP") if o.get(n, {}).get("status") not in ("derived", "verified")]
    if acts:
        say("  actions: OFF on this build -- not derived: " + ", ".join(acts))


def wiki_and_git(a, build, path):
    step(7, "Wiki, commit, push")
    wiki = os.path.join(ROOT, "tools", "wiki", "build_wiki.py")
    if os.path.exists(wiki):
        run([sys.executable, wiki], check=False)
    if a.no_git or not have_git():
        say("  git skipped" + (" (--no-git)" if a.no_git else ""))
        return
    files = [os.path.relpath(path, ROOT)]
    if os.path.exists(os.path.join(ROOT, "wiki", "index.html")):
        files.append(os.path.join("wiki", "index.html"))
    run(["git", "add"] + files)
    staged = run(["git", "diff", "--cached", "--quiet"], check=False)
    if staged.returncode == 0:
        say("  nothing changed -- nothing to commit")
        return
    run(["git", "commit", "-m", f"Offsets for client-{build}", "--"] + files)
    if ask("  Push to GitHub now (every client downloads the new file from there)?", a.yes):
        run(["git", "push"], check=False)
    else:
        say("  not pushed -- run `git push` when ready")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", help="a specific build (e.g. 241-3) instead of the live one")
    ap.add_argument("--ghidra", help="Ghidra install folder (the one with support\\analyzeHeadless.bat)")
    ap.add_argument("--rederive", action="store_true", help="derive again even if the build's file exists")
    ap.add_argument("--no-game", action="store_true", help="do not start the game to verify")
    ap.add_argument("--no-git", action="store_true", help="do not pull, commit or push")
    ap.add_argument("--yes", action="store_true", help="push without asking")
    a = ap.parse_args()

    say("0xClient update -- " + ROOT)
    git_pull(a)
    build = which_build(a)
    path = derive(a, build)
    build_dist(a)
    report = verify_in_game(a, build)
    report_findings(build, path, report)
    wiki_and_git(a, build, path)
    say()
    say(f"Done: client-{build} is ready.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        stop("interrupted.", 130)
