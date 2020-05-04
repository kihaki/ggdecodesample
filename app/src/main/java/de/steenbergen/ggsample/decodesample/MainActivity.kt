package de.steenbergen.ggsample.decodesample

import android.animation.TimeAnimator
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import de.steenbergen.ggsample.decodesample.video.player.VideoPlayerThread
import de.steenbergen.ggsample.decodesample.video.renderer.FragmentShaders
import de.steenbergen.ggsample.decodesample.video.renderer.GLByteBufferTexturesRenderer
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var backgroundDispatchedFrameCounter = 0L
        var effectDispatchedFrameCounter = 0L
        var alphaDispatchedFrameCounter = 0L

        val backgroundRenderer =
            VideoPlayerThread(
                assets.openFd("background.mp4"),
                "background",
                350, 350, 5
            ) { backgroundDispatchedFrameCounter += 1 }
        val effectRenderer =
            VideoPlayerThread(
                assets.openFd("foreground.mp4"),
                "effect",
                350, 350, 1
            ) { effectDispatchedFrameCounter += 1 }
        val alphaRenderer =
            VideoPlayerThread(
                assets.openFd("alpha.mp4"),
                "alpha",
                350, 350, 1
            ) { alphaDispatchedFrameCounter += 1 }
        backgroundRenderer.start()
        effectRenderer.start()
        alphaRenderer.start()

        var firstFrameReleased = true
        var frameReleased = false
        var releasedFrameCounter = 0

        val renderer = GLByteBufferTexturesRenderer(
            textureNames = listOf("background", "effect", "alpha"),
            fragmentShader = FragmentShaders.alphaBlendByteBuffer,
            { byteBufferReceiver, surfaceSet ->
                Log.i("RenderingProcess", "Attaching receiver")
                backgroundRenderer.bufferReceiver = byteBufferReceiver
                effectRenderer.bufferReceiver = byteBufferReceiver
                alphaRenderer.bufferReceiver = byteBufferReceiver

                firstFrameReleased = false
            },
            {
                firstFrameReleased = true
                frameReleased = true
                releasedFrameCounter += 1
                Log.i("RenderingProcess", "Released frame")
            }
        )

        preview.setRenderer(renderer)

        val vsync = TimeAnimator()
        vsync.setTimeListener { animation, totalTime, deltaTime ->
            synchronized(this) {
                backgroundRenderer.canReleaseFrame = !firstFrameReleased || frameReleased
                effectRenderer.canReleaseFrame = !firstFrameReleased || frameReleased
                alphaRenderer.canReleaseFrame = !firstFrameReleased || frameReleased
            }
            frameReleased = false
        }
        vsync.start()

        unlock_switch.setOnCheckedChangeListener { buttonView, isChecked ->
            synchronized(this) {
                backgroundRenderer.isDetachedFromVSync = isChecked
                effectRenderer.isDetachedFromVSync = isChecked
                alphaRenderer.isDetachedFromVSync = isChecked
            }
        }

        val frameRateUpdateAnimator = TimeAnimator()
        var previousUpdateMillis = 0L
        frameRateUpdateAnimator.setTimeListener { animation, totalTime, deltaTime ->
            val deltaMillis = totalTime - previousUpdateMillis
            if (deltaMillis >= 1000L) {
                framerate.text =
                    "FPS: " + (backgroundDispatchedFrameCounter.toFloat() / deltaMillis.toFloat()) * 1000
                previousUpdateMillis = totalTime
                releasedFrameCounter = 0
                backgroundDispatchedFrameCounter = 0
                effectDispatchedFrameCounter = 0
                alphaDispatchedFrameCounter = 0
            }
        }
        frameRateUpdateAnimator.start()
    }
}
