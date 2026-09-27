#!/usr/bin/env python3
"""Write a deterministic manifest for the packaged OSS debug APKs."""

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import zipfile
from pathlib import Path


PROPERTY_GO_VERSION = "de.schuelken.cloudbridge.goVersion"
PROPERTY_RCLONE_REF = "de.schuelken.cloudbridge.rCloneRef"
PROPERTY_NDK_VERSION = "de.schuelken.cloudbridge.ndkVersion"


def read_gradle_properties(path):
    properties = {}
    with path.open(encoding="utf-8-sig") as properties_file:
        for raw_line in properties_file:
            line = raw_line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            key, separator, value = line.partition("=")
            if separator:
                properties[key.strip()] = value.strip()
    return properties


def command_output(command, label):
    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("Could not determine {} version: {}".format(label, result.stdout.strip()))
    return result.stdout.strip()


def parse_go_version(output):
    match = re.search(r"\bgo version go([^\s]+)", output)
    if not match:
        raise ValueError("Unrecognized output from 'go version': {}".format(output))
    return match.group(1)


def require_compatible_go(installed_version, required_version):
    installed = re.fullmatch(r"(\d+)\.(\d+)(?:\.(\d+))?.*", installed_version)
    required = re.fullmatch(r"(\d+)\.(\d+)(?:\.(\d+))?.*", required_version)
    if not installed or not required:
        raise ValueError(
            "Go versions must use a numeric major.minor[.patch] form: installed={}, required={}"
            .format(installed_version, required_version)
        )
    installed_key = (int(installed.group(1)), int(installed.group(2)))
    required_key = (int(required.group(1)), int(required.group(2)))
    if installed_key < required_key:
        raise ValueError(
            "Installed Go {} is older than required Go {}".format(
                installed_version, required_version
            )
        )


def parse_jdk_version(output):
    match = re.search(
        r"\b(?:openjdk|java)\s+(?:version\s+)?[\"']?([0-9]+(?:\.[0-9]+)+(?:[^\s\"']*)?)",
        output,
        re.IGNORECASE,
    )
    if not match:
        raise ValueError("Unrecognized output from 'java -version': {}".format(output))
    version = match.group(1)
    if version.split(".", 1)[0] != "17":
        raise ValueError("Expected JDK 17 for Android CI, found {}".format(version))
    return version


def read_installed_ndk_version(sdk_root, configured_version):
    source_properties = sdk_root / "ndk" / configured_version / "source.properties"
    if not source_properties.is_file():
        raise ValueError("NDK source.properties is missing: {}".format(source_properties))
    with source_properties.open(encoding="utf-8-sig") as source_file:
        for line in source_file:
            key, separator, value = line.partition("=")
            if separator and key.strip() == "Pkg.Revision":
                installed_version = value.strip()
                if installed_version != configured_version:
                    raise ValueError(
                        "Configured NDK {} does not match installed NDK {}".format(
                            configured_version, installed_version
                        )
                    )
                return installed_version
    raise ValueError("NDK Pkg.Revision is missing from {}".format(source_properties))


def read_json_object(path, label):
    try:
        with path.open(encoding="utf-8") as input_file:
            value = json.load(input_file)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Could not read {}: {}".format(label, error)) from error
    if not isinstance(value, dict):
        raise ValueError("{} must contain a JSON object".format(label))
    return value


def read_rclone_provenance(repo_root, expected_ref):
    path = repo_root / "rclone" / "build" / "rclone-provenance.json"
    provenance = read_json_object(path, "rclone source provenance")
    if provenance.get("artifactType") != "cloudbridge-rclone-source-provenance":
        raise ValueError("Unexpected rclone source provenance artifact type")
    if provenance.get("requestedCommit", "").lower() != expected_ref:
        raise ValueError("Rclone provenance requested commit does not match Gradle pin")
    if provenance.get("resolvedCommit", "").lower() != expected_ref:
        raise ValueError("Rclone provenance resolved commit does not match Gradle pin")
    if provenance.get("rclonePinVerifiedAgainstRefreshedRemoteRefs") is not True:
        raise ValueError("Rclone provenance did not verify refreshed remote refs")
    reachable_refs = provenance.get("reachableRemoteRefs")
    reachable_count = provenance.get("reachableRemoteRefCount")
    if (
        not isinstance(reachable_refs, list)
        or not reachable_refs
        or not all(isinstance(value, str) and value for value in reachable_refs)
        or not isinstance(reachable_count, int)
        or reachable_count != len(reachable_refs)
    ):
        raise ValueError("Rclone provenance has no consistent reachable remote ref evidence")
    return {
        "artifactType": provenance["artifactType"],
        "requestedCommit": expected_ref,
        "resolvedCommit": expected_ref,
        "verifiedAgainstRefreshedRemoteRefs": True,
        "reachableRemoteRefCount": reachable_count,
    }


def read_build_toolchain(repo_root):
    wrapper_text = (
        repo_root / "gradle" / "wrapper" / "gradle-wrapper.properties"
    ).read_text(encoding="utf-8")
    wrapper_match = re.search(
        r"distributionUrl=.*gradle-([0-9]+(?:\.[0-9]+)+)-[^/]+\.zip",
        wrapper_text.replace("\\:", ":"),
    )
    if not wrapper_match:
        raise ValueError("Could not determine the Gradle wrapper version")

    root_build_text = (repo_root / "build.gradle").read_text(encoding="utf-8")
    agp_match = re.search(
        r"com\.android\.tools\.build:gradle:([0-9]+(?:\.[0-9]+)+)", root_build_text
    )
    kotlin_match = re.search(
        r"kotlinVersion\s*=\s*['\"]([^'\"]+)['\"]", root_build_text
    )
    app_build_text = (repo_root / "app" / "build.gradle").read_text(encoding="utf-8")
    compile_sdk_match = re.search(r"\bcompileSdk(?:Version)?\s+([0-9]+)", app_build_text)
    if not agp_match or not kotlin_match or not compile_sdk_match:
        raise ValueError("Could not determine the Android build toolchain versions")
    return {
        "gradleVersion": wrapper_match.group(1),
        "androidGradlePluginVersion": agp_match.group(1),
        "kotlinVersion": kotlin_match.group(1),
        "compileSdk": int(compile_sdk_match.group(1)),
    }


def sha256_stream(stream):
    digest = hashlib.sha256()
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            break
        digest.update(chunk)
    return digest.hexdigest()


def collect_apks(apk_dir):
    apk_paths = sorted(
        (path for path in apk_dir.rglob("*.apk") if path.is_file()),
        key=lambda path: path.relative_to(apk_dir).as_posix(),
    )
    if not apk_paths:
        raise ValueError("No APKs were found under {}".format(apk_dir))

    apk_records = []
    libraries = {}
    for apk_path in apk_paths:
        library_records = []
        with apk_path.open("rb") as apk_stream:
            apk_digest_value = sha256_stream(apk_stream)
        with zipfile.ZipFile(apk_path, "r") as archive:
            for entry in archive.infolist():
                path_parts = entry.filename.split("/")
                if (
                    len(path_parts) != 3
                    or path_parts[0] != "lib"
                    or path_parts[2] != "librclone.so"
                    or entry.is_dir()
                ):
                    continue
                abi = path_parts[1]
                with archive.open(entry, "r") as library_stream:
                    library_hash = sha256_stream(library_stream)
                library_records.append({"abi": abi, "sha256": library_hash})
                libraries.setdefault((abi, library_hash), set()).add(
                    apk_path.relative_to(apk_dir).as_posix()
                )

        library_records.sort(key=lambda item: (item["abi"], item["sha256"]))
        apk_records.append(
            {
                "file": apk_path.relative_to(apk_dir).as_posix(),
                "sha256": apk_digest_value,
                "sizeBytes": apk_path.stat().st_size,
                "embeddedRcloneLibraries": library_records,
            }
        )

    if not libraries:
        raise ValueError("No embedded lib/<abi>/librclone.so files were found in the debug APKs")

    library_records = [
        {"abi": abi, "sha256": digest, "embeddedIn": sorted(files)}
        for (abi, digest), files in sorted(libraries.items())
    ]
    return apk_records, library_records


def get_app_source_commit(repo_root):
    status = command_output(
        ["git", "-C", str(repo_root), "status", "--porcelain=v1", "--untracked-files=all"],
        "app source worktree status",
    )
    if status:
        raise ValueError(
            "App source worktree is dirty; commit source changes before generating provenance"
        )
    commit = command_output(
        ["git", "-C", str(repo_root), "rev-parse", "--verify", "HEAD^{commit}"],
        "app source commit",
    ).lower()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Git returned a non-full app source commit: {}".format(commit))
    return commit


def build_manifest(repo_root, apk_dir, sdk_root, go_version, jdk_version):
    properties = read_gradle_properties(repo_root / "gradle.properties")
    required_properties = (PROPERTY_GO_VERSION, PROPERTY_RCLONE_REF, PROPERTY_NDK_VERSION)
    missing = [key for key in required_properties if not properties.get(key)]
    if missing:
        raise ValueError("Missing Gradle properties: {}".format(", ".join(missing)))

    rclone_ref = properties[PROPERTY_RCLONE_REF].lower()
    if not re.fullmatch(r"[0-9a-f]{40}", rclone_ref):
        raise ValueError("Configured rclone source is not a full 40-character commit SHA")

    require_compatible_go(go_version, properties[PROPERTY_GO_VERSION])
    configured_ndk = properties[PROPERTY_NDK_VERSION]
    installed_ndk = read_installed_ndk_version(sdk_root, configured_ndk)
    rclone_provenance = read_rclone_provenance(repo_root, rclone_ref)
    build_toolchain = read_build_toolchain(repo_root)
    apk_records, library_records = collect_apks(apk_dir)
    return {
        "schemaVersion": 1,
        "artifactType": "cloudbridge-oss-debug-build-manifest",
        "buildStatus": "debug-only",
        "releaseAttestation": False,
        "appSourceCommit": get_app_source_commit(repo_root),
        "rcloneSourceCommit": rclone_ref,
        "rclonePinProvenance": rclone_provenance,
        "toolchain": {
            "goVersion": go_version,
            "requiredGoVersion": properties[PROPERTY_GO_VERSION],
            "jdkVersion": jdk_version,
            "ndkVersion": installed_ndk,
            "pythonVersion": platform.python_version(),
            **build_toolchain,
        },
        "apkArtifacts": apk_records,
        "embeddedRcloneLibraries": library_records,
        "scope": (
            "Hashes identify these OSS debug APKs and embedded rclone binaries; "
            "this is not a signed build or release attestation."
        ),
    }


def main(argv=None):
    script_repo_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=script_repo_root)
    parser.add_argument(
        "--apk-dir",
        type=Path,
        default=script_repo_root / "app" / "build" / "outputs" / "apk" / "oss" / "debug",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)

    repo_root = args.repo_root.resolve()
    apk_dir = args.apk_dir.resolve()
    expected_variant_path = tuple(part.lower() for part in apk_dir.parts[-3:])
    if expected_variant_path != ("apk", "oss", "debug"):
        parser.error("--apk-dir must identify the OSS debug APK output directory")
    if not apk_dir.is_dir():
        parser.error("APK output directory does not exist: {}".format(apk_dir))

    output_path = (args.output or apk_dir / "provenance-manifest.json").resolve()
    if output_path.parent != apk_dir:
        parser.error("--output must be written alongside the OSS debug APKs")
    if output_path.suffix.lower() != ".json":
        parser.error("--output must name a JSON file")

    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_value:
        parser.error("ANDROID_HOME or ANDROID_SDK_ROOT must identify the Android SDK")

    try:
        go_version = parse_go_version(command_output(["go", "version"], "Go"))
        jdk_version = parse_jdk_version(command_output(["java", "-version"], "JDK"))
        manifest = build_manifest(repo_root, apk_dir, Path(sdk_value), go_version, jdk_version)
        output_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        print("Could not create debug provenance manifest: {}".format(error), file=sys.stderr)
        return 1

    print("Wrote debug-only APK provenance manifest: {}".format(output_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
