"""Guards the seed CSV against the events table (V4 made sdk_platform NOT NULL with no default, ADR-0046).
Run: python3 -m unittest discover -s perf -p 'test_*.py'"""
import csv
import datetime as dt
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import seed  # noqa: E402


class SeedCsvTest(unittest.TestCase):
    def test_rows_match_the_copy_columns_and_set_the_platform(self):
        with tempfile.TemporaryDirectory() as d:
            out = pathlib.Path(d) / "s.csv"
            seed.generate(20, "uniform", dt.datetime(2026, 10, 28, tzinfo=dt.timezone.utc), out)
            rows = list(csv.reader(out.open()))
        self.assertEqual(20, len(rows))
        col = seed.COPY_COLUMNS.index("sdk_platform")
        for row in rows:
            self.assertEqual(len(seed.COPY_COLUMNS), len(row))
            self.assertEqual("android", row[col])
        self.assertEqual(seed.COPY_COLUMNS.index("properties"), 6)


if __name__ == "__main__":
    unittest.main()
