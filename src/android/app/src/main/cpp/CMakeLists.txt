cmake_minimum_required(VERSION 3.22.1)
project(minis_native CXX C)

# T283: NDK signal handler for native crashes (SIGSEGV/SIGABRT/...).
# Separate library so loadLibrary() can pull it in independently —
# the crash handler must be installed before any other native lib
# has a chance to fault.
add_library(minis_crash_handler SHARED crash_handler.cpp)

# [T-android-text-segmenter] CppJieba JNI bridge for TextSegmenter's Chinese
# path. cppjieba is header-only (no static lib to build); this version vendors
# limonp inline, so a single include root (cppjieba/include) is all it needs.
# Headers are included as "cppjieba/<Name>.hpp", so we add the parent dir.
add_library(jieba_jni SHARED jieba_jni.cpp)
target_include_directories(jieba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cppjieba/include
)

find_library(log-lib log)

# crash_handler.cpp uses _Unwind_Backtrace.
# Do NOT find_library(unwind) / -lunwind: NDK llvm prebuilt puts a *host*
# libunwind.so at toolchains/llvm/prebuilt/<host>/lib/libunwind.so.
# 1.20-linux CI failed with:
#   ld.lld: error: .../linux-x86_64/lib/libunwind.so is incompatible with aarch64linux
# scripts/build_libunwind_aarch64.sh installs aarch64 libunwind.a into the
# Android sysroot; link that archive by absolute path only.
set(_unwind_lib "")
set(_unwind_search
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libunwind.a"
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/${ANDROID_PLATFORM_LEVEL}/libunwind.a"
)
if(DEFINED CMAKE_ANDROID_NDK)
    file(GLOB _unwind_glob
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libunwind.a"
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/*/libunwind.a"
    )
    list(APPEND _unwind_search ${_unwind_glob})
endif()
foreach(_c IN LISTS _unwind_search)
    if(_c AND EXISTS "${_c}")
        set(_unwind_lib "${_c}")
        break()
    endif()
endforeach()
if(NOT _unwind_lib)
    message(FATAL_ERROR
        "aarch64 libunwind.a not found under the Android sysroot. "
        "Run scripts/build_libunwind_aarch64.sh against this NDK before assemble. "
        "NDK=${CMAKE_ANDROID_NDK} SYSROOT=${CMAKE_SYSROOT}")
endif()
message(STATUS "Linking minis_crash_handler with ${_unwind_lib}")
# LLVM libunwind.a objects may carry .llvm.dependent-libraries = pthread.
# Bionic has no libpthread (pthread is in libc); NDK 28 lld then fails with
# "unable to find library from dependent library specifier: pthread".
target_link_options(minis_crash_handler PRIVATE -Wl,--no-dependent-libraries)
target_link_libraries(minis_crash_handler ${log-lib} "${_unwind_lib}")
target_link_libraries(jieba_jni ${log-lib})

# [T-shell-stall-yes-head] SIGPIPE mask bridge for SpawnSignalMask (see the
# Kotlin object): lets ProcessBuilder children start with SIGPIPE unblocked so
# guest pipelines (busybox yes | head, busybox ip addr) die instead of
# spinning when their reader exits. No dependencies beyond log.
add_library(sigpipe_bridge SHARED sigpipe_bridge.c)
target_link_libraries(sigpipe_bridge ${log-lib})
