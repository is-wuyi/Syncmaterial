#!/usr/bin/env python3
"""Restore and bound the generated demo-only GitHub Pages site."""

from __future__ import annotations

import argparse
import shutil
import tarfile
import zipfile
from pathlib import Path, PurePosixPath

MAX_SITE_BYTES = 750 * 1024 * 1024
MAX_SITE_FILES = 4096
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024


class HistoryError(RuntimeError):
    pass


def _safe_parts(name):
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise HistoryError(f"归档含不安全路径: {name!r}")
    return path.parts


def _destination(root, parts):
    destination = root.joinpath(*parts)
    current = root
    for part in parts[:-1]:
        current = current / part
        if current.is_symlink():
            raise HistoryError(f"站点目录不能含符号链接: {current.name}")
    if destination.is_symlink():
        raise HistoryError(f"不能覆盖符号链接: {destination.name}")
    return destination


def restore(archive, out):
    archive = Path(archive)
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    if not out.is_dir() or out.is_symlink():
        raise HistoryError("站点输出必须是普通目录")
    try:
        with zipfile.ZipFile(archive) as bundle:
            candidates = [item for item in bundle.infolist()
                          if not item.is_dir() and item.filename == "artifact.tar"]
            if len(candidates) != 1:
                raise HistoryError("Pages artifact 必须且只能包含一个根目录 artifact.tar")
            item = candidates[0]
            if item.file_size > MAX_ARCHIVE_BYTES:
                raise HistoryError("Pages artifact 过大")
            with bundle.open(item) as source, tarfile.open(fileobj=source, mode="r|") as tar:
                total = 0
                count = 0
                for member in tar:
                    if not (member.isfile() or member.isdir()):
                        raise HistoryError("Pages 归档只能包含普通文件和目录")
                    parts = _safe_parts(member.name)
                    count += 1
                    total += member.size
                    if count > MAX_SITE_FILES or total > MAX_SITE_BYTES:
                        raise HistoryError("恢复后的 Pages 站点超过大小或文件数限制")
                    destination = _destination(out, parts)
                    if member.isdir():
                        destination.mkdir(parents=True, exist_ok=True)
                        continue
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    source_file = tar.extractfile(member)
                    if source_file is None:
                        raise HistoryError("无法读取 Pages 归档文件")
                    with source_file, destination.open("wb") as output:
                        shutil.copyfileobj(source_file, output)
    except (OSError, zipfile.BadZipFile, tarfile.TarError) as error:
        raise HistoryError(f"无法恢复 Pages 归档: {error}") from error


def _run_dirs(site):
    demos = site / "demos"
    if not demos.is_dir() or demos.is_symlink():
        return []
    return sorted((entry for entry in demos.iterdir()
                   if entry.is_dir() and not entry.is_symlink() and entry.name.isdigit()),
                  key=lambda entry: int(entry.name), reverse=True)


def _write_index(site, runs):
    items = "\n".join(
        f'<li><a href="demos/{run.name}/">CI 演示 · 运行 {run.name}</a></li>' for run in runs)
    document = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SyncMaterial CI 功能演示</title></head>
<body><main><h1>SyncMaterial CI 功能演示</h1>
<p>离线 Player0 测试世界的真实客户端操作。每页提供带步骤的 MP4 和无字幕原始素材。</p>
<ul>
{items}
</ul></main></body></html>
"""
    (site / "index.html").write_text(document, encoding="utf-8")


def prune(site, keep, protect):
    site = Path(site)
    if keep < 1:
        raise HistoryError("保留 run 数必须至少为 1")
    if not protect.isdigit():
        raise HistoryError("当前 run ID 必须是数字")
    runs = _run_dirs(site)
    retained = [entry for entry in runs if entry.name == protect]
    retained.extend(entry for entry in runs if entry.name != protect)
    for entry in retained[keep:]:
        # Only numeric run directories produced by build_demo_page are eligible.
        shutil.rmtree(entry)
    runs = retained[:keep]
    _write_index(site, runs)
    return runs


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    restore_parser = commands.add_parser("restore")
    restore_parser.add_argument("--archive", required=True)
    restore_parser.add_argument("--out", required=True)
    prune_parser = commands.add_parser("prune")
    prune_parser.add_argument("--site", required=True)
    prune_parser.add_argument("--keep", type=int, default=20)
    prune_parser.add_argument("--protect", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "restore":
            restore(args.archive, args.out)
        else:
            prune(args.site, args.keep, args.protect)
    except HistoryError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
