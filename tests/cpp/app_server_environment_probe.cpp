// Host regression for Android-only launch environment selection. The Python
// integration driver compiles the real supervisor with __ANDROID__ enabled.
#include "app_server_process.h"

#include <cstdlib>
#include <iostream>
#include <string>

int main(int argc, char* argv[]) {
  if (argc > 1 && (std::string(argv[1]) == "--environment-child"
                  || std::string(argv[1]) == "app-server")) {
    for (const char* name : {"LD_PRELOAD", "AGENTCODI_ARCH_SHELL"}) {
      const char* value = std::getenv(name);
      std::cout << (value == nullptr ? "unset" : value) << '\n';
    }
    return 0;
  }
  if (argc != 3) return 2;
  const std::string root = argv[1];
  agentcodi::ProcessConfig config;
  config.executable = argv[0];
  config.code_mode_host_executable = argv[0];
  config.shell_executable = argv[0];
  config.working_directory = root + "/files/agentcodi/workspace";
  config.codex_home = root + "/files/agentcodi/codex-home";
  config.home_directory = root + "/files/agentcodi/home";
  config.package_prefix = root + "/files/usr";
  config.state_directory = root + "/files/agentcodi/state";
  config.temporary_directory = root + "/files/tmp";
  config.library_directory = root + "/native";
  const std::string mode = argv[2];
  if (mode == "custom") {
    config.arguments = {"--environment-child"};
  } else if (mode == "explicit-app-server") {
    config.arguments = agentcodi::CodexAppServerArguments(config);
  } else if (mode != "managed") {
    return 2;
  }
  std::string error;
  auto process = agentcodi::AppServerProcess::Start(config, &error);
  if (process == nullptr) {
    std::cerr << error << '\n';
    return 3;
  }
  for (int i = 0; i < 2; ++i) {
    std::string value;
    if (process->ReadLine(4096U, &value, &error) != agentcodi::LineReadStatus::kLine) {
      std::cerr << error << '\n';
      process->Stop(500);
      return 4;
    }
    std::cout << value << '\n';
  }
  return process->Stop(500) == 0 ? 0 : 5;
}
