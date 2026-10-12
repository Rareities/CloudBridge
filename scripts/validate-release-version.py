#!/usr/bin/env python3
"""Reject reused tags, unbumped versions and invalid ABI base codes before signing."""
import pathlib
import re
import subprocess
import sys


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def release_notes(tag, root):
    """Extract one version section from the single project changelog."""
    text = (root / "docs/FORK_CHANGELOG.md").read_text()
    heading = re.compile(r"^## " + re.escape(tag.removeprefix("v")) + r"(?: — [^\n]+)?$", re.MULTILINE)
    matches = list(heading.finditer(text))
    if len(matches) != 1:
        raise ValueError("Release changelog must contain exactly one matching version section")
    start = matches[0].start()
    end = re.search(r"^## ", text[matches[0].end():], re.MULTILINE)
    section = text[start:matches[0].end() + end.start() if end else len(text)].strip()
    if not section.split("\n", 1)[-1].strip() or "\n" not in section:
        raise ValueError("Release changelog section is empty")
    return section + "\n"


def validate(tag, root):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+", tag):
        raise ValueError("Use a release candidate tag")
    props = properties((root / "gradle.properties").read_text())
    code = int(props["io.github.rareities.protonbridge.versionCode"])
    if code <= 0 or code % 10 or code + 9 > 2100000000:
        raise ValueError("Base versionCode must be positive, end in 0 and leave ABI room")
    if tag != "v" + props["io.github.rareities.protonbridge.versionName"]:
        raise ValueError("Tag must match the checked-in versionName")
    release_notes(tag, root)
    tags = subprocess.check_output(["git", "tag", "--list"], cwd=root, text=True).splitlines()
    if tag in tags:
        raise ValueError("Published tags are immutable; choose a new version")
    for old in tags:
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", old):
            continue
        result = subprocess.run(["git", "show", old + ":gradle.properties"], cwd=root,
                                text=True, capture_output=True, check=True)
        old_props = properties(result.stdout)
        previous = old_props.get("io.github.rareities.protonbridge.versionCode", old_props.get("de.schuelken.cloudbridge.versionCode"))
        if previous is None:
            legacy = subprocess.check_output(["git", "show", old + ":app/build.gradle"], cwd=root, text=True)
            match = re.search(r"versionCode[^\n]*:\s*(\d+)", legacy)
            if not match:
                match = re.search(r"versionCode\s+(\d+)", legacy)
            if not match:
                raise ValueError("Cannot establish previous versionCode for " + old)
            previous = match.group(1)
        if code <= int(previous) + 9:
            raise ValueError("VersionCode must exceed every published ABI code")
    return code


if __name__ == "__main__":
    try:
        root = pathlib.Path(__file__).resolve().parents[1]
        if sys.argv[1] == "--notes":
            print(release_notes(sys.argv[2], root), end="")
        else:
            print("Verified release base versionCode:", validate(sys.argv[1], root))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
