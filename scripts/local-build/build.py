#!/usr/bin/env python3
"""Build the existing ARM64 APK with native Linux SDK/NDK tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
NDK_VERSION = "28.2.13676358"
BUILD_TOOLS_VERSION = "35.0.0"
PIN_NAMES = (
    "APP_NAME", "APP_ARTIFACT_NAME", "APP_ID", "APP_VERSION", "VERSION_CODE",
    "MIN_SDK", "TARGET_SDK", "ABI", "CODEX_ANDROID_VERSION", "CODEX_ANDROID_URL",
    "CODEX_ANDROID_SHA256", "CODEX_UPSTREAM_SOURCE_TAG", "CODEX_TERMUX_SOURCE_COMMIT",
    "CODEX_APP_SERVER_SOURCE_SHA256", "CODEX_CODE_MODE_HOST_SHA256",
    "CODEX_APP_SERVER_ANDROID_SHA256", "CODEX_LICENSE_SHA256", "CODEX_NOTICE_SHA256",
    "CODEX_DEFAULT_HOST_NAME", "CODEX_PACKAGED_HOST_NAME", "CODEX_DEFAULT_HOST_OFFSET",
    "TERMINAL_SHELL_NAME", "ZLIB_RUNTIME_SHA256", "PLATFORM_URL", "PLATFORM_SHA256",
    "R8_VERSION", "R8_URL", "R8_SHA256", "LIBCXX_URL", "LIBCXX_SHA256",
    "ZLIB_URL", "ZLIB_SHA256",
)
SYSTEM_LIBRARIES = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so"}


class BuildError(Exception):
    pass


def run(args, *, cwd=ROOT, env=None, capture=False):
    result = subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True,
                            text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else None


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_hash(path, expected):
    if not Path(path).is_file() or digest(path) != expected:
        raise BuildError("SHA-256 mismatch: " + str(path))


def read_pins(path):
    # Read declarations as data. Never source or execute the Android build script.
    declarations = dict(re.findall(r'^([A-Z][A-Z0-9_]*)="([^"\n]*)"$',
                                   Path(path).read_text(), re.M))
    resolved = {}

    def resolve(name, stack=()):
        if name in resolved:
            return resolved[name]
        if name in stack or name not in declarations:
            raise BuildError("Missing or recursive build pin: " + name)
        value = re.sub(r'\$\{([A-Z][A-Z0-9_]*)\}|\$([A-Z][A-Z0-9_]*)',
                       lambda match: resolve(match[1] or match[2], (*stack, name)),
                       declarations[name])
        if "$" in value or "`" in value or "\x00" in value:
            raise BuildError("Unsupported expression in build pin: " + name)
        if name.endswith("_SHA256") and not re.fullmatch(r"[0-9a-f]{64}", value):
            raise BuildError("Invalid SHA-256 pin: " + name)
        resolved[name] = value
        return value

    pins = {name: resolve(name) for name in PIN_NAMES}
    if pins["ABI"] != "arm64-v8a" or pins["MIN_SDK"] != "29":
        raise BuildError("This local driver supports the existing ARM64/API 29 contract.")
    return pins


def choose_directory(explicit, environment_names, candidates, description):
    if explicit is not None:
        selected = explicit
    else:
        selected = next((Path(os.environ[name]) for name in environment_names
                         if os.environ.get(name)), None)
        if selected is None:
            selected = next((path for path in candidates if path.is_dir()), None)
    if selected is None or not selected.is_dir():
        raise BuildError("Missing " + description + "; see scripts/local-build/README.md.")
    return selected.resolve()


def toolchain(args):
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        raise BuildError("The local builder requires Linux x86_64; it never uses emulation.")
    home = Path.home()
    sdk = choose_directory(args.sdk, ("ANDROID_HOME", "ANDROID_SDK_ROOT"), (
        home / "Android/Sdk", home / ".buildozer/android/platform/android-sdk",
        ROOT / ".cache/android-sdk"), "Android SDK (--sdk)")
    ndk = choose_directory(args.ndk, ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"), (
        sdk / "ndk" / NDK_VERSION, home / ".buildozer/android/platform/android-ndk-r28c",
        ROOT / ".cache/android-ndk-r28c"), "Android NDK r28c (--ndk)")
    properties = ndk / "source.properties"
    if not properties.is_file() or not re.search(
            r"^Pkg.Revision\s*=\s*" + re.escape(NDK_VERSION) + r"\s*$",
            properties.read_text(), re.M):
        raise BuildError("The local build pins NDK r28c (" + NDK_VERSION + ").")
    java_candidate = shutil.which("javac")
    java_candidates = [Path(java_candidate).resolve().parents[1]] if java_candidate else []
    java_home = choose_directory(None, ("AGENTCODI_JAVA_HOME", "JAVA_HOME"),
                                 java_candidates, "JDK 17")
    tools = {name: java_home / "bin" / name for name in ("java", "javac", "jar")}
    tools.update({name: sdk / "build-tools" / BUILD_TOOLS_VERSION / name
                  for name in ("aapt2", "apksigner", "zipalign")})
    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    tools.update(cxx=llvm / "aarch64-linux-android29-clang++", strip=llvm / "llvm-strip")
    missing = [str(path) for path in tools.values() if not os.access(path, os.X_OK)]
    commands = ("bash", "curl", "dpkg-deb", "cmake", "make", "readelf", "zip", "git")
    if not args.skip_tests:
        commands += ("rg", "dpkg-query")
    missing.extend(command for command in commands if shutil.which(command) is None)
    if missing:
        raise BuildError("Missing local tools:\n  " + "\n  ".join(missing))
    version = subprocess.run([str(tools["javac"]), "-version"], capture_output=True,
                             text=True, check=True)
    if not re.match(r"javac 17[.\s]", version.stdout + version.stderr):
        raise BuildError("Select JDK 17 with AGENTCODI_JAVA_HOME or JAVA_HOME.")
    return sdk, ndk, java_home, tools


def download(url, expected, destination):
    destination = Path(destination)
    if destination.is_file() and digest(destination) == expected:
        print("Using verified cache:", destination.name, flush=True)
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Keep previous cache files until a complete, hash-verified replacement exists.
    with tempfile.TemporaryDirectory(prefix=".download-", dir=destination.parent) as work:
        partial = Path(work) / destination.name
        print("Fetching", destination.name, flush=True)
        try:
            run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3", "--retry-delay", "2",
                 "--connect-timeout", "20", "--max-time", "600", "--output", partial, url])
            verify_hash(partial, expected)
        except subprocess.CalledProcessError as error:
            # The old immutable Termux packages can disappear from the rolling pool.
            # Reuse the existing private backup without depending on Actions.
            mirror = os.environ.get("AGENTCODI_INPUTS_REPO", "Mcpasi/agentcodi-build-inputs")
            tag = os.environ.get("AGENTCODI_INPUTS_TAG", "0.7.6-preview.1")
            if shutil.which("gh") is None:
                raise BuildError("Download failed; restore the pinned file in " +
                                 str(destination) + " or install/authenticate gh for the backup.") from error
            partial.unlink(missing_ok=True)
            run(["gh", "release", "download", tag, "--repo", mirror,
                 "--pattern", destination.name, "--dir", work])
            verify_hash(partial, expected)
        partial.replace(destination)
    return destination


def replace_bytes(path, before, after, count):
    if len(before) != len(after):
        raise BuildError("Binary relocation must preserve string length.")
    data = path.read_bytes()
    if data.count(before) != count:
        raise BuildError("Unexpected binary dependency/name count: " + str(path))
    path.write_bytes(data.replace(before, after))


def prepare_runtime(pins, cache, work, java_home):
    archive = cache / "codex" / pins["CODEX_ANDROID_SHA256"] / "package.tgz"
    # Preserve explicit/local archive selection and its hash checks from the build system.
    # The selection mode executes Java only, never the Android runtime.
    explicit = os.environ.get("AGENTCODI_CODEX_ARCHIVE")
    if not explicit:
        download(pins["CODEX_ANDROID_URL"], pins["CODEX_ANDROID_SHA256"], archive)
    env = {**os.environ, "AGENTCODI_JAVA_HOME": str(java_home), "AGENTCODI_CACHE_DIR": str(cache)}
    selected = run([ROOT / "scripts/update-codex-runtime.sh", "--select-build-archive"],
                   env=env, capture=True).strip()
    verify_hash(Path(selected), pins["CODEX_ANDROID_SHA256"])
    runtime = work / "runtime"
    runtime.mkdir()
    with tarfile.open(selected, "r:gz") as package:
        for name in ("bin/codex.bin", "bin/codex-code-mode-host", "LICENSE", "NOTICE", "package.json"):
            member = package.getmember("package/" + name)
            if not member.isfile():
                raise BuildError("Expected regular Community archive member: " + name)
            target = runtime / Path(name).name
            with package.extractfile(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
    for name, pin in (("codex.bin", "CODEX_APP_SERVER_SOURCE_SHA256"),
                      ("codex-code-mode-host", "CODEX_CODE_MODE_HOST_SHA256"),
                      ("LICENSE", "CODEX_LICENSE_SHA256"), ("NOTICE", "CODEX_NOTICE_SHA256")):
        verify_hash(runtime / name, pins[pin])
    binary = bytearray((runtime / "codex.bin").read_bytes())
    offset = int(pins["CODEX_DEFAULT_HOST_OFFSET"])
    old, new = (pins[key].encode() for key in ("CODEX_DEFAULT_HOST_NAME", "CODEX_PACKAGED_HOST_NAME"))
    if len(old) != len(new) or binary[offset:offset + len(old)] != old:
        raise BuildError("Pinned Codex host-name relocation no longer matches.")
    binary[offset:offset + len(old)] = new
    (runtime / "libcodex.so").write_bytes(binary)
    verify_hash(runtime / "libcodex.so", pins["CODEX_APP_SERVER_ANDROID_SHA256"])
    prefix = work / "termux-runtime"
    # Only these two ARM64 packages are data inputs. No Termux program is executed.
    for pin, filename in (("LIBCXX", "libcxx-29-aarch64.deb"), ("ZLIB", "zlib-1.3.2-aarch64.deb")):
        package = download(pins[pin + "_URL"], pins[pin + "_SHA256"], cache / filename)
        run(["dpkg-deb", "-x", package, prefix])
    return runtime, prefix / "data/data/com.termux/files/usr"


def build_arch(work, ndk, env):
    print("Building pinned Arch Terminal runtime with NDK r28c...", flush=True)
    run([sys.executable, "-B", ROOT / "scripts/arch-terminal/verify-source.py"])
    source = work / "arch-terminal"
    lock = json.loads((ROOT / "third_party/arch-terminal.lock.json").read_text())
    # Copy exactly the verified snapshot; never modify the vendored source tree.
    for name in lock["files"]:
        destination = source / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "third_party/arch-terminal" / name, destination)
    run([sys.executable, "-B", ROOT / "scripts/arch-terminal/fetch-sources.py",
         "--destination", source / ".cache/sources"])
    arch_env = {**env, "ANDROID_NDK_HOME": str(ndk)}
    run(["bash", source / "scripts/build-native.sh"], cwd=source, env=arch_env)
    run(["bash", source / "scripts/source-bundle.sh"], cwd=source, env=arch_env)
    return source


def java_argfile(path, files):
    # javac's @file syntax needs quoted paths when the checkout contains spaces.
    path.write_text("\n".join(json.dumps(str(file), ensure_ascii=False) for file in sorted(files)) + "\n")


def compile_java(work, tools, android_jar, resources, runtime, pins):
    generated = work / "generated-java"
    generated.mkdir()
    resources_apk = work / "resources.apk"
    run([sys.executable, "-B", ROOT / "scripts/arch-terminal/prepare-resources.py", resources])
    run([tools["aapt2"], "compile", "--dir", resources, "-o", work / "resources.zip"])
    run([tools["aapt2"], "link", "-o", resources_apk,
         "--manifest", ROOT / "app/src/main/AndroidManifest.xml", "--java", generated,
         "--custom-package", "de.agentcodi.app", "--extra-packages", "de.archterminal:com.termux.view",
         "--min-sdk-version", pins["MIN_SDK"], "--target-sdk-version", pins["TARGET_SDK"],
         "--version-code", pins["VERSION_CODE"], "--version-name", pins["APP_VERSION"],
         "-I", android_jar, work / "resources.zip"])
    source_roots = [ROOT / "app/src/main/java", generated]
    source_roots.extend(sorted((ROOT / "modules").glob("*/src/main/java")))
    arch = ROOT / "third_party/arch-terminal"
    source_roots.extend((arch / "app/src/main/java", arch / "third_party/termux/emulator-src/main/java",
                         arch / "third_party/termux/view-src/main/java"))
    sources = {file for source in source_roots for file in source.rglob("*.java")}
    classes = work / "classes"
    classes.mkdir()
    java_argfile(work / "java-sources.txt", sources)
    annotations = ROOT / "third_party/androidx-annotation/annotation-1.3.0.jar"
    stubs = ROOT / "third_party/android-lambda-stubs/core-lambda-stubs.jar"
    run([tools["javac"], "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
         "-bootclasspath", f"{android_jar}:{stubs}", "-classpath", annotations,
         "-d", classes, "@" + str(work / "java-sources.txt")])
    app_jar = work / "app.jar"
    run([tools["jar"], "cf", app_jar, "-C", classes, "."])
    # Keep build-only package validation out of the APK's application classes.
    metadata = work / "metadata-classes"
    metadata.mkdir()
    run([tools["javac"], "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
         "-classpath", classes, "-d", metadata,
         ROOT / "scripts/java/de/agentcodi/tools/CodexPackageMetadata.java"])
    run([tools["java"], "-cp", f"{metadata}:{classes}", "de.agentcodi.tools.CodexPackageMetadata",
         runtime / "package.json", pins["CODEX_ANDROID_VERSION"], pins["CODEX_UPSTREAM_SOURCE_TAG"]])
    return app_jar, resources_apk


def stage_native(work, tools, prefix, runtime, arch, pins):
    additions = work / "additions"
    native = additions / "lib" / pins["ABI"]
    native.mkdir(parents=True)
    libcxx = prefix / "lib/libc++_shared.so"
    zlib = prefix / "lib/libz.so.1.3.2"
    for library in (libcxx, zlib):
        if not library.is_file() or "AArch64" not in run(["readelf", "-h", library], capture=True):
            raise BuildError("Missing Android ARM64 runtime library: " + str(library))
    shutil.copy2(libcxx, native / "libc++_shared.so")
    shutil.copy2(zlib, native / "libz_1.so")
    replace_bytes(native / "libz_1.so", b"libz.so.1", b"libz_1.so", 1)
    verify_hash(native / "libz_1.so", pins["ZLIB_RUNTIME_SHA256"])
    cpp = ROOT / "modules/native-engine/src/main/cpp"
    base = [tools["cxx"], "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread",
            "-fPIC", "-Wl,-z,max-page-size=16384", "-Wl,--no-undefined",
            "-Wl,--enable-new-dtags,-rpath,$ORIGIN", "-nostdlib++"]
    engine_sources = ("agentcodi_engine.cpp", "app_server_process.cpp", "png_validator.cpp", "sha256.cpp",
                      "workspace_directory_reader.cpp", "workspace_file_reader.cpp",
                      "workspace_import_installer.cpp", "jni_bridge.cpp")
    # Use the NDK's Android headers, including JNI. Link the pinned packaged zlib
    # explicitly so the driver cannot silently select Android's system libz.so.
    run([*base, "-shared", "-fvisibility=hidden", "-I" + str(prefix / "include"),
         "-I" + str(cpp), *(cpp / name for name in engine_sources), libcxx, zlib, "-llog",
         "-Wl,-soname,libagentcodi.so", "-o", native / "libagentcodi.so"])
    replace_bytes(native / "libagentcodi.so", b"libz.so.1", b"libz_1.so", 1)
    for source, name, shared in (
            ("arch_shell_main.cpp", pins["TERMINAL_SHELL_NAME"], False),
            ("arch_proot_main.cpp", "libproot.so", False),
            ("arch_shell_environment.cpp", "libagentcodi-arch-env.so", True)):
        run([*base, *(["-shared"] if shared else ["-fPIE", "-pie"]), cpp / source,
             libcxx, "-ldl", "-o", native / name])
    run([tools["strip"], "--strip-unneeded", *(native / name for name in (
        "libagentcodi.so", pins["TERMINAL_SHELL_NAME"], "libproot.so", "libagentcodi-arch-env.so"))])
    shutil.copy2(runtime / "libcodex.so", native / "libcodex.so")
    shutil.copy2(runtime / "codex-code-mode-host", native / pins["CODEX_PACKAGED_HOST_NAME"])
    upstream_native = arch / "app/build/generated/jniLibs/arm64-v8a"
    for original, name in (("libarchterminal.so", "libarchterminal.so"),
                           ("libproot.so", "libarch-proot.so"),
                           ("libproot-loader.so", "libproot-loader.so")):
        shutil.copy2(upstream_native / original, native / name)
    for file in native.iterdir():
        file.chmod(0o755)
    assets = additions / "assets"
    shutil.copytree(arch / "app/build/generated/assets", assets)
    third_party = assets / "third-party"
    (third_party / "arch-terminal").mkdir(parents=True)
    shutil.copy2(ROOT / "third_party/arch-terminal.lock.json", third_party / "arch-terminal/source-lock.json")
    codex_assets = third_party / "codex"
    codex_assets.mkdir()
    # Copy exactly the legal files specified by the existing APK contract.
    expected = json.loads((ROOT / "scripts/package-edition/apk-contract.json").read_text())
    allowed = {Path(name).name for name in expected["third_party_assets"]
               if name.startswith("assets/third-party/codex/")}
    for name in sorted(allowed - {"LICENSE", "NOTICE"}):
        shutil.copy2(ROOT / "third_party/community-codex" / name, codex_assets / name)
    for name in ("LICENSE", "NOTICE"):
        shutil.copy2(runtime / name, codex_assets / name)
    (third_party / "zlib").mkdir()
    shutil.copy2(prefix / "share/doc/zlib/copyright", third_party / "zlib/ZLIB-LICENSE")
    libcxx_assets = third_party / "libcxx"
    libcxx_assets.mkdir()
    distributor = prefix / "share/doc/libc++/copyright"
    if distributor.is_symlink():
        if os.readlink(distributor) != "../../LICENSES/NCSA.txt":
            raise BuildError("Pinned libc++ distributor license link changed.")
    elif distributor.read_bytes() != (ROOT / "third_party/libcxx/DISTRIBUTOR-LICENSE").read_bytes():
        raise BuildError("Pinned libc++ distributor license changed.")
    for name in ("DISTRIBUTOR-LICENSE", "LLVM-LICENSES"):
        shutil.copy2(ROOT / "third_party/libcxx" / name, libcxx_assets / name)
    return additions, native


def verify_native(native, pins):
    contract = json.loads((ROOT / "scripts/package-edition/apk-contract.json").read_text())
    expected = set(contract["native_files"])
    if {path.name for path in native.iterdir()} != expected:
        raise BuildError("Local native payload differs from the APK contract.")
    for path in sorted(native.iterdir()):
        header = run(["readelf", "-h", path], capture=True)
        if "AArch64" not in header or "ELF64" not in header:
            raise BuildError("Native payload is not ARM64 ELF64: " + path.name)
        segments = run(["readelf", "-lW", path], capture=True)
        loads = [line.split() for line in segments.splitlines() if line.lstrip().startswith("LOAD ")]
        if not loads or any(int(line[-1], 16) != 16384 for line in loads):
            raise BuildError("Native payload is not aligned for 16 KiB pages: " + path.name)
        if path.name in (pins["TERMINAL_SHELL_NAME"], "libcodex.so", pins["CODEX_PACKAGED_HOST_NAME"]):
            if "/system/bin/linker64" not in segments:
                raise BuildError("Unexpected Android ELF interpreter: " + path.name)
        dynamic = run(["readelf", "-dW", path], capture=True)
        needed = set(re.findall(r"Shared library: \[([^]]+)\]", dynamic))
        unresolved = needed - expected - SYSTEM_LIBRARIES
        if unresolved:
            raise BuildError("Unresolved ELF libraries in " + path.name + ": " + ", ".join(sorted(unresolved)))
        if path.name in (pins["TERMINAL_SHELL_NAME"], "libproot.so"):
            paths = re.findall(r"Library runpath: \[([^]]+)\]", dynamic)
            if not any("$ORIGIN" in value.split(":") for value in paths):
                raise BuildError("Adapter cannot resolve adjacent runtime libraries: " + path.name)
        if path.name == "libagentcodi.so":
            if not {"libz_1.so", "libc++_shared.so"}.issubset(needed):
                raise BuildError("Engine does not link the packaged C++/zlib runtime.")
            symbols = run(["readelf", "-Ws", path], capture=True)
            for name in ("nativeSelfTest", "nativeStartAppServer", "nativeOpenWorkspaceFile",
                         "nativeWorkspaceFileMetadata", "nativeReadWorkspaceFile", "nativePositionWorkspaceFile",
                         "nativeVerifyWorkspaceFile", "nativeCloseWorkspaceFile", "nativeListWorkspaceDirectory",
                         "nativeInstallWorkspaceImportNoReplace"):
                if "Java_de_agentcodi_runtime_NativeEngine_" + name not in symbols:
                    raise BuildError("Missing engine JNI symbol: " + name)
    verify_hash(native / "libcodex.so", pins["CODEX_APP_SERVER_ANDROID_SHA256"])
    verify_hash(native / pins["CODEX_PACKAGED_HOST_NAME"], pins["CODEX_CODE_MODE_HOST_SHA256"])
    verify_hash(native / "libz_1.so", pins["ZLIB_RUNTIME_SHA256"])


def private_file(name):
    value = os.environ.get(name, "")
    path = Path(value)
    if not value or not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise BuildError(name + " must be an absolute regular, non-symlink file outside the checkout.")
    path = path.resolve()
    metadata = path.stat()
    if path.is_relative_to(ROOT) or metadata.st_size == 0 or stat.S_IMODE(metadata.st_mode) & 0o077 or metadata.st_nlink != 1:
        raise BuildError(name + " must be non-empty, private, outside the checkout and not hard-linked.")
    return path


def release_configuration(variant):
    if variant != "release":
        return None
    alias = os.environ.get("AGENTCODI_RELEASE_KEY_ALIAS", "")
    certificate = os.environ.get("AGENTCODI_RELEASE_CERT_SHA256", "").lower()
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", alias) or not re.fullmatch(r"[0-9a-f]{64}", certificate):
        raise BuildError("Release builds require AGENTCODI_RELEASE_KEY_ALIAS and AGENTCODI_RELEASE_CERT_SHA256.")
    config = {"keystore": private_file("AGENTCODI_RELEASE_KEYSTORE"), "alias": alias, "certificate": certificate}
    mode = os.environ.get("AGENTCODI_RELEASE_PASSWORD_MODE", "file")
    if mode == "file":
        config["store_password"] = private_file("AGENTCODI_RELEASE_STORE_PASSWORD_FILE")
        config["key_password"] = private_file("AGENTCODI_RELEASE_KEY_PASSWORD_FILE")
    elif mode != "prompt" or not sys.stdin.isatty():
        raise BuildError("Release password mode must be file, or prompt in an interactive terminal.")
    return config


def assemble(work, additions, resources_apk, app_jar, r8, android_jar, tools, pins, variant, release):
    dex = work / "dex"
    dex.mkdir()
    run([tools["java"], "-cp", r8, "com.android.tools.r8.D8", "--" + variant,
         "--min-api", pins["MIN_SDK"], "--lib", android_jar,
         "--lib", ROOT / "third_party/androidx-annotation/annotation-1.3.0.jar",
         "--output", dex, app_jar])
    dex_files = sorted(dex.glob("classes*.dex"))
    if len(dex_files) != 1:
        raise BuildError("Expected the same single classes.dex payload as the existing build.")
    shutil.copy2(dex_files[0], additions / "classes.dex")
    unaligned, aligned, signed = (work / name for name in ("unaligned.apk", "aligned.apk", "signed.apk"))
    shutil.copy2(resources_apk, unaligned)
    run(["zip", "-q", "-9", "-r", unaligned, "classes.dex", "lib", "assets"], cwd=additions)
    run([tools["zipalign"], "-f", "-p", "4", unaligned, aligned])
    debug_certificate = run([sys.executable, "-B", ROOT / "scripts/sign-debug-apk.py",
                             "--certificate-sha256"], capture=True).strip()
    if release is None:
        run([sys.executable, "-B", ROOT / "scripts/sign-debug-apk.py", "--unsigned", aligned,
             "--output", signed, "--min-sdk", pins["MIN_SDK"], "--apksigner", tools["apksigner"]])
        expected_certificate = debug_certificate
    else:
        if release["certificate"] == debug_certificate:
            raise BuildError("Release APK must not use the public development signer.")
        command = [tools["apksigner"], "sign", "--min-sdk-version", pins["MIN_SDK"],
                   "--ks", release["keystore"], "--ks-key-alias", release["alias"]]
        if "store_password" in release:
            command += ["--ks-pass", "file:" + str(release["store_password"]),
                        "--key-pass", "file:" + str(release["key_password"])]
        run([*command, "--out", signed, aligned])
        expected_certificate = release["certificate"]
    certificates = run([tools["apksigner"], "verify", "--min-sdk-version", pins["MIN_SDK"],
                        "--verbose", "--print-certs", signed], capture=True)
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certificates, re.M)
    if [value.lower() for value in fingerprints] != [expected_certificate]:
        raise BuildError("APK signer differs from the expected identity.")
    if release and "android debug" in certificates.lower():
        raise BuildError("Release APK must not use an Android debug certificate.")
    run([tools["zipalign"], "-c", "-p", "4", signed])
    badging = run([tools["aapt2"], "dump", "badging", signed], capture=True)
    for text in ("package: name='" + pins["APP_ID"] + "'", "versionCode='" + pins["VERSION_CODE"] + "'",
                 "versionName='" + pins["APP_VERSION"] + "'", "targetSdkVersion:'" + pins["TARGET_SDK"] + "'",
                 "application-label:'" + pins["APP_NAME"] + "'", "native-code: '" + pins["ABI"] + "'",
                 "launchable-activity: name='de.agentcodi.app.MainActivity'"):
        if text not in badging:
            raise BuildError("APK identity check failed: " + text)
    if not re.search(r"^(?:minSdkVersion|sdkVersion):'" + pins["MIN_SDK"] + "'", badging, re.M):
        raise BuildError("APK minimum SDK differs.")
    if "application-debuggable" in badging:
        raise BuildError("The APK must remain non-debuggable.")
    report = work / "package-apk-contract.json"
    run([sys.executable, "-B", ROOT / "scripts/package-edition/verify-apk-contract.py",
         "--apk", signed, "--staged", additions, "--output", report,
         *(["--release"] if release else [])])
    return signed, report


def bundle_sources(work, arch):
    sources = work / "sources"
    sources.mkdir()
    for file in (arch / "output").glob("*.tar.gz"):
        shutil.copy2(file, sources / file.name)
    # Use current checked-in bytes, and include this new driver before its first commit.
    names = set(run(["git", "ls-files", "-z"], capture=True).split("\0")) - {""}
    names.update(path.relative_to(ROOT).as_posix() for path in (
        ROOT / "scripts/build-local.sh", *sorted((ROOT / "scripts/local-build").glob("*.py")),
        ROOT / "scripts/local-build/README.md"))
    with tarfile.open(sources / "AGENTCODI-Arch-Terminal-source.tar.gz", "w:gz") as archive:
        for name in sorted(names):
            archive.add(ROOT / name, arcname=name, recursive=False)
    (sources / "SOURCE-SHA256SUMS").write_text("".join(
        digest(file) + "  " + file.name + "\n" for file in sorted(sources.glob("*.tar.gz"))))
    return sources


def main(argv=None):
    parser = argparse.ArgumentParser(prog="build-local.sh", description=__doc__, epilog=(
        "No Docker, QEMU, /system shim or Android runtime execution. "
        "Android schemas and runtime smoke tests remain separate CI/device checks. "
        "See scripts/local-build/README.md."))
    parser.add_argument("--sdk", type=Path, help="Android SDK (Build Tools 35.0.0)")
    parser.add_argument("--ndk", type=Path, help="Android NDK r28c (28.2.13676358)")
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--cache-dir", type=Path, default=Path(os.environ.get("AGENTCODI_CACHE_DIR", ROOT / ".cache/android")))
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output/local-apk")
    parser.add_argument("--skip-tests", action="store_true", help="Skip Java host tests and architecture checks")
    parser.add_argument("--check", action="store_true", help="Check tools and configuration without downloading/building")
    args = parser.parse_args(argv)
    if sys.version_info < (3, 12):
        raise BuildError("Python 3.12 or newer is required by the pinned upstream source builder.")
    pins = read_pins(ROOT / "scripts/build-debug-apk.sh")
    sdk, ndk, java_home, tools = toolchain(args)
    release = release_configuration(args.variant)
    cache, output = args.cache_dir.resolve(), args.output_dir.resolve()
    print(f"Local {args.variant} build: {pins['APP_NAME']} {pins['APP_VERSION']} / {pins['ABI']}", flush=True)
    print(f"JDK: {java_home}\nSDK: {sdk}\nNDK: {ndk}\nOutput: {output}", flush=True)
    print("Android ARM64 programs are packaged and inspected, never executed.", flush=True)
    if args.check:
        print("Local build prerequisites are available; no downloads or builds performed.")
        return
    env = {**os.environ, "JAVA_HOME": str(java_home), "AGENTCODI_JAVA_HOME": str(java_home),
           "ANDROID_HOME": str(sdk), "ANDROID_NDK_HOME": str(ndk), "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
           "PYTHONDONTWRITEBYTECODE": "1"}
    env.setdefault("ARCH_BUILD_JOBS", str(min(os.cpu_count() or 1, 4)))
    # SDK shell launchers (notably apksigner) must use the selected JDK as well.
    os.environ.update(env)
    if not args.skip_tests:
        print("Running existing architecture checks and Java host tests...", flush=True)
        run(["bash", ROOT / "scripts/check-architecture.sh"], env=env)
        run(["bash", ROOT / ".github/ci/run-java-tests.sh"], env=env)
    build_root = ROOT / ".build"
    build_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="local-apk.", dir=build_root) as directory:
        work = Path(directory)
        runtime, prefix = prepare_runtime(pins, cache, work, java_home)
        platform_archive = download(pins["PLATFORM_URL"], pins["PLATFORM_SHA256"], cache / "platform-35_r02.zip")
        r8 = download(pins["R8_URL"], pins["R8_SHA256"], cache / ("r8-" + pins["R8_VERSION"] + ".jar"))
        android_jar = work / "android.jar"
        with zipfile.ZipFile(platform_archive) as archive:
            android_jar.write_bytes(archive.read("android-35/android.jar"))
        arch = build_arch(work, ndk, env)
        print("Compiling Android resources and Java sources...", flush=True)
        app_jar, resources_apk = compile_java(work, tools, android_jar, work / "resources", runtime, pins)
        print("Cross-compiling and inspecting ARM64 native payloads...", flush=True)
        additions, native = stage_native(work, tools, prefix, runtime, arch, pins)
        verify_native(native, pins)
        print("Creating, signing and verifying the local APK...", flush=True)
        apk, contract = assemble(work, additions, resources_apk, app_jar, r8, android_jar,
                                 tools, pins, args.variant, release)
        sources = bundle_sources(work, arch)
        output.mkdir(parents=True, exist_ok=True)
        name = f"{pins['APP_ARTIFACT_NAME']}-{pins['APP_VERSION']}-{pins['ABI']}-{args.variant}.apk"
        destination = output / name
        shutil.copy2(apk, destination)
        shutil.copy2(apk, output / f"{pins['APP_ARTIFACT_NAME']}-{args.variant}.apk")
        for file in (destination, output / f"{pins['APP_ARTIFACT_NAME']}-{args.variant}.apk"):
            file.with_suffix(file.suffix + ".sha256").write_text(digest(file) + "  " + file.name + "\n")
        shutil.copy2(contract, output / "package-apk-contract.json")
        shutil.copytree(sources, output / "sources", dirs_exist_ok=True)
        (output / "local-build.json").write_text(json.dumps({
            "apk": name, "sha256": digest(apk), "variant": args.variant, "ndk": NDK_VERSION,
            "build_tools": BUILD_TOOLS_VERSION, "pins": pins,
            "host_tests": "skipped" if args.skip_tests else "architecture and Java host tests passed",
            "target_execution": "not performed; no Android ARM64 binaries were executed",
            "schema_generation": "not performed; retained the pinned Community runtime bytes",
            "device_tests": "not performed",
        }, indent=2) + "\n")
    print("\nBuilt local APK: " + str(destination), flush=True)
    print("Android runtime/schema/device tests were not run by this cross-build.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (BuildError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print("Local build failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
