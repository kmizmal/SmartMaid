import unittest

from p3_course import fixture, run_fixture, to_world


def apply_and_check(entries, cells):
    checked = set()
    for entry in entries:
        words = entry["run"].split()
        if words[0] == "fill":
            x0, y0, z0, x1, y1, z1 = map(int, words[1:7])
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    for z in range(z0, z1 + 1):
                        cells[x, y, z] = words[7]
        else:
            assert words[0] == "execute" and entry["expectResult"] == 1
            self_run = words.index("run")
            assert words[self_run + 1:] == ["say", "p3-course-audit"]
            i = 1
            while i < self_run:
                assert words[i:i + 2] == ["if", "block"]
                pos = tuple(map(int, words[i + 2:i + 5]))
                assert cells.get(pos) == words[i + 5], pos
                checked.add(pos)
                i += 6
            assert i == self_run
    return checked


class P3CourseTest(unittest.TestCase):
    def test_rotates_around_fixed_start(self):
        self.assertEqual((82, 66, 90), to_world("east", 0, 66, 0))
        self.assertEqual((82, 66, 90), to_world("north", 0, 66, 0))
        self.assertEqual((86, 66, 91), to_world("east", 4, 66, 1))
        self.assertEqual((83, 66, 86), to_world("north", 4, 66, 1))

    def test_resets_dirty_geometry_and_audits_every_cell(self):
        for direction in ("east", "north"):
            with self.subTest(direction=direction):
                cells = {to_world(direction, 4, 66, 0): "minecraft:stone"}
                entries = fixture(direction, 1)
                checked = apply_and_check(entries, cells)
                self.assertEqual(set(cells), checked)
                for along in range(-8, 9):
                    for side in range(-4, 5):
                        gap = 1 <= along <= 3 and abs(side) <= 1
                        self.assertEqual("minecraft:air" if gap else "minecraft:stone",
                                         cells[to_world(direction, along, 65, side)])
                        for y in range(66, 70):
                            self.assertEqual("minecraft:air", cells[to_world(direction, along, y, side)])
                self.assertEqual("minecraft:stone", cells[to_world(direction, 2, 64, 0)])
                before = cells.copy()
                cells[to_world(direction, 4, 66, 0)] = "minecraft:stone"
                apply_and_check(fixture(direction, 2), cells)
                self.assertEqual(before, cells)

    def test_audit_detects_a_solid_block_left_on_landing_bank(self):
        entries = fixture("north", 1)
        fills = [e for e in entries if e["run"].startswith("fill ")]
        checks = [e for e in entries if e["run"].startswith("execute ")]
        cells = {}
        apply_and_check(fills, cells)
        cells[to_world("north", 4, 66, 0)] = "minecraft:stone"
        with self.assertRaises(AssertionError):
            apply_and_check(checks, cells)

    def test_rejects_unknown_orientation(self):
        with self.assertRaises(ValueError):
            fixture("south", 1)

    def test_audits_are_executable_commands_not_unterminated_condition_chains(self):
        audits = [e for e in fixture("east", 1) if e["run"].startswith("execute ")]
        self.assertTrue(audits)
        for entry in audits:
            self.assertRegex(entry["run"], r"^execute if block -?\d+ -?\d+ -?\d+ minecraft:(?:air|stone) .* run say p3-course-audit$")

    def test_run_fixture_anchors_teleports_moves_and_checks_one_direction(self):
        entries = run_fixture("north", 1)
        self.assertEqual([82.5, 66.0, 90.5], entries[0]["place"])
        self.assertTrue(entries[-3].get("tp"))
        self.assertEqual("maidtasks move 82 66 86", entries[-2]["run"])
        self.assertEqual("player", entries[-2]["source"])
        self.assertEqual(1, entries[-2]["expectResult"])
        self.assertEqual("move", entries[-2]["expectTask"])
        self.assertEqual(120, entries[-2]["recordTicks"])
        self.assertEqual({"record": {"minTargetDistMax": 2.0, "minYMin": 65.9}}, entries[-1]["check"])

    def test_gap_four_clears_four_columns_and_targets_after_them(self):
        entries = fixture("north", 1, gap_end=4)
        cells = {}
        apply_and_check(entries, cells)
        for side in range(-1, 2):
            for along in range(1, 5):
                self.assertEqual("minecraft:air", cells[to_world("north", along, 65, side)])
            self.assertEqual("minecraft:stone", cells[to_world("north", 5, 65, side)])
        move = run_fixture("north", 1, gap_end=4)[-2]
        self.assertEqual("maidtasks move 82 66 85", move["run"])
