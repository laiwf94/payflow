#!/usr/bin/env python3
"""Writes a CycloneDX 1.6 SBOM for the payflow boot jar from gradle.lockfile.

The lockfile is the source of truth for coordinates: every library is the exact group, artifact
and version Gradle locked for productionRuntimeClasspath. The jar is read to keep only what is
really shipped (platform BOMs are locked but not packaged) and to record each library's SHA-256.
A jar in BOOT-INF/lib that the lockfile does not explain is an error, not a silent omission.

Usage: sbom.py --lockfile gradle.lockfile --jar build/libs/payflow.jar --version 1.2.3 \
           --output payflow-1.2.3.cdx.json

The output is deterministic: the serial number is derived from the content, and the timestamp
comes from SOURCE_DATE_EPOCH (the release workflow sets it to the tagged commit's time).
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import uuid
import zipfile

CONFIGURATION = "productionRuntimeClasspath"
GROUP = "io.github.laiwf94"
NAME = "payflow"
SOURCE = "https://github.com/laiwf94/payflow"

# Libraries the Spring Boot Gradle plugin adds to the jar itself, outside any locked
# configuration. Their version is Spring Boot's, which the lockfile does pin.
PLUGIN_ADDED = {"spring-boot-jarmode-tools": ("org.springframework.boot", "spring-boot")}

SEMVER = re.compile(r"^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$")


class SbomError(Exception):
    pass


def parse_lockfile(text, configuration=CONFIGURATION):
    """Returns {(group, artifact): version} for the entries locked in `configuration`."""
    locked = {}
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        coordinates, _, configurations = line.partition("=")
        if coordinates == "empty":
            continue
        parts = coordinates.split(":")
        if len(parts) != 3 or not configurations or not all(parts):
            raise SbomError(f"lockfile line {number} is not group:artifact:version=configs: {raw}")
        if configuration in configurations.split(","):
            locked[(parts[0], parts[1])] = parts[2]
    if not locked:
        raise SbomError(f"no entries locked for {configuration}")
    return locked


def jar_libraries(jar_path):
    """Returns {file name: sha256} for every jar under BOOT-INF/lib/."""
    libraries = {}
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.infolist():
            if entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                with jar.open(entry) as f:
                    libraries[entry.filename.rsplit("/", 1)[1]] = hashlib.sha256(f.read()).hexdigest()
    if not libraries:
        raise SbomError(f"{jar_path} has no BOOT-INF/lib: not a Spring Boot jar")
    return libraries


def purl(group, artifact, version):
    return f"pkg:maven/{group}/{artifact}@{version}?type=jar"


def match(locked, libraries):
    """Pairs each packaged jar with its locked coordinates. Returns sorted component tuples."""
    by_file = {}
    for (group, artifact), version in locked.items():
        name = f"{artifact}-{version}.jar"
        if name in by_file:
            raise SbomError(f"{name} is ambiguous: {by_file[name][0]} and {group}")
        by_file[name] = (group, artifact, version)
    components = []
    unexplained = []
    for file, sha256 in sorted(libraries.items()):
        if file in by_file:
            components.append((*by_file[file], sha256))
            continue
        added = next((a for a in PLUGIN_ADDED if file.startswith(a + "-")), None)
        if added:
            group, anchor = PLUGIN_ADDED[added]
            version = file[len(added) + 1 : -len(".jar")]
            if locked.get((group, anchor)) != version:
                raise SbomError(f"{file} does not match the locked {group}:{anchor}")
            components.append((group, added, version, sha256))
            continue
        unexplained.append(file)
    if unexplained:
        raise SbomError("jars in BOOT-INF/lib missing from the lockfile: " + ", ".join(unexplained))
    return sorted(components)


def timestamp(environ):
    epoch = environ.get("SOURCE_DATE_EPOCH")
    moment = (
        datetime.datetime.fromtimestamp(int(epoch), datetime.timezone.utc)
        if epoch
        else datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    )
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def build(lockfile_text, libraries, version, environ):
    if not SEMVER.match(version):
        raise SbomError(f"version {version!r} is not X.Y.Z[-pre]")
    components = [
        {
            "type": "library",
            "bom-ref": purl(g, a, v),
            "group": g,
            "name": a,
            "version": v,
            "purl": purl(g, a, v),
            "hashes": [{"alg": "SHA-256", "content": sha}],
        }
        for g, a, v, sha in match(parse_lockfile(lockfile_text), libraries)
    ]
    root = purl(GROUP, NAME, version)
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "timestamp": timestamp(environ),
            "tools": {"components": [{"type": "application", "name": "payflow scripts/sbom.py"}]},
            "component": {
                "type": "application",
                "bom-ref": root,
                "group": GROUP,
                "name": NAME,
                "version": version,
                "purl": root,
                "licenses": [{"license": {"id": "MIT"}}],
                "externalReferences": [{"type": "vcs", "url": SOURCE}],
            },
        },
        "components": components,
        # The lockfile is flat, so the graph is honest only at the top: the application
        # contains each library, without claiming which library pulled in which.
        "dependencies": [{"ref": root, "dependsOn": [c["bom-ref"] for c in components]}]
        + [{"ref": c["bom-ref"]} for c in components],
    }
    content = json.dumps(bom, sort_keys=True).encode()
    bom["serialNumber"] = "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, hashlib.sha256(content).hexdigest()))
    return bom


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lockfile", required=True)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    try:
        with open(args.lockfile, encoding="utf-8") as f:
            bom = build(f.read(), jar_libraries(args.jar), args.version, os.environ)
    except SbomError as e:
        print(f"sbom: {e}", file=sys.stderr)
        return 1
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(bom, f, indent=2, sort_keys=True)
        f.write("\n")
    print(f"sbom: {len(bom['components'])} libraries -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
