import json, sys, urllib.request

GGUF = [
    ("lfm2-350m-q4km",  "LiquidAI/LFM2-350M-GGUF",            "Q4_K_M", "LiquidAI/LFM2-350M"),
    ("qwen3-0_6b-q4km", "unsloth/Qwen3-0.6B-GGUF",            "Q4_K_M", "Qwen/Qwen3-0.6B"),
    ("qwen3-1_7b-q4km", "unsloth/Qwen3-1.7B-GGUF",            "Q4_K_M", "Qwen/Qwen3-1.7B"),
    ("qwen3-4b-q4km",   "Qwen/Qwen3-4B-GGUF",                 "Q4_K_M", "Qwen/Qwen3-4B"),
    ("gemma3-1b-q4km",  "unsloth/gemma-3-1b-it-GGUF",         "Q4_K_M", "unsloth/gemma-3-1b-it"),
    ("llama32-1b-q4km", "unsloth/Llama-3.2-1B-Instruct-GGUF", "Q4_K_M", "unsloth/Llama-3.2-1B-Instruct"),
    ("smollm3-3b-q4km", "ggml-org/SmolLM3-3B-GGUF",           "Q4_K_M", "HuggingFaceTB/SmolLM3-3B"),
    ("phi4-mini-q4km",  "unsloth/Phi-4-mini-instruct-GGUF",   "Q4_K_M", "microsoft/Phi-4-mini-instruct"),
]

def get(url, raw=False):
    req = urllib.request.Request(url, headers={"User-Agent": "tb-chat-catalog/1.0"})
    with urllib.request.urlopen(req, timeout=45) as r:
        return r.read() if raw else json.load(r)

res = []
for mid, repo, quant, base in GGUF:
    rec = {"id": mid, "repo": repo, "quant": quant, "base": base}
    try:
        d = get("https://huggingface.co/api/models/%s?blobs=true" % repo)
        rec["sha"] = d.get("sha")
        rec["gated"] = bool(d.get("gated"))
        rec["license"] = (d.get("cardData") or {}).get("license")
        pick = None
        for s in d.get("siblings", []):
            fn = s.get("rfilename", "")
            if not fn.endswith(".gguf") or "of-000" in fn:
                continue
            if quant.lower() not in fn.lower():
                continue
            lfs = s.get("lfs") or {}
            cand = {"path": fn, "size": s.get("size") or lfs.get("size"), "sha256": lfs.get("sha256")}
            # Prefer the plain quant file over UD-/imatrix- variants: shortest name wins.
            if pick is None or len(cand["path"]) < len(pick["path"]):
                pick = cand
        rec["file"] = pick
    except Exception as e:
        rec["error"] = str(e)

    # Architecture facts drive the KV-cache maths, so take them from config.json
    # rather than guessing from the parameter count.
    try:
        cfg = json.loads(get("https://huggingface.co/%s/resolve/main/config.json" % base, raw=True))
        txt = cfg.get("text_config") or cfg
        heads = txt.get("num_attention_heads")
        hidden = txt.get("hidden_size")
        hd = txt.get("head_dim") or (hidden // heads if heads and hidden else None)
        rec["arch"] = {
            "layers": txt.get("num_hidden_layers"),
            "kvHeads": txt.get("num_key_value_heads") or heads,
            "headDim": hd,
            "vocab": txt.get("vocab_size") or cfg.get("vocab_size"),
            "maxCtx": txt.get("max_position_embeddings"),
            "type": cfg.get("model_type"),
        }
    except Exception as e:
        rec["arch_error"] = str(e)
    res.append(rec)

json.dump(res, open(sys.argv[1], "w", encoding="utf-8"), indent=1)
for r in res:
    f = r.get("file"); a = r.get("arch") or {}
    print("%-17s %-38s sha=%-9s lic=%-11s gated=%s" % (
        r["id"], r["repo"], (r.get("sha") or "?")[:8], r.get("license"), r.get("gated")))
    print("     file: %s" % (("%s  %s B  %s" % (f["path"], f["size"], (f["sha256"] or "")[:16])) if f else "NONE"))
    print("     arch: L=%s kv=%s hd=%s vocab=%s ctx=%s type=%s %s" % (
        a.get("layers"), a.get("kvHeads"), a.get("headDim"), a.get("vocab"),
        a.get("maxCtx"), a.get("type"), r.get("arch_error", "")))
