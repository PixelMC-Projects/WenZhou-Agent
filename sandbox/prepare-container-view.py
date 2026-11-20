"""Prepare a read-only snapshot of operator-approved gameplay files. Input maps relative paths to SHA-256."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
from catalog import build
from view_revision import revision

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("game", type=Path)
    parser.add_argument("approved", type=Path)
    parser.add_argument("base", type=Path)
    args = parser.parse_args()
    game = args.game.resolve(strict=True)
    views = args.base / "views"
    views.mkdir(parents=True, exist_ok=True)
    starting = revision(game)
    approved_digest = hashlib.sha256(args.approved.read_bytes()).hexdigest()
    current = args.base / "current"
    if current.is_symlink() and (current/".mod-revision").is_file() and (current/".approval").is_file():
        if (current/".mod-revision").read_text() == starting and (current/".approval").read_text() == approved_digest:
            print("Gameplay snapshot already current")
            return
    view = Path(tempfile.mkdtemp(prefix="snapshot-", dir=views))
    approved = json.loads(args.approved.read_bytes())
    try:
        copied = 0
        for name, digest in sorted(approved.items()):
            source = game / name
            if Path(name).is_absolute() or ".." in Path(name).parts: raise ValueError("invalid relative path")
            if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(game): continue
            dest = view / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            with source.open("rb") as incoming, dest.open("xb") as output:
                actual = hashlib.sha256()
                while part := incoming.read(1024*1024):
                    actual.update(part)
                    output.write(part)
            if actual.hexdigest() != digest:
                dest.unlink()
                continue
            dest.chmod(0o444)
            copied += 1
        if not any((view/"mods").glob("*.jar")): raise ValueError("no approved server mods matched")
        build(view)
        # The broker checks for a changed installation before allowing the snapshot to be queried.
        if revision(game) != starting:
            raise RuntimeError("gameplay files changed during snapshot preparation")
        (view/".mod-revision").write_text(starting)
        (view/".approval").write_text(approved_digest)
        view.chmod(0o755)
        current = args.base/"current"
        next_link = args.base/("next-"+view.name)
        next_link.symlink_to(view, target_is_directory=True)
        os.replace(next_link, current)
    except BaseException:
        shutil.rmtree(view)
        raise
    print("Approved server-matching files:",copied)
    # The broker ends queries after 13 seconds. Keep recent snapshots for in-flight readers.
    for old in views.glob("snapshot-*"):
        if old != view and not old.is_symlink() and old.is_dir() and old.resolve().parent == views.resolve():
            if time.time() - old.stat().st_mtime > 60:
                shutil.rmtree(old)

if __name__=="__main__": main()
