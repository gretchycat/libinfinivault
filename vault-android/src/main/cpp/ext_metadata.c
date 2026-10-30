#include <ext4.h>
#include <ext4_inode.h>
#include <ext4_misc.h>
#include <ext4_super.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

extern int ext4_apply_transfer_fields(const char *, uint32_t, uint32_t, uint32_t, uint32_t,
                                    int64_t, uint32_t, int64_t, uint32_t);
static int put32(FILE *f, uint32_t n) {
    unsigned char bytes[4] = {n>>24,n>>16,n>>8,n};
    return fwrite(bytes, 1, 4, f) == 4 ? 0 : EIO;
}
static int put64(FILE *f, uint64_t n) { return put32(f, n>>32) || put32(f, n) ? EIO : 0; }
static int get32(FILE *f, uint32_t *n) {
    unsigned char b[4]; if (fread(b, 1, 4, f) != 4) return EINVAL;
    *n = (uint32_t)b[0]<<24 | (uint32_t)b[1]<<16 | (uint32_t)b[2]<<8 | b[3]; return 0;
}
static int get64(FILE *f, int64_t *n) {
    uint32_t high, low; if (get32(f,&high) || get32(f,&low)) return EINVAL;
    *n = (int64_t)((uint64_t)high<<32 | low); return 0;
}
static int64_t seconds(uint32_t low, uint32_t extra) { return (int64_t)(int32_t)low + ((int64_t)(extra & 3)<<32); }

int ifext_export_metadata(const char *path, const char *content) {
    struct ext4_inode inode;
    memset(&inode, 0, sizeof(inode));
    int r = ext4_raw_inode_fill(path, NULL, &inode);
    if (r) return r;
    /* These behavioral flags must not disappear in a generic copy/delete move.
     * Native inode relocation retains them; portable import support is pending. */
    if (ext4_inode_get_flags(&inode) & 0x100f8U) return ENOTSUP;
    if ((to_le16(inode.mode)&0170000) != 0040000 && ext4_inode_get_links_cnt(&inode)>1) return ENOTSUP;
    size_t length = 0;
    r = ext4_listxattr(path, NULL, 0, &length);
    if (r || length > 65536) return r ? r : EFBIG;
    char *names = calloc(length + 1, 1);
    if (!names) return ENOMEM;
    if (length) r = ext4_listxattr(path, names, length, &length);
    FILE *file = !r && content ? fopen(content, "wb") : NULL;
    if (!file) { free(names); return r ? r : EIO; }
    uint32_t count = 0;
    for (size_t p = 0; p < length;) {
        size_t n = strnlen(names+p, length-p);
        if (!n || n > 255 || n == length-p) { r = EUCLEAN; goto done; }
        count++; p += n+1;
    }
    uint32_t extra_size = to_le16(inode.extra_isize);
    uint32_t me = extra_size >= 12 ? to_le32(inode.mtime_extra) : 0;
    uint32_t ae = extra_size >= 16 ? to_le32(inode.atime_extra) : 0;
    r = put32(file,0x49464d31) || put32(file,63) || put32(file,to_le16(inode.mode)&07777) ||
        put32(file,ext4_inode_get_uid(&inode)) || put32(file,ext4_inode_get_gid(&inode)) ||
        put64(file,seconds(to_le32(inode.modification_time),me)) || put32(file,me>>2) ||
        put64(file,seconds(to_le32(inode.access_time),ae)) || put32(file,ae>>2) || put32(file,count);
    for (size_t p = 0; !r && p < length;) {
        size_t n = strlen(names+p), size = 0;
        r = ext4_getxattr(path,names+p,n,NULL,0,&size);
        if (r || size > 65536) { if (!r) r = EFBIG; break; }
        void *value = malloc(size ? size : 1);
        if (!value) { r = ENOMEM; break; }
        r = ext4_getxattr(path,names+p,n,value,size,&size);
        if (!r && (put32(file,n) || fwrite(names+p,1,n,file)!=n || put32(file,size) || fwrite(value,1,size,file)!=size)) r = EIO;
        free(value); p += n+1;
    }
done:
    if (fclose(file) && !r) r = EIO;
    free(names); return r;
}

int ifext_apply_metadata(const char *path, const char *content) {
    FILE *file = content ? fopen(content,"rb") : NULL;
    if (!file) return EINVAL;
    if (fseek(file,0,SEEK_END) || ftell(file) > 2*1024*1024 || fseek(file,0,SEEK_SET)) { fclose(file); return EFBIG; }
    uint32_t magic,mask,mode,uid,gid,mn,an,count;
    int64_t modified,accessed;
    int r = get32(file,&magic) || get32(file,&mask) || get32(file,&mode) || get32(file,&uid) || get32(file,&gid) ||
        get64(file,&modified) || get32(file,&mn) || get64(file,&accessed) || get32(file,&an) || get32(file,&count);
    if (r || magic != 0x49464d31 || (mask&~63U) || mode > 07777 || mn >= 1000000000 || an >= 1000000000 || count > 4096 || (!(mask&32) && count)) { r=EINVAL; goto done; }
    // Apply fields before ACLs: mode must agree with the ACL imported afterward.
    r = ext4_apply_transfer_fields(path,mask,mode,uid,gid,modified,mn,accessed,an);
    for (uint32_t i=0; !r && i<count; i++) {
        uint32_t n,size;
        if (get32(file,&n) || n<1 || n>255) { r=EINVAL; break; }
        char name[256];
        if (fread(name,1,n,file)!=n || memchr(name,0,n) || get32(file,&size) || size>65536) { r=EINVAL; break; }
        name[n]=0;
        void *value=malloc(size?size:1);
        if (!value) { r=ENOMEM; break; }
        if (fread(value,1,size,file)!=size) r=EINVAL;
        else r=ext4_setxattr(path,name,n,value,size);
        free(value);
    }
    if (!r && fgetc(file)!=EOF) r=EINVAL;
done:
    fclose(file); return r;
}
