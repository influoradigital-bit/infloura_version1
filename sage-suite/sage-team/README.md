# sage-team

The 24 Sage Digital services, packaged as tenants for `proof-os`.

Installed as a plugin, every skill is namespaced: `/sage-team:kavya`, `/sage-team:meera`.

**That namespacing matters.** `proof-os` discovers plugin skills as `sage-team:<name>`,
so a registry keyed on bare names (`kavya`) will not match them and those services
render blank on the map. Use `templates/registry.sage-team.json`, which is keyed to match.

The trust ceilings are not decoration:
- `meera` and `neha` are oracles — exit codes, gates/build.sh and gates/e2e.sh
- `kavya` and `kabir` are judgment — believed, never green, however confident they sound
- `swapnil` is root — channel 1, above the registry; `done_when` comes from here
