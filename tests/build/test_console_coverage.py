"""Real JVM children, unloaded classes and stale counters in console coverage."""

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "console_gate", ROOT / "source/verify-console.py"
)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ConsoleCoverage(unittest.TestCase):
    def test_children_unloaded_classes_and_rejection_of_stale_counters(self):
        classpath = gate.coverage_classpath("mcp")
        core = Path(classpath.split(os.pathsep)[0])
        version = core.parent.name
        agent = (
            core.parents[2]
            / f"org.jacoco.agent/{version}/org.jacoco.agent-{version}-runtime.jar"
        )
        with tempfile.TemporaryDirectory(prefix="jacoco-console-contract-") as folder:
            root = Path(folder)
            classes = root / "classes"
            classes.mkdir()
            source = root / "Choice.java"
            source.write_text(
                "public class Choice {\n"
                " static int choose(boolean value) {\n"
                "  if (value) return 1;\n"
                "  return 2;\n }\n"
                " public static void main(String[] args) {\n"
                "  System.out.println(choose(Boolean.parseBoolean(args[0])));\n }\n}\n"
            )
            unused = root / "Unused.java"
            unused.write_text(
                "public class Unused { public int neverCalled() { return 3; } }\n"
            )
            compile_command = [
                "javac",
                "-g",
                "-d",
                str(classes),
                str(source),
                str(unused),
            ]
            subprocess.run(compile_command, check=True, capture_output=True)
            counters = root / "children.exec"
            for value in ("true", "false"):
                subprocess.run(
                    [
                        "java",
                        f"-javaagent:{agent}=destfile={counters},append=true",
                        "-cp",
                        str(classes),
                        "Choice",
                        value,
                    ],
                    check=True,
                    capture_output=True,
                )
            command = [
                "java",
                "--class-path",
                classpath,
                str(ROOT / "source/CoverageSummary.java"),
                str(counters),
                str(classes),
                "fixture",
            ]
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            totals = json.loads(
                result.stdout.removeprefix("Java source coverage totals: ")
            )
            self.assertEqual(totals["classes"], 2)
            self.assertEqual(totals["covered_branches"], totals["total_branches"])
            self.assertEqual(totals["total_branches"], 2)
            self.assertLess(totals["covered_lines"], totals["total_lines"])
            self.assertFalse(list(root.rglob("*.xml")))
            self.assertFalse(list(root.rglob("*.html")))
            source.write_text(source.read_text().replace("return 1;", "return 7;"))
            subprocess.run(compile_command, check=True, capture_output=True)
            refused = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(refused.returncode, 0)
            self.assertNotIn("Java source coverage totals:", refused.stdout)
