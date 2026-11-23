"""The Poracle container: running commands in it, and Defects4J checkouts prepared the way
Poracle prepares them.

`Checkout`'s steps are Poracle's own (poracle.py: checkout, apply_deltas, compile,
set_failing_tests, TestUnit, get_project_cp, get_project_test_dir), run through `docker exec`.
"""

import os
import shlex
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

from preserve.bugs import Bug
from preserve.config import CONTAINER, CONTAINER_WORK, PORACLE_HOST

STARTUP = [
    "source /poracle/activate",
    "source /poracle-experiments/activate",
    "export LC_ALL=C.UTF-8 LANG=C.UTF-8",   # Math's test sources contain non-ASCII characters
    # as in the condition-aware fuzzer's image
    'export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Xmx2500m -Xms256m"',
]
# for Poracle's original setup (config.PORACLE_CONTAINER): its own environment and the locale only,
# without the JVM options taken from the condition-aware fuzzer's image
ORIGINAL_STARTUP = STARTUP[:3]
D4J_HOME = "/poracle-experiments/benchmark/defects4j"
PORACLE_EXP = "/poracle-experiments"        # Poracle's experiment directory: configs/, patches/, src/
PORACLE_PATCHES = f"{PORACLE_EXP}/patches"
TIMEOUT_RETURN_CODE = 124


# what the docker client prints when the command never ran in the container
_DOCKER_REFUSED = ("is paused", "is not running", "is restarting", "Cannot connect to the Docker daemon")
DOCKER_RETRIES, DOCKER_RETRY_SECONDS = 60, 30       # wait up to half an hour for the container


def docker(argv: list[str], **kwargs) -> subprocess.CompletedProcess:
    """Run a docker command with its output captured. When Docker refuses to run it at all (the
    container is paused, as it is for the length of a `docker commit`, or is restarting), wait
    and try again: otherwise every step issued meanwhile fails at once and looks like a result."""
    for attempt in range(DOCKER_RETRIES + 1):
        p = subprocess.run(argv, capture_output=True, **kwargs)
        err = p.stderr if isinstance(p.stderr, str) else p.stderr.decode(errors="replace")
        if p.returncode == 0 or attempt == DOCKER_RETRIES or not any(m in err for m in _DOCKER_REFUSED):
            return p
        time.sleep(DOCKER_RETRY_SECONDS)
    return p


@dataclass
class CmdResult:
    returncode: int
    stdout: str          # stdout and stderr merged, in order (JQF interleaves both)


class DockerEnv:
    """Read, write and run commands in a running container."""

    def __init__(self, container: str = CONTAINER, cwd: str = "/", startup: list[str] | None = None):
        self.container = container
        self.cwd = cwd
        self.startup = STARTUP if startup is None else startup

    def read(self, path: str) -> bytes:
        p = docker(["docker", "exec", self.container, "cat", path])
        if p.returncode != 0:
            raise FileNotFoundError(f"{path}: {p.stderr.decode(errors='replace').strip()}")
        return p.stdout

    def write(self, path: str, content: bytes) -> None:
        target = shlex.quote(path)
        p = docker(["docker", "exec", "-i", self.container, "bash", "-c",
                    f'mkdir -p "$(dirname {target})" && cat > {target}'], input=content)
        if p.returncode != 0:
            raise OSError(f"write {path} failed: {p.stderr.decode(errors='replace')}")

    def exists(self, path: str) -> bool:
        return docker(["docker", "exec", self.container, "test", "-e", path]).returncode == 0

    def exec_cmd(self, cmd: str, timeout: int = 60) -> CmdResult:
        script = "\n".join(self.startup + [f"cd {shlex.quote(self.cwd)}", f"{{ {cmd}\n}} 2>&1"])
        # `timeout` inside the container too, so a killed run does not keep running there
        argv = ["docker", "exec", "-i", self.container, "timeout", "-k", "10", str(timeout), "bash", "-s"]
        try:
            p = docker(argv, input=script, text=True, errors="replace", timeout=timeout + 30)
        except subprocess.TimeoutExpired as e:
            return CmdResult(TIMEOUT_RETURN_CODE, e.stdout if isinstance(e.stdout, str) else "")
        return CmdResult(p.returncode, p.stdout)


class CheckoutError(Exception):
    pass


class Checkout:
    """One version of a bug, checked out in the container: "b" = buggy, "f" = developer fix,
    or, with `patch` (a patch ID: a file of that name in `patches_dir`, by default Poracle's
    own patches), the buggy version with that patch applied. `suffix` is appended to the
    checkout's directory, so that runs of the same version at the same time get their own."""

    def __init__(self, bug: Bug, version: str = "b", patch: str | None = None,
                 patches_dir: str = PORACLE_PATCHES, suffix: str = ""):
        self.bug = bug
        self.workdir = f"{CONTAINER_WORK}/{bug.name}"
        self.version = "b" if patch else version
        self.patch = patch
        self.patches_dir = patches_dir
        self.patch_output = ""      # what `patch` printed (offsets and fuzz show here)
        self.dir = f"{self.workdir}/{bug.project}{bug.bug_id}" + (f"p-{patch}" if patch else version) + suffix
        self.env = DockerEnv(cwd=self.workdir)

    def _run(self, cmd: str, what: str, timeout: int = 900) -> str:
        r = self.env.exec_cmd(cmd, timeout=timeout)
        if r.returncode != 0:
            raise CheckoutError(f"{what} failed:\n{r.stdout[-2000:]}")
        return r.stdout

    def prepare(self, compile: bool = True) -> "Checkout":
        """Check out, apply the bug's build-file delta (and the patch), compile."""
        b = self.bug
        self._run(f"rm -rf {self.dir} && mkdir -p {self.workdir} && "
                  f"defects4j checkout -p {b.project} -v {b.bug_id}{self.version} -w {self.dir}", "checkout")
        self._apply_build_delta()
        if self.patch:      # as Poracle applies it
            self.patch_output = self._run(f"patch -p1 -d {self.dir} < {self.patches_dir}/{self.patch}",
                                          "apply patch")
        if compile:
            self.compile()
        return self

    def compile(self) -> None:
        self._run(f"defects4j compile -w {self.dir}", "compile")

    def all_trigger_tests(self) -> list[str]:
        """Every trigger test Defects4J lists for the bug. `trigger_tests` can be fewer: some of
        Poracle's deltas narrow the checkout's list to the tests it has generalized."""
        listing = self.env.read(f"{D4J_HOME}/framework/projects/{self.bug.project}/trigger_tests/{self.bug.bug_id}")
        return [line[4:].strip() for line in listing.decode(errors="replace").splitlines() if line.startswith("--- ")]

    def run_trigger_tests(self, tests: list[str] | None = None) -> dict[str, bool]:
        """Run trigger tests (default: the checkout's own list) on this version, as Poracle's
        plausibility step does (`defects4j test -t`, failed when `failing_tests` is not empty):
        {test: passed}."""
        passed = {}
        for test in tests or self.trigger_tests():
            r = self.env.exec_cmd(f"rm -f {self.dir}/failing_tests; defects4j test -t {shlex.quote(test)} "
                                  f"-w {self.dir}; echo FAILING_BYTES=$(cat {self.dir}/failing_tests 2>/dev/null | wc -c)",
                                  timeout=900)
            passed[test] = "FAILING_BYTES=0" in r.stdout and "Failing tests: 0" in r.stdout
        return passed

    def remove(self) -> None:
        self.env.exec_cmd(f"rm -rf {self.dir}")

    def _apply_build_delta(self) -> None:
        """Poracle's delta for the bug (build files, delta.sh), without its hand-written JQF tests."""
        delta = PORACLE_HOST / "deltas" / self.bug.project / f"{self.bug.project}_bug{self.bug.bug_id}"
        for f in sorted(p for p in delta.rglob("*") if p.is_file()):
            if not (f.name.startswith("JQF_") and f.name.endswith(".java")):
                self.env.write(f"{self.dir}/{f.relative_to(delta)}", f.read_bytes())
        if (delta / "delta.sh").exists():
            self._run(f"chmod u+rwx {self.dir}/delta.sh && {self.dir}/delta.sh {self.dir}", "delta.sh")

    def trigger_tests(self) -> list[str]:
        """Defects4J's failing tests, e.g. ['org.x.FooTest::testBar']."""
        out_file = f"{self.dir}/.trigger_tests"
        self._run(f"defects4j export -p tests.trigger -w {self.dir} -o {out_file}", "export trigger tests")
        return self.env.read(out_file).decode().splitlines()

    def relevant_tests(self) -> list[str]:
        """Defects4J's relevant tests: the test classes that load a class the developer fix
        modifies, trigger tests included, e.g. ['org.x.FooTest']."""
        out_file = f"{self.dir}/.relevant_tests"
        self._run(f"defects4j export -p tests.relevant -w {self.dir} -o {out_file}", "export relevant tests")
        return self.env.read(out_file).decode().split()

    def _tests_src_dir(self) -> str:
        """Defects4J's test source directory (dir.src.tests), relative to the checkout."""
        out_file = f"{self.dir}/.tests_src_dir"
        self._run(f"defects4j export -p dir.src.tests -w {self.dir} -o {out_file}", "export test dir")
        return self.env.read(out_file).decode().strip()

    @property
    def test_dir(self) -> str:
        """Test source directory, relative to the checkout."""
        if self.bug.project == "Chart":
            return "tests"
        for cand in ["src/test/java", "src/test"]:
            if self.env.exists(f"{self.dir}/{cand}"):
                return cand
        raise CheckoutError(f"no test dir in {self.dir}")

    @property
    def classpath(self) -> str:
        dirs = {"Math": ["target/test-classes", "target/classes"],
                "Time": ["target/test-classes", "target/classes", "build/tests", "build/classes"],
                "Lang": ["target/tests", "target/classes"],
                "Chart": ["build", "build-tests"]}[self.bug.project]
        return ":".join(f"{self.dir}/{d}" for d in dirs)

    def compile_tests(self) -> tuple[bool, str]:
        """Compile the tests with ant directly, so javac's errors come back
        (`defects4j compile` only says 'Cannot compile tests!'). Returns (ok, errors)."""
        build_xml = f"{D4J_HOME}/framework/projects/defects4j.build.xml"
        # from inside the checkout, like defects4j does: Time's build uses relative paths
        out = self.env.exec_cmd(f"cd {self.dir} && ant -f {build_xml} -Dd4j.home={D4J_HOME} "
                                f"-Dbasedir={self.dir} compile.tests", timeout=900).stdout
        if "BUILD SUCCESSFUL" in out:
            return True, ""
        lines = [l.split("[javac]", 1)[1].strip() for l in out.splitlines() if "[javac]" in l]
        lines = [l for l in lines if l and not l.startswith(("Compiling ", "Note: ", "["))
                 and "warning:" not in l]
        if not any("error" in l for l in lines):
            # not a javac error: a build problem, which is ours and not the test's
            raise CheckoutError("build failed without javac errors:\n" + "\n".join(
                l for l in out.splitlines() if "[javac]" not in l)[-2000:])
        return False, "\n".join(lines)[:6000]

    def snapshot_to(self, host_dir: Path) -> Path:
        """Copy the checkout to the host for the agent to read: without build output, and
        without Defects4J's relevant tests, which contain the bug-revealing trigger tests.
        The checkout in the container keeps them. The hidden classes are in `hidden_tests`."""
        relevant = self.relevant_tests()
        tests_root = host_dir / self._tests_src_dir()
        if host_dir.exists():
            shutil.rmtree(host_dir)
        host_dir.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["docker", "cp", f"{self.env.container}:{self.dir}", str(host_dir)],
                       check=True, capture_output=True)
        for name in ["target", "build", "build-tests", ".git", ".trigger_tests", ".relevant_tests",
                     ".tests_src_dir"]:
            p = host_dir / name
            if p.is_dir():
                shutil.rmtree(p)
            elif p.exists():
                os.unlink(p)
        self.hidden_tests = []
        for cls in sorted({c.split("$")[0] for c in relevant}):     # nested classes: their file
            f = tests_root / (cls.replace(".", "/") + ".java")
            if not f.exists():     # a relevant test we cannot hide: stop rather than leak it
                shutil.rmtree(host_dir)
                raise CheckoutError(f"relevant test {cls} not found at {f}")
            f.unlink()
            self.hidden_tests.append(cls)
        return host_dir


def jqf_test_unit(trigger_test: str) -> tuple[str, str]:
    """'org.x.FooTest::testBar' -> ('org.x.JQF_FooTest', 'testBar'): the class and method
    Poracle runs for that failing test."""
    cls, method = trigger_test.split("::")
    package, _, simple = cls.rpartition(".")
    return f"{package}.JQF_{simple}", method
