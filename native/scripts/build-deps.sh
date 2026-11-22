#!/usr/bin/env bash
# Cross-compiles the libraries libslic3r needs for Android arm64 into $ORCAMICRO_PREFIX.
#
# Versions, checksums and patches mirror OrcaSlicer's own deps/ recipes for the pinned tag.
# GUI-only and SLA-only libraries (wxWidgets, GLEW, GLFW, OpenCSG, OpenVDB, OpenEXR, Blosc)
# are left out, as are OpenSSL and libcurl, which the engine replaces or does not need.
# zlib comes from the NDK.
#
# Each library records a stamp in $ORCAMICRO_PREFIX/.stamps once installed and is skipped
# while its recipe is unchanged, so a cached prefix makes reruns cheap.
#
# usage: build-deps.sh [library...]    (default: all, in dependency order)
source "$(dirname "$0")/common.sh"
require_ndk
[ -d "$ORCA_DIR/deps" ] || die "OrcaSlicer sources not found at $ORCA_DIR; run fetch-orca.sh first"

ORCA_DEPS="$ORCA_DIR/deps"
CC_TARGET="$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang"
CXX_TARGET="$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang++"
mkdir -p "$DOWNLOAD_DIR" "$SOURCE_DIR" "$BUILD_DIR" "$ORCAMICRO_PREFIX/.stamps"

# fetch <name> <url> <sha256>: downloads once into $DOWNLOAD_DIR and prints the file path.
fetch() {
    local name=$1 url=$2 sha=$3
    local file="$DOWNLOAD_DIR/$name-$(basename "$url")"
    if [ ! -f "$file" ] || ! echo "$sha  $file" | sha256sum --check --status; then
        log "Downloading $name"
        curl -fsSL --retry 5 --retry-delay 10 -o "$file.part" "$url"
        mv "$file.part" "$file"
        echo "$sha  $file" | sha256sum --check --status || die "Checksum mismatch for $name ($url)"
    fi
    echo "$file"
}

# unpack <archive> <name>: extracts into $SOURCE_DIR/<name> without the top-level folder.
unpack() {
    local archive=$1 dest="$SOURCE_DIR/$2" tmp="$SOURCE_DIR/$2.unpack"
    rm -rf "$dest" "$tmp"
    mkdir -p "$tmp"
    case "$archive" in
        *.zip) unzip -q "$archive" -d "$tmp" ;;
        *) tar -xf "$archive" -C "$tmp" ;;
    esac
    local entries=("$tmp"/*)
    if [ "${#entries[@]}" -eq 1 ] && [ -d "${entries[0]}" ]; then
        mv "${entries[0]}" "$dest"
        rm -rf "$tmp"
    else
        mv "$tmp" "$dest"
    fi
    echo "$dest"
}

# apply_patches <source dir> <patch...>: applies Orca's recipe patches (-p1, a/ b/ paths).
apply_patches() {
    local src=$1
    shift
    for p in "$@"; do
        log "Patching with $(basename "$p")"
        patch -d "$src" -p1 --forward --ignore-whitespace --silent < "$p"
    done
}

# cmake_build <name> <source dir> [cmake args...]: configures, builds and installs one library.
cmake_build() {
    local name=$1 src=$2
    shift 2
    local bld="$BUILD_DIR/$name"
    rm -rf "$bld"
    cmake -S "$src" -B "$bld" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$ORCAMICRO_PREFIX" \
        -DCMAKE_PREFIX_PATH="$ORCAMICRO_PREFIX" \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        "$@"
    cmake --build "$bld" -j "$JOBS"
    cmake --install "$bld"
    rm -rf "$bld"
    # Unpacked sources are disposable; sources inside the OrcaSlicer checkout are not.
    case "$src" in "$SOURCE_DIR"/*) rm -rf "$src" ;; esac
}

autotools_env() {
    export CC="$CC_TARGET" CXX="$CXX_TARGET"
    export AR="$NDK_BIN/llvm-ar" RANLIB="$NDK_BIN/llvm-ranlib" NM="$NDK_BIN/llvm-nm" STRIP="$NDK_BIN/llvm-strip"
    export CFLAGS="-O2 -DNDEBUG -fPIC -DPIC" CXXFLAGS="-O2 -DNDEBUG -fPIC -DPIC"
}

# ---- recipes -----------------------------------------------------------------------------

build_expat() {
    local src
    src=$(unpack "$(fetch expat https://github.com/libexpat/libexpat/releases/download/R_2_10_0/expat-2.10.0.tar.gz \
        5466a341f9c692ef7a958a6881311be597f8e1ce345984cf78414f54793dea4c)" expat)
    cmake_build expat "$src" -DEXPAT_SHARED_LIBS=OFF -DEXPAT_DTD=ON -DEXPAT_GE=ON \
        -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TESTS=OFF \
        -DEXPAT_BUILD_DOCS=OFF -DEXPAT_BUILD_FUZZERS=OFF -DEXPAT_BUILD_PKGCONFIG=OFF \
        -DEXPAT_ENABLE_INSTALL=ON
}

build_png() {
    local src
    src=$(unpack "$(fetch png https://github.com/glennrp/libpng/archive/refs/tags/v1.6.59.zip \
        6fd97a1e5e2ac55cec0a538a9800a351eb3844086736291844ebd12db72ba433)" png)
    cmake_build png "$src" -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_PREFIX=prusaslicer_ -DPNG_TESTS=OFF \
        -DDISABLE_DEPENDENCY_TRACKING=OFF -DPNG_ARM_NEON=off
}

build_jpeg() {
    local src
    src=$(unpack "$(fetch jpeg https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.2.0.zip \
        1b4c2cb6cba1ca5e53b9e85eeeab02154cab4b70894c96e97a9c747a26d19d63)" jpeg)
    # Typed: libjpeg-turbo's own GNUInstallDirs turns an untyped relative LIBDIR into a path
    # under the current directory, which installs libjpeg.a outside the prefix.
    cmake_build jpeg "$src" -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_JPEG8=ON -DWITH_TURBOJPEG=OFF \
        -DWITH_SIMD=OFF -DCMAKE_INSTALL_LIBDIR:PATH=lib
}

build_boost() {
    local src
    src=$(unpack "$(fetch boost https://github.com/boostorg/boost/releases/download/boost-1.84.0/boost-1.84.0.tar.gz \
        4d27e9efed0f6f152dc28db6430b9d3dfb40c0345da7342eaa5a987dde57bd95)" boost)
    cmake_build boost "$src" \
        "-DBOOST_EXCLUDE_LIBRARIES=contract;fiber;numpy;stacktrace;wave;test;cobalt;python;mpi;graph_parallel" \
        -DBOOST_LOCALE_ENABLE_ICU=OFF -DBUILD_TESTING=OFF \
        -DBOOST_IOSTREAMS_ENABLE_BZIP2=OFF -DBOOST_IOSTREAMS_ENABLE_ZSTD=OFF -DBOOST_IOSTREAMS_ENABLE_LZMA=OFF
}

build_tbb() {
    local src
    src=$(unpack "$(fetch tbb https://github.com/oneapi-src/oneTBB/archive/refs/tags/v2021.5.0.zip \
        83ea786c964a384dd72534f9854b419716f412f9d43c0be88d41874763e7bb47)" tbb)
    # oneTBB strips "-Werror" from the flags, which mangles the NDK's -Werror=format-security.
    cmake_build tbb "$src" -DANDROID_DISABLE_FORMAT_STRING_CHECKS=ON \
        -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DTBB_STRICT=OFF -DTBBMALLOC_BUILD=ON \
        -DTBB_ENABLE_IPO=OFF -DTBB_DISABLE_HWLOC_AUTOMATIC_SEARCH=ON -DCMAKE_DEBUG_POSTFIX=_debug \
        -DCMAKE_INSTALL_LIBDIR=lib
}

build_gmp() {
    local src
    src=$(unpack "$(fetch gmp https://github.com/SoftFever/OrcaSlicer_deps/releases/download/gmp-6.3.0/gmp-6.3.0.tar.xz \
        a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898)" gmp)
    apply_patches "$src" "$ORCA_DEPS/GMP/0001-GMP_GCC15.patch"
    (
        cd "$src"
        autotools_env
        ./configure --host=aarch64-linux-android --prefix="$ORCAMICRO_PREFIX" \
            --enable-static --disable-shared --enable-cxx --with-pic
        make -j "$JOBS"
        make install
    )
    rm -rf "$src"
}

build_mpfr() {
    local src
    src=$(unpack "$(fetch mpfr https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.2.tar.bz2 \
        9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b)" mpfr)
    (
        cd "$src"
        autotools_env
        ./configure --host=aarch64-linux-android --prefix="$ORCAMICRO_PREFIX" \
            --enable-static --disable-shared --with-pic --with-gmp="$ORCAMICRO_PREFIX"
        make -j "$JOBS"
        make install
    )
    rm -rf "$src"
}

build_eigen() {
    local src
    src=$(unpack "$(fetch eigen https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.zip \
        0dbb1f9e3aaad66f352c03227d8c983f6f0b49e0b07e71a7300f4abcc01aee12)" eigen)
    cmake_build eigen "$src" -DBUILD_TESTING=OFF -DEIGEN_BUILD_TESTING=OFF -DEIGEN_BUILD_DOC=OFF \
        -DEIGEN_BUILD_PKGCONFIG=OFF -DEIGEN_BUILD_BLAS=OFF -DEIGEN_BUILD_LAPACK=OFF
}

build_cgal() {
    local src
    src=$(unpack "$(fetch cgal https://github.com/CGAL/cgal/releases/download/v6.2.1/CGAL-6.2.1.zip \
        eebd737d9b7f0199647ba5c1f9f6c7a3d0651aacef9c2fd4dedc52da6c25edbe)" cgal)
    cmake_build cgal "$src" -DWITH_CGAL_Qt5=OFF -DCGAL_DISABLE_GMP=OFF
}

build_cereal() {
    local src
    src=$(unpack "$(fetch cereal https://github.com/USCiLab/cereal/archive/refs/tags/v1.3.0.zip \
        71642cb54658e98c8f07a0f0d08bf9766f1c3771496936f6014169d3726d9657)" cereal)
    cmake_build cereal "$src" -DJUST_INSTALL_CEREAL=ON -DSKIP_PERFORMANCE_COMPARISON=ON -DBUILD_TESTS=OFF
}

build_nlopt() {
    local src
    src=$(unpack "$(fetch nlopt https://github.com/stevengj/nlopt/archive/v2.5.0.tar.gz \
        c6dd7a5701fff8ad5ebb45a3dc8e757e61d52658de3918e38bab233e7fd3b4ae)" nlopt)
    cmake_build nlopt "$src" -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF \
        -DNLOPT_SWIG=OFF -DNLOPT_TESTS=OFF -DCMAKE_INSTALL_LIBDIR=lib
}

build_libnoise() {
    local src
    src=$(unpack "$(fetch libnoise https://github.com/SoftFever/Orca-deps-libnoise/archive/refs/tags/1.0.zip \
        96ffd6cc47898dd8147aab53d7d1b1911b507d9dbaecd5613ca2649468afd8b6)" libnoise)
    cmake_build libnoise "$src" -DCMAKE_INSTALL_LIBDIR=lib
}

build_draco() {
    local src
    src=$(unpack "$(fetch draco https://github.com/google/draco/archive/refs/tags/1.5.7.zip \
        27b72ba2d5ff3d0a9814ad40d4cb88f8dc89a35491c0866d952473f8f9416b77)" draco)
    cmake_build draco "$src" -DDRACO_TESTS=OFF -DDRACO_JS_GLUE=OFF -DCMAKE_INSTALL_LIBDIR=lib
}

build_slvs() {
    local src
    src=$(unpack "$(fetch slvs https://github.com/JacobStoren/SolveSpaceLib/archive/4d8704523e4bf212fadf5189f92484244f670fea.zip \
        1c4bdde9c3c6ef20ea4b50b73601de56769f2eb131b36927d7c6489f102e6c30)" slvs)
    cp "$ORCA_DEPS/SLVS/CMakeLists.txt.in" "$src/CMakeLists.txt"
    cmake_build slvs "$src" -DCMAKE_INSTALL_LIBDIR=lib
}

build_occt() {
    local src
    src=$(unpack "$(fetch occt https://github.com/Open-Cascade-SAS/OCCT/archive/refs/tags/V8_0_1.zip \
        7c033d917ee8f040c0512d289dcc5f02c148889d5bac17c3e25639accb44f0da)" occt)
    apply_patches "$src" "$ORCA_DEPS/OCCT/0001-BRepMesh-seam-pcurve-at-edge-parameter.patch"
    cmake_build occt "$src" \
        -DCMAKE_CXX_STANDARD=17 -DBUILD_LIBRARY_TYPE=Static \
        -DUSE_TK=OFF -DUSE_TBB=OFF -DUSE_FREETYPE=OFF -DUSE_FFMPEG=OFF -DUSE_VTK=OFF \
        -DUSE_FREEIMAGE=OFF -DUSE_RAPIDJSON=OFF -DUSE_OPENGL=OFF -DUSE_GLES2=OFF -DUSE_D3D=OFF \
        -DBUILD_DOC_Overview=OFF -DBUILD_MODULE_ApplicationFramework=OFF -DBUILD_MODULE_Draw=OFF \
        -DBUILD_MODULE_FoundationClasses=OFF -DBUILD_MODULE_ModelingAlgorithms=ON \
        -DBUILD_MODULE_ModelingData=OFF -DBUILD_MODULE_Visualization=OFF \
        -DINSTALL_DIR_LAYOUT=Unix -DINSTALL_DIR_BIN=bin/occt -DINSTALL_DIR_LIB=lib/occt \
        -DINSTALL_DIR_INCLUDE=include/occt -DINSTALL_DIR_CMAKE=lib/cmake/occt
}

build_opencv() {
    local src
    src=$(unpack "$(fetch opencv https://github.com/opencv/opencv/archive/refs/tags/4.14.0.tar.gz \
        ee8fb9b30eb60850431b4656447080e3737b56e45719c92b67f245950609f86e)" opencv)
    # Orca only uses OpenCV core (k-means for OBJ colours). Image codecs stay off so OpenCV's
    # bundled libpng cannot clash with ours.
    cmake_build opencv "$src" \
        -DBUILD_LIST=core,imgcodecs,imgproc,world -DBUILD_opencv_world=ON -DBUILD_opencv_highgui=OFF \
        -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF -DBUILD_opencv_apps=OFF \
        -DBUILD_JAVA=OFF -DBUILD_opencv_java=OFF -DBUILD_FAT_JAVA_LIB=OFF -DBUILD_ANDROID_PROJECTS=OFF \
        -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_ANDROID_SERVICE=OFF -DBUILD_KOTLIN_EXTENSIONS=OFF \
        -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF \
        -DBUILD_ZLIB=OFF -DBUILD_PNG=OFF -DBUILD_JPEG=OFF -DBUILD_TIFF=OFF -DBUILD_WEBP=OFF \
        -DBUILD_OPENJPEG=OFF -DBUILD_JASPER=OFF -DBUILD_OPENEXR=OFF -DBUILD_TBB=OFF -DBUILD_IPP_IW=OFF \
        -DWITH_PNG=OFF -DWITH_JPEG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENJPEG=OFF -DWITH_JASPER=OFF \
        -DWITH_OPENEXR=OFF -DWITH_AVIF=OFF -DWITH_IMGCODEC_GIF=OFF -DWITH_IMGCODEC_HDR=OFF \
        -DWITH_IMGCODEC_SUNRASTER=OFF -DWITH_IMGCODEC_PXM=OFF -DWITH_IMGCODEC_PFM=OFF \
        -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_EIGEN=OFF -DWITH_LAPACK=OFF \
        -DWITH_OPENCL=OFF -DWITH_CUDA=OFF -DWITH_FFMPEG=OFF -DWITH_GSTREAMER=OFF -DWITH_1394=OFF \
        -DWITH_GTK=OFF -DWITH_QUIRC=OFF -DWITH_ADE=OFF -DWITH_PROTOBUF=OFF -DWITH_VTK=OFF -DWITH_OPENMP=OFF \
        -DWITH_OPENVINO=OFF -DWITH_INF_ENGINE=OFF -DWITH_NGRAPH=OFF \
        -DWITH_CAROTENE=OFF -DWITH_KLEIDICV=OFF -DWITH_ANDROID_MEDIANDK=OFF -DWITH_ANDROID_NATIVE_CAMERA=OFF \
        -DCV_TRACE=OFF -DENABLE_PRECOMPILED_HEADERS=OFF -DOPENCV_GENERATE_SETUPVARS=OFF \
        -DOPENCV_INSTALL_FFMPEG_DOWNLOAD_SCRIPT=OFF \
        -DINSTALL_TESTS=OFF -DINSTALL_C_EXAMPLES=OFF -DINSTALL_PYTHON_EXAMPLES=OFF
}

build_python() {
    local zip
    zip=$(fetch python https://repo1.maven.org/maven2/com/chaquo/python/target/3.12.12-0/target-3.12.12-0-arm64-v8a.zip \
        7a4a278ec1fed0e0d0359fbec71d6d481d79a7efbc8e2090267a91046417027a)
    local tmp="$SOURCE_DIR/python"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    unzip -q "$zip" -d "$tmp"

    mkdir -p "$ORCAMICRO_PREFIX/include" "$ORCAMICRO_PREFIX/lib/python3.12/lib-dynload"
    rm -rf "$ORCAMICRO_PREFIX/include/python3.12"
    cp -r "$tmp/include/python3.12" "$ORCAMICRO_PREFIX/include/"

    local lib
    for lib in libpython3.12.so libcrypto_python.so libssl_python.so libsqlite3_python.so; do
        cp "$tmp/jniLibs/arm64-v8a/$lib" "$ORCAMICRO_PREFIX/lib/$lib"
    done

    rm -f "$ORCAMICRO_PREFIX/lib/python3.12/lib-dynload/"*.so
    local f name
    for f in "$tmp"/lib-dynload/arm64-v8a/*.so; do
        name=$(basename "$f")
        case "$name" in
            _test*|test_*|_xxtestfuzz*|xxlimited*|xxsubtype*|_ctypes_test*|_tkinter*|nis*|ossaudiodev*|spwd*) continue ;;
        esac
        cp "$f" "$ORCAMICRO_PREFIX/lib/python3.12/lib-dynload/$name"
    done
    rm -rf "$tmp"
}

ALL_DEPS=(expat png jpeg boost tbb gmp mpfr eigen cgal cereal nlopt libnoise draco slvs occt opencv python)

run_dep() {
    local name=$1 key stamp="$ORCAMICRO_PREFIX/.stamps/$1"
    declare -F "build_$name" > /dev/null || die "Unknown library: $name"
    key=$({ declare -f "build_$name"; cat "$TOOLCHAIN_FILE"; echo "$ANDROID_NDK_ROOT"; } | sha256sum | cut -c1-16)
    if [ "$(cat "$stamp" 2>/dev/null || true)" = "$key" ]; then
        log "$name: up to date"
        return
    fi
    log "Building $name"
    local started=$SECONDS
    "build_$name"
    echo "$key" > "$stamp"
    log "$name: done in $((SECONDS - started))s"
}

deps=("$@")
[ "${#deps[@]}" -gt 0 ] || deps=("${ALL_DEPS[@]}")
for dep in "${deps[@]}"; do
    run_dep "$dep"
done
log "Dependencies installed in $ORCAMICRO_PREFIX"
