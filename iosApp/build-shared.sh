#!/bin/sh
# Builds the Kotlin `Shared` framework for the build Xcode is doing, and puts it where the
# extensions and the watch look: shared/build/XCFrameworks/current. Debug builds get the debug
# framework, Release builds (archives) the optimised one.
#
# Xcode's scheme pre-actions run this with CONFIGURATION set. Run it by hand for a debug build, or
# with SHARED_CONFIGURATION=Release. Any arguments go to Gradle.
set -eu

config="${SHARED_CONFIGURATION:-${CONFIGURATION:-Debug}}"
case "$config" in
  Debug | Release) ;;
  *) echo "build-shared.sh: unknown configuration '$config' (Debug or Release)" >&2; exit 1 ;;
esac
folder="$(echo "$config" | tr '[:upper:]' '[:lower:]')"

cd "$(dirname "$0")/.."
./gradlew "$@" ":shared:assembleShared${config}XCFramework"

# rsync keeps unchanged files' dates, so Xcode only relinks when the framework really changed.
mkdir -p shared/build/XCFrameworks/current
rsync -a --delete "shared/build/XCFrameworks/$folder/Shared.xcframework/" shared/build/XCFrameworks/current/Shared.xcframework/
echo "Shared.xcframework ($config) is in shared/build/XCFrameworks/current"
