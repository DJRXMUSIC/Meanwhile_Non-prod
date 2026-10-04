#!/usr/bin/env python3
"""Turns Gradle JUnit XML results into a Markdown summary (for $GITHUB_STEP_SUMMARY or a terminal).

Usage: scripts/junit-summary.py "Domain=domain/build/test-results" "App=app/build/test-results" ...
Each failure is listed with its message and the top of its stack trace, so the summary alone is
enough to paste into an AI coding assistant. In GitHub Actions each failure is also annotated.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

STACK_LINES = 25


def collect(path):
    suites = []
    for f in sorted(glob.glob(os.path.join(path, "**", "TEST-*.xml"), recursive=True)):
        try:
            suites.append(ET.parse(f).getroot())
        except ET.ParseError as e:
            print(f"> could not parse `{f}`: {e}\n")
    return suites


def main(args):
    in_actions = os.environ.get("GITHUB_ACTIONS") == "true"
    total_failed = 0
    rows, failures = [], []
    for arg in args:
        label, _, path = arg.partition("=")
        suites = collect(path or label)
        if not suites:
            rows.append(f"| {label} | – | – | – | – | not run |")
            continue
        tests = sum(int(s.get("tests", 0)) for s in suites)
        failed = sum(int(s.get("failures", 0)) + int(s.get("errors", 0)) for s in suites)
        skipped = sum(int(s.get("skipped", 0)) for s in suites)
        secs = sum(float(s.get("time", 0) or 0) for s in suites)
        total_failed += failed
        status = ":white_check_mark: passed" if failed == 0 else f":x: {failed} failed"
        rows.append(f"| {label} | {len(suites)} | {tests} | {skipped} | {secs:.1f}s | {status} |")
        for s in suites:
            for case in s.iter("testcase"):
                for problem in list(case.findall("failure")) + list(case.findall("error")):
                    name = f"{case.get('classname')}.{case.get('name')}"
                    message = (problem.get("message") or "").strip()
                    trace = (problem.text or "").strip().splitlines()[:STACK_LINES]
                    failures.append((label, name, message, trace))
                    if in_actions:
                        one_line = message.replace("\n", " ")[:300]
                        print(f"::error title=Test failed ({label})::{name}: {one_line}", file=sys.stderr)

    out = ["## Tests", "", "| Suite | Classes | Tests | Skipped | Time | Result |", "|---|---|---|---|---|---|"]
    out += rows
    if failures:
        out += ["", f"### Failures ({len(failures)})", ""]
        for label, name, message, trace in failures:
            out += [f"**{label}: `{name}`**", "", "```", message, "", *trace, "```", ""]
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
