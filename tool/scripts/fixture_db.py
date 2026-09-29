#!/usr/bin/env python3
"""Build a Lifts database with realistic history, for checking changes on the emulator.

The tables come straight from the committed Room schema (room-schemas/.../<version>.json), so the
file opens in the app without a migration. The exercise catalog and default bar are left empty on
purpose: the app seeds them on first launch, which also exercises the seeding path. Exercise ids
below are the seed slugs.

Scenario "gym" (all weights entered in lb, stored in kg like the app does; times are 19:30
America/Chicago):
  - 8 weeks: Upper (Mon, Fri) and Lower (Wed).
  - Upper: bench top set and back-off sets as two slots of the same exercise, incline dumbbell
    press with 2 warm-ups, pull-ups (reps only), cable lateral raises.
  - Lower: squat and Romanian deadlift with 2 warm-ups each, leg press.
  - The most recent Upper skipped bench: both bench slots exist but no bench set was completed.
  - A stale in-progress workout from about 65 days ago that was never finished.
  - A finished workout with 0 completed sets (opened and closed by mistake).
  - Bodyweight every 3 days, drifting from about 182 lb down to 179 lb.
  - Templates "Upper A" and "Lower A".

With --catalog-from SEEDED.db (a lifts.db pulled after the app seeded its catalog on first launch), the
exercise catalog and bars are copied in, and the things he types himself are added, so every export
field has data: the incline dumbbell press renamed "Low Incline DB Press", a pinned cue on the squat,
and a custom exercise "Cable Y-Raise" (with a cue) in the Upper A template. Without it the catalog is
left empty, as before.

Usage:
  python3 tool/scripts/fixture_db.py OUT.db [--now 2026-09-24T12:00] [--schema-version 1] [--catalog-from SEEDED.db]

Then push it with: tool/scripts/lifts-adb.sh push-db OUT.db

Stdlib only.
"""

import argparse
import datetime as dt
import json
import os
import random
import sqlite3
import sys
from zoneinfo import ZoneInfo

LB_PER_KG = 2.2046226218
ZONE = ZoneInfo("America/Chicago")
SCHEMA_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "assets", "room-schemas", "com.tczerwien.lifts.data.LiftsDatabase",
)

BENCH = "barbell-bench-press-medium-grip"
INCLINE = "incline-dumbbell-press"
PULLUP = "pullups"
LATERAL = "cable-seated-lateral-raise"
SQUAT = "barbell-squat"
RDL = "romanian-deadlift"
LEG_PRESS = "leg-press"


def kg(lb):
    return lb / LB_PER_KG


def millis(moment):
    return int(moment.timestamp() * 1000)


def create_schema(conn, version):
    with open(os.path.join(SCHEMA_DIR, f"{version}.json")) as f:
        database = json.load(f)["database"]
    for entity in database["entities"]:
        table = entity["tableName"]
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            conn.execute(index["createSql"].replace("${TABLE_NAME}", table))
    for query in database["setupQueries"]:
        conn.execute(query)
    conn.execute(f"PRAGMA user_version = {version}")


class Writer:
    def __init__(self, conn):
        self.conn = conn

    def workout(self, name, started, ended, template_id=None, notes=None):
        cur = self.conn.execute(
            "INSERT INTO workouts (name, startedAt, endedAt, templateId, notes) VALUES (?, ?, ?, ?, ?)",
            (name, millis(started), millis(ended) if ended else None, template_id, notes),
        )
        return cur.lastrowid

    def slot(self, workout_id, exercise_id, position, rest_work=None, rest_warmup=None):
        cur = self.conn.execute(
            "INSERT INTO workout_exercises (workoutId, exerciseId, position, restSecWork, restSecWarmup)"
            " VALUES (?, ?, ?, ?, ?)",
            (workout_id, exercise_id, position, rest_work, rest_warmup),
        )
        return cur.lastrowid

    def sets(self, slot_id, rows, clock):
        """rows: (type, weight_lb or None, reps, completed). Returns the advanced clock."""
        for position, (kind, weight_lb, reps, completed) in enumerate(rows):
            done_at = None
            if completed:
                clock += dt.timedelta(minutes=2 if kind == "warmup" else 3, seconds=random.randint(0, 50))
                done_at = millis(clock)
            self.conn.execute(
                "INSERT INTO sets (workoutExerciseId, position, type, weightKg, reps, completedAt)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (slot_id, position, kind, kg(weight_lb) if weight_lb is not None else None, reps, done_at),
            )
        return clock

    def template(self, name, position, slots):
        cur = self.conn.execute("INSERT INTO templates (name, position) VALUES (?, ?)", (name, position))
        template_id = cur.lastrowid
        for index, (exercise_id, target_sets, rest_work) in enumerate(slots):
            self.conn.execute(
                "INSERT INTO template_exercises (templateId, exerciseId, position, targetSets, restSecWork)"
                " VALUES (?, ?, ?, ?, ?)",
                (template_id, exercise_id, index, target_sets, rest_work),
            )
        return template_id


def upper(w, day, week, template_id, skip_bench=False):
    start = dt.datetime.combine(day, dt.time(19, 30), ZONE)
    workout_id = w.workout("Upper A", start, None, template_id)
    clock = start
    top = 185 + 5 * week
    backoff = round(top * 0.85 / 5) * 5
    done = not skip_bench
    clock = w.sets(w.slot(workout_id, BENCH, 0, 180), [("normal", top, 5, done)], clock)
    clock = w.sets(w.slot(workout_id, BENCH, 1, 120), [("normal", backoff, 8, done)] * 3, clock)
    incline = 50 + 5 * (week // 2)
    clock = w.sets(
        w.slot(workout_id, INCLINE, 2),
        [("warmup", 25, 10, True), ("warmup", 35, 6, True)] + [("normal", incline, 10 - (week % 2), True)] * 3,
        clock,
    )
    clock = w.sets(w.slot(workout_id, PULLUP, 3), [("normal", None, 8 + week // 3, True)] * 3, clock)
    clock = w.sets(w.slot(workout_id, LATERAL, 4, 60), [("normal", 15, 12, True)] * 3, clock)
    w.conn.execute("UPDATE workouts SET endedAt = ? WHERE id = ?", (millis(clock + dt.timedelta(minutes=2)), workout_id))


def lower(w, day, week, template_id):
    start = dt.datetime.combine(day, dt.time(19, 30), ZONE)
    workout_id = w.workout("Lower A", start, None, template_id)
    clock = start
    squat = 225 + 5 * week
    clock = w.sets(
        w.slot(workout_id, SQUAT, 0, 180),
        [("warmup", 135, 5, True), ("warmup", 185, 3, True)] + [("normal", squat, 5, True)] * 3,
        clock,
    )
    rdl = 185 + 5 * week
    clock = w.sets(
        w.slot(workout_id, RDL, 1, 150),
        [("warmup", 95, 8, True), ("warmup", 135, 5, True)] + [("normal", rdl, 8, True)] * 3,
        clock,
    )
    last_rep = "failure" if week % 3 == 2 else "normal"
    clock = w.sets(
        w.slot(workout_id, LEG_PRESS, 2),
        [("normal", 270 + 10 * week, 10, True)] * 2 + [(last_rep, 270 + 10 * week, 9, True)],
        clock,
    )
    w.conn.execute("UPDATE workouts SET endedAt = ? WHERE id = ?", (millis(clock + dt.timedelta(minutes=2)), workout_id))


def gym(conn, now):
    random.seed(42)
    w = Writer(conn)
    upper_t = w.template("Upper A", 0, [(BENCH, 1, 180), (BENCH, 3, 120), (INCLINE, 3, None), (PULLUP, 3, None), (LATERAL, 3, 60)])
    lower_t = w.template("Lower A", 1, [(SQUAT, 3, 180), (RDL, 3, 150), (LEG_PRESS, 3, None)])

    today = now.astimezone(ZONE).date()
    this_monday = today - dt.timedelta(days=today.weekday())
    first_monday = this_monday - dt.timedelta(weeks=8)

    # Stale workout: started ~65 days ago, two sets logged, never finished.
    stale_start = dt.datetime.combine(today - dt.timedelta(days=65), dt.time(19, 30), ZONE)
    stale = w.workout("Workout", stale_start, None)
    w.sets(w.slot(stale, BENCH, 0), [("normal", 135, 8, True), ("normal", 135, 8, True), ("normal", 135, 8, False)], stale_start)

    for week in range(8):
        monday = first_monday + dt.timedelta(weeks=week)
        last_week = week == 7
        upper(w, monday, week, upper_t)
        lower(w, monday + dt.timedelta(days=2), week, lower_t)
        upper(w, monday + dt.timedelta(days=4), week, upper_t, skip_bench=last_week)

    # Opened and finished by mistake: one slot, nothing ticked.
    oops_start = dt.datetime.combine(today - dt.timedelta(days=9), dt.time(7, 0), ZONE)
    oops = w.workout("Workout", oops_start, oops_start + dt.timedelta(minutes=8))
    w.sets(w.slot(oops, LEG_PRESS, 0), [("normal", None, None, False)] * 3, oops_start)

    weight = 182.4
    day = first_monday
    while day <= today:
        conn.execute(
            "INSERT INTO bodyweight (epochDay, weightKg) VALUES (?, ?)",
            ((day - dt.date(1970, 1, 1)).days, kg(round(weight, 1))),
        )
        weight -= 0.2 + random.uniform(-0.3, 0.3)
        day += dt.timedelta(days=3)


SCENARIOS = {"gym": gym}

RENAMED_INCLINE = "Low Incline DB Press"
SQUAT_CUE = "Brace, then sit between the heels"
CUSTOM_ID = "custom-fixture-cable-y-raise"


def own_data(conn, seeded):
    """Copies the seeded catalog and bars, then adds a rename, cues and a custom exercise."""
    conn.execute("ATTACH DATABASE ? AS seeded", (seeded,))
    conn.execute("INSERT INTO exercises SELECT * FROM seeded.exercises")
    conn.execute("INSERT INTO bars SELECT * FROM seeded.bars")
    conn.commit()
    conn.execute("DETACH DATABASE seeded")
    conn.execute("UPDATE exercises SET name = ? WHERE id = ?", (RENAMED_INCLINE, INCLINE))
    conn.execute("UPDATE exercises SET pinnedNote = ? WHERE id = ?", (SQUAT_CUE, SQUAT))
    conn.execute(
        "INSERT INTO exercises (id, name, equipment, primaryMuscles, secondaryMuscles, category, level, instructions,"
        " isCustom, pinnedNote, trackingType, perSide, hidden)"
        " VALUES (?, 'Cable Y-Raise', 'cable', ?, ?, 'strength', NULL, '[]', 1, 'Thumbs up', 'weight_reps', 0, 0)",
        (CUSTOM_ID, json.dumps(["shoulders"], separators=(",", ":")), json.dumps(["traps"], separators=(",", ":"))),
    )
    upper = conn.execute("SELECT id FROM templates WHERE name = 'Upper A'").fetchone()[0]
    position = conn.execute("SELECT COUNT(*) FROM template_exercises WHERE templateId = ?", (upper,)).fetchone()[0]
    conn.execute(
        "INSERT INTO template_exercises (templateId, exerciseId, position, targetSets, restSecWork) VALUES (?, ?, ?, 2, 60)",
        (upper, CUSTOM_ID, position),
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("out")
    parser.add_argument("--scenario", default="gym", choices=sorted(SCENARIOS))
    parser.add_argument("--now", help="local time in America/Chicago, e.g. 2026-09-24T12:00 (default: now)")
    parser.add_argument("--schema-version", type=int, default=1)
    parser.add_argument("--catalog-from", help="a pulled, seeded lifts.db: copy its catalog and add his own data")
    args = parser.parse_args()

    now = dt.datetime.fromisoformat(args.now).replace(tzinfo=ZONE) if args.now else dt.datetime.now(ZONE)
    if os.path.exists(args.out):
        os.remove(args.out)
    conn = sqlite3.connect(args.out)
    conn.execute("PRAGMA foreign_keys = ON")
    create_schema(conn, args.schema_version)
    SCENARIOS[args.scenario](conn, now)
    conn.commit()
    if args.catalog_from:
        own_data(conn, args.catalog_from)
        conn.commit()
    counts = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("exercises", "workouts", "workout_exercises", "sets", "templates", "bodyweight")}
    conn.close()
    print(f"wrote {args.out}: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
