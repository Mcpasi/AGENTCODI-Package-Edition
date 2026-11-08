// The pinned Android Codex discovers its shell through getpwuid_r, not $SHELL.
// Redirect only the app's own passwd shell to the generated Arch Bash adapter.
#include <cstdlib>
#include <cerrno>
#include <dlfcn.h>
#include <pwd.h>
#include <unistd.h>

// Termux remaps this name to a static header polyfill. Interposition must
// export Bionic's real libc symbol, rather than redefine that polyfill.
#ifdef getpwuid_r
#undef getpwuid_r
#endif

extern "C" int getpwuid_r(uid_t uid, passwd* entry, char* buffer, size_t size, passwd** result) {
  using Lookup = int (*)(uid_t, passwd*, char*, size_t, passwd**);
  static const auto lookup = reinterpret_cast<Lookup>(dlsym(RTLD_NEXT, "getpwuid_r"));
  if (lookup == nullptr) return ENOSYS;
  const int status = lookup(uid, entry, buffer, size, result);
  const char* shell = std::getenv("AGENTCODI_ARCH_SHELL");
  if (status == 0 && *result != nullptr && uid == getuid() && shell != nullptr && shell[0] == '/') {
    entry->pw_shell = const_cast<char*>(shell);
  }
  return status;
}
