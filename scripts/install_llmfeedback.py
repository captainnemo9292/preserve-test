#!/usr/bin/env python3
"""Install LLMFeatureCollector + Log.boxArray + FuzzStatement hooks into a
poracle container.  **cond-aware 는 설치하지 않음** — 이 스크립트는
install_fuzzer.py 와 orthogonal 이고, 둘 다 돌려도 되고 이 스크립트만 돌려도 됨.

통합 설치 내용:
  1. anonymous.llmfeedback.LLMFeatureCollector   (신규)
  2. Log.java  logIn/logOut 의 null/array/Object-safe 처리 + boxArray helper
  3. Log.java  LogResult.getOutputForOrg()/getOutputForPatch()  public getter
  4. FuzzStatement.java  evaluate() 와 evaluateOrg() 에 recordIteration hook
  5. main.py / poracle.py 의 non-cond-aware 수정분:
       - --base-seed CLI 플래그 + propagation (reps 지원)
       - 멀티 쓰레드 diff_out 처리 수정 (reproduce + validate 의 critical bug fix)
     cond-aware 는 설치 안 하나, 이 두 가지는 cond-aware 와 묶여 있던 **실제 버그
     수정** 이므로 유지. snapshot 과 동등한 Poracle Python 동작 보장.

적용 절차:
   python scripts/install_llmfeedback.py                 # install
   python scripts/install_llmfeedback.py --smoke         # install + smoke test
   python scripts/install_llmfeedback.py --restore       # 되돌림 (backup 에서 복원)
   python scripts/install_llmfeedback.py --commit TAG    # install + docker commit <TAG>

PRESERVE_PORACLE_CONTAINER 환경변수로 대상 컨테이너 오버라이드 가능 (기본
preserve/config.py 의 CONTAINER).  bootstrap 시나리오: 바닐라
poracle100/poracle-reproduce:latest 에서 기동한 임시 컨테이너에 이 스크립트
돌리고 docker commit → `poracle-reproduce:llmfeedback` 이미지 획득.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from preserve.config import CONTAINER  # noqa: E402

JQF = "/poracle/modules/JQF"
JDK = "/usr/lib/jvm/java-1.8.0-openjdk-amd64/bin"
BACKUP = f"{JQF}/.llmfeedback-backup"
FUZZ_SRC = f"{JQF}/fuzz/src/main/java"
LOG_JAVA = f"{JQF}/log/src/main/java/anonymous/log/Log.java"
FS_JAVA = f"{FUZZ_SRC}/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java"
COLLECTOR_JAVA = f"{FUZZ_SRC}/anonymous/llmfeedback/LLMFeatureCollector.java"

MAIN_PY = "/poracle-experiments/src/main.py"
PORACLE_PY = "/poracle-experiments/src/poracle.py"

MODIFIED = [LOG_JAVA, FS_JAVA, MAIN_PY, PORACLE_PY]
NEW = [COLLECTOR_JAVA]

RES_DIR = Path(__file__).resolve().parent / "llmfeedback"


def sh(container: str, cmd: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", "exec", container, "bash", "-c", cmd],
                          check=check, text=True, capture_output=True)


def cp_to(container: str, src: Path, dst: str) -> None:
    subprocess.run(["docker", "cp", str(src), f"{container}:{dst}"],
                   check=True, text=True)


def backup(container: str) -> None:
    sh(container, f"mkdir -p {BACKUP}/src")
    for f in MODIFIED:
        sh(container, f"[ -f {BACKUP}/src{f} ] || (mkdir -p {BACKUP}/src$(dirname {f}) "
                      f"&& cp {f} {BACKUP}/src{f})")


def install(container: str) -> None:
    backup(container)

    # 1. Log.java boxArray fix (vanilla → null/array/Object safe)
    cp_to(container, RES_DIR / "add_log_boxarray.py", "/tmp/add_log_boxarray.py")
    sh(container, "LANG=en_US.UTF-8 PYTHONIOENCODING=utf-8 python3 /tmp/add_log_boxarray.py")

    # 2. LLMFeatureCollector drop-in
    sh(container, f"mkdir -p $(dirname {COLLECTOR_JAVA})")
    cp_to(container, RES_DIR / "LLMFeatureCollector.java", COLLECTOR_JAVA)

    # 3. Log getters + FuzzStatement hooks
    cp_to(container, RES_DIR / "add_hooks.py", "/tmp/add_hooks.py")
    sh(container, "LANG=en_US.UTF-8 PYTHONIOENCODING=utf-8 python3 /tmp/add_hooks.py")

    # 4. Python-side non-cond-aware fixes (--base-seed + multi-thread diff_out)
    cp_to(container, RES_DIR / "apply_python_fixes.py", "/tmp/apply_python_fixes.py")
    sh(container, "LANG=en_US.UTF-8 PYTHONIOENCODING=utf-8 python3 /tmp/apply_python_fixes.py")

    # 5. Rebuild + redistribute Log classes
    print("=== mvn install -pl fuzz -am ===")
    sh(container, f"cd {JQF} && mvn install -pl fuzz -am -DskipTests -Drat.skip=true -q")

    print("=== jqf-log jars 재배포 ===")
    sh(container, f"""
cd {JQF}/log/target/classes
for j in {JQF}/fuzz/target/dependency/jqf-log-*.jar \\
         /root/.m2/repository/poracle/jqf-log/*/jqf-log-*.jar; do
    [ -f \"$j\" ] && jar uf \"$j\" anonymous/log/*.class && echo "  updated $j"
done
""")


def restore(container: str) -> None:
    sh(container, "set -e\n" + "\n".join(f"cp {BACKUP}/src{f} {f}" for f in MODIFIED)
       + "\n" + "\n".join(f"rm -f {f}" for f in NEW))
    print("=== mvn install -pl fuzz -am (restore) ===")
    sh(container, f"cd {JQF} && mvn install -pl fuzz -am -DskipTests -Drat.skip=true -q")


def smoke(container: str) -> bool:
    r = sh(container, r"""
source /poracle/activate; source /poracle-experiments/activate
export JAVA_TOOL_OPTIONS="-Dllmfeedback.on=true -Dllmfeedback.out=/tmp/llmf_smoke/.poracle"
rm -rf /tmp/llmf_smoke && mkdir -p /tmp/llmf_smoke/.poracle && cd /tmp/llmf_smoke
ln -sfn /poracle-experiments/configs configs
ln -sfn /poracle-experiments/deltas deltas
ln -sfn /poracle-experiments/patches patches
timeout 45 poracle configs/Patch1.json --duration 10s --num-of-threads 2 --quiet > out 2>&1 || true
if [ -f /tmp/llmf_smoke/.poracle/llm_features.json ]; then
    python3 -c 'import json; d=json.load(open("/tmp/llmf_smoke/.poracle/llm_features.json")); print("OK schema=", d.get("schema"), "fields=", len(d))'
else
    echo "features.json NOT PRODUCED"
    exit 1
fi
""", check=False)
    print(r.stdout)
    if r.returncode != 0:
        print(r.stderr, file=sys.stderr)
        return False
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--container", default=None,
                    help=f"대상 컨테이너 이름 (기본: {CONTAINER})")
    ap.add_argument("--restore", action="store_true", help="되돌림 (backup 복원)")
    ap.add_argument("--smoke", action="store_true", help="설치 후 smoke test")
    ap.add_argument("--commit", metavar="TAG",
                    help="smoke 성공 시 docker commit <컨테이너> <TAG>")
    args = ap.parse_args()

    container = args.container or CONTAINER
    # 간단 확인
    r = subprocess.run(["docker", "ps", "-a", "-q", "-f", f"name={container}"],
                       text=True, capture_output=True)
    if not r.stdout.strip():
        print(f"ERROR: 컨테이너 '{container}' 없음. docker ps 확인.", file=sys.stderr)
        return 2

    if args.restore:
        restore(container)
        print("restore done.")
        return 0

    install(container)
    print("install done.")

    if args.smoke or args.commit:
        ok = smoke(container)
        if not ok:
            print("smoke test 실패 — commit 중단.", file=sys.stderr)
            return 3

    if args.commit:
        subprocess.run(["docker", "commit", container, args.commit],
                       check=True, text=True)
        print(f"committed as {args.commit}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
