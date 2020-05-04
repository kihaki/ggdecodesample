import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.util.Log
import android.util.SparseLongArray
import de.steenbergen.ggsample.decodesample.video.extractor.AssetsVideoToSurfaceDecoderSetup
import de.steenbergen.ggsample.decodesample.video.framegrab.FrameGrabOutputSurface
import java.nio.ByteBuffer
import java.util.*

class FrameRangeExtractor(
    video: AssetFileDescriptor,
    videoHeight: Int,
    videoWidth: Int,
    val range: Int
) {
    private val TAG = "VideoDecoding:FrameRangeExtractor"
    private val TIMEOUT_USEC = 10_000L

    private val outputSurface = FrameGrabOutputSurface(videoWidth, videoHeight, range)
    private val decoderSetup = AssetsVideoToSurfaceDecoderSetup(video, outputSurface.surface)
    private val videoDecoder = decoderSetup.mediaCodec
    private val videoExtractor = decoderSetup.videoExtractor

    val keyFrameMicros = Stack<Long>().also { stack ->
        do {
            if (videoExtractor.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC)
                stack.push(videoExtractor.sampleTime)
        } while (videoExtractor.advance())
    }

    fun extractFrameRange(
        startMikros: Long
    ): List<ByteBuffer> {
        val decoderInputBuffers: Array<ByteBuffer> = videoDecoder.inputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = 0
        var decodeCount = 0
        var frameSaveTime: Long = 0

        var frames = listOf<ByteBuffer>()
        val presentationMicros = SparseLongArray()

        var outputDone = false
        var inputDone = false

        // Go to correct frame first
        videoExtractor.seekTo(startMikros, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        var presentationTimeUs = 0L

        while (!outputDone) {
            // Feed more data to the decoder.
            if (!inputDone) {
                val inputBufIndex: Int = videoDecoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufIndex >= 0) {
                    // Input buffer available
                    val inputBuf = decoderInputBuffers[inputBufIndex]
                    val chunkSize: Int = videoExtractor.readSampleData(inputBuf, 0)
                    if (chunkSize >= 0) {
                        presentationTimeUs = videoExtractor.sampleTime
                        videoDecoder.queueInputBuffer(
                            inputBufIndex,
                            0,
                            chunkSize,
                            presentationTimeUs,
                            0 /*flags*/
                        )
                        inputChunk++
                        videoExtractor.advance()
                    }
                }
            }
            if (!outputDone) {
                val outputBufferIndex: Int = videoDecoder.dequeueOutputBuffer(info, TIMEOUT_USEC)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // we don't care
                } else if (outputBufferIndex < 0) {
                    throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $outputBufferIndex")
                } else {
                    // decoderStatus >= 0
                    val doRender = info.size != 0

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    videoDecoder.releaseOutputBuffer(outputBufferIndex, doRender)
                    if (doRender) {
                        outputSurface.awaitNewImage()
                        outputSurface.drawImage(true)
                        if (decodeCount < range) {
                            val startWhen = System.nanoTime()
                            frames = outputSurface.storeFrame(decodeCount)
                            presentationMicros.put(decodeCount, presentationTimeUs)
                            frameSaveTime += System.nanoTime() - startWhen
                        } else {
                            outputDone = true
                            inputDone = true
                        }
                        decodeCount++
                    }
                }
            }
        }

        val numSaved = if (range < decodeCount) range else decodeCount
        Log.d(
            TAG,
            "Extracting the pixels for $numSaved frames took ${frameSaveTime / 1000_000}ms in total"
        )

        // Takes about 2ms
        videoDecoder.flush()

        return frames
    }
}
