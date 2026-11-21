#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Use environment variables first; prompt only for values that are missing.
KEYSTORE_PATH="${KEYSTORE_PATH:-$ROOT_DIR/generative-release.jks}"
if [[ "$KEYSTORE_PATH" != /* ]]; then
  KEYSTORE_PATH="$ROOT_DIR/$KEYSTORE_PATH"
fi

TTY_FD=""
open_tty() {
  [[ -n "$TTY_FD" ]] && return 0
  if ! { exec {TTY_FD}<>/dev/tty; } 2>/dev/null; then
    echo "ERROR: A required signing variable is unset and no interactive terminal is available. Set the missing variables in the environment." >&2
    return 1
  fi
}

prompt_if_missing() {
  local variable="$1"
  local prompt="$2"
  local secret="${3:-false}"

  [[ -n "${!variable:-}" ]] && return 0
  open_tty || return 1

  printf '%s' "$prompt" >&2
  if [[ "$secret" == "true" ]]; then
    if ! IFS= read -r -s -u "$TTY_FD" "$variable"; then
      printf '\n' >&2
      echo "ERROR: Could not read $variable from the terminal." >&2
      return 1
    fi
    printf '\n' >&2
  else
    if ! IFS= read -r -u "$TTY_FD" "$variable"; then
      echo "ERROR: Could not read $variable from the terminal." >&2
      return 1
    fi
  fi
}

prompt_if_missing KEYSTORE_PASSWORD "Keystore password: " true || exit 1
prompt_if_missing KEY_ALIAS "Key alias: " || exit 1
prompt_if_missing KEY_PASSWORD "Key password: " true || exit 1

if [[ -z "$KEYSTORE_PASSWORD" || -z "$KEY_ALIAS" || -z "$KEY_PASSWORD" ]]; then
  echo "ERROR: Keystore password, key alias, and key password must not be empty." >&2
  exit 1
fi

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "ERROR: Release keystore not found: $KEYSTORE_PATH" >&2
  exit 1
fi
if [[ ! -x "$ROOT_DIR/gradlew" ]]; then
  echo "ERROR: Gradle wrapper is missing or not executable: $ROOT_DIR/gradlew" >&2
  exit 1
fi
command -v keytool >/dev/null 2>&1 || {
  echo "ERROR: keytool is required (install a JDK)." >&2
  exit 1
}

export KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD

echo "Validating keystore and alias..."
if ! keytool -list -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass:env KEYSTORE_PASSWORD >/dev/null 2>&1; then
  echo "ERROR: Cannot open keystore or alias '$KEY_ALIAS' is invalid. Check KEYSTORE_PASSWORD and KEY_ALIAS." >&2
  exit 1
fi

echo "Building signed pre-release APK (one Gradle invocation)..."
"$ROOT_DIR/gradlew" --no-daemon :app:assembleRelease

APK_DIR="$ROOT_DIR/app/build/outputs/apk/release"
shopt -s nullglob
APKS=("$APK_DIR"/*.apk)
if (( ${#APKS[@]} != 1 )); then
  echo "ERROR: Expected exactly one APK after the pre-release build, found ${#APKS[@]} in $APK_DIR" >&2
  exit 1
fi

for apk in "${APKS[@]}"; do
  case "$(basename "$apk")" in
    *unsigned*) echo "ERROR: Gradle produced an unsigned APK: $apk" >&2; exit 1 ;;
  esac
  echo "APK: $apk"
  if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --verbose "$apk"
  elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/build-tools/36.0.0/apksigner" ]]; then
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$apk"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" ]]; then
    "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --verbose "$apk"
  else
    echo "WARNING: apksigner not found; APK was built, but signature verification was skipped." >&2
  fi
done

echo "Signed pre-release APK build finished."
