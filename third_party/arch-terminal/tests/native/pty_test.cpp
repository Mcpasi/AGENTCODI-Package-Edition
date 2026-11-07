#include "pty.hpp"
#include <chrono>
#include <csignal>
#include <fcntl.h>
#include <iostream>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
std::string read_until(int fd, const std::string& marker) {
    std::string result;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (result.find(marker) == std::string::npos) {
        require(std::chrono::steady_clock::now() < deadline, "test harness read deadline exceeded");
        pollfd item{fd, POLLIN, 0};
        if (poll(&item, 1, 100) < 1) continue;
        char buffer[16384];
        auto count = read(fd, buffer, sizeof(buffer));
        require(count > 0, ("PTY closed before marker: " + result).c_str());
        result.append(buffer, static_cast<size_t>(count));
    }
    return result;
}
archterminal::Pty shell(const std::string& command) {
    return archterminal::spawn("/bin/sh", "/", {"sh", "-c", command}, {"PATH=/usr/bin:/bin", "TERM=xterm-256color"}, 24, 80);
}
void finish(archterminal::Pty process, int expected) {
    require(archterminal::wait(process.pid) == expected, "wrong child exit status");
    close(process.master);
}
}
int main() {
    try {
        auto geometry = shell("test -t 0 && test -t 1 && test -t 2 && stty size; printf READY; read answer; printf '<%s>DONE' \"$answer\"");
        auto initial = read_until(geometry.master, "READY");
        require(initial.find("24 80") != std::string::npos, "initial window size not applied");
        require(write(geometry.master, "Grüße\n", 8) == 8, "write failed");
        require(read_until(geometry.master, "DONE").find("<Grüße>") != std::string::npos, "UTF-8 input failed");
        finish(geometry, 0);

        auto resized = shell("printf READY; read line; stty size; printf DONE");
        read_until(resized.master, "READY");
        archterminal::resize(resized.master, 47, 101, 9, 18);
        write(resized.master, "\n", 1);
        require(read_until(resized.master, "DONE").find("47 101") != std::string::npos, "resize not delivered");
        finish(resized, 0);

        auto quiet = shell("printf READY; read line; printf ALIVE; read line; exit 7");
        read_until(quiet.master, "READY");
        pollfd idle{quiet.master, POLLIN, 0};
        require(poll(&idle, 1, 300) == 0, "quiet process should remain silent");
        int status;
        require(waitpid(quiet.pid, &status, WNOHANG) == 0, "silent PTY must stay alive");
        write(quiet.master, "\n", 1);
        read_until(quiet.master, "ALIVE");
        write(quiet.master, "\n", 1);
        finish(quiet, 7);

        auto interrupt = shell("trap 'printf INTERRUPTED; exit 0' INT; printf READY; while :; do sleep 1; done");
        read_until(interrupt.master, "READY");
        write(interrupt.master, "\003", 1);
        read_until(interrupt.master, "INTERRUPTED");
        finish(interrupt, 0);

        // Simultaneous sessions must own separate PTYs, geometry and inherited descriptors.
        auto first = shell("stty -echo; printf FIRST_READY; read value; stty size; printf 'FIRST_<%s>_DONE' \"$value\"; exit 3");
        auto second = shell("stty -echo; printf SECOND_READY; read value; stty size; printf 'SECOND_<%s>_DONE' \"$value\"; read value; printf SECOND_ALIVE; exit 4");
        require(first.pid != second.pid && first.master != second.master, "sessions share a process or PTY");
        read_until(first.master, "FIRST_READY");
        read_until(second.master, "SECOND_READY");
        archterminal::resize(first.master, 31, 91, 9, 18);
        require(write(first.master, "alpha\n", 6) == 6, "first session write failed");
        auto first_output = read_until(first.master, "_DONE");
        require(first_output.find("31 91") != std::string::npos && first_output.find("FIRST_<alpha>_DONE") != std::string::npos,
                "first session lost its geometry or input");
        finish(first, 3);
        require(waitpid(second.pid, &status, WNOHANG) == 0, "closing first session ended second session");
        require(write(second.master, "beta\n", 5) == 5, "second session write failed");
        auto second_output = read_until(second.master, "_DONE");
        require(second_output.find("24 80") != std::string::npos && second_output.find("SECOND_<beta>_DONE") != std::string::npos
                && second_output.find("alpha") == std::string::npos, "session geometry or input leaked across PTYs");
        require(write(second.master, "\n", 1) == 1, "remaining session write failed");
        read_until(second.master, "SECOND_ALIVE");
        finish(second, 4);

        // Drain substantially more than a display buffer; output volume never kills the PTY.
        auto burst = shell("head -c 33554432 /dev/zero; printf BURST_DONE");
        size_t received = 0;
        std::string suffix;
        while (suffix.find("BURST_DONE") == std::string::npos) {
            pollfd item{burst.master, POLLIN, 0};
            require(poll(&item, 1, 10000) > 0, "burst test harness deadline exceeded");
            char buffer[65536];
            auto count = read(burst.master, buffer, sizeof(buffer));
            require(count > 0, "burst closed early");
            received += static_cast<size_t>(count);
            suffix.append(buffer, static_cast<size_t>(count));
            if (suffix.size() > 32) suffix.erase(0, suffix.size() - 32);
        }
        require(received >= 33554432 + 10, "output truncated");
        finish(burst, 0);

        auto signaled = shell("printf READY; read line");
        read_until(signaled.master, "READY");
        kill(signaled.pid, SIGKILL);
        finish(signaled, -SIGKILL);

        bool failed = false;
        try { archterminal::spawn("/does-not-exist", "/", {"missing"}, {}, 24, 80); }
        catch (const std::system_error&) { failed = true; }
        require(failed, "exec failure must be reported to caller");
        failed = false;
        try { auto invalid = archterminal::spawn("/bin/sh", "/missing-cwd", {"sh"}, {}, 24, 80); (void)invalid; }
        catch (const std::system_error&) { failed = true; }
        require(failed, "chdir failure must not silently start in wrong directory");
        auto after_failure = shell("printf RECOVERED");
        read_until(after_failure.master, "RECOVERED");
        finish(after_failure, 0);
        std::cout << "PTY: controlling terminal, UTF-8, geometry, resize, idle, Ctrl-C, independent concurrent sessions, 32 MiB stream, signals, exec recovery passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
