# Builds the multilingual STT pack into app/src/main/assets/models/stt-sravaani/ (git-ignored):
#
#   SraVaani-1.0 (ARTPARK + IISc), FastConformer-TDT, 443.6M params, MIT licence, 65 Indic
#   languages including Odia - which no Whisper release has a token for.
#
# Two things happen here that a plain download cannot do:
#
# 1. WEIGHT-ONLY INT8 IN QDQ FORM. The published int8 encoder is 608 MB, and 215 MB of that is
#    still fp32 - 53.8M parameters, 99.1% of them Conv weights. Those become per-channel int8
#    behind a DequantizeLinear, which lands the file at 454 MB. QDQ rather than ConvInteger is
#    deliberate: ONNX Runtime 1.22 for Android has no ConvInteger kernel, which is exactly why
#    the Whisper int8 encoder had to be swapped for its fp16 one. The compute stays fp32, so
#    this buys 154 MB and no speed change. Measured on the app's own 22 reference clips: worst
#    per-clip regression +0.0 points, one clip improved, every output script identical.
#
# 2. FRONTEND CONSTANTS. NeMo's fbank lives inside preproc.pt (a pickled torch bundle), so it is
#    dumped to JSON here rather than re-derived: window (400), fb (128x257) and the scalar
#    parameters, verbatim. SentencePiece pieces go to vocab.json because the Kotlin side only
#    needs id -> piece to decode.
#
#   pip install torch onnx numpy sentencepiece
#   python scripts/fetch-sravaani.py
import json
import os
import urllib.request

import numpy as np
import onnx
import torch
from onnx import TensorProto, helper, numpy_helper

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "app", "src", "main", "assets", "models", "stt-sravaani")
# Downloads land under build/, NOT in assets: Gradle bundles every file it finds in the assets
# tree, so leaving the 608 MB un-quantised encoder next to its 454 MB QDQ child would ship both.
SRC = os.path.join(HERE, "..", "build", "sravaani")
os.makedirs(ROOT, exist_ok=True)
os.makedirs(SRC, exist_ok=True)

FILES = [
    # (destination, source url). The int8 encoder is the community export; the decoder,
    # tokenizer, frontend and config come from the model's own repository so they cannot drift
    # apart on the int8 encoder's op names.
    ("encoder.int8.onnx", "https://huggingface.co/Trendova/sravaani-int8/resolve/main/encoder-sravaani.int8.onnx", SRC),
    ("decoder_joint.onnx", "https://huggingface.co/killbanhar/sravaani-onnx/resolve/main/decoder_joint.onnx", ROOT),
    ("tokenizer.model", "https://huggingface.co/ARTPARK-IISc/SraVaani-1.0/resolve/main/tokenizer.model", SRC),
    ("preproc.pt", "https://huggingface.co/ARTPARK-IISc/SraVaani-1.0/resolve/main/preproc.pt", SRC),
    ("config.json", "https://huggingface.co/ARTPARK-IISc/SraVaani-1.0/resolve/main/config.json", SRC),
]


def fetch(dest, url):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"  {os.path.basename(dest):24s} present {os.path.getsize(dest) / 1048576:.1f} MB, skip")
        return
    print(f"  {os.path.basename(dest):24s} downloading ...")
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"user-agent": "vani-fetch/1"})
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as w:
        got = 0
        while True:
            b = r.read(1 << 22)
            if not b:
                break
            w.write(b)
            got += len(b)
            if got % (64 << 20) < (1 << 22):
                print(f"      {got / 1048576:.0f} MB")
    os.replace(tmp, dest)
    print(f"      {got / 1048576:.1f} MB done")


def quantise_conv_weights(src, dst):
    """fp32 Conv weights -> per-channel int8 + DequantizeLinear. See the header note on why."""
    m = onnx.load(src, load_external_data=True)
    opset = {o.domain or "ai.onnx": o.version for o in m.opset_import}
    conv_w = {}
    for nd in m.graph.node:
        if nd.op_type == "Conv":
            for i in nd.input[1:]:
                conv_w.setdefault(i, 0)
                conv_w[i] += 1
    inits = {i.name: i for i in m.graph.initializer}
    targets = {n: c for n, c in conv_w.items() if n in inits and inits[n].data_type == TensorProto.FLOAT}

    new_inits, dq_for, rewired = [], {}, 0
    for name in targets:
        w = numpy_helper.to_array(inits[name]).astype(np.float32)
        if w.ndim < 2:
            continue                      # a 1-D conv bias: keep it fp32
        c = w.shape[0]
        flat = w.reshape(c, -1)
        amax = np.abs(flat).max(axis=1)
        scale = np.where(amax > 0, amax / 127.0, 1.0).astype(np.float32)
        q = np.clip(np.round(flat / scale.reshape(-1, 1)), -127, 127).astype(np.int8).reshape(w.shape)
        new_inits.append(numpy_helper.from_array(q, name + "_q"))
        # Per-channel DequantizeLinear takes scale/zero_point as a 1-D vector of length C and
        # broadcasts along `axis` itself. Reshaping to [C,1,1] passes the checker and then dies
        # at run time with a bare "Non-zero status code".
        new_inits.append(numpy_helper.from_array(scale.reshape([c]), name + "_s"))
        new_inits.append(numpy_helper.from_array(np.zeros(c, dtype=np.int8), name + "_z"))
        dq_for[name] = helper.make_node("DequantizeLinear", [name + "_q", name + "_s", name + "_z"],
                                        [name + "_dq"], name=name + "_dq", axis=0)
        rewired += targets[name]

    renamed = {n: n + "_dq" for n in dq_for}
    kept = [i for i in m.graph.initializer if i.name not in dq_for]
    nodes, emitted = [], set()
    for nd in m.graph.node:
        for k, v in enumerate(nd.input):
            if v in renamed:
                nd.input[k] = renamed[v]
                if renamed[v] not in emitted:
                    nodes.append(dq_for[v])       # a DQ must precede its first consumer
                    emitted.add(renamed[v])
        nodes.append(nd)
    graph = helper.make_graph(nodes, m.graph.name, m.graph.input, m.graph.output, kept + new_inits)
    out = helper.make_model(graph, opset_imports=[helper.make_opsetid(d, v) for d, v in opset.items()])
    out.ir_version = m.ir_version
    onnx.checker.check_model(out)
    onnx.save(out, dst)
    before, after = os.path.getsize(src), os.path.getsize(dst)
    print(f"  conv weights int8: {len(dq_for)} tensors, {rewired} conv inputs rewired, "
          f"{before / 1048576:.1f} -> {after / 1048576:.1f} MB")
    return after


def dump_frontend():
    p = torch.load(os.path.join(SRC, "preproc.pt"), map_location="cpu")
    par = {k: (float(v) if isinstance(v, (int, float, np.floating, np.integer)) else v)
           for k, v in p["params"].items()}
    json.dump(par, open(os.path.join(ROOT, "fbank_params.json"), "w", encoding="utf-8"), indent=1)
    win = p["window"].numpy().astype(np.float32)
    fb = p["fb"].numpy().astype(np.float32).reshape(p["fb"].shape[-2], p["fb"].shape[-1])
    json.dump(win.tolist(), open(os.path.join(ROOT, "fbank_window.json"), "w", encoding="utf-8"),
              separators=(",", ":"))
    # Kotlin reads melMat[bin][mel]; preproc ships fb[mel][bin].
    json.dump(fb.T.tolist(), open(os.path.join(ROOT, "fbank_matrix.json"), "w", encoding="utf-8"),
              separators=(",", ":"))
    print(f"  frontend: window {tuple(win.shape)}, fb {tuple(fb.shape)} -> mel matrix "
          f"[{fb.shape[1]}][{fb.shape[0]}] for the Kotlin index order")
    print(f"  params: n_fft={par['n_fft']} hop={par['hop_length']} win={par['win_length']} "
          f"preemph={par['preemph']} mag_power={par['mag_power']} pad={par['pad_value']}")


def dump_vocab():
    import sentencepiece as spm
    sp = spm.SentencePieceProcessor(model_file=os.path.join(SRC, "tokenizer.model"))
    json.dump([sp.id_to_piece(i) for i in range(sp.get_piece_size())],
              open(os.path.join(ROOT, "vocab.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"  vocab: {sp.get_piece_size()} pieces")


if __name__ == "__main__":
    for name, url, where in FILES:
        fetch(os.path.join(where, name), url)
    quantise_conv_weights(os.path.join(SRC, "encoder.int8.onnx"),
                          os.path.join(ROOT, "encoder.qdq.onnx"))
    dump_frontend()
    dump_vocab()
    cfg = json.load(open(os.path.join(SRC, "config.json")))
    print("  model:", {k: cfg[k] for k in ("vocab_size", "blank_id", "durations", "max_symbols",
                                           "pred_hidden", "pred_rnn_layers", "feat_in",
                                           "enc_d_model", "subsampling_factor")})
    tot = sum(os.path.getsize(os.path.join(ROOT, f)) for f in os.listdir(ROOT))
    print(f"OK. pack total {tot / 1048576:.1f} MB -> {os.path.normpath(ROOT)}")
