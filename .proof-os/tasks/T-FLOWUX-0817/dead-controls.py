"""Enabled-<Button>-without-handler sweep over the brand + creator surfaces.

A control that renders enabled and carries no onClick / asChild / type=submit /
href / form= is either dead or a trigger child. Trigger children are filtered by
hand against the surrounding <*Trigger asChild> — this script reports candidates,
it does not adjudicate them.

Usage:  python .proof-os/tasks/T-FLOWUX-0817/dead-controls.py
"""
import glob
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

HANDLERS = ('onClick', 'asChild', 'submit', 'href', 'form=')

hits = []
for f in glob.glob('src/**/*.tsx', recursive=True):
    if '.test.' in f:
        continue
    if 'creator' not in f and 'brand' not in f:
        continue
    s = open(f, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'<Button\b((?:[^>]|\n)*?)>', s):
        if any(k in m.group(1) for k in HANDLERS):
            continue
        line = s[:m.start()].count('\n') + 1
        label = re.sub(r'\s+', ' ', s[m.end():m.end() + 110].replace('\n', ' ')).strip()[:55]
        hits.append((f, line, label))

print(f'{len(hits)} <Button> with no handler in brand+creator surfaces\n')
for f, l, t in hits:
    print(f'{f}:{l} | {t}')
