#pragma once
#include <string>
#include <vector>
#include <sys/types.h>

namespace archterminal {
struct Pty { int master; pid_t pid; };
Pty spawn(const std::string& executable, const std::string& cwd,
          const std::vector<std::string>& arguments, const std::vector<std::string>& environment,
          int rows, int columns, int cell_width = 0, int cell_height = 0);
void resize(int fd, int rows, int columns, int cell_width = 0, int cell_height = 0);
int wait(pid_t pid);
}
