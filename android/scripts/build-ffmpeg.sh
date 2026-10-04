#!/usr/bin/env bash
set -euo pipefail

# Requires Linux SDK/NDK tools; a Windows NDK cannot compile under WSL.
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to a Linux Android SDK}"
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Linux NDK r25c}"
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="${FFMPEG_BUILD_DIR:-$project_dir/.native-build/ffmpeg-kit}"
commit=d6be56d7aec286eb3c292d6b23ff07a6b70d8693

for tool in git make gcc autoconf automake libtoolize pkg-config nasm yasm gperf groff java curl; do
    command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
test -x "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
mkdir -p "$(dirname "$build_dir")" "$project_dir/app/libs"
if [[ ! -d "$build_dir/.git" ]]; then
    git clone --depth 1 --branch v6.0 https://github.com/arthenica/ffmpeg-kit.git "$build_dir"
fi
[[ "$(git -C "$build_dir" rev-parse HEAD)" == "$commit" ]] || { echo "Unexpected FFmpegKit source revision" >&2; exit 1; }
cd "$build_dir"
if [[ ! -f src/libiconv/.official-release-1.17 ]]; then
    archive="$build_dir/libiconv-1.17.tar.gz"
    curl -fL --silent --show-error https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.17.tar.gz -o "$archive"
    printf '8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313  %s\n' "$archive" | sha256sum -c -
    mkdir -p src
    if [[ -d src/libiconv ]]; then
        mv src/libiconv "src/libiconv-backup-$(date +%s)"
    fi
    tar xzf "$archive" -C src
    mv src/libiconv-1.17 src/libiconv
    touch src/libiconv/.official-release-1.17
fi
# Published libiconv includes generated configure files; skip the mutable gnulib submodule pull.
git show "$commit:scripts/android/libiconv.sh" | sed '2,5d' > scripts/android/libiconv.sh
# Limit native compile concurrency for desktop/CI hosts with constrained memory.
git show "$commit:scripts/function.sh" | sed 's/echo $(nproc)/echo 4/' > scripts/function.sh
./android.sh --enable-gpl --enable-lame --enable-android-media-codec --speed \
    --disable-arm-v7a --disable-arm-v7a-neon --disable-x86 --no-archive

# Package JNI libraries with the installed Linux SDK rather than auto-installing old SDK/NDK revisions.
sed -i 's/compileSdk 33/compileSdk 35/; s/targetSdk 33/targetSdk 35/; s/ndkVersion "22.1.7171670"/ndkVersion "25.2.9519653"/' android/ffmpeg-kit-android-lib/build.gradle
sed -i "s/gradle:8.1.0/gradle:8.7.3/" android/build.gradle
sed -i 's/gradle-8.2.1-bin/gradle-8.9-bin/' android/gradle/wrapper/gradle-wrapper.properties
mkdir -p "$ANDROID_SDK_ROOT/ndk"
if [[ ! -e "$ANDROID_SDK_ROOT/ndk/25.2.9519653" ]]; then
    ln -s "$ANDROID_NDK_ROOT" "$ANDROID_SDK_ROOT/ndk/25.2.9519653"
fi
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > android/local.properties
(cd android && ./gradlew ffmpeg-kit-android-lib:assembleRelease ffmpeg-kit-android-lib:testReleaseUnitTest)
cp android/ffmpeg-kit-android-lib/build/outputs/aar/ffmpeg-kit-release.aar "$project_dir/app/libs/ffmpeg-kit.aar"
sha256sum "$project_dir/app/libs/ffmpeg-kit.aar"
