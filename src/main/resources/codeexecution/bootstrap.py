"""Applied before a user program: resource limits, the import guard, sys.path confinement.

Layer 1 of the sandbox, and defence-in-depth only. The container is the real isolation
boundary (sandbox-approach.md / the execute-code plan, ADR-1): every step here is cheap and
raises the cost of an accident, and none of it is claimed to stop a program that is actively
trying to get out. A stub like ``ctypes`` is exactly what an import guard cannot contain.

Every limit arrives as an environment variable set by Java, so the value has one owner:
AgentConfigProperties.CodeExecutionConfig. A limit that cannot be set on this platform is
skipped — the JVM-side timeout still bounds the run.
"""

import os
import resource
import sys

_USER_PROGRAM = sys.argv[1] if len(sys.argv) > 1 else None

_BLOCKED = ("socket", "ssl", "http", "urllib", "ftplib", "smtplib", "asyncio", "ctypes")

_RECURSION_LIMIT = 1000


def _apply_limits():
    limits = (
        ("RLIMIT_AS", "AI_GIT_BOT_LIMIT_AS_BYTES"),
        ("RLIMIT_CPU", "AI_GIT_BOT_LIMIT_CPU_SECONDS"),
        ("RLIMIT_FSIZE", "AI_GIT_BOT_LIMIT_FSIZE_BYTES"),
        ("RLIMIT_NPROC", "AI_GIT_BOT_LIMIT_NPROC"),
    )
    for name, variable in limits:
        raw = os.environ.get(variable)
        if not raw:
            continue
        try:
            limit = getattr(resource, name)
            value = int(raw)
            resource.setrlimit(limit, (value, value))
        except (AttributeError, ValueError, OSError):
            # Not enforceable here (or not permitted); the JVM-side timeout is the backstop.
            pass


class _ImportGuard:
    """Refuses the network-capable and FFI modules by name.

    Bypassable by construction — a module already imported, a submodule of a permitted
    package, or ``ctypes`` are all ways past it. See the module docstring.
    """

    def find_spec(self, fullname, path=None, target=None):
        if fullname.split(".")[0] in _BLOCKED:
            raise ImportError("%s is not available inside execute-code" % fullname)
        return None


def _harden_imports():
    # The bridge module is imported first, and only after sys.path has been confined (see
    # _run): it needs the real socket module. Then the blocked modules are dropped from
    # sys.modules and the guard installed, so a program cannot reach them by name either —
    # the bridge keeps its own reference.
    import ai_git_bot  # noqa: F401  - returns the tool surface bound into the program
    for name in [n for n in sys.modules if n.split(".")[0] in _BLOCKED]:
        del sys.modules[name]
    sys.meta_path.insert(0, _ImportGuard())
    return ai_git_bot


def _confine_path():
    # Keep the stdlib and the interpreter's own paths and make the execution temp directory
    # the first place a lookup lands. This cannot be left to sys.path[0]: -I implies -P, so
    # neither the script's directory nor the cwd is on the path when we start.
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path[:] = [here] + [entry for entry in sys.path if entry and entry != here]


def _run():
    _apply_limits()
    # Order matters: the bridge must be importable (path) before it is imported, and imported
    # before the guard that would refuse its own imports.
    _confine_path()
    bridge = _harden_imports()
    sys.setrecursionlimit(_RECURSION_LIMIT)

    if _USER_PROGRAM is None:
        raise SystemExit("execute-code: no program given")
    with open(_USER_PROGRAM, "r", encoding="utf-8") as handle:
        source = handle.read()
    # The program sees a normal argv, not this bootstrap's.
    sys.argv = [_USER_PROGRAM]
    # `tools` is bound up front because it is the name the tool description tells the model to
    # call (tools.list / tools.describe / tools.call). A name the program must import first is a
    # name it forgets, and the forgotten import costs a whole round to a NameError traceback
    # instead of an answer.
    exec(compile(source, _USER_PROGRAM, "exec"),
         {"__name__": "__main__", "__file__": _USER_PROGRAM, "tools": bridge.tools})


if __name__ == "__main__":
    _run()
