"""Replay one SmartMaid AutoTest ``recordCombat`` window from a game log.

The input is a copy of ``run/logs/latest.log`` produced by
``AutoTest [<id>] combat: <csv>;...`` (see the C1 contract).  This tool is
read-only: it never edits the log, the world or the fixture JSON.

Exit codes
----------
0   Data is complete and every explicitly supplied expectation holds.
1   Data is complete but an explicitly supplied expectation failed.
2   Evidence is missing/insufficient (no matching record, missing columns,
    fewer than two samples, non-consecutive game ticks, no ``tickCalls``
    increase, or an expectation cannot be evaluated).

Usage
-----
    python -B tools/combat_replay.py run/logs/latest.log --run-id CF-01 \
        --expect-dig-attempts-max 0 --expect-place-attempts-max 0

The output is JSON on stdout; ``--json-out`` also writes the same object.
Missing values are reported as ``null`` -- never silently replaced with zero.
"""

import argparse
import json
import re
from dataclasses import dataclass
from math import isfinite
from pathlib import Path

COLUMNS = (
    "tick", "gameTick", "maidId", "targetId", "maidTargetId", "engaged",
    "mode", "cooldown", "lostTicks", "eatRetreatTicks", "blockStartTick",
    "blockHoldTicks", "tickCalls", "health", "maxHealth", "food",
    "usingItem", "useItemId", "blocking", "x", "y", "z",
    "digAttempts", "placeAttempts",
)
INT_COLUMNS = frozenset({
    "tick", "gameTick", "maidId", "targetId", "maidTargetId", "engaged",
    "cooldown", "lostTicks", "eatRetreatTicks", "blockStartTick",
    "blockHoldTicks", "tickCalls", "food", "usingItem", "blocking",
    "digAttempts", "placeAttempts",
})
FLOAT_COLUMNS = frozenset({"health", "maxHealth", "x", "y", "z"})
FLAG_COLUMNS = ("engaged", "usingItem", "blocking")
FIELD_ATTRS = {
    "tick": "tick",
    "gameTick": "game_tick",
    "maidId": "maid_id",
    "targetId": "target_id",
    "maidTargetId": "maid_target_id",
    "engaged": "engaged",
    "mode": "mode",
    "cooldown": "cooldown",
    "lostTicks": "lost_ticks",
    "eatRetreatTicks": "eat_retreat_ticks",
    "blockStartTick": "block_start_tick",
    "blockHoldTicks": "block_hold_ticks",
    "tickCalls": "tick_calls",
    "health": "health",
    "maxHealth": "max_health",
    "food": "food",
    "usingItem": "using_item",
    "useItemId": "use_item_id",
    "blocking": "blocking",
    "x": "x",
    "y": "y",
    "z": "z",
    "digAttempts": "dig_attempts",
    "placeAttempts": "place_attempts",
}

COMBAT_RE = re.compile(r"AutoTest \[(?P<id>[^\]]+)\] combat: (?P<payload>.*)$")
DEBUG_RE = re.compile(r"\[SmartMaid-Debug\]\s?(?P<message>.*)$")

MELEE_MIN_COOLDOWN = 14  # DEVELOPMENT_COMBAT.md §5: clamp(..., 14, 26)


class EvidenceError(ValueError):
    """The log does not contain enough trustworthy data for a verdict."""


@dataclass(frozen=True)
class CombatSample:
    tick: int
    game_tick: int
    maid_id: int
    target_id: int
    maid_target_id: int
    engaged: int
    mode: str
    cooldown: int
    lost_ticks: int
    eat_retreat_ticks: int
    block_start_tick: int
    block_hold_ticks: int
    tick_calls: int
    health: float
    max_health: float
    food: int
    using_item: int
    use_item_id: str
    blocking: int
    x: float
    y: float
    z: float
    dig_attempts: int
    place_attempts: int


def _parse_int(value, column, sample_index):
    try:
        return int(value)
    except (TypeError, ValueError):
        raise EvidenceError(
            f"sample {sample_index}: column {column!r} is not an integer: {value!r}"
        ) from None


def _parse_float(value, column, sample_index):
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise EvidenceError(
            f"sample {sample_index}: column {column!r} is not a number: {value!r}"
        ) from None
    if not isfinite(number):
        raise EvidenceError(f"sample {sample_index}: column {column!r} is not finite: {value!r}")
    return number


def parse_samples(payload):
    """Parse the semicolon-separated CSV payload into CombatSample objects."""
    text = payload.strip()
    if not text:
        raise EvidenceError("combat record payload is empty")
    if text.endswith(";"):
        text = text[:-1]
    rows = text.split(";")
    if any(not row.strip() for row in rows):
        raise EvidenceError("combat record contains an empty sample")

    samples = []
    for index, row in enumerate(rows):
        fields = [field.strip() for field in row.split(",")]
        if len(fields) != len(COLUMNS):
            raise EvidenceError(
                f"sample {index}: expected {len(COLUMNS)} columns, found {len(fields)}"
            )
        raw = dict(zip(COLUMNS, fields))
        values = {}
        for column in COLUMNS:
            if column in INT_COLUMNS:
                values[column] = _parse_int(raw[column], column, index)
            elif column in FLOAT_COLUMNS:
                values[column] = _parse_float(raw[column], column, index)
            else:
                values[column] = raw[column]
        for column in FLAG_COLUMNS:
            if values[column] not in (0, 1):
                raise EvidenceError(
                    f"sample {index}: column {column!r} must be 0 or 1, got {values[column]!r}"
                )
        if not values["mode"]:
            raise EvidenceError(f"sample {index}: column 'mode' is empty")
        if values["maxHealth"] <= 0:
            raise EvidenceError(f"sample {index}: maxHealth must be positive")
        for column in ("digAttempts", "placeAttempts", "cooldown", "lostTicks", "eatRetreatTicks"):
            if values[column] < 0:
                raise EvidenceError(
                    f"sample {index}: column {column!r} must be non-negative, got {values[column]}"
                )
        samples.append(CombatSample(**{FIELD_ATTRS[column]: value for column, value in values.items()}))

    if len(samples) < 2:
        raise EvidenceError(f"at least two samples are required, found {len(samples)}")
    for previous, current in zip(samples, samples[1:]):
        if current.game_tick != previous.game_tick + 1:
            raise EvidenceError(
                "non-consecutive gameTick in combat record: "
                f"{previous.game_tick} -> {current.game_tick}"
            )
        if current.tick_calls < previous.tick_calls:
            raise EvidenceError(
                "tickCalls decreased in combat record: "
                f"{previous.tick_calls} -> {current.tick_calls}"
            )
    if samples[-1].tick_calls <= samples[0].tick_calls:
        raise EvidenceError(
            "tickCalls did not increase across the combat record "
            f"({samples[0].tick_calls} -> {samples[-1].tick_calls})"
        )
    return samples


def find_combat_records(lines, run_id):
    """Return [(record_id, payload)] for AutoTest combat lines whose id starts with run_id."""
    records = []
    for raw_line in lines:
        match = COMBAT_RE.search(raw_line.rstrip("\r\n"))
        if match and match.group("id").startswith(run_id):
            records.append((match.group("id"), match.group("payload")))
    return records


def parse_debug_events(lines):
    """Collect the documented [SmartMaid-Debug] combat events when present."""
    events = []
    for raw_line in lines:
        match = DEBUG_RE.search(raw_line.rstrip("\r\n"))
        if not match:
            continue
        message = match.group("message").strip()
        event = {"kind": None, "detail": message}
        if message.startswith("Combat start target="):
            event["kind"] = "start"
            event["target"] = message[len("Combat start target="):]
        elif message.startswith("Combat stop reason="):
            event["kind"] = "stop"
            event["reason"] = message[len("Combat stop reason="):]
        elif message.startswith("Combat target="):
            event["kind"] = "target"
        elif message.startswith("Combat mode="):
            event["kind"] = "mode"
            match_mode = re.match(r"Combat mode=([A-Za-z_]+)", message)
            event["mode"] = match_mode.group(1) if match_mode else None
        elif message.startswith("Combat melee attack"):
            event["kind"] = "melee"
            match_cd = re.search(r"cd=(-?\d+)", message)
            event["cooldown"] = int(match_cd.group(1)) if match_cd else None
        elif message.startswith("Combat draw bow"):
            event["kind"] = "draw_bow"
        elif message.startswith("Combat eat start"):
            event["kind"] = "eat_start"
        elif message.startswith("Combat block 举盾"):
            event["kind"] = "block_start"
            match_tick = re.search(r"(?:^|\s)t=(-?\d+)", message)
            event["tick"] = int(match_tick.group(1)) if match_tick else None
        elif message.startswith("Combat block end 收盾"):
            event["kind"] = "block_end"
            match_hold = re.search(r"holdTicks=(-?\d+)", message)
            event["hold_ticks"] = int(match_hold.group(1)) if match_hold else None
            match_tick = re.search(r"(?:^|\s)t=(-?\d+)", message)
            event["tick"] = int(match_tick.group(1)) if match_tick else None
        else:
            continue
        events.append(event)
    return events


def _dedup_consecutive(values):
    result = []
    for value in values:
        if not result or result[-1] != value:
            result.append(value)
    return result


def _count_changes(values):
    return sum(1 for previous, current in zip(values, values[1:]) if current != previous)


def _attempt_delta(samples, attribute):
    """Count positive increments of a cumulative counter over the window.

    A reset to zero inside the window is tolerated: increments before and
    after the reset both count.  The first sample is the baseline and is not
    counted as an attempt.
    """
    values = [getattr(sample, attribute) for sample in samples]
    return sum(max(0, current - previous) for previous, current in zip(values, values[1:]))


def _block_runs(samples):
    runs = []
    current = 0
    for sample in samples:
        if sample.blocking:
            current += 1
        elif current:
            runs.append(current)
            current = 0
    if current:
        runs.append(current)
    return runs


def _sample_melee_attack_indices(samples):
    """Infer melee attacks from a cooldown reset while in the MELEE branch."""
    indices = []
    for index in range(1, len(samples)):
        previous, current = samples[index - 1], samples[index]
        if (
            current.mode == "MELEE"
            and current.cooldown >= MELEE_MIN_COOLDOWN
            and current.cooldown > previous.cooldown
        ):
            indices.append(index)
    return indices


def _debug_melee_attack_indices(samples, debug_events):
    """Match ``Combat melee attack cd=N`` events to cooldown-reset samples.

    A cooldown value alone repeats across consecutive samples (14, 14, 13...),
    so match only the first sample of a reset (previous cooldown below the
    melee minimum, current at/above it).  This keeps event matching from
    collapsing two attacks onto the same cooldown plateau.
    """
    indices = []
    search_from = 0
    for event in debug_events:
        if event["kind"] != "melee" or event.get("cooldown") is None:
            continue
        for index in range(max(1, search_from), len(samples)):
            sample = samples[index]
            previous = samples[index - 1]
            if (
                sample.mode == "MELEE"
                and sample.cooldown == event["cooldown"]
                and sample.cooldown >= MELEE_MIN_COOLDOWN
                and previous.cooldown < MELEE_MIN_COOLDOWN
            ):
                indices.append(index)
                search_from = index + 1
                break
    return indices


def _melee_attack_intervals(samples, debug_events):
    debug_indices = _debug_melee_attack_indices(samples, debug_events)
    indices = debug_indices if len(debug_indices) >= 2 else _sample_melee_attack_indices(samples)
    return [
        samples[current].game_tick - samples[previous].game_tick
        for previous, current in zip(indices, indices[1:])
    ]


def analyze_samples(samples, debug_events=None):
    """Compute the replay metrics from parsed samples and optional debug events."""
    debug_events = debug_events or []
    durations = samples[-1].game_tick - samples[0].game_tick
    goal_calls = samples[-1].tick_calls - samples[0].tick_calls
    if durations <= 0:
        raise EvidenceError("durationGameTicks must be positive")

    increase_ticks = [
        samples[index].game_tick
        for index in range(1, len(samples))
        if samples[index].tick_calls > samples[index - 1].tick_calls
    ]
    max_gap = (
        max(current - previous for previous, current in zip(increase_ticks, increase_ticks[1:]))
        if len(increase_ticks) >= 2
        else None
    )

    melee_intervals = _melee_attack_intervals(samples, debug_events)

    block_holds_from_debug = [
        event["hold_ticks"]
        for event in debug_events
        if event["kind"] == "block_end" and event.get("hold_ticks") is not None
    ]
    block_holds = block_holds_from_debug or _block_runs(samples)

    ranged_draw_ticks = 0
    eat_ticks = 0
    for sample in samples:
        if not sample.using_item or sample.blocking:
            continue
        item = sample.use_item_id.lower()
        if "bow" in item:
            ranged_draw_ticks += 1
        elif item and item != "empty":
            eat_ticks += 1

    debug_summary = {
        "combatStarts": sum(1 for event in debug_events if event["kind"] == "start"),
        "combatStops": sum(1 for event in debug_events if event["kind"] == "stop"),
        "stopReasons": [event["reason"] for event in debug_events if event["kind"] == "stop"],
        "modeEvents": [event["mode"] for event in debug_events if event["kind"] == "mode"],
        "meleeAttackEvents": sum(1 for event in debug_events if event["kind"] == "melee"),
        "drawBowEvents": sum(1 for event in debug_events if event["kind"] == "draw_bow"),
        "eatStartEvents": sum(1 for event in debug_events if event["kind"] == "eat_start"),
        "blockStarts": sum(1 for event in debug_events if event["kind"] == "block_start"),
        "blockEnds": sum(1 for event in debug_events if event["kind"] == "block_end"),
    }

    return {
        "samples": len(samples),
        "durationGameTicks": durations,
        "engagedSamples": sum(sample.engaged for sample in samples),
        "goalCalls": goal_calls,
        "goalCallsPerGameTick": round(goal_calls / durations, 6),
        "maxGoalCallGapTicks": max_gap,
        "modeSequence": _dedup_consecutive([sample.mode for sample in samples]),
        "targetIdChanges": _count_changes([sample.target_id for sample in samples]),
        "maidTargetIdChanges": _count_changes([sample.maid_target_id for sample in samples]),
        "blockingTicks": sum(sample.blocking for sample in samples),
        "digAttempts": _attempt_delta(samples, "dig_attempts"),
        "placeAttempts": _attempt_delta(samples, "place_attempts"),
        "meleeAttackIntervals": melee_intervals,
        "rangedDrawTicks": ranged_draw_ticks,
        "eatTicks": eat_ticks,
        "blockHoldTicksList": block_holds,
        "debug": debug_summary,
    }


def build_expectations(args):
    """Build (metric, mode, threshold, label) tuples from parsed CLI arguments."""
    configured = (
        ("goalCallsPerGameTick", "max", args.expect_goal_calls_per_tick_max,
         "--expect-goal-calls-per-tick-max"),
        ("maxGoalCallGapTicks", "max", args.expect_max_goal_gap_ticks,
         "--expect-max-goal-gap-ticks"),
        ("targetIdChanges", "max", args.expect_target_changes_max,
         "--expect-target-changes-max"),
        ("digAttempts", "max", args.expect_dig_attempts_max,
         "--expect-dig-attempts-max"),
        ("placeAttempts", "max", args.expect_place_attempts_max,
         "--expect-place-attempts-max"),
        ("blockingTicks", "min", args.expect_blocking_ticks_min,
         "--expect-blocking-ticks-min"),
    )
    expectations = []
    for metric, mode, values, label in configured:
        for value in values or []:
            expectations.append((metric, mode, value, label))
    return expectations


def evaluate_expectations(metrics, expectations):
    results = []
    for metric, mode, threshold, label in expectations:
        actual = metrics.get(metric)
        if actual is None:
            raise EvidenceError(
                f"cannot evaluate {label}: metric {metric!r} is unavailable from this record"
            )
        ok = actual <= threshold if mode == "max" else actual >= threshold
        results.append({
            "option": label,
            "metric": metric,
            "mode": mode,
            "threshold": threshold,
            "actual": actual,
            "ok": ok,
        })
    return results


def _record_debug_window(lines, record_id):
    """Return (start, end) line indices for debug events emitted during one record.

    ``recordCombat`` logs a ``run`` line when the record starts and a ``combat:``
    line when it finishes.  Debug events for that window therefore lie between
    those two lines.  Keeping the window bounded is essential: a log contains
    many earlier AutoTest scenarios, and their debug counters must not leak into
    the current record.
    """
    end = None
    for index, raw_line in enumerate(lines):
        match = COMBAT_RE.search(raw_line.rstrip("\r\n"))
        if match and match.group("id") == record_id:
            end = index
            break
    if end is None:
        raise EvidenceError(f"cannot locate combat record line for {record_id!r}")
    start = None
    marker = f"AutoTest [{record_id}]"
    for index in range(end - 1, -1, -1):
        if marker in lines[index] and " run(" in lines[index]:
            start = index + 1
            break
    if start is None:
        start = 0
    return start, end


def replay(lines, run_id, expectations=None):
    """Analyze one recordCombat window; raise EvidenceError on insufficient data."""
    # Materialize once: callers may pass a file/iterator, and both the record
    # finder and the debug-event parser need to scan the same lines.
    lines = list(lines)
    records = find_combat_records(lines, run_id)
    if not records:
        raise EvidenceError(f"no AutoTest [{run_id}...] combat record found")
    if len(records) > 1:
        ids = ", ".join(record_id for record_id, _ in records)
        raise EvidenceError(
            f"expected one combat record for prefix {run_id!r}, found {len(records)}: {ids}"
        )
    record_id, payload = records[0]
    samples = parse_samples(payload)
    debug_start, debug_end = _record_debug_window(lines, record_id)
    debug_events = parse_debug_events(lines[debug_start:debug_end])
    metrics = analyze_samples(samples, debug_events)
    result = {"runId": run_id, "recordId": record_id, **metrics}
    expectation_results = evaluate_expectations(metrics, expectations or [])
    result["expectations"] = expectation_results
    result["verdict"] = (
        "expectation_failed"
        if any(not item["ok"] for item in expectation_results)
        else "pass"
    )
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("log", type=Path, help="game log copy, e.g. run/logs/latest.log")
    parser.add_argument("--run-id", required=True, help="AutoTest id prefix to select")
    parser.add_argument("--json-out", type=Path, help="optional path for the JSON result")
    parser.add_argument("--expect-goal-calls-per-tick-max", type=float, action="append",
                        metavar="MAX", help="maximum goalCallsPerGameTick")
    parser.add_argument("--expect-max-goal-gap-ticks", type=int, action="append",
                        metavar="MAX", help="maximum maxGoalCallGapTicks")
    parser.add_argument("--expect-target-changes-max", type=int, action="append",
                        metavar="MAX", help="maximum targetIdChanges")
    parser.add_argument("--expect-dig-attempts-max", type=int, action="append",
                        metavar="MAX", help="maximum digAttempts in the window")
    parser.add_argument("--expect-place-attempts-max", type=int, action="append",
                        metavar="MAX", help="maximum placeAttempts in the window")
    parser.add_argument("--expect-blocking-ticks-min", type=int, action="append",
                        metavar="MIN", help="minimum blockingTicks")
    args = parser.parse_args(argv)

    try:
        with args.log.open(encoding="utf-8-sig") as source:
            result = replay(source, args.run_id, build_expectations(args))
    except (OSError, EvidenceError) as exc:
        print(json.dumps({"error": str(exc), "verdict": "insufficient_evidence"},
                         ensure_ascii=False))
        return 2

    output = json.dumps(result, ensure_ascii=False)
    print(output)
    if args.json_out is not None:
        try:
            args.json_out.write_text(output + "\n", encoding="utf-8")
        except OSError as exc:
            print(json.dumps({"error": f"cannot write --json-out: {exc}",
                              "verdict": "insufficient_evidence"}, ensure_ascii=False))
            return 2
    return 0 if result["verdict"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
