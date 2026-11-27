import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import combat_replay
from combat_replay import main, replay

RECORD_ID = "cf.01"
LOG_PREFIX = "[13:00:00] [Server thread/INFO] (smartmaid) "


def sample(game_tick, tick_calls, **overrides):
    values = {
        "tick": game_tick - 1000,
        "gameTick": game_tick,
        "maidId": 7,
        "targetId": 10,
        "maidTargetId": 10,
        "engaged": 1,
        "mode": "MELEE",
        "cooldown": 0,
        "lostTicks": 0,
        "eatRetreatTicks": 0,
        "blockStartTick": -1,
        "blockHoldTicks": -1,
        "tickCalls": tick_calls,
        "health": 20.0,
        "maxHealth": 20.0,
        "food": 20,
        "usingItem": 0,
        "useItemId": "empty",
        "blocking": 0,
        "x": 0.0,
        "y": 64.0,
        "z": 0.0,
        "digAttempts": 0,
        "placeAttempts": 0,
    }
    values.update(overrides)
    return values


def csv_of(values):
    return ",".join(str(values[column]) for column in combat_replay.COLUMNS)


def make_line(*samples, record_id=RECORD_ID):
    payload = ";".join(csv_of(values) for values in samples)
    return f"{LOG_PREFIX}AutoTest [{record_id}] combat: {payload}"


def make_log(*samples, record_id=RECORD_ID, extra_lines=()):
    return [*extra_lines, make_line(*samples, record_id=record_id)]


def run_cli(lines, *args, record_id=RECORD_ID):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "latest.log"
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with redirect_stdout(io.StringIO()) as output:
            code = main([str(path), "--run-id", record_id, *args])
        return code, output.getvalue()


class CombatReplayParsingTest(unittest.TestCase):
    def test_complete_record_metrics(self):
        lines = make_log(
            sample(100, 10, engaged=0, mode="NONE", targetId=-1, maidTargetId=-1),
            sample(101, 12, mode="APPROACH", targetId=-1, maidTargetId=-1),
            sample(102, 14, mode="MELEE"),
            sample(103, 17, mode="MELEE", targetId=11, maidTargetId=11,
                   blocking=1, digAttempts=1, placeAttempts=1),
            sample(104, 20, mode="APPROACH", targetId=11, maidTargetId=11,
                   engaged=0, digAttempts=2),
        )
        result = replay(lines, RECORD_ID)
        self.assertEqual("cf.01", result["recordId"])
        self.assertEqual(5, result["samples"])
        self.assertEqual(4, result["durationGameTicks"])
        self.assertEqual(3, result["engagedSamples"])
        self.assertEqual(10, result["goalCalls"])
        self.assertEqual(2.5, result["goalCallsPerGameTick"])
        self.assertEqual(1, result["maxGoalCallGapTicks"])
        self.assertEqual(["NONE", "APPROACH", "MELEE", "APPROACH"], result["modeSequence"])
        self.assertEqual(2, result["targetIdChanges"])
        self.assertEqual(2, result["maidTargetIdChanges"])
        self.assertEqual(1, result["blockingTicks"])
        self.assertEqual(2, result["digAttempts"])
        self.assertEqual(1, result["placeAttempts"])
        self.assertEqual("pass", result["verdict"])

    def test_missing_column_is_insufficient_evidence(self):
        def row_without_last_column(values):
            return ",".join(str(values[column]) for column in combat_replay.COLUMNS[:-1])

        payload = ";".join(
            row_without_last_column(sample(game_tick, tick_calls))
            for game_tick, tick_calls in ((100, 10), (101, 11))
        )
        line = f"{LOG_PREFIX}AutoTest [{RECORD_ID}] combat: {payload}"
        code, output = run_cli([line])
        self.assertEqual(2, code)
        self.assertIn("columns", json.loads(output)["error"])

    def test_less_than_two_samples_is_insufficient_evidence(self):
        code, _ = run_cli(make_log(sample(100, 10)))
        self.assertEqual(2, code)

    def test_no_tickcalls_increase_is_insufficient_evidence(self):
        lines = make_log(sample(100, 10), sample(101, 10), sample(102, 10))
        code, _ = run_cli(lines)
        self.assertEqual(2, code)
        with self.assertRaises(combat_replay.EvidenceError):
            replay(lines, RECORD_ID)

    def test_non_consecutive_game_tick_is_insufficient_evidence(self):
        lines = make_log(sample(100, 10), sample(102, 11))
        with self.assertRaisesRegex(combat_replay.EvidenceError, "non-consecutive gameTick"):
            replay(lines, RECORD_ID)

    def test_missing_record_is_insufficient_evidence(self):
        code, _ = run_cli([LOG_PREFIX + "AutoTest [other] combat: none"], record_id="cf.01")
        self.assertEqual(2, code)

    def test_prefix_must_select_exactly_one_combat_record(self):
        lines = [
            make_line(sample(100, 10), sample(101, 11), record_id="cf.01a"),
            make_line(sample(100, 10), sample(101, 11), record_id="cf.01b"),
        ]
        code, _ = run_cli(lines, record_id="cf.01")
        self.assertEqual(2, code)
        result = replay(lines, "cf.01a")
        self.assertEqual("cf.01a", result["recordId"])

    def test_target_change_and_mode_dedup_counts(self):
        lines = make_log(
            sample(100, 10, mode="MELEE", targetId=-1, maidTargetId=-1),
            sample(101, 11, mode="MELEE", targetId=5, maidTargetId=5),
            sample(102, 12, mode="RANGED", targetId=5, maidTargetId=7),
            sample(103, 13, mode="RANGED", targetId=6, maidTargetId=7),
            sample(104, 14, mode="MELEE", targetId=6, maidTargetId=7),
        )
        result = replay(lines, RECORD_ID)
        self.assertEqual(2, result["targetIdChanges"])
        self.assertEqual(2, result["maidTargetIdChanges"])
        self.assertEqual(["MELEE", "RANGED", "MELEE"], result["modeSequence"])

    def test_dig_and_place_attempt_deltas(self):
        lines = make_log(
            sample(100, 10, digAttempts=0, placeAttempts=0),
            sample(101, 11, digAttempts=0, placeAttempts=1),
            sample(102, 12, digAttempts=2, placeAttempts=1),
            sample(103, 13, digAttempts=2, placeAttempts=2),
            sample(104, 14, digAttempts=1, placeAttempts=2),
            sample(105, 15, digAttempts=3, placeAttempts=2),
        )
        result = replay(lines, RECORD_ID)
        self.assertEqual(4, result["digAttempts"])
        self.assertEqual(2, result["placeAttempts"])

    def test_debug_events_are_parsed_when_source_is_iterator(self):
        lines = make_log(
            sample(100, 10),
            sample(101, 11),
            extra_lines=[LOG_PREFIX + "[SmartMaid-Debug] Combat start target=zombie",
                         LOG_PREFIX + "[SmartMaid-Debug] Combat melee attack cd=14 weapon=empty"],
        )
        result = replay(iter(lines), RECORD_ID)
        self.assertEqual(1, result["debug"]["combatStarts"])
        self.assertEqual(1, result["debug"]["meleeAttackEvents"])

    def test_block_hold_uses_debug_value_when_present(self):
        lines = make_log(
            sample(100, 10, blocking=0),
            sample(101, 11, blocking=1),
            sample(102, 12, blocking=1),
            sample(103, 13, blocking=0),
            extra_lines=[LOG_PREFIX + "[SmartMaid-Debug] Combat block end 收盾 holdTicks=5 t=103"],
        )
        result = replay(lines, RECORD_ID)
        self.assertEqual([5], result["blockHoldTicksList"])
        self.assertEqual(1, result["debug"]["blockEnds"])

    def test_melee_attack_intervals_use_game_ticks(self):
        lines = make_log(
            sample(100, 10, mode="MELEE", cooldown=0),
            sample(101, 11, mode="MELEE", cooldown=14),
            sample(102, 12, mode="MELEE", cooldown=14),
            sample(103, 13, mode="MELEE", cooldown=13),
            sample(104, 14, mode="MELEE", cooldown=0),
            sample(105, 15, mode="MELEE", cooldown=14),
        )
        result = replay(lines, RECORD_ID)
        self.assertEqual([4], result["meleeAttackIntervals"])

    def test_ranged_draw_and_eat_ticks_from_item_ids(self):
        lines = make_log(
            sample(100, 10, usingItem=1, useItemId="minecraft:bow"),
            sample(101, 11, usingItem=1, useItemId="minecraft:bow"),
            sample(102, 12, usingItem=1, useItemId="minecraft:cooked_beef"),
            sample(103, 13, usingItem=1, useItemId="minecraft:cooked_beef"),
            sample(104, 14, usingItem=1, useItemId="minecraft:cooked_beef", blocking=1),
        )
        result = replay(lines, RECORD_ID)
        self.assertEqual(2, result["rangedDrawTicks"])
        self.assertEqual(2, result["eatTicks"])


class CombatReplayExitCodeTest(unittest.TestCase):
    def test_expectation_pass_exits_zero(self):
        lines = make_log(sample(100, 10), sample(101, 12), sample(102, 14))
        code, output = run_cli(
            lines,
            "--expect-goal-calls-per-tick-max", "2",
            "--expect-max-goal-gap-ticks", "1",
            "--expect-target-changes-max", "0",
            "--expect-dig-attempts-max", "0",
            "--expect-place-attempts-max", "0",
            "--expect-blocking-ticks-min", "0",
        )
        self.assertEqual(0, code)
        self.assertEqual("pass", json.loads(output)["verdict"])

    def test_expectation_failure_exits_one(self):
        lines = make_log(sample(100, 10), sample(101, 11, digAttempts=1))
        code, output = run_cli(lines, "--expect-dig-attempts-max", "0")
        self.assertEqual(1, code)
        result = json.loads(output)
        self.assertEqual("expectation_failed", result["verdict"])
        self.assertFalse(result["expectations"][0]["ok"])

    def test_unavailable_max_gap_expectation_exits_two(self):
        # Only one tickCalls increase point -> maxGoalCallGapTicks cannot be computed.
        lines = make_log(sample(100, 10), sample(101, 11), sample(102, 11))
        code, _ = run_cli(lines, "--expect-max-goal-gap-ticks", "1")
        self.assertEqual(2, code)

    def test_json_out_is_written(self):
        lines = make_log(sample(100, 10), sample(101, 12))
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "latest.log"
            out_path = Path(directory) / "replay.json"
            log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            with redirect_stdout(io.StringIO()):
                code = main([str(log_path), "--run-id", RECORD_ID, "--json-out", str(out_path)])
            self.assertEqual(0, code)
            payload = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(2, payload["samples"])


if __name__ == "__main__":
    unittest.main()
