// Package Edition shell bridge. User tools are resolved by the shared PATH.
#include <cerrno>
#include <cstring>
#include <iostream>
#include <string>
#include <unistd.h>

int main(int argc, char* argv[]) {
  constexpr const char* shell = "/system/bin/sh";
  if (argc == 2 && std::string(argv[1]) == "--interactive") {
    // The Android command field owns editing, and its TextView wraps the transcript.
    // mksh's line editors horizontally redraw long input with CR/backspace, which
    // cannot be represented by the append-only output buffer. Use PTY line echo.
    char* const arguments[] = {
        const_cast<char*>(shell), const_cast<char*>("-i"),
        const_cast<char*>("+o"), const_cast<char*>("emacs"),
        const_cast<char*>("+o"), const_cast<char*>("vi"), nullptr};
    execv(shell, arguments);
  } else if (argc == 3 && std::string(argv[1]) == "-c") {
    if (std::strlen(argv[2]) > 256U * 1024U) {
      std::cerr << "Shell command exceeds the AGENTCODI limit\n";
      return 2;
    }
    char* const arguments[] = {const_cast<char*>(shell), const_cast<char*>("-c"), argv[2], nullptr};
    execv(shell, arguments);
  } else {
    std::cerr << "Usage: AGENTCODI shell [--interactive|-c command]\n";
    return 2;
  }
  std::cerr << "System shell: " << std::strerror(errno) << '\n';
  return 127;
}
