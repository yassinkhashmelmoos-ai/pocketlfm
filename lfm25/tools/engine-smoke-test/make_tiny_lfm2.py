#!/usr/bin/env python3
"""Tiny random-weight LFM2 GGUF (hybrid short-conv + attention) for smoke-testing the vendored llama.cpp + JNI.

Mirrors exactly what llama-model.cpp's LLM_ARCH_LFM2 loader asks for (tensor names, shapes, KV keys), with a
byte-level BPE vocabulary that includes the ChatML control tokens the app's prompt builder uses.
"""
import sys
import numpy as np
import gguf

N_EMBD, N_LAYER, N_FF, N_HEAD = 64, 4, 128, 4
HEAD_KV = [0, 2, 0, 2]            # 0 = short-conv (recurrent) layer, >0 = attention layer
L_CACHE = 3
HEAD_DIM = N_EMBD // N_HEAD


def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, [chr(c) for c in cs]))


def build_vocab():
    b2u = bytes_to_unicode()
    tokens = [b2u[i] for i in range(256)]
    types = [1] * 256
    # a few merges so the BPE has something to do
    merged = ["Ġt", "th", "he", "Ġthe"]
    merges = ["Ġ t", "t h", "h e", "Ġt he"]
    tokens += merged
    types += [1] * len(merged)
    specials = ["<|startoftext|>", "<|endoftext|>", "<|im_start|>", "<|im_end|>"]
    first_special = len(tokens)
    tokens += specials
    types += [3] * len(specials)           # CONTROL
    return tokens, types, merges, first_special


def make(path, quant):
    tokens, types, merges, first_special = build_vocab()
    n_vocab = len(tokens)
    bos, eot, im_start, im_end = first_special, first_special + 1, first_special + 2, first_special + 3

    w = gguf.GGUFWriter(path, "lfm2")
    w.add_name("synthetic-lfm2-smoke-test")
    w.add_context_length(4096)
    w.add_embedding_length(N_EMBD)
    w.add_block_count(N_LAYER)
    w.add_feed_forward_length(N_FF)
    w.add_head_count(N_HEAD)
    w.add_head_count_kv(HEAD_KV)
    w.add_rope_freq_base(1000000.0)
    w.add_layer_norm_rms_eps(1e-5)
    w.add_vocab_size(n_vocab)
    w.add_uint32("lfm2.shortconv.l_cache", L_CACHE)
    w.add_file_type(gguf.LlamaFileType.ALL_F32 if not quant else gguf.LlamaFileType.MOSTLY_Q8_0)

    w.add_tokenizer_model("gpt2")
    w.add_tokenizer_pre("lfm2")
    w.add_token_list(tokens)
    w.add_token_types(types)
    w.add_token_merges(merges)
    w.add_bos_token_id(bos)
    w.add_eos_token_id(im_end)
    w.add_pad_token_id(eot)

    rng = np.random.default_rng(1234)

    def lin(rows, cols):
        a = (rng.standard_normal((rows, cols)) * 0.05).astype(np.float32)
        if quant:
            return gguf.quants.quantize(a, gguf.GGMLQuantizationType.Q8_0), gguf.GGMLQuantizationType.Q8_0
        return a, gguf.GGMLQuantizationType.F32

    def add_lin(name, rows, cols):
        data, qt = lin(rows, cols)
        w.add_tensor(name, data, raw_dtype=qt) if quant else w.add_tensor(name, data)

    def add_norm(name, n):
        w.add_tensor(name, np.ones(n, dtype=np.float32))

    add_lin("token_embd.weight", n_vocab, N_EMBD)
    add_norm("token_embd_norm.weight", N_EMBD)           # LFM2's output norm (named token_embd_norm)
    for i in range(N_LAYER):
        add_norm(f"blk.{i}.attn_norm.weight", N_EMBD)
        add_norm(f"blk.{i}.ffn_norm.weight", N_EMBD)
        add_lin(f"blk.{i}.ffn_gate.weight", N_FF, N_EMBD)
        add_lin(f"blk.{i}.ffn_up.weight", N_FF, N_EMBD)
        add_lin(f"blk.{i}.ffn_down.weight", N_EMBD, N_FF)
        if HEAD_KV[i] == 0:                               # short-conv layer
            w.add_tensor(f"blk.{i}.shortconv.conv.weight", (rng.standard_normal((N_EMBD, L_CACHE)) * 0.05).astype(np.float32))
            add_lin(f"blk.{i}.shortconv.in_proj.weight", 3 * N_EMBD, N_EMBD)
            add_lin(f"blk.{i}.shortconv.out_proj.weight", N_EMBD, N_EMBD)
        else:                                             # attention layer
            kv = HEAD_DIM * HEAD_KV[i]
            add_norm(f"blk.{i}.attn_q_norm.weight", HEAD_DIM)
            add_norm(f"blk.{i}.attn_k_norm.weight", HEAD_DIM)
            add_lin(f"blk.{i}.attn_q.weight", N_EMBD, N_EMBD)
            add_lin(f"blk.{i}.attn_k.weight", kv, N_EMBD)
            add_lin(f"blk.{i}.attn_v.weight", kv, N_EMBD)
            add_lin(f"blk.{i}.attn_output.weight", N_EMBD, N_EMBD)

    w.write_header_to_file()
    w.write_kv_data_to_file()
    w.write_tensors_to_file()
    w.close()
    print(f"wrote {path}  (vocab={n_vocab}, bos={bos}, eos/im_end={im_end}, im_start={im_start}, quant={'Q8_0' if quant else 'F32'})")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "tiny_lfm2"
    make(out + "_f32.gguf", quant=False)
    make(out + "_q8.gguf", quant=True)
