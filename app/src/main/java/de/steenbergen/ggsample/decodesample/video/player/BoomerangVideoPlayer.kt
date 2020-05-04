package de.steenbergen.ggsample.decodesample.video.player

import FrameRangeExtractor
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.nio.ByteBuffer

class BoomerangVideoPlayer(
    video: AssetFileDescriptor,
    val tag: String,
    videoWidth: Int,
    videoHeight: Int,
    keyFrameDistance: Int // 1 if every frame is a keyframe, 5 if every fifth frame is one etc
) {
    private val extractor = FrameRangeExtractor(video, videoWidth, videoHeight, keyFrameDistance)
    private val keyFrameMicros = extractor.keyFrameMicros

    private var currentKeyframeTimestampMicrosIndex: Int = -1
    private var cachedFrames: List<ByteBuffer> = listOf()
    private var cachePos = 0

    private var isReverse: Boolean = false

    fun next(): ByteBuffer {
        Log.i(tag, "Rendering next, cachePos $cachePos, cachedFramesSize ${cachedFrames.size}")
        if (cachePos >= cachedFrames.size) {
            checkIfReachedEndAndReverse()
            replenishCache()
        }
        return cachedFrames[cachePos++]
    }

    private fun checkIfReachedEndAndReverse() {
        Log.i(
            tag,
            "checkIfReachedEndAndReverse isReverse $isReverse currentKeyframeTimestampMicrosIndex $currentKeyframeTimestampMicrosIndex keyFrameMicrosSize ${keyFrameMicros.size}"
        )
        if (isReverse) {
            if (currentKeyframeTimestampMicrosIndex == 0) {
                Log.i(
                    tag,
                    "checkIfReachedEndAndReverse Reversing A"
                )
                isReverse = !isReverse
            }
        } else {
            if (currentKeyframeTimestampMicrosIndex == keyFrameMicros.size - 2) { // TODO: Fix this bug properly
                Log.i(
                    tag,
                    "checkIfReachedEndAndReverse Reversing B"
                )
                isReverse = !isReverse
            }
        }
    }

    private fun replenishCache() {
        Log.i(
            tag,
            "replenishCache cachePos $cachePos currentKeyframeTimestampMicrosIndex $currentKeyframeTimestampMicrosIndex"
        )
        moveToNextKeyframeMicros()
        cachePos = 0
        val generatedFrames = extractor.extractFrameRange(
            keyFrameMicros[currentKeyframeTimestampMicrosIndex]
        )
        Log.i(
            tag,
            "replenishCache done cachePos $cachePos currentKeyframeTimestampMicrosIndex $currentKeyframeTimestampMicrosIndex cacheSize ${cachedFrames.size}"
        )
        cachedFrames = if (isReverse) {
            generatedFrames.reversed()
        } else {
            generatedFrames
        }
    }

    private fun moveToNextKeyframeMicros() {
        currentKeyframeTimestampMicrosIndex = if (isReverse) {
            ((currentKeyframeTimestampMicrosIndex + keyFrameMicros.size) - 1) % keyFrameMicros.size
        } else {
            (currentKeyframeTimestampMicrosIndex + 1) % keyFrameMicros.size
        }
    }

}
