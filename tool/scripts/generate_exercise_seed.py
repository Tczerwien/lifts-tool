#!/usr/bin/env python3
"""Generate ExerciseSeedData.kt from the free-exercise-db dataset.

Reads dist/exercises.json from https://github.com/yuhonas/free-exercise-db
(public domain, Unlicense), drops the `images` field, keeps only the text
fields, minifies the JSON, and emits a Kotlin object holding the payload as
chunked string constants (each <= CHUNK_LIMIT UTF-8 bytes, well under the JVM
constant-pool cap of 65535 bytes).

Usage:
    curl -sL -o /tmp/exercises.json \
        https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
    python3 tool/scripts/generate_exercise_seed.py /tmp/exercises.json

Stdlib only. Output path defaults to
tool/src/main/kotlin/com/tczerwien/lifts/seed/ExerciseSeedData.kt
(resolved relative to this script's location).
"""

import json
import os
import sys

FIELDS = [
    "id",
    "name",
    "force",
    "level",
    "mechanic",
    "equipment",
    "primaryMuscles",
    "secondaryMuscles",
    "instructions",
    "category",
]

# Max UTF-8 bytes per emitted string constant. The JVM caps CONSTANT_Utf8
# entries at 65535 bytes; stay comfortably below.
CHUNK_LIMIT = 55000

HEADER = """\
// GENERATED FILE - DO NOT EDIT BY HAND.
//
// Bundled exercise library from free-exercise-db
// (https://github.com/yuhonas/free-exercise-db), released into the public
// domain under the Unlicense. The `images` field is dropped; only text
// fields are kept.
//
// Regenerate from the repo root with:
//   curl -sL -o /tmp/exercises.json \\
//     https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
//   python3 tool/scripts/generate_exercise_seed.py /tmp/exercises.json
package com.tczerwien.lifts.seed

/**
 * Minified JSON payload of the bundled exercise catalog ({count} records),
 * split into chunks because JVM string constants cap at 65535 UTF-8 bytes.
 */
internal object ExerciseSeedData {{
"""


def kotlin_escape(s: str) -> str:
    """Escape a raw string for embedding in a Kotlin double-quoted literal."""
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def main() -> int:
    if len(sys.argv) < 2 or len(sys.argv) > 3:
        print(__doc__, file=sys.stderr)
        return 2

    input_path = sys.argv[1]
    if len(sys.argv) == 3:
        output_path = sys.argv[2]
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        output_path = os.path.join(
            script_dir,
            "..", "src", "main", "kotlin", "com", "tczerwien", "lifts", "seed",
            "ExerciseSeedData.kt",
        )
    output_path = os.path.normpath(output_path)

    with open(input_path, encoding="utf-8") as f:
        records = json.load(f)
    if not isinstance(records, list) or not records:
        print("error: expected a non-empty JSON array of exercises", file=sys.stderr)
        return 1

    slimmed = []
    for record in records:
        slimmed.append({field: record.get(field) for field in FIELDS})

    # ensure_ascii=True: every char in the payload is ASCII (1 byte in UTF-8),
    # so chunking by characters == chunking by bytes. Non-ASCII text survives
    # as JSON \uXXXX escapes, restored when kotlinx.serialization parses.
    payload = json.dumps(slimmed, separators=(",", ":"), ensure_ascii=True)
    assert all(ord(c) < 128 for c in payload), "payload must be pure ASCII"

    chunks = [payload[i:i + CHUNK_LIMIT] for i in range(0, len(payload), CHUNK_LIMIT)]
    for chunk in chunks:
        assert len(chunk.encode("utf-8")) <= CHUNK_LIMIT

    lines = [HEADER.format(count=len(slimmed))]
    for i, chunk in enumerate(chunks):
        lines.append('    private const val CHUNK_%d = "%s"\n' % (i, kotlin_escape(chunk)))
    joined = " + ".join("CHUNK_%d" % i for i in range(len(chunks)))
    lines.append("\n    val json: String by lazy { %s }\n" % joined)
    lines.append("}\n")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(lines))

    print(
        "wrote %s: %d records, %d bytes of JSON in %d chunk(s)"
        % (output_path, len(slimmed), len(payload), len(chunks))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
