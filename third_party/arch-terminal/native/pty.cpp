#include "pty.hpp"
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <dirent.h>
#include <fcntl.h>
#include <limits>
#include <stdexcept>
#include <system_error>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

namespace archterminal {
namespace {
struct Fd {
    int value;
    ~Fd() { if (value >= 0) ::close(value); }
    int release() { int result = value; value = -1; return result; }
};
[[noreturn]] void error(const char* operation) { throw std::system_error(errno, std::generic_category(), operation); }
[[noreturn]] void child_error(int fd) {
    int failure = errno;
    ssize_t result;
    do { result = ::write(fd, &failure, sizeof(failure)); } while (result < 0 && errno == EINTR);
    _exit(127);
}
std::vector<char*> pointers(const std::vector<std::string>& values) {
    std::vector<char*> out;
    out.reserve(values.size() + 1);
    for (const auto& value : values) {
        if (value.find('\0') != std::string::npos) throw std::invalid_argument("NUL in process argument");
        out.push_back(const_cast<char*>(value.c_str()));
    }
    out.push_back(nullptr);
    return out;
}
}

void resize(int fd, int rows, int columns, int cell_width, int cell_height) {
    if (rows < 1 || rows > 1000 || columns < 1 || columns > 1000 || cell_width < 0 || cell_height < 0)
        throw std::invalid_argument("Invalid terminal dimensions");
    winsize size{};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_xpixel = static_cast<unsigned short>(std::min(65535LL, static_cast<long long>(columns) * cell_width));
    size.ws_ypixel = static_cast<unsigned short>(std::min(65535LL, static_cast<long long>(rows) * cell_height));
    if (ioctl(fd, TIOCSWINSZ, &size) < 0) error("TIOCSWINSZ");
}

Pty spawn(const std::string& executable, const std::string& cwd,
          const std::vector<std::string>& arguments, const std::vector<std::string>& environment,
          int rows, int columns, int cell_width, int cell_height) {
    if (executable.empty() || arguments.empty() || executable.find('\0') != std::string::npos || cwd.find('\0') != std::string::npos)
        throw std::invalid_argument("Invalid process arguments");
    auto argv = pointers(arguments);
    auto envp = pointers(environment);
    Fd master{::open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC)};
    if (master.value < 0) error("open /dev/ptmx");
    if (grantpt(master.value) < 0 || unlockpt(master.value) < 0) error("unlock PTY");
    char name[128];
    int name_result = ptsname_r(master.value, name, sizeof(name));
    if (name_result != 0) { errno = name_result; error("ptsname_r"); }
    Fd slave{::open(name, O_RDWR | O_NOCTTY | O_CLOEXEC)};
    if (slave.value < 0) error("open PTY slave");
    termios attributes{};
    if (tcgetattr(slave.value, &attributes) < 0) error("tcgetattr");
    attributes.c_iflag |= IUTF8;
    attributes.c_iflag &= ~(IXON | IXOFF);
    if (tcsetattr(slave.value, TCSANOW, &attributes) < 0) error("tcsetattr");
    resize(slave.value, rows, columns, cell_width, cell_height);

    int pipe_fds[2];
    if (pipe2(pipe_fds, O_CLOEXEC) < 0) error("pipe2");
    Fd error_read{pipe_fds[0]}, error_write{pipe_fds[1]};
    // Gather inherited descriptors before fork; the child uses only async-signal-safe operations.
    std::vector<int> inherited;
    DIR* directory = opendir("/proc/self/fd");
    if (!directory) error("enumerate process descriptors");
    while (dirent* entry = readdir(directory)) {
        char* end;
        long value = strtol(entry->d_name, &end, 10);
        if (*entry->d_name && !*end && value > 2 && value != error_write.value && value <= std::numeric_limits<int>::max())
            inherited.push_back(static_cast<int>(value));
    }
    closedir(directory);
    pid_t pid = fork();
    if (pid < 0) error("fork");
    if (pid == 0) {
        sigset_t signals;
        sigemptyset(&signals);
        sigprocmask(SIG_SETMASK, &signals, nullptr);
        struct sigaction action{};
        action.sa_handler = SIG_DFL;
        for (int signal_number = 1; signal_number < NSIG; ++signal_number)
            sigaction(signal_number, &action, nullptr);
        if (setsid() < 0 || ioctl(slave.value, TIOCSCTTY, 0) < 0) child_error(error_write.value);
        for (int fd = 0; fd < 3; ++fd)
            if (dup2(slave.value, fd) < 0) child_error(error_write.value);
        for (int fd : inherited) ::close(fd);
        if (chdir(cwd.c_str()) < 0) child_error(error_write.value);
        execve(executable.c_str(), argv.data(), envp.data());
        child_error(error_write.value);
    }
    ::close(error_write.release());
    int failure = 0;
    ssize_t count;
    do { count = ::read(error_read.value, &failure, sizeof(failure)); } while (count < 0 && errno == EINTR);
    if (count > 0) {
        wait(pid);
        throw std::system_error(failure, std::generic_category(), "start terminal process");
    }
    if (count < 0) { int saved = errno; kill(pid, SIGKILL); wait(pid); errno = saved; error("read exec status"); }
    return {master.release(), pid};
}

int wait(pid_t pid) {
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) error("waitpid");
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}
}
