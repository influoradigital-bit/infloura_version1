#!/usr/bin/env bash
# gates/F-0203-npm-audit-high.sh — origin: F-0203 (vulnerable-dependencies).
# Fails on any HIGH+ npm advisory. LAW: tool-cannot-run => exit 2.
set -u
cd "$(dirname "$0")/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
command -v npm >/dev/null 2>&1 || { echo "· npm not on PATH — unavailable"; exit 2; }
npm audit --audit-level=high; rc=$?
echo "NOT CHECKED: moderate/low advisories (this gate's bar is high+); vulnerabilities with no published advisory; influora-api's Maven dependency tree"
[ $rc -eq 0 ] && exit 0 || exit 1
