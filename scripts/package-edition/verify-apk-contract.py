#!/usr/bin/env python3
"""Verify the final Package Edition APK against the actual staged native/legal bytes."""
import argparse
import hashlib
import io
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import tomllib
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
mpl_spec = importlib.util.spec_from_file_location("mpl_sources", REPO / ".github/ci/community-mpl-sources.py")
mpl_sources = importlib.util.module_from_spec(mpl_spec)
mpl_spec.loader.exec_module(mpl_sources)
collector_spec = importlib.util.spec_from_file_location("community_license_collector", REPO / ".github/ci/collect-community-licenses.py")
community_licenses = importlib.util.module_from_spec(collector_spec)
collector_spec.loader.exec_module(community_licenses)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contract():
    return json.loads((HERE / "apk-contract.json").read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def arch_assets(project=REPO):
    source = Path(project) / "third_party/arch-terminal"
    assets = {"assets/rootfs.lock.json": source / "scripts/rootfs.lock.json",
              "assets/third-party/arch-terminal/source-lock.json": Path(project) / "third_party/arch-terminal.lock.json"}
    for path in (source / "third_party").iterdir():
        if path.is_file() and (path.name.endswith("COPYING") or path.suffix == ".txt"):
            assets["assets/licenses/" + path.name] = path
    for name in ("APACHE-2.0.txt", "UPSTREAM-LICENSE.md"):
        assets["assets/licenses/" + name] = source / "third_party/termux" / name
    for name in ("LICENSE", "NOTICE.md"):
        assets["assets/licenses/" + name] = source / name
    return assets


def arch_evidence(apk, project):
    for name, source in arch_assets(project).items():
        require(apk.read(name) == source.read_bytes(), "Arch Terminal asset differs: " + name)
    lock = json.loads(apk.read("assets/third-party/arch-terminal/source-lock.json"))
    require(lock["commit"] == "5c51fdfcec0bf55349b61b6dc3fc49d1923438c3", "Unreviewed Arch Terminal revision")
    return {"version": lock["version"], "commit": lock["commit"],
            "rootfs": json.loads(apk.read("assets/rootfs.lock.json"))}


def community_evidence(archive_data, index_data, provenance_data, project):
    base = project / "third_party/community-codex"
    provenance = json.loads(provenance_data)
    index = json.loads(index_data)
    release = json.loads((project / ".github/ci/community-codex-release.json").read_text())
    require(provenance["format_version"] == index["format_version"] == 1, "Community legal format")
    require(provenance["community_release"] == release, "Community legal release binding")
    require(index["codex_source_commit"] == release["source_commit"], "Community legal source revision")
    lock_data = (base / "Cargo.lock").read_bytes()
    require(digest(lock_data) == index["cargo_lock_sha256"] == provenance["cargo_lock_sha256"],
            "Community legal Cargo.lock binding")
    require(digest(index_data) == provenance["index_sha256"] and
            digest(archive_data) == provenance["archive_sha256"], "Community legal artifact checksum")
    require(index["target"] == "aarch64-linux-android" and
            index["roots"] == ["codex-cli", "codex-code-mode-host"], "Community legal target/roots")
    locked = {(p["name"], p["version"]): p for p in tomllib.loads(lock_data.decode())["package"]}
    cargo = [c for c in index["components"] if c["kind"] in
             ("cargo-normal-and-build-closure", "cargo-locked-dependency-closure")]
    keys = {(c["name"], c["version"]) for c in cargo}
    require(len(keys) == len(cargo) == provenance["cargo_component_count"] and
            {"codex-cli", "codex-code-mode-host"}.issubset({name for name, _ in keys}),
            "Community legal component set")
    for component in cargo:
        package = locked.get((component["name"], component["version"]))
        require(package is not None and component["source"] == package.get("source") and
                component["checksum"] == package.get("checksum"), "Community legal package/source checksum")
    if any(c["kind"] == "cargo-locked-dependency-closure" for c in cargo):
        expected = community_licenses.locked_closure(list(locked.values()), index["roots"])
        require(all(c["kind"] == "cargo-locked-dependency-closure" for c in cargo)
                and keys == {(p["name"], p["version"]) for p in expected},
                "Community legal locked dependency coverage")
    v8 = [c for c in index["components"] if c["kind"] == "v8-source-material"]
    std = [c for c in index["components"] if c["kind"] == "rust-standard-library"]
    require(len(v8) == len(std) == 1 and v8[0]["version"] == "150.4.0" and
            v8[0]["source"] == provenance["v8"]["source_commit"] and
            v8[0]["submodules"] == provenance["v8"]["submodules"] and
            std[0]["version"] == provenance["rust_toolchain"]["version"], "V8/Rust legal source binding")
    require(len(index["components"]) == len(cargo) + 2, "Unexpected Community legal component")
    covered = {}
    missing = []
    for component in index["components"]:
        if not component["files"]:
            missing.append(component["name"] + " " + component["version"])
        for record in component["files"]:
            require(re.fullmatch(r"texts/[0-9a-f]{64}[.]txt", record["path"]) is not None,
                    "Unsafe Community legal path")
            require(0 < record["size"] <= 512 * 1024, "Community legal display limit")
            expected = {"size": record["size"], "sha256": record["sha256"]}
            require(record["path"] not in covered or covered[record["path"]] == expected,
                    "Conflicting Community legal record")
            covered[record["path"]] = expected
    require(sorted(missing) == sorted(gap["name"] + " " + gap["version"] for gap in index["gaps"]),
            "Community legal gaps differ from inventory")
    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == set(covered), "Community legal ZIP file set")
        for name, expected in covered.items():
            data = archive.read(name)
            require(len(data) == expected["size"] and digest(data) == expected["sha256"],
                    "Community legal text checksum")
    return {"cargo_components": len(cargo), "unique_texts": len(covered),
            "provenance": provenance, "release_blockers":
            ["Missing Community dependency legal text: " + value for value in missing]}


def verify(apk_path, staged, project=REPO, release=False):
    spec = contract()
    staged = Path(staged)
    project = Path(project)
    abi_root = "lib/" + spec["abi"] + "/"
    expected_native = {abi_root + name for name in spec["native_files"]}
    expected_assets = set(spec["third_party_assets"])
    resource_sources = {name: project / "app/src/main" / name for name in spec["resources"]}
    report = {"format_version": 2, "contract": spec, "files": {},
              "release_blockers": list(spec["release_blockers"])}
    with zipfile.ZipFile(apk_path) as apk:
        names = [entry.filename for entry in apk.infolist() if not entry.is_dir()]
        require(len(names) == len(set(names)), "Duplicate APK ZIP entry")
        require({name for name in names if name.startswith("lib/")} == expected_native,
                "APK native file/ABI set differs")
        require({name for name in names if name.startswith("assets/")} == expected_assets,
                "APK asset set differs (retired or unexpected payload)")
        for name in sorted(expected_native | expected_assets | set(resource_sources)):
            data = apk.read(name)
            source = resource_sources.get(name, staged / name)
            require(data == source.read_bytes(), "APK bytes differ from staged source: " + name)
            require(bool(data), "Empty payload/legal file: " + name)
            report["files"][name] = {"size": len(data), "sha256": digest(data)}
        community_root = "assets/third-party/codex/"
        for filename in ("DEPENDENCY-LICENSES.zip", "DEPENDENCY-LICENSE-INDEX.json", "DEPENDENCY-PROVENANCE.json",
                         *mpl_sources.FILENAMES):
            require(apk.read(community_root + filename) ==
                    (project / "third_party/community-codex" / filename).read_bytes(),
                    "Community legal checked-in source differs")
        community = community_evidence(
            apk.read(community_root + "DEPENDENCY-LICENSES.zip"),
            apk.read(community_root + "DEPENDENCY-LICENSE-INDEX.json"),
            apk.read(community_root + "DEPENDENCY-PROVENANCE.json"), project)
        runtime = community["provenance"]["community_release"]
        require(digest(apk.read(abi_root + "libcodex.so")) == runtime["apk_relocation"]["sha256"] and
                digest(apk.read(abi_root + "libcodex-codehost.so")) ==
                runtime["native_sha256"]["codex-code-mode-host"], "Community legal native artifact binding")
        report["community"] = community
        report["release_blockers"].extend(community["release_blockers"])
        report["community"]["mpl_sources"] = mpl_sources.evidence(
            *(apk.read(community_root + name) for name in mpl_sources.FILENAMES),
            json.loads(apk.read(community_root + "DEPENDENCY-LICENSE-INDEX.json")),
            (project / "third_party/community-codex/Cargo.lock").read_bytes())
        report["arch_terminal"] = arch_evidence(apk, project)
        # The complete LLVM source texts supplement the verbatim distributor notice.
        require(apk.read("assets/third-party/libcxx/DISTRIBUTOR-LICENSE") ==
                (project / "third_party/libcxx/DISTRIBUTOR-LICENSE").read_bytes(),
                "LLVM distributor legal source differs")
        require(apk.read("assets/third-party/libcxx/LLVM-LICENSES") ==
                (project / "third_party/libcxx/LLVM-LICENSES").read_bytes(),
                "LLVM legal source differs")
    report["apk_sha256"] = digest(Path(apk_path).read_bytes())
    report["license_release_ready"] = not report["release_blockers"]
    report["device_tests"] = "Not performed by hosted CI; physical Android validation remains separate"
    require(not release or report["license_release_ready"], "Final APK license gate: " +
            "; ".join(report["release_blockers"]))
    return report


def check_sources():
    spec = contract()
    builder = (REPO / "scripts/build-debug-apk.sh").read_text()
    require("verify-apk-contract.py" in builder and "--native-files" in builder,
            "APK build must consume the final contract")
    local = (REPO / "scripts/test.sh").read_text()
    host = (REPO / ".github/ci/run-cpp-tests.sh").read_text()
    suites = {path.name for path in (REPO / "tests/cpp").glob("*_test.cpp")}
    for source, label in ((local, "local"), (host, "host")):
        require(set(re.findall(r"\b[a-z_]+_test\.cpp", source)) == suites,
                label + " C++ suite set differs from active tests")
        require("host_shell_fixture.cpp" in source, label + " runner must use the host protocol fixture")
    require(len(spec["native_files"]) == len(set(spec["native_files"])) == 11,
            "Arch native contract must contain exactly eleven files")
    require("libcodex-codehost.so" in spec["native_files"], "Missing standalone code-mode host")
    for path in spec["resources"]:
        require((REPO / "app/src/main" / path).is_file(), "Missing legal resource: " + path)
    mpl_sources.evidence(
        *((REPO / "third_party/community-codex" / name).read_bytes() for name in mpl_sources.FILENAMES),
        json.loads((REPO / "third_party/community-codex/DEPENDENCY-LICENSE-INDEX.json").read_bytes()),
        (REPO / "third_party/community-codex/Cargo.lock").read_bytes())
    print("Final Package Edition source/test contract verified.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-files", action="store_true")
    parser.add_argument("--check-sources", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--staged", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    if args.native_files:
        print("\n".join(contract()["native_files"]))
    elif args.check_sources:
        check_sources()
    elif args.apk and args.staged and args.output:
        result = verify(args.apk, args.staged, release=args.release)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print("Final APK payload and legal bytes verified; release blockers:", len(result["release_blockers"]))
    else:
        parser.error("use --check-sources, --native-files or --apk/--staged/--output")
