// AGENTCODI command adapter, using Arch Terminal's unchanged PRoot runtime.
#include "arch_launch.h"

namespace {
bool host_variable(const std::string& key) {
  for (const char* prefix : {"LD_", "PROOT_", "ANDROID_", "TERMUX_", "CODEX_", "AGENTCODI_"}) {
    if (key.compare(0, std::strlen(prefix), prefix) == 0) return true;
  }
  for (const char* name : {"HOME", "USER", "LOGNAME", "SHELL", "PATH", "TERM", "COLORTERM",
                         "LANG", "TMPDIR", "TMP", "TEMP", "XDG_CACHE_HOME", "SSL_CERT_DIR"}) {
    if (key == name) return true;
  }
  return false;
}
}  // namespace

int main(int argc, char* argv[]) {
  const char* configured_files = std::getenv("AGENTCODI_ARCH_FILES");
  const std::string files = configured_files == nullptr ? "" : configured_files;
  if (!agentcodi::arch::installed(files)) {
    std::cerr << "Open Arch Terminal and install Arch before starting Codex.\n";
    return 126;
  }
  char current[PATH_MAX + 1];
  if (getcwd(current, sizeof(current)) == nullptr) return 126;
  std::vector<std::string> arguments = {
      agentcodi::arch::native_directory() + "/libarch-proot.so",
      "--kill-on-exit", "--link2symlink", "-0", "-r", files + "/arch",
      "-w", current, "-b", "/dev", "-b", "/proc", "-b", "/sys",
      "-b", files + "/arch/tmp:/dev/shm",
      "-b", files + "/runtime/resolv.conf:/etc/resolv.conf"};
  agentcodi::arch::bindings(&arguments, files);
  arguments.insert(arguments.end(), {
      "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin", "TERM=xterm-256color",
      "COLORTERM=truecolor", "LANG=C.UTF-8", "TMPDIR=/tmp",
      "AGENTCODI_WORKSPACE=" + files + "/agentcodi/workspace"});
  // The native supervisor provides an explicit environment. Preserve additional
  // per-server MCP configuration (tokens, proxy settings, etc.) while replacing
  // Android paths and loader context with the guest defaults above.
  for (char** entry = environ; entry != nullptr && *entry != nullptr; ++entry) {
    const std::string value(*entry);
    const std::size_t separator = value.find('=');
    if (separator != std::string::npos && separator > 0 && !host_variable(value.substr(0, separator))) {
      arguments.push_back(value);
    }
  }
  if (argc >= 3 && std::string(argv[1]) == "--command") {
    for (int i = 2; i < argc; ++i) arguments.emplace_back(argv[i]);
  } else {
    arguments.emplace_back("/bin/bash");
    if (argc == 1 || (argc == 2 && std::string(argv[1]) == "--interactive")) {
      arguments.emplace_back("--login");
    } else {
      for (int i = 1; i < argc; ++i) arguments.emplace_back(argv[i]);
    }
  }
  return agentcodi::arch::execute(std::move(arguments), files, true);
}
