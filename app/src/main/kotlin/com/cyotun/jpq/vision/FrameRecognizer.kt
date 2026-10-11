package com.cyotun.jpq.vision

import android.media.Image

interface FrameRecognizer : AutoCloseable {
    val ready: Boolean
    /** Image is owned by capture and valid only until this call returns. */
    fun observe(image: Image)
    override fun close()
}

/** Safe boundary until Tencent game assets and whole-round recognition pass. */
class AwaitingValidatedAdapter : FrameRecognizer {
    override val ready = false
    override fun observe(image: Image) = Unit
    override fun close() = Unit
}
