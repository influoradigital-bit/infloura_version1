import os, sys, py_compile, tempfile

src = sys.argv[1]
bad, n = [], 0
for sub in ("scripts", "gates"):
    d = os.path.join(src, sub)
    if not os.path.isdir(d):
        continue
    for f in sorted(os.listdir(d)):
        if f.endswith(".py"):
            n += 1
            try:
                py_compile.compile(os.path.join(d, f), cfile=tempfile.mktemp(), doraise=True)
            except py_compile.PyCompileError as e:
                bad.append(f"{sub}/{f}: {str(e).splitlines()[0]}")
for b in bad:
    print("  FAIL:", b)
print(f"  {n - len(bad)}/{n} python files compile")
sys.exit(1 if bad else 0)
