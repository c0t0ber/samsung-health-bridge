#!/usr/bin/env python3
"""Capture privacy-safe evidence for a natural Samsung Health Bridge run.

Take one snapshot immediately after update-in-place installation, then another
without opening the app or triggering WorkManager.  Passing the first snapshot
as --baseline evaluates whether the periodic worker both completed a period and
advanced the app's successful sync timestamp.  Google Sheet readback remains a
separate required proof.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PACKAGE = "com.roktober.samsunghealthbridge"
CURRENT_WORK_NAME = "daily-health-sheet-sync-v2"
LEGACY_WORK_NAMES = ("daily-health-sheet-sync",)
WORK_NAMES = (CURRENT_WORK_NAME, *LEGACY_WORK_NAMES)
WORK_DB_CANDIDATES = (
    Path("no_backup/androidx.work.workdb"),
    Path("databases/androidx.work.workdb"),
)
ARCHIVE_ALLOWED_FILES = frozenset(
    {
        *(str(path) for path in WORK_DB_CANDIDATES),
        *(f"{path}-wal" for path in WORK_DB_CANDIDATES),
        *(f"{path}-shm" for path in WORK_DB_CANDIDATES),
        "shared_prefs/bridge_state.xml",
    }
)
CORE_PERMISSIONS = (
    "android.permission.health.READ_STEPS",
    "android.permission.health.READ_EXERCISE",
    "android.permission.health.READ_SLEEP",
    "android.permission.health.READ_WEIGHT",
    "android.permission.health.READ_BODY_FAT",
)
BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
WORK_STATES = {
    0: "ENQUEUED",
    1: "RUNNING",
    2: "SUCCEEDED",
    3: "FAILED",
    4: "BLOCKED",
    5: "CANCELLED",
}


def _parse_timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def evaluate_natural_run(
    baseline: dict[str, Any],
    follow_up: dict[str, Any],
) -> dict[str, bool]:
    """Evaluate device-side proof without treating registration as execution."""
    before_periodic = baseline.get("periodic") or {}
    after_periodic = follow_up.get("periodic") or {}
    before_count = before_periodic.get("period_count")
    after_count = after_periodic.get("period_count")
    before_generation = before_periodic.get("generation")
    after_generation = after_periodic.get("generation")
    before_sync = _parse_timestamp(baseline.get("last_sync_at"))
    after_sync = _parse_timestamp(follow_up.get("last_sync_at"))

    period_completed = (
        isinstance(before_count, int)
        and isinstance(after_count, int)
        and after_count > before_count
    )
    sync_timestamp_advanced = (
        before_sync is not None and after_sync is not None and after_sync > before_sync
    )
    generation_preserved = (
        isinstance(before_generation, int)
        and before_generation == after_generation
    )
    successful_status = follow_up.get("last_status") == "ok"

    return {
        "period_completed": period_completed,
        "sync_timestamp_advanced": sync_timestamp_advanced,
        "generation_preserved": generation_preserved,
        "successful_status": successful_status,
        "device_natural_run_proven": (
            period_completed
            and sync_timestamp_advanced
            and generation_preserved
            and successful_status
        ),
        "canonical_sheet_readback_required": True,
        "backup_sheet_must_remain_unchanged": True,
    }


def _run_adb(adb: Path, serial: str, *args: str, binary: bool = False) -> bytes | str:
    command = [str(adb), "-s", serial, *args]
    completed = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=not binary,
    )
    if completed.returncode != 0:
        stderr = (
            completed.stderr.decode("utf-8", errors="replace")
            if binary
            else completed.stderr
        )
        raise RuntimeError(f"ADB command failed ({' '.join(args)}): {stderr.strip()}")
    return completed.stdout


def _permission_granted(package_dump: str, permission: str) -> bool:
    pattern = rf"{re.escape(permission)}:\s+granted=true"
    return re.search(pattern, package_dump) is not None


def _count_scheduler_jobs(jobs_dump: str) -> int:
    service = f"{PACKAGE}/androidx.work.impl.background.systemjob.SystemJobService"
    return sum(
        1
        for line in jobs_dump.splitlines()
        if line.lstrip().startswith("JOB ") and service in line
    )


def _extract_archive(archive: tarfile.TarFile, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    for member in archive.getmembers():
        name = member.name.removeprefix("./")
        if name not in ARCHIVE_ALLOWED_FILES:
            raise ValueError(f"Unexpected archive member: {member.name}")
        if name in seen:
            raise ValueError(f"Duplicate archive member: {member.name}")
        if not member.isfile():
            raise ValueError(f"Archive member is not a regular file: {member.name}")
        source = archive.extractfile(member)
        if source is None:
            raise ValueError(f"Could not read archive member: {member.name}")

        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with source, target.open("wb") as output:
            shutil.copyfileobj(source, output)
        seen.add(name)


def _read_preferences(path: Path) -> dict[str, str | None]:
    if not path.exists():
        return {"last_sync_at": None, "last_status": None}
    root = ET.parse(path).getroot()
    values: dict[str, str] = {}
    for child in root:
        name = child.attrib.get("name")
        if not name:
            continue
        values[name] = child.text or child.attrib.get("value", "")
    return {
        "last_sync_at": values.get("last_sync_at"),
        "last_status": values.get("last_status"),
    }


def _milliseconds_to_iso(value: int | None) -> str | None:
    if value is None or value < 0:
        return None
    return datetime.fromtimestamp(value / 1000, tz=timezone.utc).isoformat().replace(
        "+00:00", "Z"
    )


def _read_periodic_work(database: Path) -> dict[str, Any] | None:
    if not database.exists():
        return None
    with sqlite3.connect(database) as connection:
        placeholders = ",".join("?" for _ in WORK_NAMES)
        row = connection.execute(
            f"""
            SELECT n.name, w.state, w.run_attempt_count, w.period_count, w.generation,
                   w.last_enqueue_time, w.schedule_requested_at,
                   w.interval_duration, w.flex_duration
              FROM workspec AS w
              JOIN workname AS n ON n.work_spec_id = w.id
             WHERE n.name IN ({placeholders})
             ORDER BY CASE WHEN n.name = ? THEN 0 ELSE 1 END, w.generation DESC
             LIMIT 1
            """,
            (*WORK_NAMES, CURRENT_WORK_NAME),
        ).fetchone()
    if row is None:
        return None
    (
        work_name,
        state,
        run_attempt_count,
        period_count,
        generation,
        last_enqueue_time,
        schedule_requested_at,
        interval_duration,
        flex_duration,
    ) = row
    return {
        "work_name": work_name,
        "state": WORK_STATES.get(state, f"UNKNOWN({state})"),
        "run_attempt_count": run_attempt_count,
        "period_count": period_count,
        "generation": generation,
        "last_enqueue_at": _milliseconds_to_iso(last_enqueue_time),
        "schedule_requested_at": _milliseconds_to_iso(schedule_requested_at),
        "interval_hours": interval_duration / 3_600_000,
        "flex_hours": flex_duration / 3_600_000,
    }


def _find_work_database(root: Path) -> Path | None:
    return next((root / path for path in WORK_DB_CANDIDATES if (root / path).exists()), None)


def _capture_private_state(adb: Path, serial: str) -> tuple[dict[str, Any] | None, dict[str, str | None]]:
    archive_command = (
        "set --; "
        "for f in no_backup/androidx.work.workdb "
        "no_backup/androidx.work.workdb-wal "
        "no_backup/androidx.work.workdb-shm "
        "databases/androidx.work.workdb "
        "databases/androidx.work.workdb-wal "
        "databases/androidx.work.workdb-shm "
        "shared_prefs/bridge_state.xml; do "
        '[ -f "$f" ] && set -- "$@" "$f"; '
        "done; "
        '[ "$#" -gt 0 ] || exit 2; '
        'tar -cf - "$@"'
    )
    archive = _run_adb(
        adb,
        serial,
        "exec-out",
        "run-as",
        PACKAGE,
        "sh",
        "-c",
        archive_command,
        binary=True,
    )
    assert isinstance(archive, bytes)
    with tempfile.TemporaryDirectory(prefix="health-bridge-proof-") as temp_dir:
        root = Path(temp_dir)
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
            _extract_archive(tar, root)
        work_database = _find_work_database(root)
        periodic = _read_periodic_work(work_database) if work_database else None
        preferences = _read_preferences(root / "shared_prefs/bridge_state.xml")
    return periodic, preferences


def capture_state(adb: Path, serial: str) -> dict[str, Any]:
    state = _run_adb(adb, serial, "get-state")
    if not isinstance(state, str) or state.strip() != "device":
        raise RuntimeError("Selected ADB target is not in device state")

    package_dump = _run_adb(adb, serial, "shell", "dumpsys", "package", PACKAGE)
    jobs_dump = _run_adb(adb, serial, "shell", "dumpsys", "jobscheduler")
    assert isinstance(package_dump, str)
    assert isinstance(jobs_dump, str)
    periodic, preferences = _capture_private_state(adb, serial)

    version_name = re.search(r"versionName=([^\s]+)", package_dump)
    version_code = re.search(r"versionCode=(\d+)", package_dump)
    scheduler_job_count = _count_scheduler_jobs(jobs_dump)
    permissions = {
        permission.rsplit(".", 1)[-1]: _permission_granted(package_dump, permission)
        for permission in (*CORE_PERMISSIONS, BACKGROUND_PERMISSION)
    }

    return {
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "package": PACKAGE,
        "version_name": version_name.group(1) if version_name else None,
        "version_code": int(version_code.group(1)) if version_code else None,
        "permissions": permissions,
        "all_core_permissions_granted": all(
            permissions[p.rsplit(".", 1)[-1]] for p in CORE_PERMISSIONS
        ),
        "background_permission_granted": permissions[
            BACKGROUND_PERMISSION.rsplit(".", 1)[-1]
        ],
        "jobscheduler_job_present": scheduler_job_count > 0,
        "jobscheduler_job_count": scheduler_job_count,
        "last_sync_at": preferences["last_sync_at"],
        "last_status": preferences["last_status"],
        "periodic": periodic,
    }


def _default_adb() -> Path:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        return Path(sdk) / "platform-tools" / "adb"
    return Path.home() / "Library/Android/sdk/platform-tools/adb"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="User-confirmed ADB serial")
    parser.add_argument("--adb", type=Path, default=_default_adb())
    parser.add_argument("--output", type=Path, help="Write the privacy-safe snapshot JSON")
    parser.add_argument(
        "--baseline",
        type=Path,
        help="Earlier snapshot; adds a natural-run evaluation to the new snapshot",
    )
    args = parser.parse_args()

    snapshot = capture_state(args.adb, args.serial)
    if args.baseline:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        snapshot["proof"] = evaluate_natural_run(baseline, snapshot)

    rendered = json.dumps(snapshot, indent=2, sort_keys=True)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
