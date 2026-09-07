#!/usr/bin/env python
"""Gate — the API host a build bakes in is actually served by a certificate for it.

Origin: F-0721. Also closes F-0736 (this gate once listed a key in KEYS that no
scanned file assigned, and exited 0 while reporting nothing about it; unmatched
keys are now printed as UNCHECKED).

F-0721. VITE_API_BASE_URL named a host that resolves to the right VPS but
has no nginx server block and no certificate there. Requests to it fall through
to that box's default vhost, which belongs to a co-tenant product, and the TLS
handshake presents that product's certificate. Every API call from such a bundle
fails before it reaches the application, so there is no status code and no CORS
header to debug — the browser shows a URL and nothing else.

Why the existing check did not catch it: vite.config.ts already fails the build
when VITE_API_BASE_URL is unset or points at localhost. That proves the value is
non-empty and non-local. It cannot prove the host answers, and "non-empty" was
never the failure mode. Nor is DNS enough: the host resolved correctly the whole
time. Only the certificate distinguishes "this host is served" from "this host
is swallowed by someone else's default vhost."

TRUE/FALSE separation, so an outage cannot read as a pass:
  - control host reachable + target host mismatched/refused -> BROKEN (1)
  - control host unreachable                                -> UNAVAILABLE (2)
The control host is the apex of the first owned domain. If we cannot reach that,
this gate has no opinion and says so rather than guessing.

Exit 0 served · 1 not served by a certificate for it · 2 cannot tell.

Usage:
  python .proof-os/gates/api-base-url-served.py
  python .proof-os/gates/api-base-url-served.py https://host/api/v1   # explicit
"""
import os
import re
import socket
import ssl
import sys

TIMEOUT = 10
KEYS = ("VITE_API_BASE_URL", "VITE_MEERA_STREAM_URL")
ASSIGN_RE = re.compile(r"^\s*(" + "|".join(KEYS) + r")\s*=\s*(\S+)\s*$")
HOST_RE = re.compile(r"^https?://([A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9])(?::([0-9]{1,5}))?(?:/.*)?$")


def owned_apex(repo):
    path = os.path.join(repo, ".proof-os", "gates", "owned-domains.txt")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.split("#", 1)[0].strip()
                if line:
                    return line
    except OSError:
        pass
    return None


def declared_urls(repo):
    """Every non-comment assignment of a baked base URL, with its location.

    Also returns the set of KEYS no scanned file assigned. F-0736: this gate
    listed a key it never reached and still exited 0, which reads as coverage.
    An unmatched key is an UNCHECKED key, never a passing one.
    """
    found = []
    for name in (".env.production", ".env.production.local"):
        p = os.path.join(repo, name)
        if not os.path.exists(p):
            continue
        with open(p, "r", encoding="utf-8", errors="replace") as fh:
            for n, line in enumerate(fh, 1):
                if line.lstrip().startswith("#"):
                    continue
                m = ASSIGN_RE.match(line.rstrip("\n"))
                if m:
                    found.append((p, n, m.group(1), m.group(2)))
    unmatched = sorted(set(KEYS) - {k for _, _, k, _ in found})
    return found, unmatched


def probe(host, port):
    """Return (ok, detail). ok is True served, False mismatch, None unreachable."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    try:
        with socket.create_connection((host, port), timeout=TIMEOUT) as raw:
            with ctx.wrap_socket(raw, server_hostname=host) as tls:
                cert = tls.getpeercert()
                subject = ""
                for rdn in cert.get("subject", ()):
                    for k, v in rdn:
                        if k == "commonName":
                            subject = v
                return True, "certificate valid for this host (CN={})".format(subject or "?")
    except ssl.SSLCertVerificationError as e:
        return False, "certificate does not cover this host: {}".format(e.verify_message or e)
    except socket.gaierror as e:
        return None, "does not resolve: {}".format(e)
    except (socket.timeout, TimeoutError):
        return None, "timed out after {}s".format(TIMEOUT)
    except (ConnectionRefusedError, OSError) as e:
        return None, "connection failed: {}".format(e)


def main(argv):
    repo = os.environ.get("PROOF_OS_REPO", ".")
    if len(argv) > 1:
        targets = [("<argv>", 0, "VITE_API_BASE_URL", u) for u in argv[1:]]
        # argv mode checks exactly what was named; no key-coverage claim is made.
        unmatched = []
    else:
        targets, unmatched = declared_urls(repo)

    if not targets:
        print("api-base-url-served: UNAVAILABLE — no baked base URL declared to check")
        print("NOT CHECKED: everything; an empty denominator is not a pass")
        return 2

    apex = owned_apex(repo)
    if not apex:
        print("api-base-url-served: UNAVAILABLE — no owned domain declared to use as control")
        return 2

    control_ok, control_detail = probe(apex, 443)
    if control_ok is None:
        print("api-base-url-served: UNAVAILABLE — control host {} {}".format(apex, control_detail))
        print("This gate refuses to call a target failure when the network itself is the "
              "variable. Re-run with connectivity.")
        print("NOT CHECKED: every declared base URL below")
        for path, n, key, url in targets:
            print("  unchecked {}:{}  {}={}".format(path, n, key, url))
        return 2

    problems = []
    for path, n, key, url in targets:
        m = HOST_RE.match(url)
        if not m:
            problems.append((path, n, key, url, "is not an http(s) URL"))
            continue
        host = m.group(1)
        port = int(m.group(2) or 443)
        if url.startswith("http://"):
            problems.append((path, n, key, url, "is plaintext http; a production base URL must be https"))
            continue
        ok, detail = probe(host, port)
        if ok is True:
            print("OK      {}:{}  {}  {} — {}".format(path, n, key, host, detail))
        elif ok is False:
            problems.append((path, n, key, url, detail))
        else:
            # Control reached, target did not: that is the target's problem.
            problems.append((path, n, key, url, detail))

    for path, n, key, url, why in problems:
        print("BROKEN  {}:{}  {}={}  {}".format(path, n, key, url, why))

    print("api-base-url-served: {} URL(s) checked (control {} reachable), {} not served".format(
        len(targets), apex, len(problems)))
    for key in unmatched:
        print("UNCHECKED  {} — no scanned file assigns it; this gate makes NO claim about it".format(key))
    print("NOT CHECKED: any key listed above as UNCHECKED; whether the host that answers is THIS application rather than "
          "another app with a valid certificate for the same name; whether the path "
          "component of the URL exists; and whether the bundle on the server was built "
          "from this file at all")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
