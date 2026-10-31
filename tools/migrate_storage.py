#!/usr/bin/env python3
"""Copy legacy Hub data/media into a new unified storage root, without cutover."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
COVER = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png)$")


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def reject_symlink_path(path: Path, label: str) -> None:
    for candidate in (path.absolute(), *path.absolute().parents):
        if candidate.is_symlink():
            raise ValueError(f"Symlinks are not allowed for {label}: {candidate}")


def paths_overlap(first: Path, second: Path) -> bool:
    return first == second or first in second.parents or second in first.parents


def files(root: Path) -> dict[str, Path]:
    if root.is_symlink():
        raise ValueError(f"Symlinks are not allowed in migration input: {root}")
    if not root.is_dir():
        raise ValueError(f"Required directory is missing: {root}")
    result: dict[str, Path] = {}
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"Symlinks are not allowed in migration input: {path}")
        if path.is_file():
            result[path.relative_to(root).as_posix()] = path
    return result


def compare_backup(source: Path, backup: Path, label: str) -> dict[str, int]:
    source_files = files(source)
    backup_files = files(backup)
    missing = sorted(set(source_files) - set(backup_files))
    extra = sorted(set(backup_files) - set(source_files))
    mismatched = [name for name in source_files.keys() & backup_files.keys()
                  if digest(source_files[name]) != digest(backup_files[name])]
    if missing or extra or mismatched:
        raise ValueError(f"Backup verification failed for {label}: missing={missing}, extra={extra}, hash_mismatch={mismatched}")
    return {"files": len(source_files), "bytes": sum(p.stat().st_size for p in source_files.values())}


def safe_target(root: Path, relative: str) -> Path:
    if root.is_symlink():
        raise ValueError(f"Symlinks are not allowed in migration destination: {root}")
    relative_path = Path(relative)
    if relative_path.is_absolute() or any(part == ".." for part in relative_path.parts):
        raise ValueError(f"Unsafe target path: {relative}")
    root_path = root.resolve()
    candidate = root_path / relative_path
    current = candidate
    while current != root_path and root_path in current.parents:
        if current.is_symlink():
            raise ValueError(f"Symlinks are not allowed in migration destination: {current}")
        current = current.parent
    path = candidate.resolve()
    if not path.is_relative_to(root_path):
        raise ValueError(f"Unsafe target path: {relative}")
    return path


def plan_copy(source: Path, destination: Path, relative: str, report: dict) -> None:
    target = safe_target(destination, relative)
    if target.exists():
        if not target.is_file() or digest(source) != digest(target):
            report["mismatches"].append({"source": str(source), "target": str(target), "reason": "destination content differs"})
        else:
            report["already_present"].append(relative)
    else:
        report["planned_copies"].append({"source": str(source), "target": relative, "sha256": digest(source), "bytes": source.stat().st_size})


def validate_cover_index(index_file: Path) -> dict[str, dict[str, str]]:
    if index_file.is_symlink():
        raise ValueError(f"Symlink cover index is not allowed: {index_file}")

    def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate key in cover index: {key!r}")
            result[key] = value
        return result

    data = json.loads(index_file.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicate_keys)
    if not isinstance(data, dict) or set(data) != {"owners"} or not isinstance(data["owners"], dict):
        raise ValueError("cover-index.json must contain only an owners object exported from the isolated H2 copy")
    owners = data["owners"]
    result: dict[str, dict[str, str]] = {}
    for owner, covers in owners.items():
        if not isinstance(owner, str) or not UUID.fullmatch(owner.lower()) or not isinstance(covers, dict):
            raise ValueError(f"Invalid owner entry in cover index: {owner!r}")
        normalized_owner = owner.lower()
        if normalized_owner in result:
            raise ValueError(f"Duplicate owner in cover index: {owner!r}")
        result[normalized_owner] = {}
        for name, expected_hash in covers.items():
            if not isinstance(name, str) or not COVER.fullmatch(name) or not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash, re.IGNORECASE):
                raise ValueError(f"Invalid cover name/hash in index for owner {owner}: {name!r}")
            result[normalized_owner][name] = expected_hash.lower()
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-data", type=Path, required=True, help="Offline copy of old hub_data volume contents")
    parser.add_argument("--source-media", type=Path, required=True, help="Offline copy of old hub_media volume contents")
    parser.add_argument("--backup-root", type=Path, required=True, help="Verified mirror containing hub_data/ and hub_media/")
    parser.add_argument("--cover-index", type=Path, required=True, help="Owner/name/SHA-256 export from an isolated H2 database copy")
    parser.add_argument("--destination", type=Path, required=True, help="New, separate hub_storage volume root")
    parser.add_argument("--report", type=Path, required=True, help="JSON report path")
    parser.add_argument("--backend-stopped", action="store_true", help="Explicitly attest the backend is stopped")
    parser.add_argument("--backup-verified", action="store_true", help="Explicitly attest the backup was verified")
    parser.add_argument("--apply", action="store_true", help="Write planned files; omission performs a dry run")
    args = parser.parse_args()

    report = {"mode": "apply" if args.apply else "dry-run", "cutover": "not performed", "planned_copies": [],
              "already_present": [], "mismatches": [], "orphans": [], "errors": [], "backup": {}, "result": "pending"}
    try:
        for path, label in ((args.source_data, "source data"), (args.source_media, "source media"),
                            (args.backup_root, "backup root"), (args.cover_index, "cover index"),
                            (args.destination, "destination")):
            reject_symlink_path(path, label)
        source_data = args.source_data.resolve(strict=True)
        source_media = args.source_media.resolve(strict=True)
        destination = args.destination.resolve()
        backup = args.backup_root.resolve(strict=True)
        cover_index_path = args.cover_index.resolve(strict=True)
        report_path = args.report.resolve()
        if not args.backend_stopped:
            raise ValueError("Refusing to proceed without --backend-stopped")
        if not args.backup_verified:
            raise ValueError("Refusing to proceed without --backup-verified")
        if any(paths_overlap(destination, root) for root in (source_data, source_media, backup)):
            raise ValueError("Destination must be separate from source and backup roots")
        if paths_overlap(backup, source_data) or paths_overlap(backup, source_media):
            raise ValueError("Backup must be separate from source roots")
        if any(paths_overlap(report_path, root) for root in (source_data, source_media, backup, destination)) or report_path == cover_index_path:
            raise ValueError("Report path must be separate from source, backup, destination, and cover index")
        report["backup"]["hub_data"] = compare_backup(source_data, backup / "hub_data", "hub_data")
        report["backup"]["hub_media"] = compare_backup(source_media, backup / "hub_media", "hub_media")
        index = validate_cover_index(cover_index_path)

        for relative, source in sorted(files(source_data).items()):
            target_relative = f"database/{relative}"
            if relative.startswith("database/"):
                target_relative = relative
            elif relative.startswith("hub"):
                target_relative = f"database/{relative}"
            plan_copy(source, destination, target_relative, report)

        media_root = source_media / "reading-library" / "covers"
        if (source_media / "reading-library").is_symlink() or media_root.is_symlink():
            raise ValueError("Symlinks are not allowed in the legacy covers directory")
        actual = files(media_root) if media_root.exists() else {}
        referenced: set[str] = set()
        for owner, covers in sorted(index.items()):
            for name, expected_hash in sorted(covers.items()):
                referenced.add(name)
                thumb_name = name.rsplit(".", 1)[0] + ".thumb." + name.rsplit(".", 1)[1]
                referenced.add(thumb_name)
                source = actual.get(name)
                if source is None:
                    report["mismatches"].append({"ownerId": owner, "cover": name, "reason": "database reference has no source file"})
                    continue
                actual_hash = digest(source)
                if actual_hash != expected_hash:
                    report["mismatches"].append({"ownerId": owner, "cover": name, "expectedSha256": expected_hash, "actualSha256": actual_hash})
                    continue
                base = f"users/{owner}/reading-library/covers/{name}"
                plan_copy(source, destination, base, report)
                thumb = actual.get(thumb_name)
                if thumb is None:
                    report["mismatches"].append({"ownerId": owner, "cover": name, "reason": "thumbnail is missing"})
                else:
                    plan_copy(thumb, destination, f"users/{owner}/reading-library/covers/{thumb_name}", report)

        for name, source in sorted(actual.items()):
            if name not in referenced:
                file_hash = digest(source)
                quarantine_target = f"quarantine/legacy-reading-covers/{file_hash}/{Path(name).name}"
                already_present = safe_target(destination, quarantine_target).is_file()
                plan_copy(source, destination, quarantine_target, report)
                report["orphans"].append({
                    "file": name,
                    "sha256": file_hash,
                    "bytes": source.stat().st_size,
                    "quarantineTarget": quarantine_target,
                    "action": "already quarantined" if already_present else "copy to non-served quarantine",
                })

        if report["mismatches"]:
            report["result"] = "blocked-mismatches"
        elif args.apply:
            for item in report["planned_copies"]:
                source = Path(item["source"])
                target = safe_target(destination, item["target"])
                target.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".hub-migration-", delete=False) as temp:
                    temporary = Path(temp.name)
                    with source.open("rb") as stream:
                        shutil.copyfileobj(stream, temp)
                    temp.flush()
                    os.fsync(temp.fileno())
                if digest(temporary) != item["sha256"]:
                    temporary.unlink(missing_ok=True)
                    report["mismatches"].append({"source": str(source), "target": str(target), "reason": "source changed during copy"})
                    continue
                try:
                    temporary.replace(target)
                except FileExistsError:
                    temporary.unlink(missing_ok=True)
                    if not target.is_file() or digest(target) != item["sha256"]:
                        report["mismatches"].append({"source": str(source), "target": str(target), "reason": "concurrent destination differs"})
            report["result"] = "completed-with-mismatches" if report["mismatches"] else "copied-no-cutover"
        else:
            report["result"] = "ready-for-apply" if not report["mismatches"] else "blocked-mismatches"
    except Exception as error:  # Include a machine-readable failure report for operators.
        report["errors"].append(str(error))
        report["result"] = "failed"

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report["result"] in {"ready-for-apply", "copied-no-cutover"} else 2


if __name__ == "__main__":
    sys.exit(main())
