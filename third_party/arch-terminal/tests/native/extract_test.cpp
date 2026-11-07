#include "extract.hpp"
#include "deny_hardlinks.hpp"
#include <archive.h>
#include <archive_entry.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace fs = std::filesystem;
namespace {
void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
struct Entry {
    const char* name; int type; const char* data; const char* link; int mode = 0755;
    const char* access_acl = nullptr; const char* default_acl = nullptr;
};
void tar(const fs::path& path, std::initializer_list<Entry> entries) {
    archive* out = archive_write_new();
    archive_write_set_format_pax_restricted(out);
    archive_write_add_filter_gzip(out);
    require(archive_write_open_filename(out, path.c_str()) == ARCHIVE_OK, "create test archive");
    for (const auto& value : entries) {
        auto entry = archive_entry_new();
        archive_entry_set_pathname(entry, value.name);
        archive_entry_set_filetype(entry, value.type);
        archive_entry_set_perm(entry, value.mode);
        if (value.access_acl) require(archive_entry_acl_from_text(entry, value.access_acl, ARCHIVE_ENTRY_ACL_TYPE_ACCESS) == ARCHIVE_OK, "test access ACL");
        if (value.default_acl) require(archive_entry_acl_from_text(entry, value.default_acl, ARCHIVE_ENTRY_ACL_TYPE_DEFAULT) == ARCHIVE_OK, "test default ACL");
        archive_entry_set_perm(entry, value.mode);
        if (value.link) {
            if (value.type == AE_IFLNK) archive_entry_set_symlink(entry, value.link);
            else archive_entry_set_hardlink(entry, value.link);
        }
        if (value.data) archive_entry_set_size(entry, std::char_traits<char>::length(value.data));
        require(archive_write_header(out, entry) == ARCHIVE_OK, "test archive header");
        if (value.data) archive_write_data(out, value.data, std::char_traits<char>::length(value.data));
        archive_entry_free(entry);
    }
    archive_write_close(out);
    archive_write_free(out);
}
void rejects(const fs::path& base, std::initializer_list<Entry> entries) {
    fs::remove_all(base / "root");
    fs::create_directory(base / "root");
    tar(base / "test.tar.gz", entries);
    bool failed = false;
    try { archterminal::extract((base / "test.tar.gz").string(), (base / "root").string()); }
    catch (const std::exception&) { failed = true; }
    require(failed, "unsafe or invalid archive accepted");
}
int mode(const fs::path& path) {
    struct stat status{};
    require(lstat(path.c_str(), &status) == 0, "stat test path");
    return status.st_mode & 07777;
}
void test_directory_modes(const fs::path& base) {
    fs::remove_all(base / "root");
    fs::create_directory(base / "root");
    tar(base / "test.tar.gz", {
        {"usr/bin/program", AE_IFREG, "executable", nullptr},
        {"usr", AE_IFDIR, nullptr, nullptr},
        {"usr/bin", AE_IFDIR, nullptr, nullptr},
        {"private/child/data", AE_IFREG, "private", nullptr},
        {"private", AE_IFDIR, nullptr, nullptr, 0700},
        {"private/child", AE_IFDIR, nullptr, nullptr, 0750},
        {"readonly/data", AE_IFREG, "read only directory", nullptr},
        {"readonly", AE_IFDIR, nullptr, nullptr, 0555},
        {"tmp", AE_IFDIR, nullptr, nullptr, 01777},
        {"shared", AE_IFDIR, nullptr, nullptr, 02775}
    });
    // Android's inherited umask must not turn shared package directories into 0700.
    mode_t previous = umask(0077);
    try { archterminal::extract((base / "test.tar.gz").string(), (base / "root").string()); }
    catch (...) { umask(previous); throw; }
    umask(previous);
    require(mode(base / "root/usr") == 0755, "archive directory mode lost under restrictive umask");
    require(mode(base / "root/usr/bin") == 0755, "late parent directory entry mode lost");
    require(mode(base / "root/private") == 0700, "private directory mode lost");
    require(mode(base / "root/private/child") == 0750, "nested directory mode lost");
    require(mode(base / "root/readonly") == 0555, "read-only directory mode lost");
    require(mode(base / "root/tmp") == 01777, "sticky directory mode lost");
    require(mode(base / "root/shared") == 02775, "setgid directory mode lost");
    require(mode(base / "root/run") == 0755, "implicit runtime directory depends on umask");
    chmod((base / "root/readonly").c_str(), 0755);
}
void test_directory_acl_modes(const fs::path& base) {
    fs::remove_all(base / "root");
    fs::create_directory(base / "root");
    tar(base / "test.tar.gz", {
        {"access", AE_IFDIR, nullptr, nullptr, 02755,
            "user::rwx,group::r-x,other::r-x,group:wheel:r-x:998,mask::r-x"},
        {"default", AE_IFDIR, nullptr, nullptr, 02775, nullptr,
            "user::rwx,group::rwx,group:tss:rwx:972,mask::rwx,other::r-x"}
    });
    archterminal::extract((base / "test.tar.gz").string(), (base / "root").string());
    require(mode(base / "root/access") == 02755, "PAX access ACL lost directory setgid bit");
    require(mode(base / "root/default") == 02775, "PAX default-only ACL replaced directory mode");
}
void mtree(const fs::path& path, const std::string& contents) {
    fs::create_directories(path.parent_path());
    archive* out = archive_write_new();
    archive_write_set_format_raw(out);
    archive_write_add_filter_gzip(out);
    require(archive_write_open_filename(out, path.c_str()) == ARCHIVE_OK, "create package mtree");
    archive_entry* entry = archive_entry_new();
    archive_entry_set_pathname(entry, "mtree");
    archive_entry_set_filetype(entry, AE_IFREG);
    archive_entry_set_size(entry, contents.size());
    require(archive_write_header(out, entry) == ARCHIVE_OK, "write package mtree header");
    require(archive_write_data(out, contents.data(), contents.size()) == static_cast<la_ssize_t>(contents.size()), "write package mtree");
    archive_entry_free(entry);
    archive_write_close(out);
    archive_write_free(out);
}
void test_directory_repair(const fs::path& base) {
    auto root = base / "repair";
    for (const char* name : {"usr/bin", "tmp", "private", "custom", "conflict", "dev", "space dir"}) {
        fs::create_directories(root / name);
        chmod((root / name).c_str(), 0700);
    }
    chmod((root / "usr").c_str(), 0700);
    chmod((root / "private").c_str(), 0755);
    chmod((root / "custom").c_str(), 0710);
    std::ofstream(root / "usr/bin/program") << "untouched";
    chmod((root / "usr/bin/program").c_str(), 0640);
    fs::create_directories(base / "outside/child");
    chmod((base / "outside/child").c_str(), 0700);
    fs::create_directory_symlink(base / "outside", root / "redirect");
    mtree(root / "var/lib/pacman/local/package-1/mtree", "#mtree\n/set type=dir mode=755\n"
        "./usr\n./usr/bin\n./tmp mode=1777\n./private mode=700\n./custom\n./conflict\n"
        "./redirect/child\n./dev\n./space\\040dir\n./usr/bin/program type=file mode=755\n");
    mtree(root / "var/lib/pacman/local/package-2/mtree", "#mtree\n./conflict type=dir mode=750\n");
    require(archterminal::repair_directory_modes(root.string()) == 5, "wrong legacy directory repair count");
    require(mode(root / "usr") == 0755 && mode(root / "usr/bin") == 0755, "package directory modes not restored");
    require(mode(root / "tmp") == 01777 && mode(root / "private") == 0700, "package special/private modes not restored");
    require(mode(root / "space dir") == 0755, "mtree octal-escaped path not decoded");
    require(mode(root / "custom") == 0710, "unrelated custom directory mode changed");
    require(mode(root / "conflict") == 0700, "conflicting package modes must not be guessed");
    require(mode(root / "dev") == 0700, "runtime mount directory repaired");
    require(mode(base / "outside/child") == 0700, "repair followed a symlink outside the rootfs");
    require(mode(root / "usr/bin/program") == 0640, "directory repair changed a regular file");
    require(archterminal::repair_directory_modes(root.string()) == 0, "directory repair is not idempotent");
    mtree(root / "var/lib/pacman/local/package-3/mtree", "#mtree\n./../outside/child type=dir mode=777\n");
    bool rejected = false;
    try { archterminal::repair_directory_modes(root.string()); }
    catch (const std::exception&) { rejected = true; }
    require(rejected && mode(base / "outside/child") == 0700, "unsafe mtree path accepted");
}
}
int main(int argc, char** argv) {
    bool restricted = argc > 1 && std::string(argv[1]) == "--deny-hardlinks";
    if (restricted) { deny_hardlinks(); --argc; ++argv; }
    if (argc == 3 && std::string(argv[1]) == "--repair-modes") {
        std::cout << "Repaired package directories: " << archterminal::repair_directory_modes(argv[2]) << '\n';
        return 0;
    }
    if (argc == 3) { archterminal::extract(argv[1], argv[2]); return 0; }
    char directory[] = "/tmp/arch-extract-XXXXXX";
    fs::path base(mkdtemp(directory));
    try {
        if (restricted) {
            std::ofstream(base / "link-probe") << "probe";
            errno = 0;
            require(::link((base / "link-probe").c_str(), (base / "forbidden-link").c_str()) == -1 && errno == EACCES,
                "hardlink-denial policy did not reproduce permission denied");
        }
        fs::create_directory(base / "root");
        tar(base / "test.tar.gz", {{"usr/bin/bash", AE_IFREG, "ELF test", nullptr},
            {"bin", AE_IFLNK, nullptr, "usr/bin"}, {"usr/bin/bash-hard", AE_IFREG, nullptr, "usr/bin/bash"},
            {"usr/bin/forward-hard", AE_IFREG, nullptr, "usr/bin/later-hard"}, {"usr/bin/later-hard", AE_IFREG, nullptr, "usr/bin/bash-hard"},
            {"absolute", AE_IFLNK, nullptr, "/usr/bin/bash"}, {"dev/test-device", AE_IFCHR, nullptr, nullptr}});
        archterminal::extract((base / "test.tar.gz").string(), (base / "root").string());
        require(fs::is_regular_file(base / "root/bin/bash"), "relative symlink not preserved");
        require(fs::read_symlink(base / "root/absolute") == "/usr/bin/bash", "guest absolute symlink not preserved");
        require(!fs::equivalent(base / "root/usr/bin/bash", base / "root/usr/bin/bash-hard"), "archive hardlink must be an independent file on Android");
        for (const char* name : {"bash-hard", "forward-hard", "later-hard"}) {
            auto copy = base / "root/usr/bin" / name;
            require(fs::is_regular_file(fs::symlink_status(copy)), "hardlink copy must be a regular file");
            std::ifstream stream(copy);
            std::string content((std::istreambuf_iterator<char>(stream)), std::istreambuf_iterator<char>());
            require(content == "ELF test", "hardlink contents not preserved");
            require(access(copy.c_str(), X_OK) == 0, "hardlink execute mode lost");
        }
        fs::rename(base / "root/usr/bin/bash", base / "root/usr/bin/bash-renamed");
        require(fs::is_regular_file(base / "root/usr/bin/bash-hard"), "hardlink copy depends on original path");
        require(!fs::exists(base / "root/dev/test-device"), "device node should be skipped");
        require(access((base / "root/usr/bin/bash-renamed").c_str(), X_OK) == 0, "execute mode lost");
        test_directory_modes(base);
        test_directory_acl_modes(base);
        test_directory_repair(base);
        rejects(base, {{"../escape", AE_IFREG, "bad", nullptr}});
        rejects(base, {{"/tmp/escape", AE_IFREG, "bad", nullptr}});
        rejects(base, {{"escape", AE_IFLNK, nullptr, "/tmp"}, {"escape/payload", AE_IFREG, "bad", nullptr}});
        rejects(base, {{"hard", AE_IFREG, nullptr, "../outside"}});
        rejects(base, {{"hard", AE_IFREG, nullptr, "missing"}});
        rejects(base, {{"cycle-a", AE_IFREG, nullptr, "cycle-b"}, {"cycle-b", AE_IFREG, nullptr, "cycle-a"}});
        rejects(base, {{"same", AE_IFREG, "one", nullptr}, {"same", AE_IFREG, nullptr, "source"}, {"source", AE_IFREG, "two", nullptr}});
        rejects(base, {{"same", AE_IFREG, "one", nullptr}, {"same", AE_IFREG, "two", nullptr}});
        std::atomic_bool cancelled{true};
        bool cancellation_reported = false;
        try { archterminal::extract((base / "test.tar.gz").string(), (base / "cancelled").string(), &cancelled); }
        catch (const std::exception& error) { cancellation_reported = std::string(error.what()).find("abgebrochen") != std::string::npos; }
        require(cancellation_reported, "cancelled extraction continued");
        fs::remove_all(base);
        std::cout << "Extractor: gzip, modes, symlinks, Android-compatible hardlink copies/chains, special files, traversal, duplicate files, cancellation passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; fs::remove_all(base); return 1; }
}
