#!/usr/bin/env python3
"""Reject reused tags, unbumped versions and invalid ABI base codes before signing."""
import pathlib
import re
import subprocess
import sys


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def validate(tag, root):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+", tag):
        raise ValueError("Use a release candidate tag")
    props = properties((root / "gradle.properties").read_text())
    code = int(props["de.schuelken.cloudbridge.versionCode"])
    if code <= 0 or code % 10 or code + 9 > 2100000000:
        raise ValueError("Base versionCode must be positive, end in 0 and leave ABI room")
    if tag != "v" + props["de.schuelken.cloudbridge.versionName"]:
        raise ValueError("Tag must match the checked-in versionName")
    if not (root / "docs/releases" / (tag + ".md")).is_file():
        raise ValueError("Release changelog is missing")
    tags = subprocess.check_output(["git", "tag", "--list"], cwd=root, text=True).splitlines()
    if tag in tags:
        raise ValueError("Published tags are immutable; choose a new version")
    for old in tags:
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", old):
            continue
        result = subprocess.run(["git", "show", old + ":gradle.properties"], cwd=root,
                                text=True, capture_output=True, check=True)
        previous = properties(result.stdout).get("de.schuelken.cloudbridge.versionCode")
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
        print("Verified release base versionCode:", validate(sys.argv[1], pathlib.Path(__file__).resolve().parents[1]))
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
