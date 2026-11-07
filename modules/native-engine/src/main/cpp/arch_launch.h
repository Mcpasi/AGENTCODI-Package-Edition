#pragma once
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits.h>
#include <signal.h>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace agentcodi::arch {
inline std::string native_directory() {
  char path[PATH_MAX + 1];
  const ssize_t count = readlink("/proc/self/exe", path, PATH_MAX);
  if (count <= 0) return {};
  path[count] = '\0';
  const std::string executable(path);
  return executable.substr(0, executable.find_last_of('/'));
}

inline bool installed(const std::string& files) {
  struct stat info {};
  return !files.empty() && files.front() == '/'
      && stat((files + "/arch/.arch-terminal-ready").c_str(), &info) == 0
      && S_ISREG(info.st_mode);
}

inline void bindings(std::vector<std::string>* arguments, const std::string& files) {
  const std::string workspace = files + "/agentcodi/workspace";
  arguments->insert(arguments->end(), {
      "-b", workspace + ":" + workspace,
      "-b", workspace + ":/workspace",
      "-b", files + "/agentcodi/home:" + files + "/agentcodi/home"});
}

inline volatile sig_atomic_t command_child = 0;
inline volatile sig_atomic_t stopping = 0;
inline void stop_command(int) {
  stopping = 1;
  if (command_child > 0) kill(command_child, SIGQUIT);
}

inline int execute(std::vector<std::string> arguments, const std::string& files, bool supervise = false) {
  const std::string runtime = files + "/runtime/tmp";
  const std::string loader = native_directory() + "/libproot-loader.so";
  // Android loader variables must never enter the glibc guest.
  unsetenv("LD_PRELOAD");
  unsetenv("LD_LIBRARY_PATH");
  setenv("PROOT_LOADER", loader.c_str(), 1);
  setenv("PROOT_TMP_DIR", runtime.c_str(), 1);
  setenv("TMPDIR", runtime.c_str(), 1);
  std::vector<char*> argv;
  for (auto& value : arguments) argv.push_back(value.data());
  argv.push_back(nullptr);
  if (supervise) {
    // PRoot ignores SIGTERM. Translate command cancellation to its cleanup path
    // and wait for tracees to be reaped, without imposing a command deadline.
    struct sigaction action {}, previous[4] {};
    const int signals[] = {SIGTERM, SIGQUIT, SIGHUP, SIGINT};
    sigemptyset(&action.sa_mask);
    action.sa_handler = stop_command;
    for (int i = 0; i < 4; ++i) {
      if (i == 3) action.sa_handler = SIG_IGN;
      sigaction(signals[i], &action, &previous[i]);
    }
    const pid_t child = fork();
    if (child < 0) return 126;
    if (child == 0) {
      for (int i = 0; i < 4; ++i) sigaction(signals[i], &previous[i], nullptr);
      execv(argv.front(), argv.data());
      _exit(127);
    }
    command_child = child;
    if (stopping) kill(child, SIGQUIT);
    int status = 0;
    while (waitpid(child, &status, 0) < 0) {
      if (errno != EINTR) return 126;
    }
    command_child = 0;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
  }
  execv(argv.front(), argv.data());
  std::cerr << "Arch PRoot: " << std::strerror(errno) << '\n';
  return 127;
}
}  // namespace agentcodi::arch
