#!/usr/bin/env python3
"""Generate the bundled exercise catalog, tool/src/main/assets/exercises.json, from free-exercise-db.

Reads dist/exercises.json from https://github.com/yuhonas/free-exercise-db
(public domain, Unlicense), drops the `images` field, keeps only the text
fields and writes the list as minified, ASCII-only JSON. Lifts reads it with
lightContext.readAsset (seed/BundledCatalog.kt).

When the catalog changes, bump BundledCatalog.VERSION: installed phones then
take the new exercises in, and new facts about ones they have, keeping what is
his (renames, cues, types). App ids come from the record ids in their order
(SeedParser.slugify, "-2" for a repeat), and workouts store them: an update
must not rename or reorder records whose ids would change. SeedParserTest pins
the well-known ones.

Usage:
    curl -sL -o /tmp/exercises.json \
        https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
    python3 tool/scripts/generate_exercise_seed.py /tmp/exercises.json

Stdlib only. The output path defaults to tool/src/main/assets/exercises.json
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


def main() -> int:
    if len(sys.argv) < 2 or len(sys.argv) > 3:
        print(__doc__, file=sys.stderr)
        return 2

    input_path = sys.argv[1]
    if len(sys.argv) == 3:
        output_path = sys.argv[2]
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        output_path = os.path.join(script_dir, "..", "src", "main", "assets", "exercises.json")
    output_path = os.path.normpath(output_path)

    with open(input_path, encoding="utf-8") as f:
        records = json.load(f)
    if not isinstance(records, list) or not records:
        print("error: expected a non-empty JSON array of exercises", file=sys.stderr)
        return 1

    slimmed = [{field: record.get(field) for field in FIELDS} for record in records]

    # ensure_ascii=True: non-ASCII text survives as JSON \uXXXX escapes, restored when
    # kotlinx.serialization parses it.
    payload = json.dumps(slimmed, separators=(",", ":"), ensure_ascii=True)
    assert all(ord(c) < 128 for c in payload), "payload must be pure ASCII"

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="ascii", newline="\n") as f:
        f.write(payload)

    print("wrote %s: %d records, %d bytes" % (output_path, len(slimmed), len(payload)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
