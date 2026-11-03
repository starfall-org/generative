#!/usr/bin/env bash
set -euo pipefail

GITHUB_RELEASE_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
: "${GITHUB_RELEASE_TOKEN:?Set secret GITHUB_TOKEN (or GH_TOKEN) in Codemagic environment group github_release}"
export GH_TOKEN="$GITHUB_RELEASE_TOKEN"
repo="${GITHUB_RELEASE_REPO:-starfall-org/generative}"
cd "${CM_BUILD_DIR:-${AC_REPOSITORY_DIR:-$(git rev-parse --show-toplevel)}}"
command -v gh >/dev/null || { echo 'GitHub CLI (gh) is required.' >&2; exit 1; }

# Keep the GitHub tag in sync with the version embedded in the APK.
metadata_json="${RELEASE_METADATA_JSON:-app/build/outputs/apk/release/output-metadata.json}"
version="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$metadata_json")"

# Find the branch even for detached CI checkouts. The full branch name (including
# slashes) is part of the version on every branch except main.
branch="$(git branch --show-current 2>/dev/null || true)"
if [[ -z "$branch" ]]; then
  remote_branches="$(git for-each-ref --points-at HEAD --format='%(refname:strip=3)' refs/remotes/origin | grep -vx 'HEAD' || true)"
  if grep -qx 'main' <<<"$remote_branches"; then
    branch=main
  else
    branch="$(head -n 1 <<<"$remote_branches")"
  fi
fi
[[ -n "$branch" ]] || { echo 'Cannot determine the current git branch.' >&2; exit 1; }

if [[ ! "$version" =~ ^([0-9]+\.[0-9]+\.[0-9]+)(.*)$ ]]; then
  echo "Invalid app version: $version (expected <major>.<minor>.<commit-count>[-<branch-name>])." >&2
  exit 1
fi
base_version="${BASH_REMATCH[1]}"
if [[ "$branch" == main ]]; then
  is_prerelease=false
  expected_suffix=''
else
  is_prerelease=true
  expected_suffix="-$branch"
fi
expected_version="$base_version$expected_suffix"
[[ "$version" == "$expected_version" ]] || {
  echo "App version $version does not match branch $branch (expected $expected_version)." >&2
  exit 1
}

expected_tag="$version"
tag="${GITHUB_RELEASE_TAG:-$expected_tag}"
[[ "$tag" == "$expected_tag" ]] || { echo "Configured GitHub release tag $tag does not match app version $expected_tag." >&2; exit 1; }
if [[ -n "${CM_TAG:-}" && "$CM_TAG" != "$tag" ]]; then
  echo "Build tag $CM_TAG does not match configured release tag $tag." >&2
  exit 1
fi

echo "Branch: $branch; publishing as $([[ "$is_prerelease" == true ]] && echo pre-release || echo release) ($tag)."

commit="$(git rev-parse HEAD)"
short_commit="$(git rev-parse --short=12 HEAD)"
shopt -s nullglob
apk_dir="${RELEASE_APK_DIR:-app/build/outputs/apk/release}"
# Skip unsigned APKs (AGP convention: "*unsigned*.apk") — only signed builds are published.
apks=()
for candidate in "$apk_dir"/*.apk; do
  case "$(basename "$candidate")" in
    *unsigned*) continue ;;
  esac
  apks+=("$candidate")
done
(( ${#apks[@]} > 0 )) || { echo 'Missing signed release APKs.' >&2; exit 1; }
checksums=()
for apk in "${apks[@]}"; do
  checksum="$apk.sha1"
  hash="$(shasum -a 1 "$apk" | awk '{print $1}')"
  printf '%s  %s\n' "$hash" "$(basename "$apk")" > "$checksum"
  checksums+=("$checksum")
done

if gh api "repos/$repo/git/ref/tags/$tag" >/dev/null 2>&1; then
  gh api --method PATCH "repos/$repo/git/refs/tags/$tag" \
    -f sha="$commit" -F force=true >/dev/null
else
  gh api --method POST "repos/$repo/git/refs" \
    -f ref="refs/tags/$tag" -f sha="$commit" >/dev/null
fi

# Generate the stable release's GitHub changelog against the most recent
# earlier *stable* release, not a newer/adjacent pre-release.
notes_file="$(mktemp)"
trap 'rm -f "$notes_file"' EXIT
if [[ "$is_prerelease" == true ]]; then
  # Branch builds retain the existing, stream-specific end-user notes.
  python3 "$PWD/scripts/generate-release-notes.py" \
    --tag "$tag" --repo "$repo" --output "$notes_file"
else
  previous_stable="$(
    gh api --paginate "repos/$repo/releases?per_page=100" \
      --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' |
      python3 "$PWD/scripts/previous-stable-release.py" "$tag"
  )"
  [[ -n "$previous_stable" ]] || {
    echo "No earlier published stable release found for $tag; refusing to use a pre-release as notes baseline." >&2
    exit 1
  }
  echo "Generating GitHub release notes for $tag since stable release $previous_stable."
  gh api --method POST "repos/$repo/releases/generate-notes" \
    -f tag_name="$tag" -f previous_tag_name="$previous_stable" \
    --jq .body > "$notes_file"
  [[ -s "$notes_file" ]] || {
    echo "GitHub returned empty release notes for $tag; refusing to publish empty notes." >&2
    exit 1
  }
fi

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  echo "Release $tag already exists; tag moved to $short_commit and matching artifacts will be replaced."
  gh release edit "$tag" --repo "$repo" --title "v$tag" --notes-file "$notes_file"
else
  create_flags=(--latest)
  [[ "$is_prerelease" != true ]] || create_flags=(--prerelease --latest=false)
  gh release create "$tag" --repo "$repo" \
    --title "v$tag" --notes-file "$notes_file" "${create_flags[@]}"
fi

gh release upload "$tag" "${apks[@]}" "${checksums[@]}" --repo "$repo" --clobber
if [[ "$is_prerelease" == true ]]; then
  gh release edit "$tag" --repo "$repo" --draft=false --prerelease --latest=false
else
  gh release edit "$tag" --repo "$repo" --draft=false --prerelease=false --latest
fi
gh release view "$tag" --repo "$repo" --json url --jq .url
