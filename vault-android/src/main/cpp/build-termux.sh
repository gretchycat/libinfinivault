#!/bin/sh
set -eu
native_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native_output=$1
native_java_home=$2
mkdir -p "$native_output/arm64-v8a"
# Plain C: no dependency on a Termux library or executable at runtime.
set -- "$native_src/ext_backend.c" "$native_src/ext_jni.c" "$native_src/ext_metadata.c"
for native_source in "$native_src"/third_party/lwext4/src/*.c; do
    case "$native_source" in */ext4_mkfs.c|*/ext4_mbr.c) continue;; esac
    set -- "$@" "$native_source"
done
clang -std=gnu11 -O2 -fPIC -shared -Wall -Wextra -Wno-unused-parameter \
    -DCONFIG_USE_DEFAULT_CFG=1 -DCONFIG_DEBUG_PRINTF=0 -DCONFIG_DEBUG_ASSERT=0 \
    -DCONFIG_BLOCK_DEV_CACHE_SIZE=64 -I"$native_src/third_party/lwext4/include" \
    -I"$native_java_home/include" -I"$native_java_home/include/linux" \
    "$@" -Wl,-z,max-page-size=16384 -Wl,-soname,libinfiniext.so \
    -o "$native_output/arm64-v8a/libinfiniext.so"
# The Termux compiler injects its own RUNPATH; ship only Android system deps.
patchelf --remove-rpath "$native_output/arm64-v8a/libinfiniext.so"
