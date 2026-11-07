// Add AGENTCODI's shared workspace to the unmodified Arch Terminal LaunchSpec.
#include "arch_launch.h"

int main(int argc, char* argv[]) {
  std::string root;
  int guest = argc;
  for (int i = 1; i < argc; ++i) {
    if (std::string(argv[i]) == "-r" && i + 1 < argc) root = argv[++i];
    if (std::string(argv[i]) == "/usr/bin/env") { guest = i; break; }
  }
  if (root.size() < 5 || root.substr(root.size() - 5) != "/arch" || guest == argc) {
    std::cerr << "Unsupported Arch Terminal launch specification\n";
    return 126;
  }
  const std::string files = root.substr(0, root.size() - 5);
  if (!agentcodi::arch::installed(files)) return 126;
  std::vector<std::string> arguments = {agentcodi::arch::native_directory() + "/libarch-proot.so"};
  for (int i = 1; i < guest; ++i) {
    arguments.emplace_back(argv[i]);
    if (std::string(argv[i]) == "-w" && i + 1 < guest) {
      ++i;
      arguments.emplace_back(files + "/agentcodi/workspace");
    }
  }
  agentcodi::arch::bindings(&arguments, files);
  for (int i = guest; i < argc; ++i) arguments.emplace_back(argv[i]);
  return agentcodi::arch::execute(std::move(arguments), files);
}
