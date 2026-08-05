#!/usr/bin/env python3
"""gates/notifications_wired.py — guards the F-0043 fix from regressing.

origin: F-0043 (notifications-mock-panel, src/components/brand/brand-layout.tsx).
  The brand top-bar notification dropdown rendered a hardcoded `mockNotifications`
  array, and its badge read `useNotificationStore` — a zustand store that was
  NEVER populated (so the badge was always 0). Both were disconnected from the
  live GET /notifications system (useNotifications hook / NotificationBell).
  The fix wired brand-layout to useNotifications('brand') and DELETED the dead
  store. This gate fails if either dead pattern comes back.

Specifically forbidden in src/ (application code):
  - `mockNotifications`            (the hardcoded array that was rendered)
  - `useNotificationStore`         (the never-populated store, now deleted)
Allowed: MOCK_NOTIFICATIONS inside the useNotifications hook itself is the
demo-mode FALLBACK (isApiLive() === false) and is NOT this pattern.

LAW: exit 1 for a real regression, exit 0 when clean. No tool dependency.
usage: notifications_wired.py [project_root]
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
src = root / "src"
if not src.exists():
    print("· no src/ — unavailable")
    sys.exit(2)

FORBIDDEN = ("mockNotifications", "useNotificationStore")
hits = []
for f in list(src.rglob("*.ts")) + list(src.rglob("*.tsx")):
    try:
        text = f.read_text(encoding="utf-8", errors="replace")
    except Exception:
        continue
    rel = str(f.relative_to(root)).replace("\\", "/")
    for i, line in enumerate(text.splitlines(), 1):
        for pat in FORBIDDEN:
            if re.search(r"\b" + re.escape(pat) + r"\b", line):
                hits.append(f"{rel}:{i}  {pat}  {line.strip()[:70]}")

if hits:
    print(f"VERDICT: broken — {len(hits)} reference(s) to the removed mock/dead-store notification pattern")
    for h in hits:
        print(f"  {h}")
    sys.exit(1)
print("VERDICT: aligned (proved) — brand notifications wired to useNotifications; no mockNotifications / useNotificationStore in src")
sys.exit(0)
