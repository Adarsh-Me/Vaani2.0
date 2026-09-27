# Single-model MT + fast TTS + multilingual STT. Run once; copies to <filesDir>/models/.
# pip install huggingface_hub optimum[onnxruntime] transformers"<5" onnx onnxruntime
from huggingface_hub import snapshot_download
MT = "ai4bharat/indictrans2-indic-indic-dist-320M"   # single, covers en<->indic + indic<->indic (11 langs)
TTS = "ai4bharat/IndicF5"
print("MT:", snapshot_download(MT))
print("TTS:", snapshot_download(TTS))
print("Next: export MT with optimum-cli onnx --model", MT, " (encoder + decoder only), quantize INT8.")
print("The decoder_with_past export is useless here: it asks for pre-computed cross-attention")
print("caches that the encoder never emits, so TranslatorEngine decodes without a KV cache.")
print("export IndicF5 split text_encoder/fm_decoder/vocoder_backbone INT8 + vocos_head.npz.")
print("STT needs no export - run scripts/fetch-sravaani.py (downloads and QDQ-quantises).")
