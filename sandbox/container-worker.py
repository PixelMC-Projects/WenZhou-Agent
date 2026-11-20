"""Socket-activated broker. Only the sandboxed shell receives client input."""
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import time
import uuid
from view_revision import revision

BASE = Path("/opt/pixelmc-wenzhou")
DOCKER = "/usr/bin/docker"
LIMIT = 12 * 1024

def query(script):
    if not script or len(script) > 8192 or b"\0" in script:
        return {"status": "invalid_request", "message": "查询无效。"}
    image = (BASE / "image-id").read_text().strip()
    import re
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
        return {"status": "unavailable", "message": "沙箱镜像未确认。"}
    view = (BASE / "current").resolve(strict=True)
    if view.parent != BASE / "views" or not (view / "CATALOG.tsv").is_file():
        return {"status": "unavailable", "message": "玩法视图尚未准备好。"}
    if revision(Path(os.environ["WENZHOU_GAME"])) != (view / ".mod-revision").read_text():
        return {"status":"unavailable","message":"整合包文件已变化，隔离资料待刷新。请使用游戏内生效查询。"}
    name = "wz-read-" + uuid.uuid4().hex
    argv = [DOCKER, "run", "--rm", "-i", "--pull=never", "--name", name, "--network=none", "--ipc=none",
            "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65534:65534",
            "--pids-limit=40", "--memory=384m", "--memory-swap=384m", "--cpus=1",
            "--ulimit", "nofile=96:96", "--ulimit", "core=0:0", "--log-driver=none",
            "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=48m,mode=1777",
            "--mount", "type=bind,source=" + str(view) + ",target=/game,readonly",
            "--workdir=/game", "--env", "HOME=/tmp", "--env", "LANG=C.UTF-8",
            "--env", "JAVA_TOOL_OPTIONS=-Xmx144m -XX:+UseSerialGC -XX:ActiveProcessorCount=1",
            "--entrypoint=/usr/bin/timeout", image, "-k", "1", "10", "/bin/bash", "--noprofile", "--norc", "-s"]
    proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    result = bytearray()
    expired = False
    deadline = time.monotonic() + 13
    try:
        proc.stdin.write(script + b"\n")
        proc.stdin.close()
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout, selectors.EVENT_READ)
            while len(result) <= LIMIT:
                left = deadline - time.monotonic()
                if left <= 0 or not selector.select(left):
                    expired = True
                    break
                part = os.read(proc.stdout.fileno(), min(4096, LIMIT + 1 - len(result)))
                if not part: break
                result.extend(part)
        if not expired and len(result) <= LIMIT:
            proc.wait(timeout=max(.1, deadline - time.monotonic()))
    finally:
        if proc.poll() is None:
            subprocess.run([DOCKER,"rm","-f",name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3)
            proc.kill()
            proc.wait()
    return {"status":"sandbox_output","output":bytes(result[:LIMIT]).decode("utf-8","replace"),
            "exit_code":proc.returncode,"truncated":len(result)>LIMIT,
            "time_limit_reached":expired or proc.returncode in (124,137)}

if __name__ == "__main__":
    try:
        result = query(sys.stdin.buffer.read(8193))
    except (OSError, ValueError, subprocess.SubprocessError):
        result = {"status":"unavailable","message":"隔离玩法查询未完成。"}
    print(json.dumps(result,ensure_ascii=False),flush=True)
