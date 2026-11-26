"""Emit setup-only AutoTest input for a dedicated P3 test world (never a personal save)."""

import argparse
import json


START_X = 82
START_Z = 90
ALONG = range(-8, 9)
SIDE = range(-4, 5)
CHECK_BATCH = 32
STONE = "minecraft:stone"
AIR = "minecraft:air"


def to_world(direction, along, y, side):
    if direction == "east":
        return START_X + along, y, START_Z + side
    if direction == "north":
        return START_X + side, y, START_Z - along
    raise ValueError(f"unknown course direction: {direction}")


def xyz(pos):
    return " ".join(map(str, pos))


def fixture(direction, trial):
    prefix = f"p3.course.{direction}.{trial}"
    entries = []

    def fill(name, a, b, block):
        p, q = (to_world(direction, *pos) for pos in (a, b))
        lo, hi = tuple(map(min, p, q)), tuple(map(max, p, q))
        # An identical reset may report no changed blocks. Verify the result independently.
        entries.append({"id": f"{prefix}.{name}", "run": f"fill {xyz(lo)} {xyz(hi)} {block}"})

    fill("floor", (-8, 64, -4), (8, 65, 4), STONE)
    fill("clear", (-8, 66, -4), (8, 69, 4), AIR)
    fill("trench", (1, 65, -1), (3, 65, 1), AIR)

    conditions = []
    for y in range(64, 70):
        for along in ALONG:
            for side in SIDE:
                in_trench = y == 65 and 1 <= along <= 3 and abs(side) <= 1
                block = AIR if y >= 66 or in_trench else STONE
                conditions.append(f"if block {xyz(to_world(direction, along, y, side))} {block}")
    for i in range(0, len(conditions), CHECK_BATCH):
        batch = conditions[i:i + CHECK_BATCH]
        # Every condition is part of one execute chain; the final run terminates it.
        audit = "execute " + " ".join(batch) + " run say p3-course-audit"
        entries.append({"id": f"{prefix}.audit.{i // CHECK_BATCH + 1:02d}",
                        "run": audit, "expectResult": 1})
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("direction", choices=("east", "north"))
    args = parser.parse_args()
    entries = fixture(args.direction, 1) + fixture(args.direction, 2)
    print(json.dumps(entries, indent=2))


if __name__ == "__main__":
    main()
