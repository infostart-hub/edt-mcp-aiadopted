"""Run the unchanged Maven gates and print JaCoCo totals without retained reports."""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def reactor_outputs(module):
    return [
        p.parent / "target"
        for p in (ROOT / module).rglob("pom.xml")
        if "target" not in p.relative_to(ROOT).parts and p.parent.name != "bom"
    ]


def fingerprint():
    names = (
        subprocess.check_output(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            cwd=ROOT,
        )
        .decode()
        .split("\0")
    )
    digest = hashlib.sha256()
    outputs = reactor_outputs("mcp") + reactor_outputs("proxy")
    for name in sorted(set(names) - {""}):
        path = ROOT / name
        if path.is_file() and not any(output in path.parents for output in outputs):
            digest.update(name.encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()


def coverage_classpath(module):
    output = subprocess.check_output(
        [
            "mvn",
            "help:evaluate",
            "-q",
            "-Dexpression=settings.localRepository",
            "-DforceStdout",
        ],
        cwd=ROOT,
        text=True,
    )
    repository = Path(re.sub(r"\x1b\[[0-9;]*m", "", output).strip())
    if not repository.is_dir():
        raise RuntimeError("Maven did not identify its existing dependency repository")
    pom = ET.parse(ROOT / module / "pom.xml")
    plugin = next(
        p
        for p in pom.findall(".//m:plugin", NS)
        if p.findtext("m:artifactId", namespaces=NS) == "jacoco-maven-plugin"
    )
    version = plugin.findtext("m:version", namespaces=NS)
    parent = (
        repository
        / f"org/jacoco/org.jacoco.build/{version}/org.jacoco.build-{version}.pom"
    )
    asm = ET.parse(parent).findtext("m:properties/m:asm.version", namespaces=NS)
    jars = [
        repository
        / f"org/jacoco/org.jacoco.core/{version}/org.jacoco.core-{version}.jar"
    ]
    jars += [
        repository / f"org/ow2/asm/{name}/{asm}/{name}-{asm}.jar"
        for name in ("asm", "asm-commons", "asm-tree")
    ]
    if not all(path.is_file() for path in jars):
        raise RuntimeError("The declared JaCoCo analysis dependencies are unavailable")
    return os.pathsep.join(map(str, jars))


def test_summary(module):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    skipped = []
    for report in (ROOT / module).glob("**/target/surefire-reports/TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.findall("testcase"):
            reason = case.find("skipped")
            if reason is not None:
                skipped.append(
                    {
                        "test": case.get("classname", "") + "." + case.get("name", ""),
                        "reason": reason.get("message")
                        or reason.text
                        or "declared skip",
                    }
                )
    print("Java test totals:", totals, flush=True)
    for case in skipped:
        print("Java test skip:", case, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", choices=("mcp", "proxy"))
    module = parser.parse_args().module
    before = fingerprint()
    # These are disposable reactor outputs rebuilt by this command's clean phase.
    targets = reactor_outputs(module)
    with tempfile.TemporaryDirectory(prefix="edt-verification-") as directory:
        counters = Path(directory) / "jacoco.exec"
        try:
            result = subprocess.run(
                [
                    "mvn",
                    "-f",
                    str(ROOT / module / "pom.xml"),
                    "clean",
                    "verify",
                    "--batch-mode",
                    "--no-transfer-progress",
                    "-T",
                    "1",
                    "-Dmaven.test.redirectTestOutputToFile=false",
                    "-Dsurefire.useFile=false",
                    "-Dcoverage.report.skip=true",
                    "-Djacoco.destFile=" + str(counters),
                ],
                cwd=ROOT,
                env={
                    **os.environ,
                    "JAVA_TOOL_OPTIONS": (
                        os.environ.get("JAVA_TOOL_OPTIONS", "")
                        + f' -Djava.io.tmpdir="{directory}"'
                    ).strip(),
                },
                check=False,
            )
            test_summary(module)
            if result.returncode:
                # The launcher log is internal test evidence, not a retained report.
                for log in (ROOT / module).glob(
                    "tests/*/target/work/configuration/*.log"
                ):
                    print(
                        "\n".join(log.read_text(errors="replace").splitlines()[-100:])
                    )
                return result.returncode
            if before != fingerprint():
                raise RuntimeError("Verification inputs changed during the Maven gate")
            if module == "mcp":
                subprocess.run(
                    [
                        sys.executable,
                        "-B",
                        "-m",
                        "unittest",
                        "discover",
                        "-s",
                        "tests/build",
                        "-q",
                    ],
                    cwd=ROOT,
                    check=True,
                )
            classes = (
                ROOT / "mcp/bundles/com.ditrix.edt.mcp.server/target/classes"
                if module == "mcp"
                else ROOT / "proxy/target/classes"
            )
            subprocess.run(
                [
                    "java",
                    "--class-path",
                    coverage_classpath(module),
                    str(ROOT / "source/CoverageSummary.java"),
                    str(counters),
                    str(classes),
                    module,
                ],
                cwd=ROOT,
                check=True,
            )
            if before != fingerprint():
                raise RuntimeError(
                    "Verification inputs changed during coverage analysis"
                )
            print("Verification inputs SHA-256:", before)
            return 0
        finally:
            for target in targets:
                if target.is_dir():
                    shutil.rmtree(target)


if __name__ == "__main__":
    raise SystemExit(main())
