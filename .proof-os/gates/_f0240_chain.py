#!/usr/bin/env python
"""
.proof-os/gates/_f0240_chain.py — the assertion table for F-0240, applied to whichever
(page, form) pair is handed to it.

F-0240's property is a CHAIN, not a token: the type the brand picks on the picker must be
handed to CampaignForm, must survive into the form's state, and must be placed in the create
payload. The old gate grepped for the literal `campaignType: selectedType` anywhere in the
page — which a hoisted-but-unused local satisfies forever while the chain is severed. So each
link here is extracted from its own SYNTACTIC POSITION (the value of the `initialValues`
JSX attribute; the argument of the formData useState; the value of the payload's
`campaignType` key) and checked for what it references. A literal sitting somewhere else in
the file is not in any of those positions and cannot satisfy any of them.

usage:  _f0240_chain.py <page.tsx-code-view> <campaign-form.tsx-code-view>
        exit 0 = chain intact · 1 = a link is severed · 2 = could not read

Both arguments are expected to be COMMENT-STRIPPED views (gates/_code.sh code_view), so a
comment quoting the fix cannot satisfy a link.

BLIND SPOT, stated: the brace/paren scanner below is depth-counting, not a TS parser. A
string literal or template literal containing an unbalanced brace inside one of the three
regions would confuse the extraction. It would produce a false RED (link not found), never a
false green, which is the safe direction.
"""
import io
import re
import sys


def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


def balanced(src, start, opener, closer):
    """src[start] must be `opener`; returns the balanced span including both ends."""
    depth = 0
    for i in range(start, len(src)):
        c = src[i]
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return None


def jsx_element(src, tag):
    """The full `<Tag ... >` / `<Tag ... />` opening element, braces respected."""
    m = re.search(r"<" + tag + r"\b", src)
    if not m:
        return None
    depth = 0
    i = m.end()
    while i < len(src):
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        elif c == ">" and depth == 0:
            return src[m.start():i + 1]
        i += 1
    return None


def attr_expr(element, name):
    """The `{...}` expression of a JSX attribute, or None if the attribute is absent."""
    m = re.search(r"\b" + name + r"\s*=\s*\{", element)
    if not m:
        return None
    return balanced(element, m.end() - 1, "{", "}")


def call_arg(src, pattern):
    """The balanced `( ... )` following the first match of `pattern`."""
    m = re.search(pattern, src)
    if not m:
        return None
    i = src.find("(", m.end() - 1)
    if i < 0:
        return None
    return balanced(src, i, "(", ")")


def object_value(obj, key):
    """The value expression of `key` at the top level of an object literal `{ ... }`."""
    depth = 0
    i = 0
    n = len(obj)
    while i < n:
        c = obj[i]
        if c in "{[(":
            depth += 1
            i += 1
            continue
        if c in "}])":
            depth -= 1
            i += 1
            continue
        if depth == 1:
            m = re.match(r"\b" + key + r"\s*:", obj[i:])
            if m and (i == 0 or not (obj[i - 1].isalnum() or obj[i - 1] in "_.$")):
                j = i + m.end()
                d2 = 0
                start = j
                while j < n:
                    c2 = obj[j]
                    if c2 in "{[(":
                        d2 += 1
                    elif c2 in "}])":
                        if d2 == 0:
                            break
                        d2 -= 1
                    elif c2 == "," and d2 == 0:
                        break
                    j += 1
                return obj[start:j].strip()
        i += 1
    return None


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("usage: _f0240_chain.py <page-view> <form-view>\n")
        return 2
    try:
        page = read(sys.argv[1])
        form = read(sys.argv[2])
    except OSError as e:
        sys.stderr.write("cannot read a view: %s\n" % e)
        return 2

    bad = 0

    # LINK 1 — the picker hands the picked type to the form.
    el = jsx_element(page, "CampaignForm")
    if el is None:
        print("x link 1: the page never renders <CampaignForm> — there is no handoff at all")
        bad += 1
    else:
        expr = attr_expr(el, "initialValues")
        if expr is None:
            print("x link 1: <CampaignForm> has no initialValues attribute — the picked type "
                  "cannot reach the form (F-0240 verbatim)")
            bad += 1
        elif "selectedType" not in expr:
            print("x link 1: the value of <CampaignForm initialValues={...}> does not reference "
                  "the picked type. It is: " + " ".join(expr.split()))
            # ASCII only in printed output: this runs under a cp1252 stdout on Windows, where a
            # non-ASCII dash comes out as a replacement char and makes the finding look corrupt.
            print("    A `campaignType: selectedType` literal elsewhere in the file does not "
                  "count - it has to be in THIS position to reach the form.")
            bad += 1

    # LINK 2 — the form's state actually absorbs initialValues.
    arg = call_arg(form, r"useState\s*<\s*CampaignFormData\s*>")
    if arg is None:
        print("x link 2: no useState<CampaignFormData>(...) found in the form — cannot tell "
              "whether initialValues reaches the form's state")
        bad += 1
    elif "initialValues" not in arg:
        print("x link 2: the form's formData state is initialised without initialValues, so a "
              "picked type handed in is dropped on arrival. Initialiser: "
              + " ".join(arg.split()))
        bad += 1

    # LINK 3 — the create payload carries it.
    m = re.search(r"\bpayload\s*=\s*\{", form)
    obj = balanced(form, m.end() - 1, "{", "}") if m else None
    if obj is None:
        print("x link 3: no `payload = { ... }` object literal found in the form — cannot tell "
              "what is sent to POST /campaigns")
        bad += 1
    else:
        val = object_value(obj, "campaignType")
        if val is None:
            print("x link 3: the create payload has no campaignType key — the backend applies "
                  "its own default and the brand's choice is lost (F-0240 verbatim)")
            bad += 1
        elif "formData.campaignType" not in val:
            print("x link 3: the payload's campaignType is not derived from the form's state. "
                  "It is: campaignType: " + " ".join(val.split()))
            bad += 1

    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
