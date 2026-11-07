/* Minimal Bionic/Linux compatibility header for the unmodified talloc.c. */
#pragma once
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/param.h>
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3
#define PRINTF_ATTRIBUTE(a,b) __attribute__((format(printf,a,b)))
#define _PUBLIC_ __attribute__((visibility("default")))
#define __location__ __FILE__ ":" __LINE_STR__
#define __LINE_STR__ TALLOC_STR(__LINE__)
#define TALLOC_STR(x) TALLOC_STR2(x)
#define TALLOC_STR2(x) #x
