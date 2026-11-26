#!/usr/bin/env python3
"""Apply non-Track-B Python fixes to vanilla Poracle (main.py + poracle.py).

snapshot 에서 cond-aware 변경과 함께 묶여 있던 **non-cond-aware** 수정분을 vanilla
Poracle 소스에 다시 적용. 두 가지가 핵심:

  1. --base-seed CLI 플래그 + propagation
     - main.py 에 argparse 추가
     - poracle.py 에서 `random.seed(0)` → `random.seed(base_seed)`
     - Zest 커맨드 라인에 `--base-seed {}` 전달
     preserve-test 의 `--reps N` 옵션이 base-seed 를 가변시키는데, vanilla 는 하드코딩
     seed=0 이라 reps > 1 이 의미 없음.

  2. 멀티 쓰레드 diff_out 처리 수정 (reproduce + validate)
     - vanilla: `len(input_files) < 1 → raise`, `assert len(input_files) == 1`
     - 수정: 비어있는 thread 는 skip, 여러 thread 들 중 diff 낸 모든 input 처리
     vanilla 는 멀티 쓰레드 fuzz 에서 느린 thread 의 empty diff_out 가 빠른 thread 의
     valid counterexample 을 지워버림. `--num-of-threads N` (N>1) 사용 시 치명적.

idempotent: 이미 적용돼 있으면 no-op.
"""
import sys

MAIN_PY = "/poracle-experiments/src/main.py"
PORACLE_PY = "/poracle-experiments/src/poracle.py"


def patch_main(src: str) -> str:
    if "--base-seed" in src:
        return src
    # Add --base-seed arg after --use-opad arg
    needle_opad = (
        "    parser.add_argument('--use-opad', action='store_true',\n"
        "                        help='use opad mode (default: %(default)s)')"
    )
    if needle_opad not in src:
        sys.exit("main.py anchor missing: --use-opad arg")
    add_arg = needle_opad + (
        "\n    parser.add_argument('--base-seed', metavar='NUM', type=int, default=0,\n"
        "                        help='Base seed for RNG (allows rep variation; default: %(default)s)')"
    )
    src = src.replace(needle_opad, add_arg, 1)

    needle_cfg = "    poracle_config['use_opad'] = args.use_opad"
    if needle_cfg not in src:
        sys.exit("main.py anchor missing: use_opad config propagation")
    add_cfg = needle_cfg + "\n    poracle_config['base_seed'] = args.base_seed"
    src = src.replace(needle_cfg, add_cfg, 1)
    return src


def patch_poracle_random_seed(src: str) -> str:
    if "self.config.get('base_seed'" in src:
        return src
    old = (
        "        # To make the system more deterministic, we start with a fixed seed\n"
        "        random.seed(0)"
    )
    new = (
        "        # Seed is overridable via --base-seed to vary across reps while\n"
        "        # keeping each rep deterministic.\n"
        "        random.seed(self.config.get('base_seed', 0))"
    )
    if old not in src:
        sys.exit("poracle.py anchor missing: random.seed(0)")
    return src.replace(old, new, 1)


def patch_poracle_zest_cmd(src: str) -> str:
    """Add --base-seed flag to the Zest subprocess invocation."""
    if "--base-seed {}" in src:
        return src
    # Find the Zest command format string; it has `--delta {}` as an anchor
    old = (
        "        --duration {} \\\n"
        "        --exploreDuration {} \\\n"
        "        --delta {} \\\n"
    )
    if old not in src:
        sys.exit("poracle.py anchor missing: --delta line")
    new = old + "        --base-seed {} \\\n"
    src = src.replace(old, new, 1)
    # Also augment the .format(...) argument list — find `self.delta_allowed,`
    # and add base_seed right after
    delta_arg = "                                  self.delta_allowed,"
    if delta_arg not in src:
        sys.exit("poracle.py anchor missing: delta_allowed format arg")
    new_arg = delta_arg + "\n                                  self.config.get('base_seed', 0),"
    src = src.replace(delta_arg, new_arg, 1)
    return src


def patch_poracle_reproduce_mt(src: str) -> str:
    """Multi-thread diff_out iteration in reproduce(): accept all diffs across
    threads, skip empty ones."""
    if "any_reproduced" in src:
        return src
    old = (
        "        for thread_dir in thread_dirs:\n"
        "            diff_out_dir = join(fuzz_out_dir, thread_dir, 'diff_out')\n"
        "            input_files = os.listdir(diff_out_dir)\n"
        "            if len(input_files) < 1:\n"
        "                logger.info('diff_out directory is empty')\n"
        "                return\n"
        "            assert len(input_files) == 1\n"
        "            input_file = input_files[0]\n"
        "            repro(diff_out_dir, input_file)"
    )
    new = (
        "        # Reproduce every thread that found a diff. Threads that produced\n"
        "        # nothing are skipped (continue), not an abort — otherwise one slow\n"
        "        # thread's empty diff_out would discard the fast thread's valid\n"
        "        # counterexample under multi-thread fuzzing.\n"
        "        any_reproduced = False\n"
        "        for thread_dir in thread_dirs:\n"
        "            diff_out_dir = join(fuzz_out_dir, thread_dir, 'diff_out')\n"
        "            if not isdir(diff_out_dir):\n"
        "                continue\n"
        "            input_files = os.listdir(diff_out_dir)\n"
        "            if len(input_files) < 1:\n"
        "                logger.info('diff_out directory is empty for thread {}'.format(thread_dir))\n"
        "                continue\n"
        "            for input_file in input_files:\n"
        "                repro(diff_out_dir, input_file)\n"
        "                any_reproduced = True\n"
        "        if not any_reproduced:\n"
        "            logger.info('no thread produced a diff_out input to reproduce')"
    )
    if old not in src:
        sys.exit("poracle.py anchor missing: reproduce() single-thread loop")
    return src.replace(old, new, 1)


def patch_poracle_validate_mt(src: str) -> str:
    """Multi-thread diff_out iteration in validate(): pick first thread with a
    diff, skip empties, error only if none."""
    if "no thread produced a diff_out input" in src:
        return src
    old = (
        "        # obtain input_file\n"
        "        fuzz_out_dir = join(self.fuzz_out_dir, test_unit.get_test_id())\n"
        "        thread_dirs = os.listdir(fuzz_out_dir)\n"
        "        for thread_dir in thread_dirs:\n"
        "            diff_out_dir = join(fuzz_out_dir, thread_dir, 'diff_out')\n"
        "            input_files = os.listdir(diff_out_dir)\n"
        "            if len(input_files) < 1:\n"
        "                raise PoracleException('diff_out directory is empty')\n"
        "            assert len(input_files) == 1\n"
        "            input_file = input_files[0]"
    )
    new = (
        "        # obtain input_file: pick the first thread that produced a diff-revealing\n"
        "        # input. Under multi-thread fuzzing only one (fastest) thread typically\n"
        "        # writes to diff_out, so iterating every thread and asserting one-file-each\n"
        "        # discards the valid counterexample.\n"
        "        fuzz_out_dir = join(self.fuzz_out_dir, test_unit.get_test_id())\n"
        "        thread_dirs = os.listdir(fuzz_out_dir)\n"
        "        input_file = None\n"
        "        for thread_dir in thread_dirs:\n"
        "            diff_out_dir = join(fuzz_out_dir, thread_dir, 'diff_out')\n"
        "            if not isdir(diff_out_dir):\n"
        "                continue\n"
        "            input_files = os.listdir(diff_out_dir)\n"
        "            if len(input_files) < 1:\n"
        "                continue\n"
        "            input_file = input_files[0]\n"
        "            break\n"
        "        if input_file is None:\n"
        "            raise PoracleException('no thread produced a diff_out input')"
    )
    if old not in src:
        sys.exit("poracle.py anchor missing: validate() single-thread loop")
    return src.replace(old, new, 1)


def main() -> int:
    for path, patchers in [
        (MAIN_PY, [patch_main]),
        (PORACLE_PY, [patch_poracle_random_seed, patch_poracle_zest_cmd,
                      patch_poracle_reproduce_mt, patch_poracle_validate_mt]),
    ]:
        src = open(path, encoding="utf-8").read()
        before = src
        for p in patchers:
            src = p(src)
        if src == before:
            print(f"{path}: already patched - skip")
        else:
            open(path, "w", encoding="utf-8").write(src)
            print(f"{path}: patched ({len(patchers)} fixes attempted)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
