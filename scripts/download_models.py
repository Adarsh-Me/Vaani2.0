# Single-model MT + fast TTS + bilingual STT. Run once; copies to <filesDir>/models/.
# pip install huggingface_hub optimum[onnxruntime] transformers"<5" nemo_toolkit torch onnx onnxruntime
from huggingface_hub import snapshot_download
MT = "ai4bharat/indictrans2-indic-indic-dist-320M"   # single, covers en<->indic + indic<->indic (11 langs)
STT = "ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large"
TTS = "ai4bharat/IndicF5"
STT_W = "onnx-community/whisper-base"  # multilingual mic for the other 10 langs; INT8 3-file only
# (node scripts/fetch-whisper-base.mjs pulls encoder/decoder/with_past INT8 + tokenizer.json;
#  node scripts/gen-whisper-mel.mjs builds the Hann/mel constants - no export needed)
print("MT:", snapshot_download(MT))
print("STT:", snapshot_download(STT))
print("TTS:", snapshot_download(TTS))
print("Next: export MT with optimum-cli onnx --model", MT, " (encoder/decoder/decoder_with_past), quantize INT8;")
print("export STT CTC branch to model.int8.opt.onnx (+.data, vocab.txt, mel_filters.json, hanning_window.json);")
print("export IndicF5 split text_encoder/fm_decoder/vocoder_backbone INT8 + vocos_head.npz.")
