import unittest

from p3_replay import replay


RECORD_ID = "p3.o.north.move"
TAKEOFF = (81, 89, 84, 92)
LANDING = (81, 85, 84, 86)


def log(*samples):
    positions = ";".join(f"{tick},82.5,{y},{z},0,0,0,{ground}"
                         for tick, y, z, ground in samples)
    return [f"[Server thread/INFO] (smartmaid) AutoTest [{RECORD_ID}] record: {positions}"]


def analyze(*samples, lines=None):
    return replay(log(*samples) if lines is None else lines, RECORD_ID, 66, TAKEOFF, LANDING)


class P3ReplayTest(unittest.TestCase):
    def test_drop_after_far_bank_landing_is_not_an_outbound_drop(self):
        result = analyze((223, 66, 89.9, "true"), (224, 66.42, 89.2, "false"),
                         (225, 67, 85.4, "true"), (226, 66.92, 86.6, "false"),
                         (227, 65.855, 87.4, "false"))
        self.assertEqual((224, 225, 227), (result["takeoff_tick"], result["first_landing_tick"],
                                          result["first_below_tick"]))
        self.assertEqual("landed_in_zone", result["outbound"])
        self.assertEqual("below_threshold_observed", result["post_landing"])

    def test_outbound_drop_is_not_masked_by_later_landing(self):
        result = analyze((1, 66, 89.9, "true"), (2, 65.8, 87, "false"),
                         (3, 66, 85.4, "true"))
        self.assertEqual("below_threshold", result["outbound"])
        self.assertEqual(2, result["first_below_tick"])
        self.assertEqual("not_evaluated", result["post_landing"])

    def test_only_first_landing_counts(self):
        result = analyze((1, 66, 89.9, "true"), (2, 67, 89.8, "false"),
                         (3, 66, 89.7, "true"), (4, 67, 88, "false"),
                         (5, 66, 85.4, "true"))
        self.assertEqual(3, result["first_landing_tick"])
        self.assertEqual("outside_landing_zone", result["outbound"])

    def test_missing_takeoff_or_landing_is_invalid(self):
        for values in [((1, 66, 89.9, "true"), (2, 66, 85.4, "true")),
                       ((1, 66, 89.9, "true"), (2, 67, 87, "false"))]:
            with self.subTest(values=values):
                self.assertEqual("invalid", analyze(*values)["outbound"])

    def test_takeoff_must_start_on_near_bank(self):
        result = analyze((1, 66, 87, "true"), (2, 67, 87, "false"),
                         (3, 66, 85.4, "true"))
        self.assertEqual("outside_takeoff_zone", result["outbound"])

    def test_no_post_landing_observation_is_not_a_pass(self):
        result = analyze((1, 66, 89.9, "true"), (2, 67, 87, "false"),
                         (3, 65.9, 85.4, "true"))
        self.assertEqual("landed_in_zone", result["outbound"])
        self.assertEqual("not_observed", result["post_landing"])

    def test_walking_off_edge_is_not_a_jump_even_above_threshold(self):
        result = analyze((1, 66, 89.9, "true"), (2, 65.95, 88, "false"),
                         (3, 66, 85.4, "true"))
        self.assertEqual("not_rising_at_takeoff", result["outbound"])

    def test_record_cut_at_sample_boundary_is_rejected(self):
        short = log((1, 66, 89.9, "true"), (2, 67, 87, "false"), (3, 66, 85.4, "true"))
        with self.assertRaisesRegex(ValueError, "expected 4 samples, found 3"):
            replay(short, RECORD_ID, 66, TAKEOFF, LANDING, expected_samples=4)

    def test_missing_duplicate_nonfinite_truncated_and_gapped_records_rejected(self):
        good = log((1, 66, 89.9, "true"), (2, 67, 87, "false"), (3, 66, 85.4, "true"))
        for lines in ([], good + good, [good[0].replace(",67,", ",nan,")],
                      [good[0][:-4]], [good[0].replace(";2,", ";4,")]):
            with self.subTest(lines=lines):
                with self.assertRaises(ValueError):
                    analyze(lines=lines)
