"""Replay one SmartMaid AutoTest record without assuming that post-landing motion is follow."""

import argparse
import json
from dataclasses import dataclass
from math import isfinite
from pathlib import Path


@dataclass(frozen=True)
class Sample:
    tick: int
    x: float
    y: float
    z: float
    on_ground: bool

    def position(self):
        return [self.x, self.y, self.z]


def contains(zone, sample):
    min_x, min_z, max_x, max_z = zone
    return min_x <= sample.x < max_x and min_z <= sample.z < max_z


def read_samples(lines, record_id):
    marker = f" AutoTest [{record_id}] record: "
    records = [line.split(marker, 1)[1].strip() for line in lines if marker in line]
    if len(records) != 1:
        raise ValueError(f"{record_id}: expected one record, found {len(records)}")

    samples = []
    for text in records[0].split(";"):
        fields = text.split(",")
        if len(fields) != 8 or fields[7] not in ("true", "false"):
            raise ValueError(f"{record_id}: malformed trajectory sample: {text!r}")
        tick = int(fields[0])
        values = tuple(map(float, fields[1:7]))
        if not all(map(isfinite, values)):
            raise ValueError(f"{record_id}: non-finite trajectory sample at tick {tick}")
        if samples and tick != samples[-1].tick + 1:
            raise ValueError(f"{record_id}: non-consecutive trajectory tick {tick}")
        samples.append(Sample(tick, *values[:3], fields[7] == "true"))
    return samples


def replay(lines, record_id, platform_y, takeoff_zone, landing_zone, *, expected_samples=None):
    if not isfinite(platform_y):
        raise ValueError("platform-y must be finite")
    for zone in (takeoff_zone, landing_zone):
        min_x, min_z, max_x, max_z = zone
        if not all(map(isfinite, zone)) or min_x >= max_x or min_z >= max_z:
            raise ValueError("zone bounds must be finite and increasing")
    samples = read_samples(lines, record_id)
    if expected_samples is not None and len(samples) != expected_samples:
        raise ValueError(f"{record_id}: expected {expected_samples} samples, found {len(samples)}")
    threshold = platform_y - 0.1
    first_below = next((s for s in samples if s.y < threshold), None)
    result = {"record": record_id, "samples": len(samples), "outbound": "invalid",
              "post_landing": "not_evaluated", "takeoff_tick": None,
              "first_landing_tick": None, "first_below_tick": first_below.tick if first_below else None,
              "first_below_position": first_below.position() if first_below else None,
              "first_landing_position": None}
    if not samples[0].on_ground:
        return result
    takeoff_index = next((i for i, s in enumerate(samples) if not s.on_ground), None)
    if takeoff_index is None:
        return result
    launch_from, launch = samples[takeoff_index - 1:takeoff_index + 1]
    result["takeoff_tick"] = launch.tick
    landing = next((s for s in samples[takeoff_index + 1:] if s.on_ground), None)
    if landing is None:
        return result

    result.update(first_landing_tick=landing.tick, first_landing_position=landing.position())
    if first_below and first_below.tick <= landing.tick:
        result["outbound"] = "below_threshold"
    elif not contains(takeoff_zone, launch_from):
        result["outbound"] = "outside_takeoff_zone"
    elif launch.y <= launch_from.y:
        result["outbound"] = "not_rising_at_takeoff"
    elif not contains(landing_zone, landing):
        result["outbound"] = "outside_landing_zone"
    else:
        result["outbound"] = "landed_in_zone"
        # No task/target is recorded per tick; this is not a verdict on owner-follow.
        result["post_landing"] = (
            "below_threshold_observed" if first_below else
            "no_below_threshold_observed" if landing.tick < samples[-1].tick else "not_observed"
        )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("record_id")
    parser.add_argument("--platform-y", type=float, required=True)
    parser.add_argument("--expected-samples", type=int, required=True, help="recordTicks in the input")
    for name in ("takeoff-zone", "landing-zone"):
        parser.add_argument(f"--{name}", type=float, nargs=4, required=True,
                            metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"),
                            help="inclusive minimum / exclusive maximum on X and Z")
    args = parser.parse_args()
    try:
        with args.log.open(encoding="utf-8-sig") as source:
            result = replay(source, args.record_id, args.platform_y, args.takeoff_zone, args.landing_zone,
                            expected_samples=args.expected_samples)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(json.dumps(result, ensure_ascii=False))
    return 2 if result["outbound"] == "invalid" or result["post_landing"] == "not_observed" else int(
        result["outbound"] != "landed_in_zone" or result["post_landing"] == "below_threshold_observed"
    )


if __name__ == "__main__":
    raise SystemExit(main())
