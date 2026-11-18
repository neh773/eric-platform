"""The script library source must compile: two branches can each add a namespace under the same name and merge cleanly."""
import pathlib
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[2] / "gateway/scripting/src/main/resources/oip_system.py"


class OipSystemCompilesTest(unittest.TestCase):
    def test_compiles(self):
        compile(SOURCE.read_text(), str(SOURCE), "exec")


if __name__ == "__main__":
    unittest.main()
