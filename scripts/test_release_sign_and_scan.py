from __future__ import annotations

import os
import shutil
import subprocess
import sys
import unittest
import uuid
from pathlib import Path
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parent))

import release_sign_and_scan as release_scan


APKSIGNER_SUCCESS = """\
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
Signer #1 certificate DN: CN=Release
Signer #1 certificate SHA-256 digest: aa:bb:cc
"""


class ReleaseSignAndScanTest(unittest.TestCase):
    def setUp(self) -> None:
        temp_parent = Path(__file__).resolve().parents[1] / "build" / "test-tmp"
        temp_parent.mkdir(parents=True, exist_ok=True)
        self.root_dir = temp_parent / f"release-sign-{uuid.uuid4().hex}"
        self.root_dir.mkdir(parents=True)
        self.release_dir = self.root_dir / "app" / "build" / "outputs" / "apk" / "release"
        self.release_dir.mkdir(parents=True)

        self.root_patch = mock.patch.object(release_scan, "ROOT_DIR", self.root_dir)
        self.release_dir_patch = mock.patch.object(release_scan, "DEFAULT_RELEASE_DIR", self.release_dir)
        self.root_patch.start()
        self.release_dir_patch.start()

    def tearDown(self) -> None:
        self.release_dir_patch.stop()
        self.root_patch.stop()
        shutil.rmtree(self.root_dir, ignore_errors=True)

    def write_apk(self, path: Path, content: bytes = b"apk") -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def test_rejects_explicit_apk_outside_release_flow_without_skip_build(self) -> None:
        with self.assertRaisesRegex(release_scan.ReleaseScanError, "without --skip-build"):
            release_scan.validate_explicit_apk_build_flow(Path("downloaded.apk"), skip_build=False)

    def test_rejects_debug_output_path(self) -> None:
        debug_apk = self.write_apk(
            self.root_dir / "app" / "build" / "outputs" / "apk" / "debug" / "vrcx-android.apk"
        )

        with self.assertRaisesRegex(release_scan.ReleaseScanError, "debug APK"):
            release_scan.resolve_apk_path(debug_apk, skip_build=True)

    def test_rejects_stale_release_artifact(self) -> None:
        old_apk = self.write_apk(self.release_dir / "vrcx-android-1.4.0.apk", b"old")
        new_apk = self.write_apk(self.release_dir / "vrcx-android-1.5.0.apk", b"new")
        os.utime(old_apk, (1, 1))
        os.utime(new_apk, (2, 2))

        with self.assertRaisesRegex(release_scan.ReleaseScanError, "stale release APK"):
            release_scan.resolve_apk_path(old_apk, skip_build=True)

    def test_rejects_explicit_release_apk_that_does_not_match_built_output(self) -> None:
        explicit_apk = self.write_apk(self.release_dir / "vrcx-android-1.4.0.apk", b"old")
        built_apk = self.write_apk(self.release_dir / "vrcx-android-1.5.0.apk", b"new")

        with self.assertRaisesRegex(release_scan.ReleaseScanError, "does not match"):
            release_scan.resolve_apk_path(explicit_apk, skip_build=False, built_release_apk=built_apk)

    def test_verify_apk_signing_rejects_unsigned_apk(self) -> None:
        apk_path = self.write_apk(self.release_dir / "vrcx-android.apk")
        failed_verify = subprocess.CompletedProcess(
            args=[],
            returncode=1,
            stdout="",
            stderr="DOES NOT VERIFY\n",
        )

        with mock.patch.object(release_scan, "find_android_build_tool", return_value=Path("apksigner")):
            with mock.patch.object(release_scan.subprocess, "run", return_value=failed_verify):
                with self.assertRaisesRegex(release_scan.ReleaseScanError, "signature verification failed"):
                    release_scan.verify_apk_signing(apk_path)

    def test_verify_apk_signing_rejects_debug_certificate(self) -> None:
        apk_path = self.write_apk(self.release_dir / "vrcx-android.apk")
        debug_cert_output = APKSIGNER_SUCCESS.replace("CN=Release", "CN=Android Debug")
        completed_verify = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=debug_cert_output,
            stderr="",
        )

        with mock.patch.object(release_scan, "find_android_build_tool", return_value=Path("apksigner")):
            with mock.patch.object(release_scan.subprocess, "run", return_value=completed_verify):
                with self.assertRaisesRegex(release_scan.ReleaseScanError, "debug certificate"):
                    release_scan.verify_apk_signing(apk_path)

    def test_verify_apk_signing_rejects_debuggable_manifest_when_aapt_is_available(self) -> None:
        apk_path = self.write_apk(self.release_dir / "vrcx-android.apk")
        apksigner_result = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=APKSIGNER_SUCCESS,
            stderr="",
        )
        aapt_result = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout="application-debuggable\n",
            stderr="",
        )

        def fake_find_tool(tool_name: str) -> Path | None:
            return Path(tool_name) if tool_name in {"apksigner", "aapt"} else None

        with mock.patch.object(release_scan, "find_android_build_tool", side_effect=fake_find_tool):
            with mock.patch.object(release_scan.subprocess, "run", side_effect=[apksigner_result, aapt_result]):
                with self.assertRaisesRegex(release_scan.ReleaseScanError, "debug APK"):
                    release_scan.verify_apk_signing(apk_path)

    def test_verify_apk_signing_returns_scheme_metadata(self) -> None:
        apk_path = self.write_apk(self.release_dir / "vrcx-android.apk")
        completed_verify = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=APKSIGNER_SUCCESS,
            stderr="",
        )

        def fake_find_tool(tool_name: str) -> Path | None:
            return Path("apksigner") if tool_name == "apksigner" else None

        with mock.patch.object(release_scan, "find_android_build_tool", side_effect=fake_find_tool):
            with mock.patch.object(release_scan.subprocess, "run", return_value=completed_verify):
                signing_info = release_scan.verify_apk_signing(apk_path)

        self.assertEqual(signing_info.verifier, "apksigner")
        self.assertEqual(signing_info.signer_count, 1)
        self.assertTrue(signing_info.scheme_verified["v2"])
        self.assertEqual(signing_info.signer_certificate_sha256, ["aa:bb:cc"])


if __name__ == "__main__":
    unittest.main()
