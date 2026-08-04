# sage-digital — plugin marketplace

Two plugins. One is the machine, one is the tenants.

| plugin | what it is |
|---|---|
| `proof-os` 0.1.9 | the OS: derived graph, executable gates, ledger, trust registry |
| `sage-team` 0.1.0 | 24 services that run on it |

## Install (as a marketplace)

    /plugin marketplace add <your-github-user>/<this-repo>
    /plugin install proof-os@sage-digital
    /plugin install sage-team@sage-digital

A local path works too, no git needed:

    /plugin marketplace add /absolute/path/to/this/folder

## Try one without installing

    claude --plugin-dir ./dist/proof-os-0.1.9.plugin
    claude --plugin-dir ./dist/sage-team-0.1.0.plugin

Then `/proof-os:os-setup`, then `/proof-os:work`.

## Namespacing — read this before you wire the registry

Installed as a plugin, skills are namespaced: `/sage-team:kavya`, not `/kavya`.
`proof-os` discovers them as `sage-team:<name>`, so a registry keyed on bare names
will not match and those services render blank.

Use `sage-team/templates/registry.sage-team.json`, which is keyed to match.

## Release

    bash build-plugin.sh ./proof-os 0.2.0 dist/proof-os-0.2.0.plugin

Compiles first, stamps `MANIFEST.sha256`, zips, then **extracts the archive again and
verifies version and every hash from inside it**. Steps 1-3 are claims. Step 4 is evidence.
Origin: three releases once shipped a stale manifest because a version bump chained after
a failing compile and silently never ran.
