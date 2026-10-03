#!/usr/bin/env bash
# Moves Lifts data on and off a device over adb. Uses `run-as`, so it only works with a
# debuggable (debug) build. Pick the device with ANDROID_SERIAL, e.g. ANDROID_SERIAL=emulator-5560.
#
#   lifts-adb.sh pull [DIR]           copy the exports and backups (what Tool Manager serves) into DIR
#   lifts-adb.sh push-restore FILE    put FILE in Restore; Home then offers to check and restore it
#   lifts-adb.sh pull-db [DIR]        copy lifts.db (+ -wal/-shm, so recent writes aren't lost) into DIR
#   lifts-adb.sh push-db FILE         stop the app and replace its database with FILE (no checks, no
#                                     pre-restore copy: for test data, never for a real log); warns
#                                     when FILE's schema is newer than the installed app can open
set -euo pipefail

PKG=com.tczerwien.lifts
ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")}"

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

# PRAGMA user_version of SQLite file $1: 4 bytes, big-endian, at offset 60 of its header. Empty when
# $1 isn't a SQLite database.
user_version() {
  [ "$(head -c 15 -- "$1" 2>/dev/null | tr -d '\0')" = "SQLite format 3" ] || return 0
  od -An -tu1 -j60 -N4 -- "$1" 2>/dev/null | awk 'NF == 4 { print $1 * 16777216 + $2 * 65536 + $3 * 256 + $4 }'
}

# The newest database version the installed Lifts can open: the highest N of its APK's
# assets/room-schemas/.../N.json. Empty when it isn't installed or its APK can't be read. Call it in
# $(...) only: the fallback's trap cleans up when that subshell ends.
app_schema() {
  local apk tmp listing quoted
  apk="$("$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
  [ -n "$apk" ] || return 0
  # The device's own zipinfo (Android 10+) lists the APK in place; else pull it (85 MB for a debug build).
  listing="$("$ADB" shell zipinfo -1 "$apk" 2>/dev/null | tr -d '\r')"
  if ! grep -q '^assets/' <<<"$listing"; then
    tmp="$(mktemp -d)" || return 0
    printf -v quoted '%q' "$tmp"
    trap "rm -rf -- $quoted" EXIT
    "$ADB" pull "$apk" "$tmp/base.apk" >/dev/null 2>&1 && listing="$(unzip -Z1 "$tmp/base.apk" 2>/dev/null)"
  fi
  sed -n 's#^assets/room-schemas/[^/]*/\([0-9][0-9]*\)\.json$#\1#p' <<<"$listing" | sort -n | tail -n 1
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
  name="$(basename -- "$file")"
  # The name goes into a shell command on the device, and Home hides dot files: keep it plain.
  if [[ ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "rename it first: letters, digits, '.', '_' and '-' only, not starting with a dot: $name" >&2
    exit 1
  fi
  # Restore's own subfolders: a file of either name would be moved into the folder and never offered.
  case "$name" in applied|rejected) echo "rename it first: '$name' is a folder in Restore" >&2; exit 1 ;; esac
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
  local version schema
  version="$(user_version "$file" || true)"
  schema="$(app_schema || true)"
  echo "$(basename -- "$file"): schema ${version:-? (not a SQLite database)}; the installed Lifts opens up to ${schema:-? (its APK could not be read)}"
  if [ "$version" = 0 ]; then
    echo "warning: user_version 0: Room never opened it, so the app will not migrate it; it opens only if its tables already match the app's newest schema (${schema:-?}), else it crashes at launch" >&2
  elif [ -n "$version" ] && [ -n "$schema" ]; then
    if [ "$version" -gt "$schema" ]; then
      echo "warning: this Lifts can't open it (Room has no downgrade) and will crash at launch; build it with fixture_db.py --schema-version $schema" >&2
    elif [ "$version" -lt "$schema" ]; then
      echo "the app migrates it to schema $schema at launch"
    fi
  fi
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
