#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

IOS_APP_DIR="${ROOT_DIR}/app"
ANDROID_DIR="${ROOT_DIR}/app-android"
SERVER_DIR="${ROOT_DIR}/server"

APK_PATH="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
IPA_PATH="${IOS_APP_DIR}/dist/result.ipa"
AMD64_PATH="${SERVER_DIR}/dist/hopperd-linux-amd64"
ARM64_PATH="${SERVER_DIR}/dist/hopperd-linux-arm64"

IOS_TEAM_ID="A59M2YXS84"

NOTES=""

die() {
  echo "error: $*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: ./release.sh [-n|--notes TEXT] [NOTES]

Build Android APK, iOS IPA, and server binaries, then create a GitHub release.

Tag format: ios{version}/android{version}/server{version}

Options:
  -n, --notes TEXT   Release notes (prompted if omitted)
  -h, --help         Show this help
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' is required"
}

json_version() {
  local file="$1"
  [[ -f "$file" ]] || die "version file not found: $file"
  python3 -c "import json; print(json.load(open('${file}'))['version'])" \
    || die "failed to read version from $file"
}

android_version_name() {
  local gradle="${ANDROID_DIR}/app/build.gradle.kts"
  local version
  version="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$gradle" \
    | head -n1 \
    | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')"
  [[ -n "$version" ]] || die "versionName not found in $gradle"
  echo "$version"
}

ios_version_name() {
  local pbx="${IOS_APP_DIR}/Hopper.xcodeproj/project.pbxproj"
  local version
  [[ -f "$pbx" ]] || die "Xcode project not found: $pbx"
  # Hopper app target, Release configuration — not HopperExtension.
  version="$(
    awk '
      /isa = XCBuildConfiguration/ { v=""; p="" }
      /MARKETING_VERSION[[:space:]]*=/ {
        v=$0
        sub(/.*MARKETING_VERSION[[:space:]]*=[[:space:]]*/, "", v)
        sub(/;.*/, "", v)
        gsub(/[[:space:]]/, "", v)
      }
      /PRODUCT_NAME[[:space:]]*=/ {
        p=$0
        sub(/.*PRODUCT_NAME[[:space:]]*=[[:space:]]*/, "", p)
        sub(/;.*/, "", p)
        gsub(/[[:space:]]/, "", p)
      }
      /name[[:space:]]*=[[:space:]]*Release;/ && p == "Hopper" && v != "" {
        print v
        exit
      }
    ' "$pbx"
  )"
  [[ -n "$version" ]] || die "Hopper Release MARKETING_VERSION not found in $pbx"
  echo "$version"
}

require_artifact() {
  local path="$1"
  [[ -f "$path" && -s "$path" ]] || die "missing or empty artifact: $path"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        usage
        exit 0
        ;;
      -n|--notes)
        [[ $# -ge 2 ]] || die "--notes requires a value"
        NOTES="$2"
        shift 2
        ;;
      --)
        shift
        break
        ;;
      -*)
        die "unknown option: $1"
        ;;
      *)
        if [[ -n "$NOTES" ]]; then
          die "unexpected argument: $1"
        fi
        NOTES="$1"
        shift
        ;;
    esac
  done

  if [[ $# -gt 0 ]]; then
    if [[ -n "$NOTES" ]]; then
      die "unexpected argument: $1"
    fi
    NOTES="$*"
  fi
}

prompt_notes() {
  if [[ -n "$NOTES" ]]; then
    return 0
  fi
  if [[ ! -t 0 ]]; then
    die "release notes required (pass -n/--notes or a positional argument)"
  fi
  echo "Enter release notes (end with Ctrl-D):"
  NOTES="$(cat)"
  [[ -n "${NOTES//[[:space:]]/}" ]] || die "release notes cannot be empty"
}

build_android() {
  echo "==> Building Android APK"
  "${ANDROID_DIR}/build-apk.sh"
  require_artifact "$APK_PATH"
  echo "OK: $APK_PATH"
  echo
}

build_ios() {
  local archive_path export_dir export_plist exported
  archive_path="${IOS_APP_DIR}/build/Hopper.xcarchive"
  export_dir="${IOS_APP_DIR}/dist"
  export_plist="$(mktemp "${TMPDIR:-/tmp}/hopper-export.XXXXXX")"

  echo "==> Building iOS IPA"
  mkdir -p "${IOS_APP_DIR}/build" "$export_dir"
  rm -rf "$archive_path"
  rm -f "${export_dir}"/*.ipa


  cat >"$export_plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>development</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>teamID</key>
	<string>${IOS_TEAM_ID}</string>
	<key>compileBitcode</key>
	<false/>
	<key>stripSwiftSymbols</key>
	<true/>
</dict>
</plist>
EOF

  (
    cd "$IOS_APP_DIR"
    xcodebuild \
      -project Hopper.xcodeproj \
      -scheme Hopper \
      -configuration Release \
      -destination 'generic/platform=iOS' \
      -derivedDataPath "${IOS_APP_DIR}/derivedData" \
      -archivePath "$archive_path" \
      -allowProvisioningUpdates \
      archive

    xcodebuild \
      -exportArchive \
      -archivePath "$archive_path" \
      -exportPath "$export_dir" \
      -exportOptionsPlist "$export_plist" \
      -allowProvisioningUpdates
  )

  rm -f "$export_plist"

  exported="$(find "$export_dir" -maxdepth 1 -name '*.ipa' -type f | head -n1)"
  [[ -n "$exported" ]] || die "IPA export finished but no .ipa found in $export_dir"
  if [[ "$exported" != "$IPA_PATH" ]]; then
    mv -f "$exported" "$IPA_PATH"
  fi
  require_artifact "$IPA_PATH"
  echo "OK: $IPA_PATH"
  echo
}

build_server() {
  echo "==> Building server binaries"
  "${SERVER_DIR}/build_dist.sh"
  require_artifact "$AMD64_PATH"
  require_artifact "$ARM64_PATH"
  echo "OK: $AMD64_PATH"
  echo "OK: $ARM64_PATH"
  echo
}

create_github_release() {
  local tag="$1"
  echo "==> Creating GitHub release: ${tag}"
  require_cmd gh
  gh auth status >/dev/null 2>&1 || die "gh is not authenticated (run: gh auth login)"

  if gh release view "$tag" >/dev/null 2>&1; then
    die "release already exists for tag: $tag"
  fi

  gh release create "$tag" \
    --title "$tag" \
    --notes "$NOTES" \
    "$APK_PATH" \
    "$AMD64_PATH" \
    "$ARM64_PATH" \
    "$IPA_PATH"

  echo
  echo "Release created:"
  gh release view "$tag" --json url -q .url
}

main() {
  parse_args "$@"
  prompt_notes

  require_cmd python3
  require_cmd xcodebuild
  require_cmd gh

  local ios_ver android_ver server_ver tag
  ios_ver="$(ios_version_name)"
  android_ver="$(android_version_name)"
  server_ver="$(json_version "${SERVER_DIR}/VERSION.json")"
  tag="ios${ios_ver}/android${android_ver}/server${server_ver}"

  echo "Release tag: ${tag}"
  echo

  build_android
  build_ios
  build_server

  require_artifact "$APK_PATH"
  require_artifact "$IPA_PATH"
  require_artifact "$AMD64_PATH"
  require_artifact "$ARM64_PATH"

  create_github_release "$tag"
}

main "$@"
