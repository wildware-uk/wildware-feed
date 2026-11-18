#!/usr/bin/env python3
"""Fails when a backtick test name in common or iOS tests uses a character Kotlin/Native rejects.

The JVM accepts these names, so only the Mac's iOS build would find them, late and slowly.
Kotlin/Native refuses , . ; [ ] / < > : and \\ in a name ("Name contains illegal characters").
"""
import pathlib
import re
import sys

ILLEGAL = set(",.;[]/<>:\\")
NAME = re.compile(r"fun\s+`([^`]*)`")

problems = []
for path in sorted(pathlib.Path(".").glob("**/src/*/kotlin/**/*.kt")):
    source_set = path.parts[path.parts.index("src") + 1]
    if source_set not in ("commonTest", "iosTest") and not source_set.startswith("ios"):
        continue
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        for name in NAME.findall(line):
            bad = sorted(set(name) & ILLEGAL)
            if bad:
                problems.append(f"{path}:{number}: test name has {' '.join(repr(c) for c in bad)}, which Kotlin/Native rejects: {name!r}")

for problem in problems:
    print(problem)
if problems:
    sys.exit(1)
print("Every common and iOS test name builds on Kotlin/Native.")
