#include "workspace_file_reader.h"

#include <cerrno>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

void expect(bool condition, const std::string& message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

class TemporaryDirectory final {
 public:
  TemporaryDirectory() {
    std::vector<char> pattern;
    const std::string value = "/tmp/agentcodi-workspace-reader-XXXXXX";
    pattern.assign(value.begin(), value.end());
    pattern.push_back('\0');
    char* created = mkdtemp(pattern.data());
    if (created == nullptr) {
      throw std::runtime_error("could not create temporary test directory");
    }
    path_ = created;
  }

  ~TemporaryDirectory() {
    std::error_code ignored;
    std::filesystem::remove_all(path_, ignored);
  }

  const std::filesystem::path& path() const { return path_; }

 private:
  std::filesystem::path path_;
};

void make_directory(const std::filesystem::path& path) {
  if (mkdir(path.c_str(), 0700) != 0) {
    throw std::runtime_error("could not create test directory");
  }
}

void write_file(const std::filesystem::path& path, const std::string& contents) {
  const int descriptor = open(
      path.c_str(),
      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
      0600);
  if (descriptor < 0) {
    throw std::runtime_error("could not create test file");
  }
  std::size_t offset = 0;
  while (offset < contents.size()) {
    const ssize_t count = write(
        descriptor,
        contents.data() + offset,
        contents.size() - offset);
    if (count < 0 && errno == EINTR) {
      continue;
    }
    if (count <= 0) {
      close(descriptor);
      throw std::runtime_error("could not write test file");
    }
    offset += static_cast<std::size_t>(count);
  }
  if (close(descriptor) != 0) {
    throw std::runtime_error("could not close test file");
  }
}

std::string read_all(agentcodi::WorkspaceFileReader* reader) {
  std::string result;
  unsigned char buffer[4];
  while (true) {
    std::string error;
    const ssize_t count = reader->Read(buffer, sizeof(buffer), &error);
    expect(count >= 0, error.empty() ? "reader failed" : error);
    if (count == 0) {
      break;
    }
    result.append(
        reinterpret_cast<const char*>(buffer),
        static_cast<std::size_t>(count));
  }
  return result;
}

void reads_nested_regular_file() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  make_directory(workspace);
  make_directory(workspace / "nested");
  write_file(workspace / "nested" / "payload.bin", "workspace-bytes");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "nested/payload.bin",
          1024,
          &error);
  expect(reader != nullptr, error);
  expect(reader->metadata().size == 15, "reader reported the wrong size");
  expect(reader->Position(10, &error), error);
  unsigned char suffix[5];
  expect(reader->Read(suffix, sizeof(suffix), &error) == 5, error);
  expect(
      std::string(reinterpret_cast<const char*>(suffix), sizeof(suffix)) == "bytes",
      "positioned reader returned the wrong suffix");
  expect(!reader->Position(16, &error), "reader accepted a position beyond EOF");
  expect(reader->Position(0, &error), error);
  expect(read_all(reader.get()) == "workspace-bytes", "reader changed file bytes");
  expect(reader->VerifyUnchanged(&error), error);
}

void rejects_final_symbolic_link() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(private_directory);
  write_file(private_directory / "outside.bin", "outside-bytes");
  expect(
      symlink(
          (private_directory / "outside.bin").c_str(),
          (workspace / "linked.bin").c_str()) == 0,
      "could not create final symbolic link");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "linked.bin",
          1024,
          &error);
  expect(reader == nullptr, "reader followed a final symbolic link");
}

void rejects_symbolic_parent() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(private_directory);
  write_file(private_directory / "outside.bin", "outside-bytes");
  expect(
      symlink(private_directory.c_str(), (workspace / "linked-parent").c_str()) == 0,
      "could not create parent symbolic link");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "linked-parent/outside.bin",
          1024,
          &error);
  expect(reader == nullptr, "reader followed a symbolic parent directory");
}

void rejects_hard_link() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(private_directory);
  const std::filesystem::path outside = private_directory / "outside.bin";
  write_file(outside, "outside-bytes");
  expect(
      link(outside.c_str(), (workspace / "linked.bin").c_str()) == 0,
      "could not create hard link");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "linked.bin",
          1024,
          &error);
  expect(reader == nullptr, "reader accepted a hard-linked file");
}

void keeps_open_descriptor_inside_original_workspace_tree() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(workspace / "nested");
  make_directory(private_directory);
  write_file(workspace / "nested" / "payload.bin", "safe");
  write_file(private_directory / "payload.bin", "outside");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "nested/payload.bin",
          1024,
          &error);
  expect(reader != nullptr, error);
  expect(
      rename(
          (workspace / "nested").c_str(),
          (workspace / "nested-before-swap").c_str()) == 0,
      "could not move original parent directory");
  expect(
      symlink(private_directory.c_str(), (workspace / "nested").c_str()) == 0,
      "could not replace parent directory with a symbolic link");

  expect(read_all(reader.get()) == "safe", "reader escaped through replaced parent");
  expect(
      !reader->VerifyUnchanged(&error),
      "reader did not report the replaced workspace path");
  const std::string draft = "draft";
  expect(!reader->Replace(reinterpret_cast<const unsigned char*>(draft.data()),
      draft.size(), &error), "save followed a replaced symbolic parent");
  auto outside = agentcodi::WorkspaceFileReader::Open(
      private_directory.string(), "payload.bin", 1024, &error);
  expect(outside != nullptr && read_all(outside.get()) == "outside",
      "failed save changed the outside file");
}

void saves_complete_revisions_atomically_with_permissions() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  make_directory(workspace / "nested");
  const auto file = workspace / "nested" / "script.sh";
  write_file(file, "original");
  expect(chmod(file.c_str(), 0750) == 0, "could not set script permissions");
  std::string error;
  auto reader = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "nested/script.sh", 1024, &error);
  expect(reader != nullptr, error);
  const std::string replacement = "#!/system/bin/sh\necho changed\n";
  expect(reader->Replace(reinterpret_cast<const unsigned char*>(replacement.data()),
      replacement.size(), &error), error);
  expect(read_all(reader.get()) == "original", "save truncated the original inode in place");
  auto saved = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "nested/script.sh", 1024, &error);
  expect(saved != nullptr && read_all(saved.get()) == replacement,
      "save did not install the complete replacement");
  struct stat attributes {};
  expect(stat(file.c_str(), &attributes) == 0 && (attributes.st_mode & 0777) == 0750,
      "save did not preserve executable permissions");
  expect(saved->Replace(nullptr, 0, &error), error);
  expect(std::filesystem::file_size(file) == 0U, "save could not empty the file");
  for (const auto& entry : std::filesystem::directory_iterator(workspace / "nested")) {
    expect(entry.path().filename() == "script.sh", "save left a temporary file behind");
  }
}

void rejects_changed_revision_and_oversized_save() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  write_file(workspace / "code.txt", "original");
  std::string error;
  auto reader = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "code.txt", 8, &error);
  expect(reader != nullptr, error);
  const std::string too_large = "oversized";
  expect(!reader->Replace(reinterpret_cast<const unsigned char*>(too_large.data()),
      too_large.size(), &error), "save ignored its byte limit");
  expect(reader->VerifyUnchanged(&error), "rejected save changed the original");
  expect(rename((workspace / "code.txt").c_str(), (workspace / "old.txt").c_str()) == 0,
      "could not replace external revision");
  write_file(workspace / "code.txt", "external");
  const std::string draft = "draft";
  expect(!reader->Replace(reinterpret_cast<const unsigned char*>(draft.data()),
      draft.size(), &error), "save overwrote an external replacement");
  auto external = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "code.txt", 8, &error);
  expect(external != nullptr && read_all(external.get()) == "external",
      "failed save altered the external revision");
}

void failed_save_keeps_original_when_parent_is_unwritable() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  write_file(workspace / "code.txt", "original");
  std::string error;
  auto reader = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "code.txt", 1024, &error);
  expect(reader != nullptr, error);
  expect(chmod(workspace.c_str(), 0500) == 0, "could not make parent unwritable");
  const std::string draft = "draft";
  const bool saved = reader->Replace(reinterpret_cast<const unsigned char*>(draft.data()),
      draft.size(), &error);
  expect(chmod(workspace.c_str(), 0700) == 0, "could not restore parent permissions");
  expect(!saved, "save succeeded in an unwritable directory");
  expect(reader->VerifyUnchanged(&error), "failed save changed the original revision");
  for (const auto& entry : std::filesystem::directory_iterator(workspace)) {
    expect(entry.path().filename() == "code.txt", "failed save left a temporary file");
  }
}

}  // namespace

int main() {
  try {
    reads_nested_regular_file();
    rejects_final_symbolic_link();
    rejects_symbolic_parent();
    rejects_hard_link();
    keeps_open_descriptor_inside_original_workspace_tree();
    saves_complete_revisions_atomically_with_permissions();
    rejects_changed_revision_and_oversized_save();
    failed_save_keeps_original_when_parent_is_unwritable();
    std::cout << "Workspace file reader tests passed." << std::endl;
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "Workspace file reader test failed: " << error.what() << std::endl;
    return 1;
  }
}
