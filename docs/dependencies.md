# Dependencies and native source builds

The extraction retains Commons Compress/XZ (through the current Store module),
7-Zip-JBinding-4Android and zstd runtime packaging. The original ext/lwext4 source
and its license are retained under vault-android/src/main/cpp/third_party/lwext4;
the packaged license is under src/main/assets/licenses. JNI class/load names and
CMake/Termux build paths are unchanged in semantics. No precompiled .so is added
by this extraction.

7-Zip and zstd use the existing upstream AAR packaging. F-Droid source-build and
all ABI/toolchain review remain required; native compilation was exercised only
with the available Termux ARM64 toolchain. Android-compatible parser/mount APIs
still use Android Uri and Store's current Android contracts. Desktop/iOS migration
must preserve metadata, random access and mutation/recovery semantics.
Original-code licensing is pending owner selection (LICENSE.md).
