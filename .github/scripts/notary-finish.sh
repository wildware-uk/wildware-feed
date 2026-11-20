#!/usr/bin/env bash
# One look at one Mac notarisation, for notary-status.yml and notary-sweep.yml. Asks Apple once,
# so it takes seconds, never hours:
#   accepted  With a run and artifact: checks Apple's report is for that exact .dmg. With a tag
#             too: puts it on the release (SHA256SUMS.txt first, then the .dmg), fixes the notes,
#             and clears the release's notary-pending.json.
#   pending   With a tag: writes notary-pending.json on the release and turns notary-sweep on,
#             which asks again every 15 minutes until Apple decides.
#   rejected  Apple's report is printed, notary-pending.json is cleared, and it fails.
#
#   notary-finish.sh SUBMISSION_ID [RUN_ID ARTIFACT [TAG]]
#
# Needs GH_TOKEN, GH_REPO, and ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH for asc.py.
set -euo pipefail
submission="$1"; run_id="${2:-}"; artifact="${3:-}"; tag="${4:-}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pending_asset=notary-pending.json
name=""
if [ -n "$tag" ]; then name="Wildware-Feed-${tag#v}-mac-arm64.dmg"; fi

clear_pending() {
  if [ -n "$tag" ] && gh release view "$tag" --json assets --jq '.assets[].name' | grep -qx "$pending_asset"; then
    gh release delete-asset "$tag" "$pending_asset" --yes
  fi
}

ask=(notary-wait "$submission")
if [ -n "$name" ]; then ask+=(--name "$name"); fi
rc=0
: > "$work/out"
GITHUB_OUTPUT="$work/out" python3 "$here/asc.py" "${ask[@]}" || rc=$?
status="$(sed -n 's/^status=//p' "$work/out" | tail -1)"
if [ -n "${GITHUB_OUTPUT:-}" ] && [ -n "$status" ]; then echo "status=$status" >> "$GITHUB_OUTPUT"; fi

case "$status" in
  rejected)
    clear_pending
    exit 1
    ;;
  pending)
    if [ -z "$tag" ]; then
      echo "Not decided yet. Run this again later."
      exit 0
    fi
    python3 - "$work/$pending_asset" "$submission" "$run_id" "$artifact" <<'PY'
import json, sys
path, submission, run_id, artifact = sys.argv[1:]
json.dump({"submission_id": submission, "run_id": run_id, "artifact": artifact}, open(path, "w"))
PY
    gh release upload "$tag" "$work/$pending_asset" --clobber
    gh workflow enable notary-sweep.yml
    echo "::notice::$tag waits for Apple. notary-sweep asks again every 15 minutes and adds the .dmg once it's accepted."
    exit 0
    ;;
  accepted) ;;
  *)
    # No answer from Apple, or the wrong file: whatever is waiting stays waiting.
    exit "$((rc == 0 ? 1 : rc))"
    ;;
esac

if [ -z "$run_id" ]; then
  echo "Give run_id and artifact too, to check the .dmg is the file Apple accepted (and tag, to publish it)."
  exit 0
fi

gh run download "$run_id" --name "$artifact" --dir "$work/dist"
found="$(find "$work/dist" -maxdepth 1 -name '*.dmg')"
if [ "$(printf '%s\n' "$found" | grep -c .)" != "1" ]; then
  echo "::error::Artifact $artifact of run $run_id should hold exactly one .dmg."
  exit 1
fi
dmg_name="$(basename "$found")"
if [ -n "$name" ] && [ "$dmg_name" != "$name" ]; then
  echo "::error::Artifact $artifact holds $dmg_name, not $name."
  exit 1
fi
python3 "$here/asc.py" notary-verify "$submission" --file "$found"

if [ -z "$tag" ]; then
  echo "::notice::$dmg_name is the file Apple notarised. No tag given, so nothing was published."
  exit 0
fi

# The app only offers a file it has a checksum for, so SHA256SUMS.txt goes up first and the .dmg
# after it: never a .dmg on the release that it can't check.
gh release download "$tag" --pattern SHA256SUMS.txt --dir "$work/old"
# Every other file's line as it was, then this .dmg's (replacing any earlier one).
{ awk -v f="$dmg_name" '$2 != f && $2 != "*" f' "$work/old/SHA256SUMS.txt"; (cd "$work/dist" && sha256sum "$dmg_name"); } > "$work/SHA256SUMS.txt"
cat "$work/SHA256SUMS.txt"
gh release upload "$tag" "$work/SHA256SUMS.txt" --clobber
gh release upload "$tag" "$found" --clobber

# The notes: no more "Mac version follows", and no "not notarised" for this file. Both lines as
# docs/release-notes.sh writes them.
gh release view "$tag" --json body --jq .body > "$work/notes.md"
python3 - "$work/notes.md" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
text = re.sub(r"^> \*\*Mac version follows within the hour\*\*.*(\r?\n)*", "", text, flags=re.M)
# The Mac's "not signed" line too (an older release's); the Windows one goes on ", so SmartScreen".
text = re.sub(r"^This build is (signed but \*\*not notarised\*\*|\*\*not signed\*\*\. The first time it is refused).*$",
              "This build is signed and notarised by Apple, so it opens straight away.", text, flags=re.M)
open(path, "w", encoding="utf-8").write(text)
PY
gh release edit "$tag" --notes-file "$work/notes.md"
clear_pending
echo "::notice::$dmg_name is on the $tag release, notarised by Apple."
