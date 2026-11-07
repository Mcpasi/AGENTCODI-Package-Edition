// Executed against the actual Bionic loader during APK assembly.
#include <cstdlib>
#include <cstring>
#include <pwd.h>
#include <unistd.h>

int main() {
  passwd entry {}, *result = nullptr;
  char buffer[65536];
  const char* expected = std::getenv("AGENTCODI_ARCH_SHELL");
  return expected == nullptr
      || getpwuid_r(getuid(), &entry, buffer, sizeof(buffer), &result) != 0
      || result == nullptr || std::strcmp(result->pw_shell, expected) != 0;
}
