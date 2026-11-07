#pragma once
#include <cerrno>
#include <cstddef>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdexcept>
#include <sys/prctl.h>
#include <sys/syscall.h>

// Android's app SELinux domain denies hardlinks. Reproduce EACCES in this test process on Linux.
inline void deny_hardlinks() {
    sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#ifdef __NR_link
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_link, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_linkat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    sock_fprog program{static_cast<unsigned short>(sizeof(instructions) / sizeof(instructions[0])), instructions};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0 || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) < 0)
        throw std::runtime_error("Cannot install hardlink-denial test policy");
}
