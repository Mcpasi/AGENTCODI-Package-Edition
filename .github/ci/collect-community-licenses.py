#!/usr/bin/env python3
"""Collect pinned source license files without authorizing publication."""
import argparse, base64, hashlib, json, os, re, subprocess, tarfile, tomllib, urllib.request, zipfile
from pathlib import Path

def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True)

def sha(data):
    return hashlib.sha256(data).hexdigest()


def locked_closure(packages, roots):
    """Conservative root closure, including locked dev and other-platform edges."""
    by_name = {}
    for package in packages:
        by_name.setdefault(package["name"], []).append(package)
    selected = {}

    def visit(package):
        key = (package["name"], package["version"])
        if key in selected:
            if selected[key] != package:
                raise ValueError("Ambiguous locked package: " + repr(key))
            return
        selected[key] = package
        for dependency in package.get("dependencies", []):
            parts = dependency.split(" ")
            candidates = [p for p in by_name[parts[0]]
                          if (len(parts) < 2 or p["version"] == parts[1])
                          and (len(parts) < 3 or p.get("source") == parts[2].strip("()"))]
            if len(candidates) != 1:
                raise ValueError("Ambiguous locked dependency: " + dependency)
            visit(candidates[0])

    for root in roots:
        candidates = by_name.get(root, [])
        if len(candidates) != 1:
            raise ValueError("Missing or ambiguous root: " + root)
        visit(candidates[0])
    return [selected[key] for key in sorted(selected)]


def source_packages(root):
    """Read package metadata without Cargo, build scripts or Git commands."""
    result = {}
    for manifest in sorted(root.rglob("Cargo.toml")):
        try:
            contents = tomllib.loads(manifest.read_text())
        except tomllib.TOMLDecodeError:
            continue  # Invalid-manifest regression fixtures are not packages.
        package = contents.get("package")
        if not package or not isinstance(package.get("name"), str) or "version" not in package:
            continue
        package = dict(package)
        inherited = [key for key, value in package.items()
                     if isinstance(value, dict) and value.get("workspace") is True]
        if inherited:
            parent = manifest.parent
            while True:
                workspace_file = parent / "Cargo.toml"
                workspace = (tomllib.loads(workspace_file.read_text()).get("workspace")
                             if workspace_file.is_file() else None)
                if workspace is not None:
                    for key in inherited:
                        package[key] = workspace["package"][key]
                    break
                if parent == root or parent.parent == parent:
                    raise ValueError("Missing workspace metadata: " + str(manifest))
                parent = parent.parent
        key = (package["name"], package["version"])
        # Examples can repeat package identities; they are never locked roots.
        result.setdefault(key, (package, manifest.parent))
    return result


def locked_packages(codex, packages, cache, seed):
    """Reuse exact immutable evidence; download only changed source snapshots."""
    cache.mkdir(parents=True, exist_ok=True)
    workspace = source_packages(codex)
    seed_index = json.loads((seed / "DEPENDENCY-LICENSE-INDEX.json").read_text()) if seed else None
    reusable = {(c["name"], c["version"], c.get("source"), c.get("checksum")): c
                for c in seed_index["components"] if c.get("source") and c["files"]} if seed_index else {}
    extracted = {}
    for number, locked in enumerate(packages, 1):
        key = (locked["name"], locked["version"])
        identity = key + (locked.get("source"), locked.get("checksum"))
        if identity in reusable:
            yield locked, None, None, reusable[identity]
            continue
        source = locked.get("source")
        if source is None:
            package, root = workspace[key]
        else:
            if source.startswith("registry+"):
                url = ("https://static.crates.io/crates/" + key[0] + "/" + key[0] + "-" + key[1] + ".crate")
                expected = locked["checksum"]
                prefix = key[0] + "-" + key[1]
            elif source.startswith("git+https://github.com/"):
                repository = source[4:].split("?")[0].removesuffix(".git")
                revision = source.rsplit("#", 1)[1]
                if not re.fullmatch(r"[0-9a-f]{40}", revision):
                    raise ValueError("Invalid Git source revision")
                url = repository.replace("github.com/", "codeload.github.com/") + "/tar.gz/" + revision
                expected = None
                prefix = repository.rsplit("/", 1)[1] + "-" + revision
            else:
                raise ValueError("Unsupported source: " + source)
            cache_key = sha(url.encode())
            archive = cache / (cache_key + ".tar.gz")
            if not archive.exists():
                request = urllib.request.Request(url, headers={"User-Agent": "AGENTCODI-license-audit"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    data = response.read(192 * 1024 * 1024 + 1)
                if len(data) > 192 * 1024 * 1024:
                    raise ValueError("Source archive exceeds limit")
                if expected and sha(data) != expected:
                    raise ValueError("Source checksum: " + repr(key))
                archive.write_bytes(data)
            if expected and sha(archive.read_bytes()) != expected:
                raise ValueError("Cached source checksum: " + repr(key))
            if cache_key not in extracted:
                destination = cache / cache_key
                destination.mkdir(exist_ok=True)
                with tarfile.open(archive) as tar:
                    members = tar.getmembers()
                    if (sum(m.size for m in members) > 768 * 1024 * 1024
                            or any(not m.name.startswith(prefix + "/") and m.name != prefix for m in members)):
                        raise ValueError("Unexpected source archive root or size")
                    tar.extractall(destination, filter="data")
                extracted[cache_key] = source_packages(destination / prefix)
            package, root = extracted[cache_key][key]
        package = dict(package, source=source, manifest_path=str(root / "Cargo.toml"))
        if source and source.startswith("git+"):
            package["source_root"] = str(cache / cache_key / prefix)
        print("Source evidence " + str(number) + "/" + str(len(packages)) + ": " + " ".join(key), flush=True)
        yield locked, package, root, None

def files(root):
    return [p for p in sorted(root.rglob("*")) if p.is_file() and not p.is_symlink()
            and ".git" not in p.parts and "target" not in p.relative_to(root).parts
            and (re.match(r"^(license|licence|copying|copyright|notice)(?:$|[._-])", p.name, re.I)
                 or any(part.lower() in ("licenses", "licences") for part in p.relative_to(root).parts[:-1]))]


ROOT_LICENSE_CACHE = {}

def repository_legal(package, root, output):
    repository = package.get("repository") or package.get("source", "")
    match = re.search(r"github.com/([^/]+)/([^/#?]+)", repository)
    if not match:
        raise ValueError("No public GitHub source repository")
    repo = match[1] + "/" + match[2].removesuffix(".git")
    vcs_file = root / ".cargo_vcs_info.json"
    if vcs_file.is_file():
        revision = json.loads(vcs_file.read_text())["git"]["sha1"]
    elif str(package.get("source", "")).startswith("git+"):
        revision = package["source"].rsplit("#", 1)[1]
    else:
        raise ValueError("Published crate has no exact Git revision")
    if not re.fullmatch("[0-9a-f]{40}", revision):
        raise ValueError("Invalid crate Git revision")
    key = (repo, revision)
    if key not in ROOT_LICENSE_CACHE:
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "AGENTCODI-license-audit"}
        if os.environ.get("GH_TOKEN"):
            headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
        request = urllib.request.Request(
            "https://api.github.com/repos/" + repo + "/contents?ref=" + revision, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response:
            entries = json.load(response)
        texts = {}
        for entry in entries:
            if entry["type"] == "file" and re.match(
                    r"^(license|licence|copying|copyright|notice)(?:$|[._-])", entry["name"], re.I):
                with urllib.request.urlopen(entry["download_url"], timeout=60) as response:
                    texts[entry["name"]] = response.read()
        ROOT_LICENSE_CACHE[key] = texts
    texts = ROOT_LICENSE_CACHE[key]
    if not texts:
        raise ValueError("Exact source revision has no root legal file")
    destination = output / "upstream-notices" / (package["name"] + "-" + package["version"])
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in texts.items():
        (destination / name).write_bytes(data)
    return destination, files(destination), {"repository": repo, "revision": revision,
        "reason": "Original repository-level terms omitted from the published crate"}


def declared_terms(package, root, output, codex):
    expression = package.get("license", "")
    allowed_apache = {"Apache-2.0", "MIT OR Apache-2.0", "Apache-2.0 OR MIT",
                      "Apache-2.0/MIT", "MIT/Apache-2.0",
                      "MIT OR Apache-2.0 OR LGPL-2.1-or-later", "MIT OR Apache-2.0 OR Zlib"}
    selected = "Apache-2.0" if expression in allowed_apache else "MIT" if expression == "MIT" else None
    if selected is None:
        raise ValueError("No reviewed standard-license selection: " + expression)
    destination = output / "declared-notices" / (package["name"] + "-" + package["version"])
    destination.mkdir(parents=True, exist_ok=True)
    original = []
    # Preserve supplied notices, including headers when the archive has no legal file.
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 1024 * 1024:
            continue
        if path.suffix not in (".rs", ".md", ".txt", ".toml"):
            continue
        try:
            lines = path.read_text().splitlines()
        except UnicodeError:
            continue
        mentions = [line for line in lines if re.search("copyright", line, re.I)]
        if mentions:
            original.append(path.relative_to(root).as_posix() + "\n" + "\n".join(mentions))
    notice = ("Package: " + package["name"] + " " + package["version"] +
              "\nLicense declared by the checksum-verified published Cargo.toml: " + expression +
              "\nSelected distribution terms: " + selected +
              "\nAuthors declared by the published Cargo metadata: " +
              (", ".join(package.get("authors") or []) or "(none declared)") +
              "\nRepository declared by the package: " + str(package.get("repository")) +
              "\nThis is an attribution from published metadata; no copyright date is invented.\n")
    (destination / "ATTRIBUTION.txt").write_text(notice)
    if original:
        (destination / "ORIGINAL-COPYRIGHT-NOTICES.txt").write_text("\n\n".join(original) + "\n")
    if selected == "Apache-2.0":
        data = (codex / "LICENSE").read_text()
    else:
        data = (Path(__file__).parent / "license-templates/MIT.txt").read_text()
        # The SPDX placeholder is not an upstream copyright notice. Supplied
        # notices/authors are retained separately, without fabricating dates.
        data = data.replace("Copyright (c) <year> <copyright holders>\n\n", "")
    (destination / ("LICENSE-" + selected + ".txt")).write_text(data)
    for path in root.glob("README*"):
        if path.is_file():
            (destination / path.name).write_bytes(path.read_bytes())
    return destination, sorted(destination.iterdir()), {
        "basis": "License declaration in the checksum-verified published crate",
        "declared_spdx": expression, "selected_spdx": selected,
        "authors": package.get("authors") or [],
        "reason": "Publisher omitted a standalone legal file; supplied authors/notices retained"}

def main():
    parser = argparse.ArgumentParser()
    for option in ("codex", "output"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--v8", type=Path)
    parser.add_argument("--seed-v8", type=Path)
    parser.add_argument("--locked-closure", action="store_true",
                        help="Conservatively collect every root-reachable locked edge without Cargo or Git")
    parser.add_argument("--source-commit")
    parser.add_argument("--seed-cargo", type=Path)
    parser.add_argument("--source-cache", type=Path)
    parser.add_argument("--no-export", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    workspace = args.codex / "codex-rs"
    lock_data = (workspace / "Cargo.lock").read_bytes()
    locked = {(p["name"], p["version"]): p for p in tomllib.loads(lock_data.decode())["package"]}
    if args.locked_closure:
        if not args.source_commit or not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
            raise ValueError("Locked-source collection requires an independently verified --source-commit")
        selected = locked_closure(list(locked.values()), ["codex-cli", "codex-code-mode-host"])
        keys = {(p["name"], p["version"]) for p in selected}
        inputs = locked_packages(args.codex, selected,
                                 args.source_cache or args.output / "source-cache", args.seed_cargo)
    else:
        tree = run("cargo", "+1.95.0", "tree", "--locked", "--target", "aarch64-linux-android",
                   "--edges", "normal,build", "--prefix", "none", "--format", "{p}",
                   "-p", "codex-cli", "-p", "codex-code-mode-host", cwd=workspace)
        keys = set()
        for line in tree.splitlines():
            match = re.match(r"^(\S+) v(\S+)", line)
            if match:
                keys.add(match.groups())
        if not keys:
            raise ValueError("Empty Cargo normal closure")
        metadata = json.loads(run("cargo", "+1.95.0", "metadata", "--locked",
            "--format-version", "1", "--filter-platform", "aarch64-linux-android", cwd=workspace))
        inputs = ((locked[(p["name"], p["version"])], p, Path(p["manifest_path"]).parent, None)
                  for p in metadata["packages"] if (p["name"], p["version"]) in keys)
    if (workspace / "Cargo.lock").read_bytes() != lock_data:
        raise ValueError("Cargo.lock changed")
    components, payload, gaps = [], {}, []
    def add(component, root, candidates):
        records = []
        for p in sorted(set(candidates)):
            data = p.read_bytes()
            if not data:
                continue
            name = "texts/" + sha(data) + ".txt"
            payload[name] = data
            records.append({"source_path": p.relative_to(root).as_posix(), "path": name,
                            "size": len(data), "sha256": sha(data)})
        component["files"] = records
        if not records:
            gaps.append({k: component.get(k) for k in ("name", "version", "license", "repository", "source", "legal_source")})
        components.append(component)
    for package, p, root, reused in inputs:
        if reused:
            with zipfile.ZipFile(args.seed_cargo / "DEPENDENCY-LICENSES.zip") as archive:
                for record in reused["files"]:
                    data = archive.read(record["path"])
                    if sha(data) != record["sha256"] or len(data) != record["size"]:
                        raise ValueError("Reused Cargo legal checksum")
                    payload[record["path"]] = data
            component = dict(reused)
            component["kind"] = "cargo-locked-dependency-closure"
            components.append(component)
            continue
        candidates = files(root)
        license_file = p.get("license_file") or p.get("license-file")
        if license_file:
            candidates.append(root / license_file)
        if p.get("source") is None:
            root = args.codex.resolve()
            candidates.extend([root / "LICENSE", root / "NOTICE"])
        elif not candidates and p.get("source_root"):
            root = Path(p["source_root"])
            candidates = [f for f in files(root) if f.parent == root]
        legal_source = {}
        if not candidates:
            try:
                root, candidates, legal_source = repository_legal(p, root, args.output)
            except Exception as error:
                failure = str(error)
                try:
                    root, candidates, legal_source = declared_terms(p, root, args.output, args.codex)
                    legal_source["repository_lookup_result"] = failure
                except Exception as fallback_error:
                    legal_source = {"unresolved_reason": failure + "; " + str(fallback_error)}
        add({"kind": "cargo-locked-dependency-closure" if args.locked_closure else "cargo-normal-and-build-closure",
             "name": p["name"], "version": p["version"],
             "license": p.get("license"), "authors": p.get("authors"), "repository": p.get("repository"),
             "source": p.get("source"), "checksum": package.get("checksum"),
             "legal_source": legal_source}, root, candidates)
    if {(p["name"], p["version"]) for p in components} != keys:
        raise ValueError("Cargo inventory differs from requested closure")
    if args.seed_v8:
        seed = json.loads((args.seed_v8 / "DEPENDENCY-LICENSE-INDEX.json").read_text())
        selected = [c for c in seed["components"] if c["kind"] in
                    ("v8-source-material", "rust-standard-library")]
        if len(selected) != 2 or seed["gaps"]:
            raise ValueError("Incomplete pinned V8/standard-library seed")
        with zipfile.ZipFile(args.seed_v8 / "DEPENDENCY-LICENSES.zip") as archive:
            for component in selected:
                for record in component["files"]:
                    data = archive.read(record["path"])
                    if sha(data) != record["sha256"] or len(data) != record["size"]:
                        raise ValueError("Seed legal checksum")
                    payload[record["path"]] = data
                components.append(component)
    else:
        v8 = args.v8.resolve()
        submodules = []
        for line in run("git", "submodule", "status", "--recursive", cwd=v8).splitlines():
            if not line.startswith(" "):
                raise ValueError("Uninitialized or changed V8 submodule: " + line)
            commit, path = line.strip().split()[:2]
            submodules.append({"path": path, "commit": commit})
        add({"kind": "v8-source-material", "name": "rusty-v8-and-submodules", "version": "150.4.0",
             "source": run("git", "rev-parse", "HEAD", cwd=v8).strip(),
             "submodules": submodules}, v8, files(v8))
        toolchain = Path(run("rustc", "+1.95.0", "--print", "sysroot").strip())
        notice = toolchain / "share/doc/rust/COPYRIGHT-library.html"
        if not notice.is_file():
            gaps.append({"name": "rust-standard-library", "reason": "COPYRIGHT-library.html unavailable"})
        else:
            add({"kind": "rust-standard-library", "name": "rust-standard-library", "version": "1.95.0"},
                toolchain, [notice])
    index = {"format_version": 1,
        "codex_source_commit": args.source_commit if args.locked_closure else run("git", "rev-parse", "HEAD", cwd=args.codex).strip(),
        "cargo_lock_sha256": sha(lock_data), "target": "aarch64-linux-android",
        "roots": ["codex-cli", "codex-code-mode-host"], "components": components, "gaps": gaps}
    if args.locked_closure:
        index["selection"] = "All Cargo.lock edges reachable from the roots, including dev and other-platform dependencies; a conservative superset of the Android normal/build closure."
    (args.output / "DEPENDENCY-LICENSE-INDEX.json").write_text(json.dumps(index, indent=2) + "\n")
    with zipfile.ZipFile(args.output / "DEPENDENCY-LICENSES.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payload.items()):
            item = zipfile.ZipInfo(name, (2026, 10, 6, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, data)
    summary = {"components": len(components), "unique_texts": len(payload), "gaps": gaps,
               "cargo_lock_sha256": index["cargo_lock_sha256"]}
    (args.output / "collection-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("LICENSE_RESEARCH_SUMMARY=" + json.dumps(summary))
    for name in (() if args.no_export else ("DEPENDENCY-LICENSE-INDEX.json", "DEPENDENCY-LICENSES.zip")):
        encoded = base64.b64encode((args.output / name).read_bytes()).decode()
        for offset in range(0, len(encoded), 60000):
            print("LICENSE_EXPORT " + name + " " + str(offset) + " " + encoded[offset:offset+60000])

if __name__ == "__main__":
    main()
