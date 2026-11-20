import hashlib
from pathlib import Path

def revision(game):
    result = hashlib.sha256()
    for pattern in ("mods/*.jar","kubejs/**/*.js","kubejs/data/**/*.json","scripts/**/*.zs","world/datapacks/**/*.json","world/datapacks/*.zip"):
        for file in sorted(game.glob(pattern)):
            if file.is_symlink() or not file.is_file(): continue
            info = file.stat()
            result.update(f"{file.relative_to(game)}/{info.st_size}/{info.st_mtime_ns}\n".encode())
    return result.hexdigest()
