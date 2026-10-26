#!/usr/bin/env python3
"""Safely compact reviewed, non-game phone diagnostic ZIPs.

The command is dry-run by default. Applying requires an explicit, reviewed plan
and ``--apply``. It never recursively removes files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shutil
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from typing import Any


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
LIVE_MARKERS = {"live", "live-evidence", "diagnostics-live.zip"}
PROTECTED_SERIES = ("beta-c45", "beta-c46", "beta-c47")


class PlanError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _inside(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _lexical_relative(value: Any, label: str) -> Path:
    if not isinstance(value, str) or not value or "\x00" in value:
        raise PlanError(f"{label} must be a non-empty relative path")
    candidate = Path(value)
    if candidate.is_absolute() or candidate.drive:
        raise PlanError(f"{label} must be relative to captures/phone")
    if any(part in ("", ".", "..") for part in candidate.parts):
        raise PlanError(f"{label} contains an unsafe path component")
    return candidate


def _resolve_phone_path(phone_root: Path, value: Any, label: str) -> Path:
    relative = _lexical_relative(value, label)
    # Reject symlinks at every existing path component. Resolving first would
    # hide the fact that a lexical path crossed a link.
    cursor = phone_root
    for part in relative.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise PlanError(f"{label} traverses a symlink: {value}")
    root_resolved = phone_root.resolve(strict=True)
    resolved = (phone_root / relative).resolve(strict=False)
    if not _inside(resolved, root_resolved):
        raise PlanError(f"{label} resolves outside captures/phone")
    _check_allowed_path(relative, label)
    return resolved


def _check_allowed_path(relative: Path, label: str) -> None:
    parts = [part.casefold() for part in relative.parts]
    joined = "/".join(parts)
    if any(marker in joined for marker in PROTECTED_SERIES):
        raise PlanError(f"{label} is in a protected C4.5/C4.6/C4.7 path")
    if any(part in LIVE_MARKERS or part.startswith("live-") or part.endswith("-live")
           for part in parts) or "diagnostics-live" in joined:
        raise PlanError(f"{label} is in a live-evidence path")

    first = parts[0] if parts else ""
    allowed = first.startswith("preflight-") or first.startswith("candidate-c3-") or first.startswith("candidate-c4-review-")
    if first.startswith("beta-c42-preflight-") or first.startswith("beta-c44-preflight-"):
        allowed = "task-capture-case2" in parts
    if not allowed:
        raise PlanError(f"{label} is not in an allowed expired non-game capture area")


def _validate_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise PlanError(f"{label} must be a lowercase SHA-256 hex digest")
    return value


def _check_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise PlanError(f"{label} must be an existing regular file")


def _check_new_path(path: Path, label: str) -> None:
    if path.exists() or path.is_symlink():
        raise PlanError(f"{label} already exists")
    if not path.parent.is_dir():
        raise PlanError(f"{label} parent directory does not exist")


def _zip_member_path(value: Any) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise PlanError("keepMembers paths must be exact ZIP member names")
    if PureWindowsPath(value).drive or value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/")):
        raise PlanError(f"unsafe ZIP member path: {value}")
    pure = PurePosixPath(value)
    if pure.is_absolute() or any(part in ("", ".", "..") for part in pure.parts):
        raise PlanError(f"unsafe ZIP member path: {value}")
    return value


def _validate_keep_members(items: Any) -> list[dict[str, str]]:
    if not isinstance(items, list) or not items:
        raise PlanError("keepMembers must be a non-empty exact list")
    result: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in items:
        if not isinstance(item, dict) or set(item) != {"path", "sha256"}:
            raise PlanError("each keepMembers item must contain only path and sha256")
        name = _zip_member_path(item["path"])
        if name in seen:
            raise PlanError(f"duplicate keepMembers path: {name}")
        seen.add(name)
        result.append({"path": name, "sha256": _validate_sha(item["sha256"], f"SHA for {name}")})
    return result


def _preflight_operation(phone_root: Path, operation: Any, index: int) -> dict[str, Any]:
    label = f"operations[{index}]"
    if not isinstance(operation, dict):
        raise PlanError(f"{label} must be an object")
    if operation.get("evidenceClass") != "non_game":
        raise PlanError(f"{label}.evidenceClass must explicitly be non_game")
    kind = operation.get("operation")
    source = _resolve_phone_path(phone_root, operation.get("sourceZip"), f"{label}.sourceZip")
    expected = _validate_sha(operation.get("expectedSourceSha256"), f"{label}.expectedSourceSha256")
    _check_regular_file(source, f"{label}.sourceZip")
    if source.suffix.casefold() != ".zip" or not zipfile.is_zipfile(source):
        raise PlanError(f"{label}.sourceZip must be a valid ZIP file")
    actual = sha256_file(source)
    if actual != expected:
        raise PlanError(f"{label}.sourceZip SHA-256 changed: expected {expected}, got {actual}")
    manifest = _resolve_phone_path(phone_root, operation.get("manifestPath"), f"{label}.manifestPath")
    if manifest.suffix.casefold() != ".json":
        raise PlanError(f"{label}.manifestPath must end in .json")
    _check_new_path(manifest, f"{label}.manifestPath")
    if manifest == source:
        raise PlanError(f"{label}.manifestPath must differ from sourceZip")

    if kind == "compact":
        target = _resolve_phone_path(phone_root, operation.get("targetZip"), f"{label}.targetZip")
        if target.suffix.casefold() != ".zip":
            raise PlanError(f"{label}.targetZip must end in .zip")
        _check_new_path(target, f"{label}.targetZip")
        if target in (source, manifest) or target.parent != source.parent:
            raise PlanError(f"{label}.targetZip must be a new sibling of sourceZip")
        members = _validate_keep_members(operation.get("keepMembers"))
        with zipfile.ZipFile(source, "r") as archive:
            infos = archive.infolist()
            names: dict[str, list[zipfile.ZipInfo]] = {}
            for info in infos:
                names.setdefault(info.filename, []).append(info)
            selected: list[tuple[zipfile.ZipInfo, str]] = []
            for item in members:
                matches = names.get(item["path"], [])
                if len(matches) != 1:
                    raise PlanError(f"ZIP member must occur exactly once: {item['path']}")
                info = matches[0]
                content = archive.read(info)
                digest = sha256_bytes(content)
                if digest != item["sha256"]:
                    raise PlanError(f"ZIP member SHA-256 changed: {item['path']}")
                selected.append((info, digest))
        return {"operation": kind, "source": source, "sourceSha256": actual,
                "target": target, "manifest": manifest, "members": members,
                "selectedInfos": [info for info, _ in selected]}

    if kind == "duplicate":
        canonical = _resolve_phone_path(phone_root, operation.get("retainCanonicalFile"), f"{label}.retainCanonicalFile")
        _check_regular_file(canonical, f"{label}.retainCanonicalFile")
        if canonical.suffix.casefold() != ".zip" or not zipfile.is_zipfile(canonical):
            raise PlanError(f"{label}.retainCanonicalFile must be a valid ZIP file")
        if canonical == source or sha256_file(canonical) != actual:
            raise PlanError(f"{label}.retainCanonicalFile must exist with the same SHA-256 as sourceZip")
        return {"operation": kind, "source": source, "sourceSha256": actual,
                "canonical": canonical, "manifest": manifest}

    raise PlanError(f"{label}.operation must be compact or duplicate")


def _write_new_file(path: Path, writer) -> None:
    """Create a file without overwriting a path that appeared after preflight."""
    with path.open("xb") as stream:
        writer(stream)
        stream.flush()
        os.fsync(stream.fileno())


def _stage_compact(operation: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
    source: Path = operation["source"]
    target: Path = operation["target"]
    temp_fd, temp_name = tempfile.mkstemp(prefix=".compact-phone-", suffix=".zip", dir=target.parent)
    os.close(temp_fd)
    temp = Path(temp_name)
    member_records = []
    try:
        with zipfile.ZipFile(source, "r") as src, zipfile.ZipFile(temp, "w") as dst:
            for item in operation["members"]:
                info = next(info for info in operation["selectedInfos"] if info.filename == item["path"])
                content = src.read(info)
                # Preserve timestamps and file attributes while retaining the
                # original compression method. Member bytes were pre-verified.
                dst.writestr(info, content)
                member_records.append({"path": item["path"], "sha256": sha256_bytes(content), "bytes": len(content)})
        with zipfile.ZipFile(temp, "r") as check:
            if len(check.infolist()) != len(operation["members"]):
                raise PlanError("staged ZIP member count mismatch")
            for item in operation["members"]:
                content = check.read(item["path"])
                if sha256_bytes(content) != item["sha256"]:
                    raise PlanError(f"staged ZIP verification failed: {item['path']}")
        info = {"targetSha256": sha256_file(temp), "targetBytes": temp.stat().st_size,
                "members": member_records}
        return temp, info
    except Exception:
        temp.unlink(missing_ok=True)
        raise


def _commit_new_from_temp(temp: Path, target: Path) -> None:
    # O_EXCL prevents overwriting a file that appeared after preflight.
    _check_new_path(target, "output")
    created = False
    try:
        with target.open("xb") as out:
            created = True
            with temp.open("rb") as src:
                shutil.copyfileobj(src, out, length=1024 * 1024)
            out.flush()
            os.fsync(out.fileno())
    except Exception:
        if created and target.exists():
            target.unlink()
        raise
    finally:
        temp.unlink(missing_ok=True)


def _verify_final_compact(item: dict[str, Any], staged: dict[str, Any]) -> None:
    target: Path = item["target"]
    if sha256_file(target) != staged["targetSha256"]:
        raise PlanError(f"installed ZIP hash mismatch: {target}")
    with zipfile.ZipFile(target, "r") as archive:
        if archive.testzip() is not None or len(archive.infolist()) != len(item["members"]):
            raise PlanError(f"installed ZIP integrity check failed: {target}")
        for member in item["members"]:
            content = archive.read(member["path"])
            if sha256_bytes(content) != member["sha256"]:
                raise PlanError(f"installed ZIP member verification failed: {member['path']}")


def _manifest_payload(item: dict[str, Any], staged: dict[str, Any] | None) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "format": "jpq-phone-test-evidence-compaction-v1",
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "operation": item["operation"],
        "sourceZip": str(item["source"]),
        "sourceSha256": item["sourceSha256"],
        "sourceUnlinkAfterManifest": True,
    }
    if item["operation"] == "compact":
        payload.update({"targetZip": str(item["target"]), "targetSha256": staged["targetSha256"],
                        "targetBytes": staged["targetBytes"], "members": staged["members"]})
    else:
        payload.update({"retainCanonicalFile": str(item["canonical"]),
                        "canonicalSha256": item["sourceSha256"]})
    return payload


def execute_plan(plan: dict[str, Any], repo_root: Path, apply: bool = False) -> list[dict[str, Any]]:
    repo_root = repo_root.resolve(strict=True)
    captures_root = repo_root / "captures"
    phone_lexical = captures_root / "phone"
    if captures_root.is_symlink() or phone_lexical.is_symlink():
        raise PlanError("captures/phone root may not traverse a symlink")
    phone_root = phone_lexical.resolve(strict=True)
    if not phone_root.is_dir():
        raise PlanError("captures/phone root is missing")
    if not isinstance(plan, dict) or plan.get("version") != 1 or not isinstance(plan.get("operations"), list) or not plan["operations"]:
        raise PlanError("plan must contain version=1 and a non-empty operations list")

    # Validate all planned operations before writing anything.
    operations = [_preflight_operation(phone_root, op, i) for i, op in enumerate(plan["operations"])]
    all_sources={item["source"] for item in operations}
    if any(item.get("canonical") in all_sources for item in operations):
        raise PlanError("a retained canonical ZIP must not be removed by another operation")
    for i, first in enumerate(operations):
        for second in operations[i + 1:]:
            if first["source"] == second["source"] or first["manifest"] == second["manifest"]:
                raise PlanError("operations may not share a source or manifest path")
            if first.get("target") and first["target"] in (second["source"], second["manifest"], second.get("target")):
                raise PlanError("operation output paths may not overlap")
            if second.get("target") and second["target"] in (first["source"], first["manifest"]):
                raise PlanError("operation output paths may not overlap")

    previews = []
    for item in operations:
        record = {"operation": item["operation"], "sourceZip": str(item["source"]),
                  "sourceSha256": item["sourceSha256"], "manifestPath": str(item["manifest"]),
                  "wouldUnlinkSource": True}
        if item["operation"] == "compact":
            record.update({"targetZip": str(item["target"]), "keepMembers": item["members"]})
        else:
            record["retainCanonicalFile"] = str(item["canonical"])
        previews.append(record)
    if not apply:
        return previews

    staged_temps: list[Path] = []
    staged_results: list[dict[str, Any] | None] = []
    manifest_payloads: list[dict[str, Any]] = []
    try:
        # Prepare and verify every compact ZIP before any source can be removed.
        for item in operations:
            if item["operation"] == "compact":
                temp, result = _stage_compact(item)
                staged_temps.append(temp)
                staged_results.append(result)
            else:
                staged_results.append(None)
        for item, result in zip(operations, staged_results):
            manifest_payloads.append(_manifest_payload(item, result))

        # Install all outputs with exclusive creation. If any install fails,
        # sources remain available for recovery.
        temp_iter = iter(staged_temps)
        for item in operations:
            if item["operation"] == "compact":
                _commit_new_from_temp(next(temp_iter), item["target"])
        compact_results = iter(result for item, result in zip(operations, staged_results)
                               if item["operation"] == "compact")
        for item in operations:
            if item["operation"] == "compact":
                _verify_final_compact(item, next(compact_results))
        for item, payload in zip(operations, manifest_payloads):
            encoded = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            _write_new_file(item["manifest"], lambda stream, data=encoded: stream.write(data))

        # All outputs and manifests now exist and have passed verification.
        for item in operations:
            current_source=_resolve_phone_path(phone_root,str(item["source"].relative_to(phone_root)),"sourceZip before unlink")
            if current_source!=item["source"]:
                raise PlanError("source ZIP path changed before unlink")
            _check_regular_file(item["source"], "sourceZip before unlink")
            if sha256_file(item["source"]) != item["sourceSha256"]:
                raise PlanError("source ZIP changed before unlink; keeping it")
            if item["operation"] == "duplicate":
                _check_regular_file(item["canonical"], "retainCanonicalFile before unlink")
                if sha256_file(item["canonical"]) != item["sourceSha256"]:
                    raise PlanError("canonical ZIP changed before unlink; keeping duplicate source")
        for item in operations:
            item["source"].unlink()
        return previews
    finally:
        for temp in staged_temps:
            temp.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True, help="reviewed JSON plan")
    parser.add_argument("--apply", action="store_true", help="write compact ZIPs/manifests and unlink listed source ZIP files")
    args = parser.parse_args(argv)
    plan_path = Path(args.plan)
    try:
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        repo_root = Path(__file__).resolve().parents[2]
        result = execute_plan(plan, repo_root, apply=args.apply)
        print(json.dumps({"mode": "apply" if args.apply else "dry-run", "operations": result}, ensure_ascii=False, indent=2))
        return 0
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError, PlanError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
