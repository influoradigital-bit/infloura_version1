# proof-os — a trust layer for AI work
Skills are tenants. This is the landlord.
/os-setup once per project → /work for everything after.
Plugin = the machine (read-only). <project>/.proof-os/ = the memory (yours).
proved vs believed · gates before judgment · every change journaled with who ·
failures close only by becoming permanent checks · working .md dies on task close.


## Graph engines

proof-os reads topology from one of two engines, and the graph always says which.

| engine | how | oracle_strength |
|---|---|---|
| `scan.py-regex` | built in, no dependency, JS/TS/PY | weak — pattern matching, 0% parser-derived |
| `graphify` | Tree-sitter AST, 25 languages, symbol-level | strong — 100% parser-derived |

Graphify (MIT, Graphify-Labs/graphify) derives topology properly. To use it:

    uv tool install graphifyy && graphify install
    /graphify .                                   # in your assistant
    python3 scripts/adapt_graphify.py graphify-out/graph.json \
        --out .proof-os/graph.json --project .

The adapter applies the trust law to the graph itself. Graphify tags every edge with
`_origin`: `ast` when a parser produced it, otherwise a model did. Parser edges may
render green. Model-derived edges are capped at `partial` and can never be green —
an edge a language model guessed is an opinion about your code, not a fact about it.

`gates/graph_source.py` enforces both halves: the graph must name its engine, and no
model-derived edge may render aligned.
