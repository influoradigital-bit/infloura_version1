"""F-0681 scanner — every constrained field on CreatorProfilePatchRequest must be either
HIGHLIGHTED (rendered inline) or LABELLED (named in the toast). Never silently swallowed.

Written as its own file rather than a heredoc inside the .sh: a heredoc'd Python loses a level of
backslash escaping in this environment and has silently neutered a regex gate here before.
"""
import re, sys, pathlib

DTO = pathlib.Path("influora-api/src/main/java/com/influora/web/dto/creator/CreatorProfileDtos.java")
TSX = pathlib.Path("src/pages/creator-profile.tsx")

def fail(msg):
    print(msg); sys.exit(1)
def unavailable(msg):
    print(msg); sys.exit(2)

if not DTO.exists(): unavailable(f"- {DTO} missing - unavailable")
if not TSX.exists(): unavailable(f"- {TSX} missing - unavailable")

java = DTO.read_text(encoding="utf-8", errors="replace")
m = re.search(r"record\s+CreatorProfilePatchRequest\s*\((.*?)\)\s*\{", java, re.S)
if not m:
    unavailable("- could not locate the CreatorProfilePatchRequest record - shape changed, so any"
                " claim below would be fabricated")
body = m.group(1)

CONSTRAINT = re.compile(r"@(Size|DecimalMin|DecimalMax|Min|Max|NotNull|NotBlank|Pattern|Email)\b")
NAME = re.compile(r"(\w+)\s*,?\s*$")
constrained = []
for raw in body.split("\n"):
    line = raw.split("//")[0].rstrip()
    if not line.strip() or not CONSTRAINT.search(line):
        continue
    nm = NAME.search(line)
    if nm:
        constrained.append(nm.group(1))

if not constrained:
    unavailable("- found ZERO constrained fields on the DTO, which contradicts the known @Size"
                " annotations - treating as a broken parser, not a pass")
print(f"- DTO declares {len(constrained)} constrained field(s): {', '.join(sorted(constrained))}")

tsx = TSX.read_text(encoding="utf-8", errors="replace")
hm = re.search(r"HIGHLIGHTED_FIELD_KEYS\s*=\s*new Set\(\[(.*?)\]\)", tsx, re.S)
lm = re.search(r"FIELD_LABELS:\s*Record<string, string>\s*=\s*\{(.*?)\n\};", tsx, re.S)
if not hm: fail("VERDICT: broken - HIGHLIGHTED_FIELD_KEYS is gone (F-0681)")
if not lm: fail("VERDICT: broken - FIELD_LABELS is gone (F-0681)")
highlighted = set(re.findall(r"'([^']+)'", hm.group(1)))
labelled = set(re.findall(r"(\w+)\s*:", lm.group(1)))
print(f"- TSX highlights {len(highlighted)}, labels {len(labelled)}")

# 1. Nothing constrained may be silently swallowed.
swallowed = [f for f in constrained if f not in highlighted and f not in labelled]
if swallowed:
    fail("VERDICT: broken - these constrained DTO fields are neither highlighted nor named, so a\n"
         "         server rejection on them shows a toast that points at nothing: "
         + ", ".join(sorted(swallowed)) + " (F-0681)")

# 2. A key may not CLAIM a highlight it does not render - that is the F-0681 defect in reverse.
unrendered = [f for f in highlighted if f"fieldErrors.{f}" not in tsx]
if unrendered:
    fail("VERDICT: broken - HIGHLIGHTED_FIELD_KEYS promises a highlight for fields the form never\n"
         "         renders, so the toast lies again: " + ", ".join(sorted(unrendered)) + " (F-0681)")

# 3. The index normaliser must survive - Spring names list violations `categories[0]`.
if "baseFieldName" not in tsx or r"\[\d+\]" not in tsx:
    fail("VERDICT: broken - the `categories[0]` index normaliser is gone; per-item violations are\n"
         "         swallowed again (F-0681)")

print("- every constrained field is highlighted or named; no key promises an unrendered highlight;"
      " the index normaliser is intact")
sys.exit(0)
