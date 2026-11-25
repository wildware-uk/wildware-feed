#!/usr/bin/env bash
# Checks a release APK against Google Play's rules before it goes anywhere:
#   - not debuggable;
#   - targets at least MIN_TARGET_SDK (Play's minimum for new apps and updates);
#   - every 32-bit native library also comes in 64-bit;
#   - native libraries work on phones with 16 KB memory pages (Play requires it from Android 15).
# Usage: check-android-build.sh <apk> [build-tools dir]. Exits non-zero with a ::error:: line per problem.
set -euo pipefail

apk="$1"
tools="${2:-$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)}"
min_target="${MIN_TARGET_SDK:-36}"
problems=0
fail() { echo "::error::$1"; problems=1; }

badging="$("$tools/aapt2" dump badging "$apk")"

if grep -q "^application-debuggable" <<<"$badging"; then
  fail "The APK is debuggable. Play refuses debuggable builds."
fi

target="$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging")"
if [ -z "$target" ] || [ "$target" -lt "$min_target" ]; then
  fail "targetSdk is ${target:-missing}; Play needs at least $min_target."
fi

abis="$(sed -n "s/^native-code: //p" <<<"$badging" | tr -d "'")"
for pair in "armeabi-v7a arm64-v8a" "x86 x86_64"; do
  set -- $pair
  if grep -qw -- "$1" <<<"$abis" && ! grep -qw -- "$2" <<<"$abis"; then
    fail "Native code for $1 but not $2. Play needs the 64-bit version of every 32-bit library."
  fi
done

# Each native library's loadable segments must be aligned to 16 KB (0x4000) or more.
libs="$(mktemp -d)"
trap 'rm -rf "$libs"' EXIT
unzip -q -o "$apk" 'lib/*' -d "$libs" 2>/dev/null || true
while IFS= read -r so; do
  for align in $(readelf -lW "$so" | awk '$1 == "LOAD" {print $NF}'); do
    if [ $((align)) -lt 16384 ]; then
      fail "${so#"$libs"/} is aligned to $align, not 16 KB. Update the library that ships it."
      break
    fi
  done
done < <(find "$libs" -name '*.so')

# Uncompressed libraries must also sit on 16 KB boundaries inside the APK itself.
if [ -n "$abis" ] && ! "$tools/zipalign" -c -P 16 4 "$apk" >/dev/null; then
  fail "The APK's native libraries aren't 16 KB aligned in the zip (zipalign -c -P 16 failed)."
fi

[ "$problems" = 0 ] || exit 1
echo "Play checks passed: not debuggable, targetSdk $target, native code: ${abis:-none}."
