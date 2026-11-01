"""Unit tests for sbom.py; CI runs them with python3 -m unittest discover -s scripts."""

import hashlib
import json
import os
import tempfile
import unittest
import zipfile

import sbom

LOCK = """# This is a Gradle generated file for dependency locking.
ch.qos.logback:logback-core:1.5.38=compileClasspath,productionRuntimeClasspath,runtimeClasspath
org.springframework.boot:spring-boot:4.1.1=compileClasspath,productionRuntimeClasspath
tools.jackson:jackson-bom:3.1.7=compileClasspath,productionRuntimeClasspath
org.junit.jupiter:junit-jupiter-api:6.0.1=testCompileClasspath,testRuntimeClasspath
com.google.errorprone:error_prone_core:2.50.0=errorprone
empty=developmentOnly,testAndDevelopmentOnly
"""

LIBS = {
    "logback-core-1.5.38.jar": "a" * 64,
    "spring-boot-4.1.1.jar": "b" * 64,
    "spring-boot-jarmode-tools-4.1.1.jar": "c" * 64,
}

EPOCH = {"SOURCE_DATE_EPOCH": "1793577600"}


class LockfileTest(unittest.TestCase):
    def test_keeps_only_the_production_runtime(self):
        self.assertEqual(
            sbom.parse_lockfile(LOCK),
            {
                ("ch.qos.logback", "logback-core"): "1.5.38",
                ("org.springframework.boot", "spring-boot"): "4.1.1",
                ("tools.jackson", "jackson-bom"): "3.1.7",
            },
        )

    def test_a_malformed_line_is_an_error(self):
        with self.assertRaisesRegex(sbom.SbomError, "line 1"):
            sbom.parse_lockfile("org.example:only-two=productionRuntimeClasspath\n")

    def test_a_lockfile_without_the_configuration_is_an_error(self):
        with self.assertRaisesRegex(sbom.SbomError, "no entries"):
            sbom.parse_lockfile("org.example:a:1=testRuntimeClasspath\n")


class BuildTest(unittest.TestCase):
    def bom(self, libs=LIBS, version="1.2.3", environ=EPOCH):
        return sbom.build(LOCK, libs, version, environ)

    def test_lists_each_packaged_library_with_its_locked_coordinates_and_hash(self):
        components = self.bom()["components"]
        self.assertEqual(
            [(c["group"], c["name"], c["version"]) for c in components],
            [
                ("ch.qos.logback", "logback-core", "1.5.38"),
                ("org.springframework.boot", "spring-boot", "4.1.1"),
                ("org.springframework.boot", "spring-boot-jarmode-tools", "4.1.1"),
            ],
        )
        logback = components[0]
        self.assertEqual(logback["purl"], "pkg:maven/ch.qos.logback/logback-core@1.5.38?type=jar")
        self.assertEqual(logback["hashes"], [{"alg": "SHA-256", "content": "a" * 64}])

    def test_a_locked_bom_that_is_not_packaged_is_left_out(self):
        names = [c["name"] for c in self.bom()["components"]]
        self.assertNotIn("jackson-bom", names)

    def test_a_packaged_jar_the_lockfile_does_not_explain_fails(self):
        with self.assertRaisesRegex(sbom.SbomError, "sneaky-1.0.jar"):
            self.bom({**LIBS, "sneaky-1.0.jar": "d" * 64})

    def test_plugin_jars_must_match_the_locked_spring_boot(self):
        libs = {k: v for k, v in LIBS.items() if "jarmode" not in k}
        libs["spring-boot-jarmode-tools-4.0.0.jar"] = "c" * 64
        with self.assertRaisesRegex(sbom.SbomError, "does not match"):
            self.bom(libs)

    def test_metadata_names_the_release(self):
        bom = self.bom(version="2.0.0-rc.1")
        self.assertEqual(bom["bomFormat"], "CycloneDX")
        self.assertEqual(bom["specVersion"], "1.6")
        root = bom["metadata"]["component"]
        self.assertEqual(root["purl"], "pkg:maven/io.github.laiwf94/payflow@2.0.0-rc.1?type=jar")
        self.assertEqual(bom["metadata"]["timestamp"], "2026-11-02T00:00:00Z")
        self.assertEqual(bom["dependencies"][0]["ref"], root["bom-ref"])
        self.assertEqual(len(bom["dependencies"][0]["dependsOn"]), 3)

    def test_the_same_input_gives_the_same_document(self):
        self.assertEqual(json.dumps(self.bom()), json.dumps(self.bom()))
        self.assertNotEqual(self.bom()["serialNumber"], self.bom(version="1.2.4")["serialNumber"])
        self.assertRegex(self.bom()["serialNumber"], r"^urn:uuid:[0-9a-f-]{36}$")

    def test_a_version_that_is_not_semver_is_refused(self):
        for bad in ["v1.2.3", "1.2", "1.2.3 ", "latest"]:
            with self.assertRaises(sbom.SbomError, msg=bad):
                self.bom(version=bad)


class JarTest(unittest.TestCase):
    def test_reads_and_hashes_boot_inf_lib_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = os.path.join(tmp, "payflow.jar")
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("BOOT-INF/classes/App.class", b"x")
                z.writestr("BOOT-INF/lib/logback-core-1.5.38.jar", b"logback")
                z.writestr("BOOT-INF/lib/spring-boot-4.1.1.jar", b"boot")
            lock = os.path.join(tmp, "gradle.lockfile")
            with open(lock, "w") as f:
                f.write(LOCK)
            out = os.path.join(tmp, "sbom.json")
            self.assertEqual(
                sbom.main(["--lockfile", lock, "--jar", jar, "--version", "1.0.0", "--output", out]), 0
            )
            with open(out) as f:
                components = json.load(f)["components"]
            self.assertEqual(
                components[0]["hashes"][0]["content"], hashlib.sha256(b"logback").hexdigest()
            )

    def test_a_plain_jar_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = os.path.join(tmp, "plain.jar")
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("App.class", b"x")
            with self.assertRaisesRegex(sbom.SbomError, "not a Spring Boot jar"):
                sbom.jar_libraries(jar)


if __name__ == "__main__":
    unittest.main()
