#include "extract.hpp"
#include <archive.h>
#include <archive_entry.h>
#include <cerrno>
#include <filesystem>
#include <fcntl.h>
#include <memory>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <system_error>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace archterminal {
namespace fs = std::filesystem;
namespace {
struct Fd {
    int value;
    ~Fd() { if (value >= 0) ::close(value); }
};
void check_cancel(const std::atomic_bool* cancelled) {
    if (cancelled && cancelled->load(std::memory_order_relaxed)) throw std::runtime_error("Installation abgebrochen; Download bleibt fortsetzbar.");
}
std::string relative_path(const char* raw) {
    if (!raw || !*raw || *raw == '/') throw std::runtime_error("Archive contains absolute or empty path");
    fs::path value(raw);
    for (const auto& part : value) if (part == "..") throw std::runtime_error("Archive path escapes rootfs");
    auto normalized = value.lexically_normal();
    if (normalized.filename().empty()) normalized = normalized.parent_path();
    return normalized.string();
}
using DirectoryModes = std::map<fs::path, mode_t>;
struct PackageDirectoryModes { DirectoryModes modes; std::set<fs::path> conflicts; };
bool real_directory(const fs::path& root, const fs::path& relative);
PackageDirectoryModes package_directory_modes(const fs::path& root);
void directory(const fs::path& root, const fs::path& relative, DirectoryModes& modes) {
    auto output = root / relative;
    if (fs::create_directory(output)) {
        // Keep extraction writable; apply the archive's final modes after all children and links exist.
        if (chmod(output.c_str(), 0700) < 0)
            throw std::system_error(errno, std::generic_category(), "prepare rootfs directory");
        modes.emplace(relative, 0755);
    } else if (!fs::is_directory(fs::symlink_status(output))) {
        throw std::runtime_error("Archive directory is not a real directory: " + relative.string());
    }
}
void parents(const fs::path& root, const fs::path& relative, DirectoryModes& modes) {
    fs::path current(root);
    for (const auto& component : relative.parent_path()) {
        if (component == "." || component.empty()) continue;
        current /= component;
        auto status = fs::symlink_status(current);
        if (fs::is_symlink(status)) throw std::runtime_error("Archive writes through symbolic link: " + relative.string());
        if (!fs::exists(status)) directory(root, current.lexically_relative(root), modes);
        else if (!fs::is_directory(status)) throw std::runtime_error("Archive parent is not a directory");
    }
}
void write_data(archive* source, int fd, const std::atomic_bool* cancelled) {
    char buffer[64 * 1024];
    while (true) {
        check_cancel(cancelled);
        auto count = archive_read_data(source, buffer, sizeof(buffer));
        if (count == 0) return;
        if (count < 0) throw std::runtime_error(archive_error_string(source));
        la_ssize_t offset = 0;
        while (offset < count) {
            auto written = ::write(fd, buffer + offset, static_cast<size_t>(count - offset));
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) throw std::system_error(errno, std::generic_category(), "write rootfs file");
            offset += written;
        }
    }
}
void copy_hardlink(const fs::path& source, const fs::path& destination, const std::atomic_bool* cancelled) {
    // Android app domains cannot create hardlinks, even inside their own data directory.
    // Materialize archive hardlinks as independent regular files before installing any symlinks.
    Fd input{::open(source.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC)};
    if (input.value < 0) throw std::system_error(errno, std::generic_category(), "open archive hardlink source: " + source.string());
    struct stat status{};
    if (fstat(input.value, &status) < 0) throw std::system_error(errno, std::generic_category(), "stat archive hardlink source: " + source.string());
    if (!S_ISREG(status.st_mode)) throw std::runtime_error("Invalid archive hardlink source: " + source.string());
    Fd output{::open(destination.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600)};
    if (output.value < 0) throw std::system_error(errno, std::generic_category(), "create archive hardlink copy: " + destination.string());
    char buffer[64 * 1024];
    while (true) {
        check_cancel(cancelled);
        auto count = ::read(input.value, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) throw std::system_error(errno, std::generic_category(), "read archive hardlink source: " + source.string());
        if (count == 0) break;
        ssize_t offset = 0;
        while (offset < count) {
            auto written = ::write(output.value, buffer + offset, static_cast<size_t>(count - offset));
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) throw std::system_error(errno, std::generic_category(), "write archive hardlink copy: " + destination.string());
            offset += written;
        }
    }
    if (fchmod(output.value, status.st_mode & 0777) < 0)
        throw std::system_error(errno, std::generic_category(), "chmod archive hardlink copy: " + destination.string());
}
}
void extract(const std::string& archive_path, const std::string& destination, const std::atomic_bool* cancelled) {
    check_cancel(cancelled);
    fs::path root(destination);
    if (!fs::is_directory(fs::symlink_status(root)) || !fs::is_empty(root))
        throw std::runtime_error("Rootfs staging directory must be empty and must be a real directory");
    std::unique_ptr<archive, decltype(&archive_read_free)> input(archive_read_new(), archive_read_free);
    if (!input) throw std::bad_alloc();
    archive_read_support_filter_gzip(input.get());
    archive_read_support_format_tar(input.get());
    if (archive_read_open_filename(input.get(), archive_path.c_str(), 64 * 1024) != ARCHIVE_OK)
        throw std::runtime_error(archive_error_string(input.get()));
    struct Link { fs::path name; std::string target; bool hard; };
    std::vector<Link> links;
    DirectoryModes directories;
    archive_entry* entry;
    int result;
    while ((result = archive_read_next_header(input.get(), &entry)) == ARCHIVE_OK) {
        check_cancel(cancelled);
        auto name = relative_path(archive_entry_pathname(entry));
        if (name == "." || name.empty()) continue;
        // Hardware device nodes cannot be created by an Android app. Real /dev and /proc are bound at launch.
        if (name == "dev" || name.rfind("dev/", 0) == 0 || name == "proc" || name.rfind("proc/", 0) == 0 || name == "sys" || name.rfind("sys/", 0) == 0) continue;
        parents(root, name, directories);
        auto output = root / name;
        if (const char* target = archive_entry_hardlink(entry)) {
            links.push_back({name, relative_path(target), true});
        } else if (const char* target = archive_entry_symlink(entry)) {
            // Absolute links are valid inside the guest. They are installed last, never traversed during extraction.
            links.push_back({name, target, false});
        } else if (archive_entry_filetype(entry) == AE_IFDIR) {
            directory(root, name, directories);
            directories[name] = archive_entry_perm(entry) & 07777;
        } else if (archive_entry_filetype(entry) == AE_IFREG) {
            int fd = ::open(output.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (fd < 0) throw std::system_error(errno, std::generic_category(), "create rootfs file: " + name);
            try {
                write_data(input.get(), fd, cancelled);
                if (fchmod(fd, (archive_entry_perm(entry) & 0777) | S_IRUSR | S_IWUSR) < 0)
                    throw std::system_error(errno, std::generic_category(), "chmod rootfs file");
            } catch (...) { ::close(fd); throw; }
            ::close(fd);
        }
    }
    if (result != ARCHIVE_EOF) throw std::runtime_error(archive_error_string(input.get()));
    // Copy hardlink contents first so a symbolic-link member cannot redirect a source or parent.
    // Resolve forward references/chains without relying on the ordering of tar hardlink entries.
    std::vector<bool> copied(links.size(), false);
    size_t remaining = 0;
    for (const auto& link : links) if (link.hard) ++remaining;
    while (remaining > 0) {
        bool progressed = false;
        for (size_t index = 0; index < links.size(); ++index) {
            const auto& link = links[index];
            if (!link.hard || copied[index]) continue;
            check_cancel(cancelled);
            parents(root, link.name, directories);
            parents(root, link.target, directories);
            std::error_code error;
            auto status = fs::symlink_status(root / link.target, error);
            if (error == std::errc::no_such_file_or_directory) continue;
            if (error) throw fs::filesystem_error("inspect archive hardlink source", root / link.target, error);
            if (!fs::exists(status)) continue;
            if (!fs::is_regular_file(status)) throw std::runtime_error("Invalid archive hardlink source: " + link.target);
            copy_hardlink(root / link.target, root / link.name, cancelled);
            copied[index] = true;
            --remaining;
            progressed = true;
        }
        if (!progressed) throw std::runtime_error("Missing or cyclic archive hardlink source");
    }
    for (const auto& link : links) if (!link.hard) {
        check_cancel(cancelled);
        parents(root, link.name, directories);
        fs::create_symlink(link.target, root / link.name);
    }
    for (const char* name : {"dev", "proc", "sys", "tmp", "run", "root", "etc"}) directory(root, name, directories);
    // Children precede parents so read-only directory entries cannot prevent extraction or chmod.
    for (auto item = directories.rbegin(); item != directories.rend(); ++item) {
        check_cancel(cancelled);
        if (chmod((root / item->first).c_str(), item->second) < 0)
            throw std::system_error(errno, std::generic_category(), "chmod rootfs directory: " + item->first.string());
    }
}

namespace {
bool real_directory(const fs::path& root, const fs::path& relative) {
    fs::path current(root);
    for (const auto& component : relative) {
        current /= component;
        if (!fs::is_directory(fs::symlink_status(current))) return false;
    }
    return true;
}
PackageDirectoryModes package_directory_modes(const fs::path& root) {
    if (!fs::is_directory(fs::symlink_status(root)) || !real_directory(root, "var/lib/pacman/local"))
        throw std::runtime_error("Invalid rootfs package database for directory repair");
    PackageDirectoryModes packages;
    for (const auto& package : fs::directory_iterator(root / "var/lib/pacman/local")) {
        if (!fs::is_directory(package.symlink_status())) continue;
        auto mtree = package.path() / "mtree";
        if (!fs::is_regular_file(fs::symlink_status(mtree))) continue;
        Fd descriptor{::open(mtree.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC)};
        if (descriptor.value < 0) throw std::system_error(errno, std::generic_category(), "open package mtree");
        std::unique_ptr<archive, decltype(&archive_read_free)> input(archive_read_new(), archive_read_free);
        if (!input) throw std::bad_alloc();
        archive_read_support_filter_gzip(input.get());
        archive_read_support_format_mtree(input.get());
        if (archive_read_open_fd(input.get(), descriptor.value, 64 * 1024) != ARCHIVE_OK)
            throw std::runtime_error("Cannot read package mtree: " + mtree.string());
        archive_entry* entry;
        int result;
        while ((result = archive_read_next_header(input.get(), &entry)) == ARCHIVE_OK) {
            if (archive_entry_filetype(entry) != AE_IFDIR) continue;
            auto name = relative_path(archive_entry_pathname(entry));
            if (name == "." || name.empty() || name == "dev" || name.rfind("dev/", 0) == 0
                || name == "proc" || name.rfind("proc/", 0) == 0 || name == "sys" || name.rfind("sys/", 0) == 0) continue;
            mode_t mode = archive_entry_perm(entry) & 07777;
            auto inserted = packages.modes.emplace(name, mode);
            if (!inserted.second && inserted.first->second != mode) packages.conflicts.insert(name);
        }
        if (result != ARCHIVE_EOF) throw std::runtime_error("Invalid package mtree: " + mtree.string());
    }
    return packages;
}
}

size_t repair_directory_modes(const std::string& destination) {
    fs::path root(destination);
    auto packages = package_directory_modes(root);
    size_t repaired = 0;
    for (auto item = packages.modes.rbegin(); item != packages.modes.rend(); ++item) {
        if (packages.conflicts.count(item->first) || !real_directory(root, item->first)) continue;
        auto path = root / item->first;
        struct stat status{};
        if (lstat(path.c_str(), &status) < 0) throw std::system_error(errno, std::generic_category(), "stat package directory");
        mode_t current = status.st_mode & 07777;
        if (current == item->second) continue;
        // Only repair modes produced by the old mkdir/umask combination, once per installation.
        if (current != 0700 && current != 0750 && current != 0755 && current != 0770 && current != 0775 && current != 0777) continue;
        if (chmod(path.c_str(), item->second) < 0)
            throw std::system_error(errno, std::generic_category(), "repair package directory: " + item->first.string());
        ++repaired;
    }
    return repaired;
}
}
