#!/usr/bin/env python3
"""Record what the DLL's self-check saw in the running game into offsets/client-<build>.json.

    python tools/update/verify.py build/dist/offsets/selfcheck-241-3.json
    python tools/update/verify.py <report> --dry-run      # show what would change

The DLL writes offsets\\selfcheck-<build>.json beside itself the first time you are logged in on a
build (client/selfcheck.hpp). Each offset it could test carries a verdict:

  pass       a CROSS-CHECK held (two independent reads agreed, or a read matched a value we already
             knew). The entry becomes "verified", with the date and what was seen as the evidence.
  plausible  the value had the right shape but a wrong offset could have produced it too. Recorded as
             a note; the status does not change.
  fail       the prediction did not hold. The entry becomes "suspect", with what was read -- so the
             next person, and the DLL's [build] log, treat it as wrong until it is re-derived.
  skip       nothing to test (no NPCs nearby, a function that is not derived). Ignored.

Only a report for the SAME binary (sha256) and the SAME value is applied: a report about a value the
file no longer holds says nothing about the new one.
"""
import argparse
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OFFSETS_DIR = os.path.join(ROOT, "offsets")


def apply(report, data, pass_only=False):
    """Fold the report's verdicts into `data` (an offsets file). Returns a list of change lines."""
    changes = []
    day = report.get("when", "")[:10]
    for name, r in report.get("offsets", {}).items():
        e = data["offsets"].get(name)
        verdict = r.get("verdict")
        if not e or verdict in (None, "skip"):
            continue
        if e.get("value") != r.get("value"):
            changes.append(f"  {name:28s} SKIPPED: report tested {r.get('value')}, file now holds {e.get('value')}")
            continue
        seen = f"self-check {day}: {r.get('detail', '')}"
        if verdict == "pass":
            if e["status"] != "verified":
                changes.append(f"  {name:28s} {e['status']} -> verified")
            e["status"] = "verified"
            e["verified_on"] = day
            e["verification"] = seen
            e.pop("since", None)
            e.pop("since_status", None)
            e.pop("inference", None)
        elif verdict == "fail" and not pass_only:
            if e["status"] != "suspect":
                changes.append(f"  {name:28s} {e['status']} -> suspect ({r.get('detail', '')})")
            e["status"] = "suspect"
            e["verification"] = "FAILED " + seen
        elif verdict == "plausible":
            e["plausible"] = seen
    return changes


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report", help="offsets/selfcheck-<build>.json written by the DLL")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--pass-only", action="store_true",
                    help="record passes (and plausible notes) but leave failures alone -- for when a failure "
                         "may be the check's fault rather than the offset's, and needs a look first")
    a = ap.parse_args()

    report = json.load(open(a.report, encoding="utf-8"))
    build = report.get("build")
    path = os.path.join(OFFSETS_DIR, f"client-{build}.json")
    if not os.path.exists(path):
        raise SystemExit(f"no {os.path.relpath(path, ROOT)}: the report is for a build this repository has no file for")
    data = json.load(open(path, encoding="utf-8"))
    if report.get("sha256") and data.get("sha256") and report["sha256"] != data["sha256"]:
        raise SystemExit(f"the report was taken on osclient.exe {report['sha256']}, the file is for {data['sha256']}")

    changes = apply(report, data, a.pass_only)
    counts = {}
    for v in data["offsets"].values():
        counts[v["status"]] = counts.get(v["status"], 0) + 1
    print(f"client-{build}: {len(changes)} change(s)", file=sys.stderr)
    for c in changes:
        print(c, file=sys.stderr)
    print(f"status now: {counts}", file=sys.stderr)
    if a.dry_run:
        return
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, indent=2) + "\n")
    print(f"wrote {os.path.relpath(path, ROOT)} -- commit it (and rebuild the wiki) so every client gets it", file=sys.stderr)


if __name__ == "__main__":
    main()
