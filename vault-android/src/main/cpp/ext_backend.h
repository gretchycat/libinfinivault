#ifndef INFINIFOLDER_EXT_BACKEND_H
#define INFINIFOLDER_EXT_BACKEND_H
#include <stdint.h>
#include <stddef.h>
struct ifext_io {
    void *context;
    uint64_t size;
    int (*read)(void *, uint64_t, void *, size_t);
    int (*write)(void *, uint64_t, const void *, size_t);
    int (*flush)(void *);
};
/* Paths are UTF-8 relative to the selected filesystem, never host paths.
 * Only content names an app-private payload file. Probe never writes. */
enum { IFEXT_PROBE, IFEXT_CREATE, IFEXT_MKDIR, IFEXT_WRITE, IFEXT_DELETE, IFEXT_MOVE, IFEXT_EXPORT_METADATA, IFEXT_APPLY_METADATA };
int ifext_run(struct ifext_io *, int action, const char *path, const char *target,
              const char *content, uint64_t result[6], char *error, size_t error_size);
#endif
