# Vendored lwext4

Upstream: https://github.com/gkostka/lwext4
Commit: `58bcf89a121b72d4fb66334f1693d3b30e4cb9c5` (2026-10-26 import).
Upstream source and copyright/license notices are retained. See LICENSE and each
source's header; the complete library includes GPL-2.0-or-later modules.

InfiniFolder changes:

- Support metadata_csum_seed in inode, extent, directory, allocation and group
  checksums. Journal checksums still use the journal UUID.
- Use a dirty state without inventing a filesystem error at mount.
- Always release mount/cache/device registry state after unmount I/O failures.
- Retain transaction-start/commit failures ignored by upstream high-level APIs so
  the integration refuses publication after any failed transaction.
- Treat checksum warnings as fatal transaction failures; block further writes and
  refuse publication when corrupted metadata or journal checksums are detected.
- Replace recursive directory deletion with checked traversal, link-aware file
  unlinking and an empty-directory primitive. Avoid upstream stale-checksum inode
  reentry during directory truncation and preserve hardlinks outside deleted trees.
- Recalculate and verify the directory leaf checksum when changing a non-indexed
  directory's `..` entry during a move. Upstream updated the entry without updating
  the checksum, causing a successful move to leave the directory inconsistent.
- Initialize the complete new indexed-directory leaf block, including its empty
  inode field, before calculating its checksum. The upstream no-read buffer could
  contain old bytes, and its inode field was cleared after checksum calculation.
- Add transactional inode mode/uid/gid and precise access/modification timestamp
  import for checked transfer metadata. Preserve the inode type and validate
  timestamp representability before publication.
- Bound raw inode copies for 128-byte inode layouts, correct the inline xattr
  value base, reject malformed inline attribute areas, and replace a null-pointer
  xattr-list size calculation with sizeof. Metadata export/import includes ACLs
  and extended attributes; the Kotlin boundary validates ACL/mode consistency.

The integration uses a stricter feature whitelist than upstream. It rejects MMP,
external journals, pending orphans, fast commits, encryption, inline data, bigalloc,
unknown features and filesystems flagged as having errors. Clean orphan_file is
preserved but not used by this offline API. Only private snapshots may be mutated.
