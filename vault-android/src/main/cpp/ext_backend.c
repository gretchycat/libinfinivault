#include "ext_backend.h"
#include <ext4.h>
#include <ext4_super.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>

/* lwext4's device registry is global. Other providers and transfers continue;
 * serialize only this backend's native calls, never Android's main thread. */
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static const char *mount_point = "/ifext/";
extern int ext4_transaction_error(const char *);
extern int ext4_dir_remove_empty(const char *);
extern int ifext_export_metadata(const char *, const char *);
extern int ifext_apply_metadata(const char *, const char *);
struct checked_io { struct ifext_io *io; int failed; };

static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static int open_device(struct ext4_blockdev *bd) { (void)bd; return 0; }
static int close_device(struct ext4_blockdev *bd) { (void)bd; return 0; }
static int read_blocks(struct ext4_blockdev *bd, void *data, uint64_t block, uint32_t count) {
    struct checked_io *checked = bd->bdif->p_user;
    struct ifext_io *io = checked->io;
    if (checked->failed) return checked->failed;
    if (block > io->size / 512 || count > io->size / 512 - block) return EIO;
    return checked->failed = io->read(io->context, block * 512, data, (size_t)count * 512);
}
static int write_blocks(struct ext4_blockdev *bd, const void *data, uint64_t block, uint32_t count) {
    struct checked_io *checked = bd->bdif->p_user;
    struct ifext_io *io = checked->io;
    if (ext4_integrity_status()) return checked->failed = EUCLEAN;
    if (checked->failed) return checked->failed;
    if (!io->write) return EROFS;
    if (block > io->size / 512 || count > io->size / 512 - block) return EIO;
    return checked->failed = io->write(io->context, block * 512, data, (size_t)count * 512);
}
static int full_path(const char *relative, char **out) {
    if (!relative || relative[0] == '/') return EINVAL;
    const char *part = relative;
    while (*part) {
        const char *end = strchr(part, '/');
        size_t n = end ? (size_t)(end - part) : strlen(part);
        if (!n || n > 255 || (n == 1 && part[0] == '.') ||
            (n == 2 && part[0] == '.' && part[1] == '.')) return EINVAL;
        if (!end) break;
        part = end + 1;
        if (!*part) return EINVAL;
    }
    size_t n = strlen(relative);
    if (n > 65536) return ENAMETOOLONG;
    *out = malloc(n + strlen(mount_point) + 1);
    if (!*out) return ENOMEM;
    sprintf(*out, "%s%s", mount_point, relative);
    return 0;
}
/* Never traverse a symlink to mutate a different entry. Native moves preserve
 * the source inode, including hardlinks, ownership, modes and xattrs. */
static int check_ancestors(char *path) {
    for (char *p = path + strlen(mount_point); *p; ++p) {
        if (*p != '/') continue;
        *p = 0;
        uint32_t mode = 0;
        int r = ext4_mode_get(path, &mode);
        *p = '/';
        if (r) return r;
        if ((mode & 0170000) != 0040000) return ENOTDIR;
    }
    return 0;
}
static int exists(const char *path, uint32_t *mode) { return ext4_mode_get(path, mode); }
/* A newly created directory is empty. Looking up an absent child makes lwext4
 * verify its directory index and leaf checksums, including an empty indexed
 * leaf which may otherwise not be visited until after publication. */
static int verify_new_directory(const char *path) {
    static const char child[] = "/.infinifolder-integrity-probe";
    size_t n = strlen(path);
    if (n > 65536 - sizeof(child)) return ENAMETOOLONG;
    char *probe = malloc(n + sizeof(child));
    if (!probe) return ENOMEM;
    memcpy(probe, path, n);
    memcpy(probe + n, child, sizeof(child));
    uint32_t mode = 0;
    int r = ext4_mode_get(probe, &mode);
    free(probe);
    if (ext4_integrity_status()) return EUCLEAN;
    return r == ENOENT || r == EOK ? EOK : r;
}
static int touch_parent(char *path, uint32_t now) {
    char *slash = strrchr(path, '/');
    if (!slash) return EINVAL;
    char saved = slash[1]; slash[1] = 0;
    int r = ext4_mtime_set(path, now);
    if (!r) r = ext4_ctime_set(path, now);
    slash[1] = saved;
    return r;
}
static int remove_tree(const char *path, unsigned depth) {
    if (depth > 128) return ELOOP;
    uint32_t mode;
    int r = ext4_mode_get(path, &mode);
    if (r) return r;
    if ((mode & 0170000) != 0040000) return ext4_fremove(path);
    ext4_dir directory;
    r = ext4_dir_open(&directory, path);
    if (r) return r;
    char **children = NULL;
    size_t count = 0, capacity = 0;
    const ext4_direntry *entry;
    while ((entry = ext4_dir_entry_next(&directory))) {
        size_t n = entry->name_length;
        if ((n == 1 && entry->name[0] == '.') ||
            (n == 2 && entry->name[0] == '.' && entry->name[1] == '.')) continue;
        if (!n || memchr(entry->name, '/', n) || memchr(entry->name, 0, n)) { r = EUCLEAN; break; }
        if (count == capacity) {
            if (count >= 1000000) { r = EFBIG; break; }
            size_t next = capacity ? capacity * 2 : 32;
            char **grown = realloc(children, next * sizeof(char *));
            if (!grown) { r = ENOMEM; break; }
            children = grown; capacity = next;
        }
        size_t prefix = strlen(path);
        if (prefix + n + 1 > 65536) { r = ENAMETOOLONG; break; }
        children[count] = malloc(prefix + n + 2);
        if (!children[count]) { r = ENOMEM; break; }
        memcpy(children[count], path, prefix);
        children[count][prefix] = '/';
        memcpy(children[count] + prefix + 1, entry->name, n);
        children[count][prefix + n + 1] = 0;
        count++;
    }
    int closed = ext4_dir_close(&directory);
    if (!r) r = closed;
    if (!r && ext4_integrity_status()) r = EUCLEAN;
    for (size_t i = 0; i < count; ++i) {
        if (!r) r = remove_tree(children[i], depth + 1);
        free(children[i]);
    }
    free(children);
    return r ? r : ext4_dir_remove_empty(path);
}

int ifext_run(struct ifext_io *io, int action, const char *path, const char *target,
              const char *content, uint64_t result[6], char *error, size_t error_size) {
    int r = 0, cleanup = 0;
    int registered = 0, mounted = 0, journal_started = 0;
    const char *step = "validate filesystem";
    char *source_path = NULL, *target_path = NULL;
    unsigned char super[1024], physical[512];
    uint32_t mode = 0, unused_mode = 0;
    int read_only = action == IFEXT_PROBE || action == IFEXT_EXPORT_METADATA;
    struct checked_io checked = { .io = io };
    struct ext4_blockdev_iface iface = { .open = open_device, .bread = read_blocks,
        .bwrite = write_blocks, .close = close_device, .ph_bsize = 512,
        .ph_bcnt = io->size / 512, .ph_bbuf = physical, .p_user = &checked };
    struct ext4_blockdev bd = { .bdif = &iface, .part_size = io->size };
    memset(result, 0, sizeof(uint64_t) * 6);
    pthread_mutex_lock(&lock);
    ext4_integrity_reset();
#define CHECK(call, label) do { step = label; r = (call); if (!r && ext4_integrity_status()) r = EUCLEAN; if (r) goto finish; } while (0)
    if (action < IFEXT_PROBE || action > IFEXT_APPLY_METADATA || io->size < 2048 || io->size % 512) { r = EINVAL; goto finish; }
    CHECK(io->read(io->context, 1024, super, sizeof(super)), "read ext superblock");
    if (super[56] != 0x53 || super[57] != 0xef) { r = ENOTSUP; goto finish; }
    uint32_t compat = le32(super + 92), incompat = le32(super + 96), ro = le32(super + 100);
    if (le32(super+72) != 0) { step="unsupported ext creator OS"; r=ENOTSUP; goto finish; }
    int has_journal = !!(compat & 4), needs_recovery = !!(incompat & 4);
    result[3] = has_journal;
    /* Explicit write feature whitelist. In particular never bypass MMP,
     * encryption, bigalloc, fast commits, external journals or pending orphans.
     * A clean orphan_file is preserved: this offline API has no open-unlinked
     * files; ORPHAN_PRESENT is rejected through the RO feature whitelist. */
    if ((compat & ~0x103cU) || (incompat & ~0x22d6U) || (ro & ~0x47bU)) {
        step = "unsupported ext feature flags (original retained)"; r = ENOTSUP; goto finish;
    }
    unsigned state = (unsigned)super[58] | (unsigned)super[59] << 8;
    if ((state & 2) || (!(state & 1) && !needs_recovery) ||
        (needs_recovery && !has_journal) || le32(super + 228) || le32(super + 232)) {
        step = "filesystem requires e2fsck before editing"; r = EUCLEAN; goto finish;
    }
    if (!read_only && !io->write) { r = EROFS; goto finish; }
    CHECK(full_path(path, &source_path), "validate source path");
    if (!read_only && action != IFEXT_APPLY_METADATA && !*path) { r = EINVAL; goto finish; }
    if (action == IFEXT_MOVE) {
        CHECK(full_path(target, &target_path), "validate destination path");
        if (!*target || !strcmp(path, target) ||
            (!strncmp(path, target, strlen(path)) && target[strlen(path)] == '/')) { r = EINVAL; goto finish; }
    }
    CHECK(ext4_device_register(&bd, "ifext"), "register ext device"); registered = 1;
    CHECK(ext4_mount("ifext", mount_point, read_only), "mount ext filesystem"); mounted = 1;
    if (!read_only) {
        if (needs_recovery) {
            CHECK(ext4_recover(mount_point), "recover ext journal"); result[4] = 1;
        }
        if (has_journal) { CHECK(ext4_journal_start(mount_point), "start ext journal"); journal_started = 1; }
    }
    CHECK(check_ancestors(source_path), "check source directory ancestors");
    if (action == IFEXT_MOVE) CHECK(check_ancestors(target_path), "check destination directory ancestors");
    if (action == IFEXT_CREATE || action == IFEXT_MKDIR || action == IFEXT_MOVE) {
        int found = exists(action == IFEXT_MOVE ? target_path : source_path, &unused_mode);
        if (ext4_integrity_status()) { r = EUCLEAN; goto finish; }
        if (!found) { step = "destination already exists"; r = EEXIST; goto finish; }
        if (found != ENOENT) { r = found; goto finish; }
    }
    if (action == IFEXT_WRITE || action == IFEXT_DELETE || action == IFEXT_MOVE) {
        CHECK(exists(source_path, &mode), "inspect source entry");
        if (action == IFEXT_WRITE && (mode & 0170000) != 0100000) { r = EINVAL; goto finish; }
    }
    switch (action) {
    case IFEXT_PROBE: break;
    case IFEXT_EXPORT_METADATA: CHECK(ifext_export_metadata(source_path,content), "read ext transfer metadata"); break;
    case IFEXT_APPLY_METADATA: CHECK(ifext_apply_metadata(source_path,content), "preserve ext transfer metadata"); break;
    case IFEXT_CREATE: {
        ext4_file f;
        CHECK(ext4_fopen2(&f, source_path, O_WRONLY | O_CREAT | O_EXCL), "create ext file");
        CHECK(ext4_fclose(&f), "close new ext file");
        break;
    }
    case IFEXT_MKDIR:
        CHECK(ext4_dir_mk(source_path), "create ext directory");
        CHECK(verify_new_directory(source_path), "verify new ext directory checksum");
        break;
    case IFEXT_WRITE: {
        FILE *input = content ? fopen(content, "rb") : NULL;
        if (!input) { step = "open staged file contents"; r = errno ? errno : EINVAL; goto finish; }
        ext4_file f;
        r = ext4_fopen2(&f, source_path, O_WRONLY | O_TRUNC);
        if (!r) {
            unsigned char *buffer = malloc(128 * 1024);
            if (!buffer) r = ENOMEM;
            while (!r) {
                size_t n = fread(buffer, 1, 128 * 1024, input), written = 0;
                if (!n) { if (ferror(input)) r = EIO; break; }
                r = ext4_fwrite(&f, buffer, n, &written);
                if (!r && n != written) r = EIO;
            }
            free(buffer);
            cleanup = ext4_fclose(&f); if (!r) r = cleanup;
        }
        fclose(input);
        step = "write ext file"; if (r) goto finish;
        break;
    }
    case IFEXT_DELETE:
        CHECK(remove_tree(source_path, 0), "delete ext entry"); break;
    case IFEXT_MOVE: {
        struct ext4_inode inode;
        uint32_t before = 0, after = 0;
        CHECK(ext4_raw_inode_fill(source_path, &before, &inode), "inspect original inode");
        CHECK(ext4_frename(source_path, target_path), "move ext entry");
        CHECK(ext4_raw_inode_fill(target_path, &after, &inode), "check moved inode");
        if (before != after || exists(source_path, &unused_mode) != ENOENT) { r = EIO; goto finish; }
        result[5] = after;
        break;
    }
    }
    if (action >= IFEXT_CREATE && action <= IFEXT_MOVE) {
        uint32_t now = (uint32_t)time(NULL);
        const char *final_path = action == IFEXT_MOVE ? target_path : source_path;
        if (action == IFEXT_CREATE || action == IFEXT_MKDIR) {
            CHECK(ext4_mode_set(final_path, action == IFEXT_MKDIR ? 0755 : 0644), "set new ext entry mode");
            CHECK(ext4_atime_set(final_path, now), "set new ext entry access time");
        }
        if (action != IFEXT_DELETE) {
            CHECK(ext4_ctime_set(final_path, now), "update ext entry change time");
            if (action != IFEXT_MOVE) CHECK(ext4_mtime_set(final_path, now), "update ext entry modification time");
        }
        CHECK(touch_parent(source_path, now), "update source directory time");
        if (action == IFEXT_MOVE) CHECK(touch_parent(target_path, now), "update destination directory time");
    }
    struct ext4_mount_stats stats;
    CHECK(ext4_transaction_error(mount_point), "commit ext transaction");
    CHECK(ext4_mount_point_stats(mount_point, &stats), "read ext capacity");
    result[0] = stats.block_size; result[1] = stats.blocks_count; result[2] = stats.free_blocks_count;
finish:
    if (!r && ext4_integrity_status()) { r = EUCLEAN; step = "ext metadata checksum failed; run e2fsck on a copy"; }
    if (journal_started) { cleanup = ext4_journal_stop(mount_point); if (!r && cleanup) { r = cleanup; step = "commit ext journal"; } }
    if (mounted) {
        cleanup = ext4_cache_flush(mount_point); if (!r && cleanup) { r = cleanup; step = "flush ext metadata"; }
        cleanup = ext4_umount(mount_point); if (!r && cleanup) { r = cleanup; step = "unmount ext filesystem"; }
    }
    if (!r && checked.failed) { r = checked.failed; step = "ext block I/O"; }
    if (registered) { cleanup = ext4_device_unregister("ifext"); if (!r) r = cleanup; }
    if (!read_only && io->flush) { cleanup = io->flush(io->context); if (!r && cleanup) { r = cleanup; step = "sync ext image"; } }
    if (r) snprintf(error, error_size, "%s: %s (code %d)", step, strerror(r), r);
    free(source_path); free(target_path);
    pthread_mutex_unlock(&lock);
    return r;
}
