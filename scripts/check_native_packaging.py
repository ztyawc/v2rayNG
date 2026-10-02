#!/usr/bin/env python3
"""Verify native libraries in APKs, including every ABI declared by the app."""
import argparse
import re
from pathlib import Path
from zipfile import ZipFile

REPO = Path(__file__).resolve().parents[1]
REQUIRED = {"libgojni.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"}


def declared_abis():
    gradle = (REPO / "V2rayNG/app/build.gradle.kts").read_text()
    block = re.search(r'include\(\s*"arm64-v8a"(.*?)\)', gradle, re.S)
    if block is None:
        raise ValueError("Cannot find the app's default ABI declarations")
    return set(re.findall(r'"([^\"]+)"', block.group(0)))


def check(apks, expected):
    covered = set()
    for apk in apks:
        with ZipFile(apk) as archive:
            names = set(archive.namelist())
        present = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        if not present or not present <= expected:
            raise ValueError(f"{apk}: unexpected or absent native ABIs: {present}")
        for abi in present:
            missing = REQUIRED - {Path(name).name for name in names if name.startswith(f"lib/{abi}/")}
            if missing:
                raise ValueError(f"{apk}: {abi} missing {sorted(missing)}")
        if "universal" in apk.name and present != expected:
            raise ValueError(f"{apk}: universal APK omits {sorted(expected - present)}")
        covered |= present
        print(f"OK {apk.name}: {', '.join(sorted(present))}")
    if covered != expected:
        raise ValueError(f"APK set omits {sorted(expected - covered)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--abis", help="Semicolon-separated ABI_FILTERS used for this build")
    args = parser.parse_args()
    apks = sorted(args.directory.rglob("*.apk"))
    if not apks:
        parser.error("No APKs found")
    check(apks, set(args.abis.split(";")) if args.abis else declared_abis())
