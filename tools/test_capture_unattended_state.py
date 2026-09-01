import importlib.util
import io
import sqlite3
import tarfile
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("capture_unattended_state.py")
SPEC = importlib.util.spec_from_file_location("capture_unattended_state", MODULE_PATH)
assert SPEC is not None
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class NaturalRunProofTest(unittest.TestCase):
    def test_periodic_completion_and_new_sync_timestamp_prove_natural_device_run(self):
        baseline = {
            "last_sync_at": "2026-08-26T10:42:07.220213Z",
            "last_status": "ok",
            "periodic": {"period_count": 0, "generation": 7},
        }
        follow_up = {
            "last_sync_at": "2026-08-28T20:00:00Z",
            "last_status": "ok",
            "periodic": {"period_count": 1, "generation": 7},
        }

        proof = MODULE.evaluate_natural_run(baseline, follow_up)

        self.assertTrue(proof["period_completed"])
        self.assertTrue(proof["sync_timestamp_advanced"])
        self.assertTrue(proof["generation_preserved"])
        self.assertTrue(proof["device_natural_run_proven"])
        self.assertTrue(proof["canonical_sheet_readback_required"])

    def test_period_count_without_new_successful_sync_is_not_proof(self):
        baseline = {
            "last_sync_at": "2026-08-26T10:42:07.220213Z",
            "last_status": "ok",
            "periodic": {"period_count": 0, "generation": 7},
        }
        follow_up = {
            "last_sync_at": "2026-08-26T10:42:07.220213Z",
            "last_status": "google_action_required",
            "periodic": {"period_count": 1, "generation": 7},
        }

        proof = MODULE.evaluate_natural_run(baseline, follow_up)

        self.assertTrue(proof["period_completed"])
        self.assertFalse(proof["sync_timestamp_advanced"])
        self.assertFalse(proof["device_natural_run_proven"])

    def test_work_database_prefers_confirmed_no_backup_location(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            confirmed = root / "no_backup/androidx.work.workdb"
            confirmed.parent.mkdir(parents=True)
            confirmed.touch()

            selected = MODULE._find_work_database(root)

            self.assertEqual(confirmed, selected)

    def test_periodic_reader_selects_v2_worker_during_legacy_migration(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Path(temp_dir) / "androidx.work.workdb"
            with sqlite3.connect(database) as connection:
                connection.execute(
                    """
                    CREATE TABLE workspec (
                        id TEXT PRIMARY KEY,
                        state INTEGER,
                        run_attempt_count INTEGER,
                        period_count INTEGER,
                        generation INTEGER,
                        last_enqueue_time INTEGER,
                        schedule_requested_at INTEGER,
                        interval_duration INTEGER,
                        flex_duration INTEGER
                    )
                    """
                )
                connection.execute(
                    "CREATE TABLE workname (name TEXT, work_spec_id TEXT)"
                )
                connection.execute(
                    "INSERT INTO workspec VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ("new-id", 0, 0, 0, 0, 1000, 2000, 86400000, 7200000),
                )
                connection.execute(
                    "INSERT INTO workname VALUES (?, ?)",
                    ("daily-health-sheet-sync-v2", "new-id"),
                )

            periodic = MODULE._read_periodic_work(database)

            self.assertEqual("daily-health-sheet-sync-v2", periodic["work_name"])
            self.assertEqual("ENQUEUED", periodic["state"])

    def test_namespaced_jobscheduler_entry_is_counted(self):
        jobs_dump = """
        JOB androidx.work.systemjobscheduler:10515/5: abc #DailySyncWorker#@com.roktober.samsunghealthbridge/androidx.work.impl.background.systemjob.SystemJobService
          READY: false
        """

        self.assertEqual(1, MODULE._count_scheduler_jobs(jobs_dump))

    def test_archive_extraction_accepts_only_fixed_regular_files(self):
        archive = self._legacy_tar(
            [("shared_prefs/bridge_state.xml", b"<map />", tarfile.REGTYPE, "")]
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "snapshot"

            MODULE._extract_archive(archive, destination)

            self.assertEqual(
                b"<map />",
                (destination / "shared_prefs/bridge_state.xml").read_bytes(),
            )

    def test_archive_extraction_rejects_path_traversal_on_legacy_python(self):
        archive = self._legacy_tar(
            [("../escape", b"owned", tarfile.REGTYPE, "")]
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "snapshot"

            with self.assertRaises(ValueError):
                MODULE._extract_archive(archive, destination)

            self.assertFalse((Path(temp_dir) / "escape").exists())

    def test_archive_extraction_rejects_symlinks_on_legacy_python(self):
        archive = self._legacy_tar(
            [("shared_prefs/bridge_state.xml", b"", tarfile.SYMTYPE, "../escape")]
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "snapshot"

            with self.assertRaises(ValueError):
                MODULE._extract_archive(archive, destination)

    @staticmethod
    def _legacy_tar(entries):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for name, data, member_type, linkname in entries:
                member = tarfile.TarInfo(name)
                member.type = member_type
                member.linkname = linkname
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        buffer.seek(0)
        inner = tarfile.open(fileobj=buffer, mode="r:")

        class LegacyTar:
            def getmembers(self):
                return inner.getmembers()

            def extractfile(self, member):
                return inner.extractfile(member)

            def extractall(self, path, **kwargs):
                if "filter" in kwargs:
                    raise TypeError("unexpected keyword argument 'filter'")
                return inner.extractall(path)

        return LegacyTar()


if __name__ == "__main__":
    unittest.main()
