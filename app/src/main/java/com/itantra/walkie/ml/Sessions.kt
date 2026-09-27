package com.itantra.walkie.ml

import ai.onnxruntime.OrtSession

/**
 * The one place ONNX Runtime session options are built, so the memory numbers stay comparable
 * across the seven graphs.
 *
 * The CPU memory arena is what holds this app's RAM. Every model here takes a shape that changes
 * with each turn - mel frames per utterance, tokens per sentence - so the arena grows to the
 * largest intermediate any turn has ever needed and never gives the pages back, and a handset that
 * transmitted one short sentence then sat idle carries 1.18 GB of pooled, private, dirty memory.
 *
 * Measured rather than reasoned about, with `Debug.getNativeHeapAllocatedSize()` after each engine
 * on the LOAD line and `debug demo` for what it cost the solve:
 *   arena on everywhere      2,247 MB   render 6.0 s
 *   arena off everywhere     1,088 MB   render 10.8 s   (every Euler step pays a malloc again)
 *   memory pattern off       2,247 MB   render 6.7 s    (no memory effect at all)
 *   arena shrinkage cpu:100  2,254 MB   render 6.1 s    (accepted, and a no-op)
 *
 * So the arena stays on, including on the encoder. Turning it off there alone was tried: it saved
 * the predicted 505 MB (2,254 -> 1,749 MB) and doubled the encoder's pass, Hindi 287 -> 616 ms and
 * Gujarati 327 -> 1,453 ms, which is a slower answer on the one action the product is named after.
 * If a 4 GB field handset starts getting killed under this, that trade is the first one to revisit
 * and it is one argument to add back here.
 */
object Sessions {
    fun options(threads: Int): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
}
