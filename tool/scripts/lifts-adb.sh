#!/usr/bin/env bash
# Moves Lifts data on and off a device over adb. Uses `run-as`, so it only works with a
# debuggable (debug) build. Pick the device with ANDROID_SERIAL, e.g. ANDROID_SERIAL=emulator-5560.
#
#   lifts-adb.sh pull [DIR]           copy the exports and backups (what Tool Manager serves) into DIR
#   lifts-adb.sh push-restore FILE    put FILE in Restore; Home then offers to check and restore it
#   lifts-adb.sh pull-db [DIR]        copy lifts.db (+ -wal/-shm, so recent writes aren't lost) into DIR
#   lifts-adb.sh push-db FILE         stop the app and replace its database with FILE (no checks, no
#                                     pre-restore copy: for test data, never for a real log)
set -euo pipefail

PKG=com.tczerwien.lifts
ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")}"

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

run_as() {
  "$ADB" exec-out run-as "$PKG" "$@"
}

pull_db() {
  local dir="${1:-.}"
  mkdir -p "$dir"
  local name
  for name in lifts.db lifts.db-wal lifts.db-shm; do
    if [ "$(run_as sh -c "test -f databases/$name && echo yes" || true)" = "yes" ]; then
      run_as cat "databases/$name" >"$dir/$name"
    else
      rm -f "$dir/$name"
    fi
  done
  echo "pulled into $dir: $(cd "$dir" && ls lifts.db* 2>/dev/null | tr '\n' ' ')"
}

pull() {
  local dir="${1:-.}"
  mkdir -p "$dir"
  run_as tar -cf - -C files/shared exports backups | tar -xf - -C "$dir"
  echo "pulled into $dir:"
  (cd "$dir" && ls -l exports backups)
}

push_restore() {
  local file="${1:?push-restore needs a backup file}"
  [ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }
  local name
  name="$(basename "$file")"
  # The name goes into a shell command on the device, and Home hides dot files: keep it plain.
  if [[ ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "rename it first: letters, digits, '.', '_' and '-' only, not starting with a dot: $name" >&2
    exit 1
  fi
  # Through /data/local/tmp rather than exec-in, which returns before the remote write has finished.
  # The copy lands under a dot name and is renamed once whole, so Home never offers half a file.
  local tmp=/data/local/tmp/lifts-restore.db
  "$ADB" push "$file" "$tmp" >/dev/null
  "$ADB" shell "run-as $PKG sh -c 'umask 077; mkdir -p files/shared/restore && cp $tmp files/shared/restore/.incoming && mv files/shared/restore/.incoming \"files/shared/restore/$name\"'"
  "$ADB" shell rm -f "$tmp"
  echo "put $name in Restore; open Lifts (Home) after a few seconds to check it and restore"
}

push_db() {
  local file="${1:?push-db needs a database file}"
  [ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }
  "$ADB" shell am force-stop "$PKG"
  "$ADB" exec-in run-as "$PKG" sh -c \
    'mkdir -p databases && rm -f databases/lifts.db-wal databases/lifts.db-shm && cat > databases/lifts.db' <"$file"
  echo "pushed $file (app stopped; launch it to load the new data)"
}

case "${1:-}" in
  pull) shift; pull "$@" ;;
  push-restore) shift; push_restore "$@" ;;
  pull-db) shift; pull_db "$@" ;;
  push-db) shift; push_db "$@" ;;
  *) usage ;;
esac
