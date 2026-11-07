/* The pinned PRoot revision omits string.h in ashmem_memfd.c. */
#if !defined(__ASSEMBLER__) && !defined(NO_LIBC_HEADER) && __STDC_HOSTED__
#include <string.h>
#endif
