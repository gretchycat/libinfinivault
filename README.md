# libinfinivault

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :vault-android:testDebugUnitTest` and the relevant assemble task.

Source dependencies: libinfinistream, libinfinistore, libinfinisound. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

`vault-android` owns archive readers/writers, mounted archive/image providers, nested image filesystems, compression, private mutation batches and ext/lwext4 JNI source builds. Store supplies resource access and publication services; it never depends on this module. VaultStreams exposes lazy entry SourceResources and translates common positional sources to existing parsers. Sequential decompression does not claim positional access; parser staging/index limitations remain explicit and unchanged. Mount/write callbacks, revision verification/recovery and read-only format restrictions are preserved.

JNI classes/load names, native CMake/Termux scripts and third-party notices follow the code. Desktop/iOS portability of all parsers is future work; this is the existing Android-compatible implementation. Existing 7-Zip AAR and zstd packaging require an F-Droid source-build review; no additional opaque native blobs were introduced.

The common-source entry adapter negotiates from the actual opened handle. It preserves positional access into existing parsers when available, and uses a bounded replay prefix for sequential sources. Detected container signatures select the parser even when the filename is misleading; original name/provenance and known entry warnings remain available. Positional identification has a bounded two-second probe budget and a few KiB of requested data; it never stages a large positional source just for identification. The existing Android mounted-provider compatibility path retains its format and mutation semantics during migration.

The `ArchiveReader` compatibility path also checks content when the extension is
already recognized, so a real ZIP named `.tar` reaches the ZIP reader. Detected
outer gzip/bzip2/xz/zstd/Unix-compress compression is retained in the parser hint;
compressed ZIP streams use the corresponding decompressor before the ZIP reader.
Other archive/compression combinations retain their existing parser restrictions.
This selects parsers without changing the host's displayed MIME identity or icon.
Malformed input still fails in the real reader; detection does not fabricate a
successful browse result or add write support. Remote/reopen costs and device
browse acceptance remain separate checks.

Playlist-container compatibility names now live alongside ArchiveReader in Vault and delegate to Sound's small audio-api module. Store no longer needs a media-library dependency; no playlist/CDDB algorithm or public package name changed.

## Leased ZIP resource expansion

`VaultZipExtraction.extract(File, directory, output, cancellation)` owns the
existing EPUB viewer's explicit ZIP-to-directory compatibility path. The host
supplies capacity-checked output streams (InfiniFolder uses DiskCache.Lease),
retains staging/resource leases and publishes the completed directory only after
successful parsing. The helper closes archive, entry and output handles, checks
cancellation during entry copying and reports copied files/bytes and skipped
unsafe paths. Canonical containment prevents extraction outside the destination;
unsafe entries never reach the output factory. Cache policy and publication are
not duplicated in Vault. This path still expands the complete package, and does
not claim lazy archive resource serving or portable ZIP packaging on every OS.

Six new focused cases cover ordinary/empty resources, confinement, output-capacity
failures, cancellation before/during copy and invalid archives. The current
standalone Vault suite has nine passing cases; InfiniFolder's separate full
storage suite retains its documented pre-existing failures.

## Non-extracting streaming views

`ArchiveStorageProvider.openStreamingSeekable(ref)` returns only an existing
native entry view (for example stored ZIP or a filesystem/partition slice). It
returns Unsupported when that would require entry extraction, including nested
parents without native views. The legacy `openSeekable` extraction fallback is
retained for existing callers. Video uses native views where available and
reopenable entry streams for compressed ZIP input. Outer-container and other
format-specific caching limits remain unchanged.
