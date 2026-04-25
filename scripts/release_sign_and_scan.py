from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib import error, request


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILE = ROOT_DIR / ".env"
DEFAULT_OUTPUT_DIR = ROOT_DIR / "build" / "release-evidence"
DEFAULT_RELEASE_DIR = ROOT_DIR / "app" / "build" / "outputs" / "apk" / "release"
VT_API_BASE_URL = "https://www.virustotal.com/api/v3"
VT_GUI_BASE_URL = "https://www.virustotal.com/gui/file"
LARGE_FILE_THRESHOLD_BYTES = 32 * 1024 * 1024


class ReleaseScanError(RuntimeError):
    """Raised when the release signing or VirusTotal flow cannot continue."""


class VirusTotalApiError(ReleaseScanError):
    """Raised when a VirusTotal API request fails."""

    def __init__(self, message: str, *, status_code: int | None = None, body: str | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.body = body


@dataclass(slots=True)
class VirusTotalConfig:
    api_key: str
    private_scanning: bool
    enable_internet: bool
    intercept_tls: bool


@dataclass(slots=True)
class ApkSigningInfo:
    verifier: str
    signer_count: int | None
    scheme_verified: dict[str, bool]
    signer_certificate_sha256: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load release credentials from .env, build the signed release APK, "
            "scan it with VirusTotal, and write a release-ready evidence report."
        )
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        default=DEFAULT_ENV_FILE,
        help="Path to the .env file that holds signing and VirusTotal settings.",
    )
    parser.add_argument(
        "--apk",
        type=Path,
        help=(
            "Path to a signed release APK. Requires --skip-build unless the path matches "
            "the APK produced by the release build."
        ),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="Directory where the JSON and Markdown evidence files are written.",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Reuse an existing signed APK instead of running the Gradle release build first.",
    )
    parser.add_argument(
        "--force-upload",
        action="store_true",
        help="Upload the APK even if VirusTotal already has a report for its SHA-256 hash.",
    )
    parser.add_argument(
        "--analysis-timeout",
        type=int,
        default=300,
        help="Maximum time in seconds to wait for a new VirusTotal analysis to finish.",
    )
    parser.add_argument(
        "--poll-interval",
        type=int,
        default=10,
        help="Polling interval in seconds while waiting for VirusTotal analysis completion.",
    )
    parser.add_argument(
        "--domains-limit",
        type=int,
        default=50,
        help="Maximum number of contacted domains to include in the release evidence report.",
    )
    return parser.parse_args()


def strip_optional_quotes(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].strip()
        if "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = strip_optional_quotes(value.strip())
        os.environ.setdefault(key, value)


def parse_bool(value: str | None, *, default: bool = False) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def resolve_gradle_wrapper() -> Path:
    return ROOT_DIR / ("gradlew.bat" if os.name == "nt" else "gradlew")


def run_release_build() -> Path:
    required_env_vars = ["VRCX_KEYSTORE_PASSWORD"]
    missing = [name for name in required_env_vars if not os.environ.get(name)]
    if missing:
        missing_list = ", ".join(missing)
        raise ReleaseScanError(
            f"Missing required signing settings: {missing_list}. "
            "Set them in .env or the process environment before running this script."
        )

    gradle_wrapper = resolve_gradle_wrapper()
    if not gradle_wrapper.exists():
        raise ReleaseScanError(f"Gradle wrapper not found at {gradle_wrapper.name}.")

    command = [str(gradle_wrapper), "assembleRelease"]
    print("Building signed release APK...")
    subprocess.run(command, cwd=ROOT_DIR, check=True, env=os.environ.copy())
    return find_newest_release_apk()


def path_is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.resolve(strict=False).relative_to(parent.resolve(strict=False))
        return True
    except ValueError:
        return False


def same_file(left: Path, right: Path) -> bool:
    try:
        return left.resolve(strict=True) == right.resolve(strict=True)
    except FileNotFoundError:
        return left.resolve(strict=False) == right.resolve(strict=False)


def resolve_requested_path(path: Path) -> Path:
    return path if path.is_absolute() else ROOT_DIR / path


def list_release_apks() -> list[Path]:
    return sorted(
        DEFAULT_RELEASE_DIR.glob("*.apk"),
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=True,
    )


def find_newest_release_apk() -> Path:
    apk_candidates = list_release_apks()
    if not apk_candidates:
        raise ReleaseScanError(
            "No signed release APK was found. Run the build first or pass --apk with a release artifact."
        )
    return apk_candidates[0]


def looks_like_debug_artifact_path(path: Path) -> bool:
    lower_parts = [part.lower() for part in path.parts]
    lower_name = path.name.lower()
    if lower_name.endswith("-debug.apk") or "-debug-" in lower_name:
        return True

    for index in range(len(lower_parts) - 2):
        if lower_parts[index : index + 3] == ["outputs", "apk", "debug"]:
            return True
    return False


def reject_debug_artifact_path(apk_path: Path) -> None:
    if looks_like_debug_artifact_path(apk_path):
        raise ReleaseScanError(
            f"Refusing debug APK artifact: {repo_relative_path(apk_path)}. "
            "Use the APK from app/build/outputs/apk/release or pass a signed release artifact."
        )


def reject_stale_release_artifact(apk_path: Path) -> None:
    if not path_is_relative_to(apk_path, DEFAULT_RELEASE_DIR):
        return

    newest_release_apk = find_newest_release_apk()
    if same_file(apk_path, newest_release_apk):
        return

    raise ReleaseScanError(
        f"Refusing stale release APK: {repo_relative_path(apk_path)}. "
        f"The newest release artifact is {repo_relative_path(newest_release_apk)}."
    )


def validate_explicit_apk_build_flow(explicit_apk: Path | None, *, skip_build: bool) -> None:
    if explicit_apk is None or skip_build:
        return

    apk_path = resolve_requested_path(explicit_apk)
    reject_debug_artifact_path(apk_path)
    if path_is_relative_to(apk_path, DEFAULT_RELEASE_DIR):
        return

    raise ReleaseScanError(
        "Refusing --apk without --skip-build because the explicit APK is outside "
        "app/build/outputs/apk/release. Let the script use the release build output, "
        "or pass --skip-build when intentionally scanning an existing signed APK."
    )


def resolve_apk_path(
    explicit_apk: Path | None,
    *,
    skip_build: bool,
    built_release_apk: Path | None = None,
) -> Path:
    if explicit_apk is not None:
        apk_path = resolve_requested_path(explicit_apk)
        if not apk_path.exists():
            raise ReleaseScanError(f"APK not found: {explicit_apk}")

        reject_debug_artifact_path(apk_path)

        if not skip_build:
            if built_release_apk is None:
                raise ReleaseScanError("Internal error: release build did not return an APK path.")
            if not same_file(apk_path, built_release_apk):
                raise ReleaseScanError(
                    f"Refusing --apk without --skip-build because {repo_relative_path(apk_path)} "
                    "does not match the APK produced by assembleRelease "
                    f"({repo_relative_path(built_release_apk)})."
                )
            return built_release_apk

        reject_stale_release_artifact(apk_path)
        return apk_path

    if built_release_apk is not None:
        return built_release_apk

    return find_newest_release_apk()


def sha256_for_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file_handle:
        for chunk in iter(lambda: file_handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def format_timestamp(epoch_seconds: int | None) -> str | None:
    if epoch_seconds is None:
        return None
    return datetime.fromtimestamp(epoch_seconds, tz=UTC).isoformat().replace("+00:00", "Z")


def repo_relative_path(path: Path) -> str:
    try:
        return path.relative_to(ROOT_DIR).as_posix()
    except ValueError:
        return path.name


def build_tools_version_key(version_dir: Path) -> tuple[int, int, int, int, str]:
    version_numbers = [int(part) for part in re.findall(r"\d+", version_dir.name)]
    padded_numbers = (version_numbers + [0, 0, 0, 0])[:4]
    return (*padded_numbers, version_dir.name)


def decode_local_properties_path(value: str) -> str:
    return value.replace("\\:", ":").replace("\\\\", "\\")


def android_sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        env_value = os.environ.get(env_name)
        if env_value:
            roots.append(Path(env_value))

    local_properties = ROOT_DIR / "local.properties"
    if local_properties.exists():
        for raw_line in local_properties.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip() == "sdk.dir":
                roots.append(Path(decode_local_properties_path(value.strip())))
                break

    unique_roots: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        marker = str(root.resolve(strict=False)).lower()
        if marker not in seen:
            unique_roots.append(root)
            seen.add(marker)
    return unique_roots


def executable_names(tool_name: str) -> list[str]:
    if os.name == "nt":
        return [f"{tool_name}.bat", f"{tool_name}.exe", tool_name]
    return [tool_name]


def find_android_build_tool(tool_name: str) -> Path | None:
    candidates: list[Path] = []
    tool_files = executable_names(tool_name)
    for sdk_root in android_sdk_roots():
        build_tools_dir = sdk_root / "build-tools"
        if not build_tools_dir.is_dir():
            continue
        for version_dir in build_tools_dir.iterdir():
            for tool_file in tool_files:
                candidate = version_dir / tool_file
                if candidate.exists():
                    candidates.append(candidate)

    if candidates:
        return sorted(candidates, key=lambda path: build_tools_version_key(path.parent), reverse=True)[0]

    for tool_file in tool_files:
        path_candidate = shutil.which(tool_file)
        if path_candidate:
            return Path(path_candidate)
    return None


def sanitized_tool_output(stdout: str, stderr: str) -> str:
    combined = "\n".join(part for part in (stdout.strip(), stderr.strip()) if part)
    lines = [
        line
        for line in combined.splitlines()
        if "certificate DN:" not in line
    ]
    return "\n".join(lines[:12]).strip()


def parse_apksigner_output(output: str) -> ApkSigningInfo:
    scheme_verified: dict[str, bool] = {}
    for match in re.finditer(r"Verified using (v[\d.]+) scheme.*:\s*(true|false)", output, re.IGNORECASE):
        scheme_verified[match.group(1)] = match.group(2).lower() == "true"

    signer_count_match = re.search(r"Number of signers:\s*(\d+)", output, re.IGNORECASE)
    signer_count = int(signer_count_match.group(1)) if signer_count_match else None
    signer_certificate_sha256 = re.findall(
        r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)",
        output,
        re.IGNORECASE,
    )

    return ApkSigningInfo(
        verifier="apksigner",
        signer_count=signer_count,
        scheme_verified=scheme_verified,
        signer_certificate_sha256=signer_certificate_sha256,
    )


def output_uses_debug_certificate(output: str) -> bool:
    return re.search(r"Signer #\d+ certificate DN:.*Android Debug", output, re.IGNORECASE) is not None


def reject_debuggable_manifest(apk_path: Path) -> None:
    aapt = find_android_build_tool("aapt") or find_android_build_tool("aapt2")
    if aapt is None:
        return

    result = subprocess.run(
        [str(aapt), "dump", "badging", str(apk_path)],
        cwd=ROOT_DIR,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode == 0 and "application-debuggable" in result.stdout:
        raise ReleaseScanError(
            f"Refusing debug APK: {repo_relative_path(apk_path)} declares android:debuggable=true."
        )


def verify_apk_signing(apk_path: Path) -> ApkSigningInfo:
    apksigner = find_android_build_tool("apksigner")
    if apksigner is None:
        raise ReleaseScanError(
            "Cannot verify APK signing because apksigner was not found. "
            "Set ANDROID_HOME or ANDROID_SDK_ROOT to an Android SDK with build-tools installed."
        )

    result = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk_path)],
        cwd=ROOT_DIR,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = sanitized_tool_output(result.stdout, result.stderr)
        suffix = f" {detail}" if detail else ""
        raise ReleaseScanError(
            f"APK signature verification failed for {repo_relative_path(apk_path)} using apksigner.{suffix}"
        )

    combined_output = "\n".join((result.stdout, result.stderr))
    if output_uses_debug_certificate(combined_output):
        raise ReleaseScanError(
            f"Refusing APK signed with the Android debug certificate: {repo_relative_path(apk_path)}."
        )

    signing_info = parse_apksigner_output(combined_output)
    if not any(signing_info.scheme_verified.values()):
        raise ReleaseScanError(
            f"apksigner did not report any verified APK signature scheme for {repo_relative_path(apk_path)}."
        )
    if signing_info.signer_count == 0:
        raise ReleaseScanError(f"apksigner did not report any APK signers for {repo_relative_path(apk_path)}.")

    reject_debuggable_manifest(apk_path)
    return signing_info


def build_multipart_body(file_path: Path, *, extra_fields: dict[str, str] | None = None) -> tuple[bytes, str]:
    boundary = f"----codex-vt-{uuid.uuid4().hex}"
    body = bytearray()

    for field_name, field_value in (extra_fields or {}).items():
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(
            f'Content-Disposition: form-data; name="{field_name}"\r\n\r\n{field_value}\r\n'.encode("utf-8")
        )

    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(
        (
            f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
    )
    body.extend(file_path.read_bytes())
    body.extend(f"\r\n--{boundary}--\r\n".encode("utf-8"))

    return bytes(body), f"multipart/form-data; boundary={boundary}"


class VirusTotalClient:
    def __init__(self, config: VirusTotalConfig) -> None:
        self.config = config

    def _request_json(
        self,
        method: str,
        target: str,
        *,
        body: bytes | None = None,
        content_type: str | None = None,
        allow_missing: bool = False,
    ) -> dict[str, Any] | None:
        url = target if target.startswith("http://") or target.startswith("https://") else f"{VT_API_BASE_URL}{target}"
        http_request = request.Request(url=url, method=method, data=body)
        http_request.add_header("accept", "application/json")
        http_request.add_header("x-apikey", self.config.api_key)
        if content_type:
            http_request.add_header("content-type", content_type)

        try:
            with request.urlopen(http_request, timeout=90) as response:
                payload = response.read().decode("utf-8")
                return json.loads(payload) if payload else {}
        except error.HTTPError as exc:
            response_body = exc.read().decode("utf-8", errors="replace")
            if allow_missing and exc.code == 404:
                return None
            raise VirusTotalApiError(
                f"VirusTotal request failed with HTTP {exc.code}: {method} {url}",
                status_code=exc.code,
                body=response_body,
            ) from exc
        except error.URLError as exc:
            raise VirusTotalApiError(f"VirusTotal request failed: {exc.reason}") from exc

    def fetch_file(self, sha256_hash: str) -> dict[str, Any] | None:
        return self._request_json("GET", f"/files/{sha256_hash}", allow_missing=True)

    def get_upload_url(self) -> str:
        path = "/private/files/upload_url" if self.config.private_scanning else "/files/upload_url"
        response = self._request_json("GET", path)
        upload_url = (response or {}).get("data")
        if not isinstance(upload_url, str) or not upload_url:
            raise ReleaseScanError("VirusTotal did not return a usable upload URL.")
        return upload_url

    def upload_file(self, file_path: Path) -> dict[str, Any]:
        extra_fields: dict[str, str] = {}
        if self.config.private_scanning:
            extra_fields["enable_internet"] = str(self.config.enable_internet).lower()
            extra_fields["intercept_tls"] = str(self.config.intercept_tls).lower()

        body, content_type = build_multipart_body(file_path, extra_fields=extra_fields)
        upload_target = (
            self.get_upload_url() if file_path.stat().st_size > LARGE_FILE_THRESHOLD_BYTES else
            ("/private/files" if self.config.private_scanning else "/files")
        )
        return self._request_json("POST", upload_target, body=body, content_type=content_type) or {}

    def poll_analysis(self, analysis_id: str, *, timeout_seconds: int, poll_interval_seconds: int) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() <= deadline:
            response = self._request_json("GET", f"/analyses/{analysis_id}") or {}
            status = (((response.get("data") or {}).get("attributes") or {}).get("status"))
            if status == "completed":
                return response
            time.sleep(poll_interval_seconds)
        raise ReleaseScanError(
            f"Timed out waiting for VirusTotal analysis {analysis_id} after {timeout_seconds} seconds."
        )

    def fetch_contacted_domains(self, sha256_hash: str, *, limit: int) -> tuple[list[dict[str, Any]], str | None]:
        collected: list[dict[str, Any]] = []
        next_target = f"/files/{sha256_hash}/contacted_domains?limit={min(limit, 40)}"

        while next_target and len(collected) < limit:
            try:
                response = self._request_json("GET", next_target)
            except VirusTotalApiError as exc:
                if exc.status_code in {403, 404}:
                    return [], (
                        "VirusTotal did not return contacted domain data for this file. "
                        "That can happen when sandbox network evidence is unavailable for the API plan or sample."
                    )
                raise

            payload = response or {}
            collected.extend(payload.get("data") or [])
            next_target = (payload.get("links") or {}).get("next")

        return collected[:limit], None


def build_vt_config() -> VirusTotalConfig:
    api_key = os.environ.get("VIRUSTOTAL_API_KEY")
    if not api_key:
        raise ReleaseScanError(
            "Missing VIRUSTOTAL_API_KEY. Add it to .env or the process environment before running this script."
        )

    private_scanning = parse_bool(os.environ.get("VIRUSTOTAL_PRIVATE_SCANNING"))
    enable_internet = parse_bool(os.environ.get("VIRUSTOTAL_ENABLE_INTERNET"), default=private_scanning)
    intercept_tls = parse_bool(os.environ.get("VIRUSTOTAL_INTERCEPT_TLS"))

    return VirusTotalConfig(
        api_key=api_key,
        private_scanning=private_scanning,
        enable_internet=enable_internet,
        intercept_tls=intercept_tls,
    )


def wait_for_file_report(
    client: VirusTotalClient,
    sha256_hash: str,
    *,
    timeout_seconds: int,
    poll_interval_seconds: int,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() <= deadline:
        file_report = client.fetch_file(sha256_hash)
        if file_report is not None:
            return file_report
        time.sleep(poll_interval_seconds)
    raise ReleaseScanError(
        f"Timed out waiting for VirusTotal to publish a file report for {sha256_hash}."
    )


def build_release_evidence(
    client: VirusTotalClient,
    apk_path: Path,
    signing_info: ApkSigningInfo,
    *,
    force_upload: bool,
    analysis_timeout: int,
    poll_interval: int,
    domains_limit: int,
) -> dict[str, Any]:
    sha256_hash = sha256_for_file(apk_path)
    file_report = client.fetch_file(sha256_hash)
    uploaded = False
    analysis_response: dict[str, Any] | None = None

    if file_report is None or force_upload:
        print("Submitting APK to VirusTotal...")
        upload_response = client.upload_file(apk_path)
        analysis_id = (upload_response.get("data") or {}).get("id")
        if not isinstance(analysis_id, str) or not analysis_id:
            raise ReleaseScanError("VirusTotal upload did not return a usable analysis ID.")
        analysis_response = client.poll_analysis(
            analysis_id,
            timeout_seconds=analysis_timeout,
            poll_interval_seconds=poll_interval,
        )
        file_report = wait_for_file_report(
            client,
            sha256_hash,
            timeout_seconds=analysis_timeout,
            poll_interval_seconds=poll_interval,
        )
        uploaded = True

    if file_report is None:
        raise ReleaseScanError("VirusTotal did not return a file report for the APK.")

    contacted_domains, domains_note = client.fetch_contacted_domains(sha256_hash, limit=domains_limit)
    file_attributes = (file_report.get("data") or {}).get("attributes") or {}
    analysis_attributes = ((analysis_response or {}).get("data") or {}).get("attributes") or {}

    return {
        "generated_at": datetime.now(tz=UTC).isoformat().replace("+00:00", "Z"),
        "apk": {
            "path": repo_relative_path(apk_path),
            "filename": apk_path.name,
            "size_bytes": apk_path.stat().st_size,
            "sha256": sha256_hash,
        },
        "signing": {
            "verified": True,
            "verifier": signing_info.verifier,
            "signer_count": signing_info.signer_count,
            "scheme_verified": signing_info.scheme_verified,
            "signer_certificate_sha256": signing_info.signer_certificate_sha256,
        },
        "virustotal": {
            "report_url": f"{VT_GUI_BASE_URL}/{sha256_hash}",
            "analysis_mode": "private" if client.config.private_scanning else "public",
            "enable_internet": client.config.enable_internet if client.config.private_scanning else False,
            "intercept_tls": client.config.intercept_tls if client.config.private_scanning else False,
            "source": "new_upload" if uploaded else "existing_hash_lookup",
            "analysis_id": ((analysis_response or {}).get("data") or {}).get("id"),
            "analysis_status": analysis_attributes.get("status"),
            "last_analysis_date": format_timestamp(file_attributes.get("last_analysis_date")),
            "first_submission_date": format_timestamp(file_attributes.get("first_submission_date")),
            "last_submission_date": format_timestamp(file_attributes.get("last_submission_date")),
            "last_analysis_stats": file_attributes.get("last_analysis_stats") or {},
            "contacted_domains": contacted_domains,
            "contacted_domains_note": domains_note,
        },
    }


def ordered_stat_items(stats: dict[str, int]) -> list[tuple[str, int]]:
    preferred_order = [
        "malicious",
        "suspicious",
        "harmless",
        "undetected",
        "timeout",
        "failure",
        "type-unsupported",
        "confirmed-timeout",
    ]
    seen = set()
    items: list[tuple[str, int]] = []

    for key in preferred_order:
        if key in stats:
            items.append((key, stats[key]))
            seen.add(key)

    for key in sorted(stats):
        if key not in seen:
            items.append((key, stats[key]))

    return items


def render_domain_line(domain_object: dict[str, Any]) -> str:
    domain_id = domain_object.get("id", "unknown-domain")
    attributes = domain_object.get("attributes") or {}
    stats = attributes.get("last_analysis_stats") or {}
    categories = attributes.get("categories") or {}

    qualifiers: list[str] = []
    if stats:
        malicious = stats.get("malicious")
        suspicious = stats.get("suspicious")
        if malicious is not None or suspicious is not None:
            qualifiers.append(f"VT malicious={malicious or 0}, suspicious={suspicious or 0}")
    if categories:
        category_text = ", ".join(f"{name}: {value}" for name, value in sorted(categories.items()))
        qualifiers.append(f"categories: {category_text}")

    if not qualifiers:
        return f"- `{domain_id}`"

    return f"- `{domain_id}` ({'; '.join(qualifiers)})"


def build_markdown_report(evidence: dict[str, Any]) -> str:
    apk = evidence["apk"]
    signing = evidence["signing"]
    vt = evidence["virustotal"]
    stats = vt.get("last_analysis_stats") or {}
    domain_objects = vt.get("contacted_domains") or []

    lines = [
        "## Release Integrity",
        "",
        f"- APK: `{apk['filename']}`",
        f"- Artifact path: `{apk['path']}`",
        f"- SHA-256: `{apk['sha256']}`",
        f"- Signature verifier: `{signing['verifier']}`",
        f"- Signature schemes verified: `{json.dumps(signing['scheme_verified'], sort_keys=True)}`",
        f"- VirusTotal report: {vt['report_url']}",
        "",
        "## VirusTotal Summary",
        "",
        f"- Evidence source: `{vt['source']}`",
        f"- Scan mode: `{vt['analysis_mode']}`",
    ]

    if vt.get("analysis_mode") == "private":
        lines.append(f"- Internet-enabled sandbox requested: `{str(vt.get('enable_internet')).lower()}`")
        lines.append(f"- TLS interception requested: `{str(vt.get('intercept_tls')).lower()}`")

    if vt.get("last_analysis_date"):
        lines.append(f"- Last analysis date: `{vt['last_analysis_date']}`")

    if stats:
        lines.extend(
            [
                "",
                "| Signal | Count |",
                "| --- | ---: |",
            ]
        )
        for key, value in ordered_stat_items(stats):
            lines.append(f"| {key} | {value} |")

    lines.extend(
        [
            "",
            "## Contacted Domains Observed By VirusTotal",
            "",
            "These domains were reported by VirusTotal as contacted during analysis of the signed APK. "
            "This is sandbox observation data, not a complete proof of every possible runtime destination.",
            "",
        ]
    )

    if domain_objects:
        lines.extend(render_domain_line(domain_object) for domain_object in domain_objects)
    else:
        lines.append(
            vt.get("contacted_domains_note")
            or "VirusTotal did not report any contacted domains for this file."
        )

    lines.append("")
    return "\n".join(lines)


def write_reports(evidence: dict[str, Any], output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)

    apk_filename = Path(evidence["apk"]["filename"]).stem
    json_path = output_dir / f"{apk_filename}.virustotal.json"
    markdown_path = output_dir / f"{apk_filename}.release-evidence.md"

    json_path.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    markdown_path.write_text(build_markdown_report(evidence), encoding="utf-8")

    return json_path, markdown_path


def main() -> int:
    args = parse_args()
    load_env_file(args.env_file)

    validate_explicit_apk_build_flow(args.apk, skip_build=args.skip_build)
    built_release_apk = None
    if not args.skip_build:
        built_release_apk = run_release_build()

    apk_path = resolve_apk_path(
        args.apk,
        skip_build=args.skip_build,
        built_release_apk=built_release_apk,
    )
    signing_info = verify_apk_signing(apk_path)
    vt_client = VirusTotalClient(build_vt_config())
    evidence = build_release_evidence(
        vt_client,
        apk_path,
        signing_info,
        force_upload=args.force_upload,
        analysis_timeout=args.analysis_timeout,
        poll_interval=args.poll_interval,
        domains_limit=args.domains_limit,
    )
    json_path, markdown_path = write_reports(evidence, args.output_dir)

    print(f"Signed APK: {repo_relative_path(apk_path)}")
    print(f"SHA-256: {evidence['apk']['sha256']}")
    print(f"VirusTotal report: {evidence['virustotal']['report_url']}")
    print(f"JSON evidence: {repo_relative_path(json_path)}")
    print(f"Markdown evidence: {repo_relative_path(markdown_path)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReleaseScanError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    except subprocess.CalledProcessError as exc:
        print(f"error: release build failed with exit code {exc.returncode}", file=sys.stderr)
        raise SystemExit(exc.returncode) from exc
