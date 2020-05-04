package de.steenbergen.ggsample.decodesample.video.extractor

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class AssetsVideoToSurfaceDecoderSetup(
    assetVideo: AssetFileDescriptor, // Input
    surface: Surface? // Output
) {
    private val TAG = "AssetsVideoDecoder"
    val videoExtractor: MediaExtractor = createExtractor(assetVideo)
    val inputFormat: MediaFormat
    val mediaCodec: MediaCodec

    init {
        val videoInputTrack = getFirstVideoTrackIndex(videoExtractor)
        when (videoInputTrack >= 0) {
            true -> videoExtractor.selectTrack(videoInputTrack)
            false -> throw RuntimeException("No video track found in input file")
        }
        inputFormat = videoExtractor.getTrackFormat(videoInputTrack)
        mediaCodec = createVideoDecoder(inputFormat, surface)
    }

    private fun createExtractor(video: AssetFileDescriptor): MediaExtractor {
        val extractor = MediaExtractor()
        extractor.setDataSource(video.fileDescriptor, video.startOffset, video.length)
        return extractor
    }

    private fun createVideoDecoder(inputFormat: MediaFormat, surface: Surface?): MediaCodec {
        val decoder = MediaCodec.createDecoderByType(inputFormat.mimeType)
        decoder.configure(inputFormat, surface, null, 0)
        decoder.start()
        return decoder
    }

    private fun getFirstVideoTrackIndex(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            Log.d(TAG, "Format for track $index is ${extractor.getTrackFormat(index).mimeType}")
            if (extractor.getTrackFormat(index).isVideoFormat) {
                return index
            }
        }
        return -1
    }

    private val MediaFormat.mimeType get() = getString(MediaFormat.KEY_MIME)
    private val MediaFormat.isVideoFormat get() = mimeType?.startsWith("video/") ?: false
}
