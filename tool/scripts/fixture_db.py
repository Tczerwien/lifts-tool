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

Scenario "long" (--scenario long, needs --catalog-from; --years N, default 3), for measuring speed with
years of history: four templates (Upper A Mon, Lower A Tue, Upper B Thu, Lower B Fri, 17:30-19:00
with a few minutes' jitter), four workouts every week for N years, each exercise with warm-ups where
the template says so, work sets with RIR on most of them, now and then a failure or drop set, a
skipped exercise or a note. Loads climb in 6-week blocks with a deload week after each. Four custom
exercises: Cable Y-Raise (weight x reps), Hanging Knee Raise (reps only), Machine-Assisted Pull-Up
(assisted, help stored negative) and Old Cable Row (hidden, only in the first year's workouts); the
one-arm dumbbell row is per side, the squat has a cue, the incline press is renamed. A weigh-in every
day. No workout is left open.

Usage:
  python3 tool/scripts/fixture_db.py OUT.db [--now 2026-09-24T12:00] [--schema-version 1] [--catalog-from SEEDED.db]
  python3 tool/scripts/fixture_db.py OUT.db --scenario long --catalog-from SEEDED.db [--years 3]

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


# --- Scenario "long": years of four workouts a week ---

ROW = "one-arm-dumbbell-row"
PULLDOWN = "wide-grip-lat-pulldown"
PUSHDOWN = "triceps-pushdown"
LEG_CURL = "lying-leg-curls"
CALF = "standing-calf-raises"
PLANK = "plank"
OHP = "standing-military-press"
WEIGHTED_PULLUP = "weighted-pull-ups"
CURL = "dumbbell-bicep-curl"
FACE_PULL = "face-pull"
DEADLIFT = "barbell-deadlift"
HACK = "hack-squat"
LEG_EXT = "leg-extensions"
SEATED_CALF = "seated-calf-raise"
Y_RAISE = "custom-long-cable-y-raise"
KNEE_RAISE = "custom-long-hanging-knee-raise"
ASSISTED = "custom-long-machine-assisted-pull-up"
OLD_ROW = "custom-long-old-cable-row"

# (id, name, equipment, primary, secondary, tracking, hidden)
LONG_CUSTOM = [
    (Y_RAISE, "Cable Y-Raise", "cable", ["shoulders"], ["traps"], "weight_reps", 0),
    (KNEE_RAISE, "Hanging Knee Raise", "body only", ["abdominals"], [], "reps_only", 0),
    (ASSISTED, "Machine-Assisted Pull-Up", "machine", ["lats"], ["biceps"], "assisted_bodyweight", 0),
    (OLD_ROW, "Old Cable Row", "cable", ["middle back"], ["lats"], "weight_reps", 1),
]

# A slot: (exercise, warm-ups, work sets, reps (lo, hi), start load in lb (None: none; negative: help),
# weekly step in lb, rest s, target RIR). Loads are what a lifter uses at the start of the history.
LONG_TEMPLATES = [
    ("Upper A", 0, [
        (BENCH, 2, 3, (5, 8), 165, 2.5, 180, 2),
        (ROW, 0, 3, (8, 12), 55, 1.25, 90, 2),
        (INCLINE, 1, 3, (8, 12), 45, 1.25, 120, 2),
        (PULLDOWN, 0, 3, (10, 12), 120, 1.25, 90, 2),
        (LATERAL, 0, 3, (12, 15), 10, 0.5, 60, 1),
        (PUSHDOWN, 0, 3, (10, 15), 50, 1.25, 60, 1),
    ]),
    ("Lower A", 1, [
        (SQUAT, 3, 3, (5, 6), 205, 2.5, 180, 2),
        (RDL, 2, 3, (8, 10), 165, 2.5, 150, 2),
        (LEG_PRESS, 0, 3, (10, 12), 270, 5.0, 120, 2),
        (LEG_CURL, 0, 3, (10, 12), 70, 1.25, 90, 1),
        (CALF, 0, 3, (12, 15), 120, 2.5, 60, 1),
        (PLANK, 0, 2, (45, 60), None, 0, 60, None),
    ]),
    ("Upper B", 2, [
        (OHP, 2, 3, (5, 8), 95, 1.25, 150, 2),
        (WEIGHTED_PULLUP, 0, 3, (6, 8), 10, 1.25, 120, 2),
        (ASSISTED, 0, 2, (8, 12), -60, 1.25, 90, 2),
        (CURL, 0, 3, (10, 12), 25, 0.5, 60, 1),
        (FACE_PULL, 0, 3, (15, 20), 40, 0.5, 60, 1),
        (Y_RAISE, 0, 2, (12, 15), 10, 0.25, 60, 1),
    ]),
    ("Lower B", 3, [
        (DEADLIFT, 3, 1, (3, 5), 275, 5.0, 240, 2),
        (HACK, 1, 3, (8, 10), 180, 2.5, 150, 2),
        (LEG_EXT, 0, 3, (12, 15), 90, 1.25, 60, 1),
        (SEATED_CALF, 0, 3, (12, 15), 90, 1.25, 60, 1),
        (KNEE_RAISE, 0, 3, (12, 15), None, 0, 60, None),
    ]),
]

LONG_NOTES = ["Slept badly", "Felt strong today", "Gym busy, supersetted the accessories", "Left shoulder a bit off", "New PR music"]
SLOT_NOTES = ["Paused reps", "Belt on the top set", "Grip gave out", "Machine 3 was taken, used 2"]


def plate_round(lb, step=2.5):
    return round(lb / step) * step


def long_workout(w, rng, name, template_id, slots, start, block_week, year_week):
    """One finished workout from [slots]; loads for [block_week] of a 7-week block (6 build, 1 deload)."""
    workout_id = w.workout(name, start, None, template_id, rng.choice(LONG_NOTES) if rng.random() < 0.08 else None)
    clock = start
    deload = block_week == 6
    for position, (exercise, warmups, work, (lo, hi), load, step, rest, rir) in enumerate(slots):
        # The hidden custom row stands in for the one-arm row in the first year.
        if exercise == ROW and year_week < 52:
            exercise = OLD_ROW
        slot_note = rng.choice(SLOT_NOTES) if rng.random() < 0.03 else None
        cur = w.conn.execute(
            "INSERT INTO workout_exercises (workoutId, exerciseId, position, notes, restSecWork, targetRepMin, targetRepMax, targetRir)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (workout_id, exercise, position, slot_note, rest, lo if exercise != PLANK else None, hi if exercise != PLANK else None, rir),
        )
        slot_id = cur.lastrowid
        skipped = rng.random() < 0.02
        # Six weeks of building per block, then a deload; each block starts a little above the last.
        progress = year_week // 7 + min(block_week, 5)
        if load is None:
            weight = None
        elif load < 0:
            weight = min(0.0, load + step * progress)  # less help as he gets stronger
        else:
            weight = plate_round(load + step * progress * 1.0, 2.5 if load >= 45 else 1.25)
        if deload and weight is not None and weight > 0:
            weight = plate_round(weight * 0.85, 2.5 if weight >= 45 else 1.25)
        position_in_slot = 0
        for i in range(warmups):
            fraction = [0.4, 0.6, 0.8][i] if warmups == 3 else [0.5, 0.75][i] if warmups == 2 else 0.6
            clock += dt.timedelta(minutes=2, seconds=rng.randint(0, 40))
            w.conn.execute(
                "INSERT INTO sets (workoutExerciseId, position, type, weightKg, reps, completedAt) VALUES (?, ?, 'warmup', ?, ?, ?)",
                (slot_id, position_in_slot, kg(plate_round(weight * fraction)), 5, None if skipped else millis(clock)),
            )
            position_in_slot += 1
        for i in range(work):
            clock += dt.timedelta(seconds=rest + rng.randint(20, 70))
            kind = "normal"
            reps = hi - (i if not deload else 0) - rng.randint(0, 1)
            reps = max(lo - 1, reps)
            if i == work - 1 and not deload and rng.random() < 0.06:
                kind = "failure"
            if i == work - 1 and exercise in (PUSHDOWN, LATERAL, CURL) and rng.random() < 0.08:
                kind = "drop"
            duration = None
            if exercise == PLANK:
                duration, reps = 45 + 5 * min(progress, 6) + rng.randint(0, 10), None
            set_weight = None if weight is None else kg(weight)
            rpe = None
            if rir is not None and rng.random() < 0.7:
                rpe = 10.0 - max(0, rir - (1 if i == work - 1 else 0) + rng.choice([-1, 0, 0, 1]))
            w.conn.execute(
                "INSERT INTO sets (workoutExerciseId, position, type, weightKg, reps, durationSec, rpe, completedAt)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (slot_id, position_in_slot, kind, set_weight, reps, duration, rpe, None if skipped else millis(clock)),
            )
            position_in_slot += 1
    w.conn.execute("UPDATE workouts SET endedAt = ? WHERE id = ?", (millis(clock + dt.timedelta(minutes=3)), workout_id))


def long_history(conn, now, years=3):
    rng = random.Random(7)
    w = Writer(conn)
    template_ids = []
    for name, position, slots in LONG_TEMPLATES:
        cur = conn.execute("INSERT INTO templates (name, position) VALUES (?, ?)", (name, position))
        template_id = cur.lastrowid
        template_ids.append(template_id)
        for index, (exercise, warmups, work, (lo, hi), _load, _step, rest, rir) in enumerate(slots):
            hold = exercise == PLANK
            conn.execute(
                "INSERT INTO template_exercises (templateId, exerciseId, position, targetSets, restSecWork, warmupSets,"
                " repMin, repMax, targetRir) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (template_id, exercise, index, work, rest, warmups, None if hold else lo, None if hold else hi, rir),
            )
    today = now.astimezone(ZONE).date()
    this_monday = today - dt.timedelta(days=today.weekday())
    weeks = years * 52
    first_monday = this_monday - dt.timedelta(weeks=weeks)
    offsets = [0, 1, 3, 4]  # Mon, Tue, Thu, Fri
    for week in range(weeks):
        monday = first_monday + dt.timedelta(weeks=week)
        for (name, _position, slots), template_id, offset in zip(LONG_TEMPLATES, template_ids, offsets):
            day = monday + dt.timedelta(days=offset)
            start = dt.datetime.combine(day, dt.time(17, 30), ZONE) + dt.timedelta(minutes=rng.randint(0, 25))
            if start >= now:
                continue
            long_workout(w, rng, name, template_id, slots, start, week % 7, week)

    weight = 176.0
    day = first_monday
    while day <= today:
        conn.execute(
            "INSERT INTO bodyweight (epochDay, weightKg) VALUES (?, ?)",
            ((day - dt.date(1970, 1, 1)).days, kg(round(weight, 1))),
        )
        # A slow gain with daily noise, a cut each spring.
        weight += (0.012 if day.month not in (3, 4, 5) else -0.03) + rng.uniform(-0.5, 0.5) * 0.3
        day += dt.timedelta(days=1)


def long_own_data(conn, seeded):
    """The catalog and bars from [seeded], then his own: custom exercises, a rename, a cue, a per-side lift."""
    conn.execute("ATTACH DATABASE ? AS seeded", (seeded,))
    conn.execute("INSERT INTO exercises SELECT * FROM seeded.exercises")
    conn.execute("INSERT INTO bars SELECT * FROM seeded.bars")
    conn.commit()
    conn.execute("DETACH DATABASE seeded")
    conn.execute("UPDATE exercises SET name = ? WHERE id = ?", (RENAMED_INCLINE, INCLINE))
    conn.execute("UPDATE exercises SET pinnedNote = ? WHERE id = ?", (SQUAT_CUE, SQUAT))
    conn.execute("UPDATE exercises SET perSide = 1 WHERE id = ?", (ROW,))
    for exercise_id, name, equipment, primary, secondary, tracking, hidden in LONG_CUSTOM:
        conn.execute(
            "INSERT INTO exercises (id, name, equipment, primaryMuscles, secondaryMuscles, category, level, instructions,"
            " isCustom, pinnedNote, trackingType, perSide, hidden)"
            " VALUES (?, ?, ?, ?, ?, 'strength', NULL, '[]', 1, NULL, ?, 0, ?)",
            (exercise_id, name, equipment, json.dumps(primary, separators=(",", ":")),
             json.dumps(secondary, separators=(",", ":")), tracking, hidden),
        )


SCENARIOS = {"gym": gym, "long": long_history}

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
    parser.add_argument("--years", type=int, default=3, help="scenario long: years of history (default 3)")
    args = parser.parse_args()
    if args.scenario == "long" and not args.catalog_from:
        parser.error("--scenario long needs --catalog-from: its custom exercises would stop the app seeding the catalog")

    now = dt.datetime.fromisoformat(args.now).replace(tzinfo=ZONE) if args.now else dt.datetime.now(ZONE)
    if os.path.exists(args.out):
        os.remove(args.out)
    conn = sqlite3.connect(args.out)
    conn.execute("PRAGMA foreign_keys = ON")
    create_schema(conn, args.schema_version)
    if args.scenario == "long":
        long_history(conn, now, args.years)
    else:
        SCENARIOS[args.scenario](conn, now)
    conn.commit()
    if args.catalog_from:
        (long_own_data if args.scenario == "long" else own_data)(conn, args.catalog_from)
        conn.commit()
    counts = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("exercises", "workouts", "workout_exercises", "sets", "templates", "bodyweight")}
    conn.close()
    print(f"wrote {args.out}: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
