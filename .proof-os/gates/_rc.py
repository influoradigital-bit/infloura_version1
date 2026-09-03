"""gates/_rc.py — liveness for the python gates. origin: F-0026, and the audit that
found _rc.sh had only ever been wired into the shell gates.

A fast gate can still hang: a pathological regex, a repo walk over a mounted network
folder, a subprocess that never returns. "It finished in milliseconds last time" is a
belief about the past, and F-0026 is precisely the failure of treating one of those as
present-tense fact. So every gate says whether it is running and how it ended, and
gates/liveness.py READS what is written here — a liveness record nobody reads is a
belief with a filename.

0.3.4 fixes:
  #29 CONCURRENCY. Two runs of the same gate shared one `<gate>.rc`, so the first to
      finish wrote its exit code over a run that was still going — a PASS attributed
      to a live process. Each process now owns `<gate>.<pid>.rc`; `<gate>.rc` is a
      pointer that is never written over a run whose pid is still alive.
  #30 `PROOF_OS_DIR` defaulted to a RELATIVE ".proof-os" and os.makedirs CREATED it,
      so a gate run from another cwd manufactured a fresh store there and the
      project's watcher saw no rc at all. We now walk UPWARD for an existing store and
      create nothing if there is none.
  #31 the ordinary success path recorded `exit=unknown`: a gate that returns 0 by
      falling off the end never reported 0, so "finished clean" and "died silently"
      were the same record. Normal interpreter termination is now recorded as 0, and
      the line says HOW the code was observed (`observed=`).
  #32 the SystemExit branch in the excepthook was dead code by its own comment, and
      the rc write was truncate-then-write — a reader could see a half-line. Writes
      are now atomic (tmp + os.replace).

What can still be missed, and is therefore written honestly rather than guessed:
a literal `raise SystemExit(n)` in a gate body is invisible to Python (CPython
consumes it before atexit runs and it never reaches sys.excepthook), so it would be
recorded as `exit=0 observed=fell-off-end`. `sys.exit()`, `exit()`, `quit()`, an
uncaught exception, and TERM/INT/HUP/QUIT are all observed directly. No gate in this
tree raises SystemExit literally; gates/liveness.py reports the `observed=` field so a
reader can see which of these produced the number.

Usage (first executable line of a gate):
    import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from _rc import rc_init; rc_init("meta_length")
"""
import atexit, builtins, os, signal, sys, time

_state = {"path": None, "ptr": None, "written": False}


def _stamp():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def find_store():
    """An EXISTING .proof-os, or None. A read never creates state (§4)."""
    env = os.environ.get("PROOF_OS_DIR")
    if env:
        return os.path.abspath(env) if os.path.isdir(env) else None
    d = os.path.abspath(os.getcwd())
    while True:
        cand = os.path.join(d, ".proof-os")
        if os.path.isdir(cand):
            return cand
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def _atomic(path, text):
    tmp = f"{path}.{os.getpid()}.tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write(text)
        os.replace(tmp, path)
        return True
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        return False


def read_pid(path):
    """The pid recorded in an rc file, or None."""
    try:
        with open(path, encoding="utf-8") as fh:
            for tok in fh.read().split():
                if tok.startswith("pid="):
                    return int(tok[4:])
    except (OSError, ValueError):
        return None
    return None


def pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True                      # exists, owned by someone else
    except OSError:
        return False
    return True


def _write(code, observed):
    if _state["written"] or not _state["path"]:
        return
    _state["written"] = True
    line = (f"exit={code} pid={os.getpid()} ended={_stamp()} "
            f"observed={observed}\n")
    _atomic(_state["path"], line)
    # the pointer is only ours to move while it still names us (#29)
    ptr = _state["ptr"]
    if ptr and read_pid(ptr) == os.getpid():
        _atomic(ptr, line)


def _norm(code):
    if code is None:
        return 0
    if isinstance(code, bool):
        return int(code)
    if isinstance(code, int):
        return code
    return 1                              # sys.exit("message") is a failure exit


def rc_init(name):
    store = find_store()
    if store is None:                     # #30: no store => nothing to record into
        _state["path"] = None
        return
    d = os.path.join(store, "rc")
    try:
        os.makedirs(d, exist_ok=True)     # a subdir of an EXISTING store, never a store
    except OSError:
        _state["path"] = None
        return

    pid = os.getpid()
    path = os.path.abspath(os.path.join(d, f"{name}.{pid}.rc"))
    ptr = os.path.abspath(os.path.join(d, f"{name}.rc"))
    start = f"running pid={pid} started={_stamp()}\n"
    if not _atomic(path, start):
        _state["path"] = None
        return
    other = read_pid(ptr) if os.path.exists(ptr) else None
    if other is None or other == pid or not pid_alive(other):
        _atomic(ptr, start)
        _state["ptr"] = ptr
    else:
        _state["ptr"] = None              # another run of this gate is still alive
    _state["path"] = path

    real_exit, real_hook = sys.exit, sys.excepthook

    def _exit(code=0):
        _write(_norm(code), "sys.exit")
        real_exit(code)

    def _hook(t, v, tb):
        # SystemExit never reaches excepthook (CPython handles it first), so this
        # branch is only ever a real exception — and an exception is not a pass.
        _write(1, f"exception:{getattr(t, '__name__', t)}")
        real_hook(t, v, tb)

    def _sig(signum, frame):
        _write(128 + signum, "signal")    # killed: observed, not guessed
        real_exit(128 + signum)

    for _name in ("SIGTERM", "SIGINT", "SIGHUP", "SIGQUIT"):
        sig = getattr(signal, _name, None)
        if sig is None:
            continue
        try:
            signal.signal(sig, _sig)
        except (OSError, ValueError):
            pass                          # not the main thread / not supported here

    sys.exit, sys.excepthook = _exit, _hook
    for _b in ("exit", "quit"):           # site's Quitter raises SystemExit directly
        if hasattr(builtins, _b):
            setattr(builtins, _b, _exit)
    # The interpreter reaching finalisation with nothing else recorded means the gate
    # ran off the end of its main body: that IS exit 0, and 0.3.3's `unknown` here lost
    # every clean run (#31). Every other ending is captured above, before this fires.
    atexit.register(lambda: _write(0, "fell-off-end"))
