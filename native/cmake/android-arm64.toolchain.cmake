# Toolchain shared by every OrcaMicro native build: the dependencies and the engine itself.
# It wraps the NDK toolchain and pins the ABI, API level and C++ runtime so all static
# libraries are built the same way.
#
# Environment:
#   ANDROID_NDK_ROOT  - Android NDK to build with (required)
#   ORCAMICRO_PREFIX  - install prefix of the cross-compiled dependencies (optional)

if(NOT DEFINED ENV{ANDROID_NDK_ROOT})
    message(FATAL_ERROR "Set ANDROID_NDK_ROOT to the Android NDK directory")
endif()

set(ANDROID_ABI "arm64-v8a" CACHE STRING "" FORCE)
set(ANDROID_PLATFORM "android-28" CACHE STRING "" FORCE)
set(ANDROID_STL "c++_static" CACHE STRING "" FORCE)
# Android 15+ devices may use 16 KB memory pages.
set(ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES ON CACHE BOOL "" FORCE)

include("$ENV{ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake")

# Orca's autotools recipes read TOOLCHAIN_PREFIX for --host.
set(TOOLCHAIN_PREFIX "aarch64-linux-android")

# The 2021-2024 era dependencies (OCCT 7.6, oneTBB 2021.5, CGAL 5.6) predate the NDK's
# libc++ dropping C++17-removed library features, and some trip newer clang errors.
string(APPEND CMAKE_CXX_FLAGS
    " -D_LIBCPP_ENABLE_CXX17_REMOVED_FEATURES"
    " -D_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION"
    " -D_LIBCPP_ENABLE_CXX17_REMOVED_AUTO_PTR"
    " -D_LIBCPP_ENABLE_CXX17_REMOVED_RANDOM_SHUFFLE"
    " -D_LIBCPP_ENABLE_CXX17_REMOVED_BINDERS"
    " -Wno-register"
    " -Wno-error=enum-constexpr-conversion"
    " -Wno-deprecated-declarations")

# The NDK restricts find_* to its sysroot. Our dependency prefix is added as a root
# so packages installed there are found, while host libraries in /usr never are.
if(DEFINED ENV{ORCAMICRO_PREFIX})
    list(APPEND CMAKE_FIND_ROOT_PATH "$ENV{ORCAMICRO_PREFIX}")
endif()
