import os, json
def data_dir():
    d = os.environ.get("PROOF_OS_DIR", os.path.join(os.getcwd(), ".proof-os"))
    os.makedirs(os.path.join(d, "ledger"), exist_ok=True)
    os.makedirs(os.path.join(d, "tasks"), exist_ok=True)
    os.makedirs(os.path.join(d, "gates"), exist_ok=True)
    led = os.path.join(d, "ledger", "failures.jsonl")
    if not os.path.exists(led): open(led, "a").close()
    return d
def plugin_dir():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def load_registry():
    p = os.path.join(data_dir(), "registry.json")
    return json.load(open(p)) if os.path.exists(p) else None

def version():
    """F-0024: never report numbers without saying which build produced them."""
    p = os.path.join(plugin_dir(), ".claude-plugin", "plugin.json")
    try:
        return json.load(open(p)).get("version", "unknown")
    except Exception:
        return "unknown"
