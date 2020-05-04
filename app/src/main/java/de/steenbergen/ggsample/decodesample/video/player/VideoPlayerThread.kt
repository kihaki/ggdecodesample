package de.steenbergen.ggsample.decodesample.video.player

import android.content.res.AssetFileDescriptor
import de.steenbergen.ggsample.decodesample.video.renderer.OnByteBufferReleasedListener

class VideoPlayerThread(
    private val video: AssetFileDescriptor,
    private val textureName: String,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val keyFrameDistance: Int,
    private val onDispatchedFrame: () -> Unit
) : Thread() {

    var player: BoomerangVideoPlayer? = null

    var bufferReceiver: OnByteBufferReleasedListener? = null

    var isDetachedFromVSync = false
    var canReleaseFrame: Boolean = false

    override fun run() {
        player =
            BoomerangVideoPlayer(
                video,
                "BoomerangPlay:$textureName",
                videoWidth,
                videoHeight,
                keyFrameDistance
            )

        while (true) {
            if (isDetachedFromVSync || canReleaseFrame) {
                player?.next()?.let {
                    bufferReceiver?.onByteBufferReleased(
                        textureName,
                        0,
                        it
                    )
                    onDispatchedFrame()
                }
                canReleaseFrame = false
            }
        }
    }
}
