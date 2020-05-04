package de.steenbergen.ggsample.decodesample.video.framegrab

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder


class FrameGrabOutputSurface(
    private val width: Int,
    private val height: Int,
    historySize: Int // This many past frames will be remembered
) : OnFrameAvailableListener {
    private val TAG = "CodecOutputSurface"

    private var mTextureRender: STextureRender? = null
    private var mSurfaceTexture: SurfaceTexture? = null


    var surface: Surface? = null
        private set
    private var eGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eGLContext = EGL14.EGL_NO_CONTEXT
    private var eGLSurface = EGL14.EGL_NO_SURFACE
    private val frameSyncObject = Object() // guards frameAvailable
    private var frameAvailable = false

    private val buffers = List<ByteBuffer>(historySize) {
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    }

    init {
        eglSetup()
        makeCurrent()
        setup()
    }

    private fun setup() {
        mTextureRender =
            STextureRender()
        mTextureRender!!.surfaceCreated()
        Log.d(
            TAG,
            "textureID=" + mTextureRender!!.textureId
        )
        mSurfaceTexture = SurfaceTexture(mTextureRender!!.textureId)

        mSurfaceTexture!!.setOnFrameAvailableListener(this)
        surface = Surface(mSurfaceTexture)
    }

    private fun eglSetup() {
        eGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eGLDisplay, version, 0, version, 1)) {
            eGLDisplay = null
            throw RuntimeException("unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs =
            arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eGLDisplay, attribList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eGLContext = EGL14.eglCreateContext(
            eGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            attrib_list, 0
        )
        checkEglError("eglCreateContext")
        if (eGLContext == null) {
            throw RuntimeException("null context")
        }

        // Create a pbuffer surface.
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eGLSurface = EGL14.eglCreatePbufferSurface(eGLDisplay, configs[0], surfaceAttribs, 0)
        checkEglError("eglCreatePbufferSurface")
        if (eGLSurface == null) {
            throw RuntimeException("surface was null")
        }
    }

    fun release() {
        if (eGLDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eGLDisplay, eGLSurface)
            EGL14.eglDestroyContext(eGLDisplay, eGLContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eGLDisplay)
        }
        eGLDisplay = EGL14.EGL_NO_DISPLAY
        eGLContext = EGL14.EGL_NO_CONTEXT
        eGLSurface = EGL14.EGL_NO_SURFACE
        surface!!.release()

        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //mSurfaceTexture.release();
        mTextureRender = null
        surface = null
        mSurfaceTexture = null
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eGLDisplay, eGLSurface, eGLSurface, eGLContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun awaitNewImage() {
        val TIMEOUT_MS = 2500L
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(TIMEOUT_MS)
                    if (!frameAvailable) {
                        throw RuntimeException("frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    // shouldn't happen
                    throw RuntimeException(ie)
                }
            }
            frameAvailable = false
        }

        mTextureRender!!.checkGlError("before updateTexImage")
        mSurfaceTexture!!.updateTexImage()
    }

    fun drawImage(invert: Boolean) {
        mTextureRender!!.drawFrame(mSurfaceTexture!!, invert)
    }

    // SurfaceTexture callback
    override fun onFrameAvailable(st: SurfaceTexture) {
        Log.d(
            TAG,
            "new frame available"
        )
        synchronized(frameSyncObject) {
            if (frameAvailable) {
                throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    fun storeFrame(index: Int): List<ByteBuffer> {
        val buffer = buffers[index]
        buffer.rewind()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(
            0,
            0,
            width,
            height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )
        return buffers
    }

    private fun checkEglError(msg: String) {
        var error: Int
        if (EGL14.eglGetError().also { error = it } != EGL14.EGL_SUCCESS) {
            throw RuntimeException(
                "$msg: EGL error: 0x" + Integer.toHexString(
                    error
                )
            )
        }
    }
}
