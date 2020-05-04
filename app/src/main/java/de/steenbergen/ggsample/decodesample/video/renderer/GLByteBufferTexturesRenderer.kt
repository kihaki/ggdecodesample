package de.steenbergen.ggsample.decodesample.video.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import de.steenbergen.ggsample.decodesample.widget.GLTextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLByteBufferTexturesRenderer(
    private val textureNames: List<String>,
    private val fragmentShader: String,
    private val onSurfacePreparedListener: (OnByteBufferReleasedListener, Map<String, Surface>) -> Unit,
    private val onCompleteFrameRendered: (renderer: GLByteBufferTexturesRenderer) -> Unit
) : GLTextureView.Renderer,
    SurfaceTexture.OnFrameAvailableListener,
    OnByteBufferReleasedListener {

    data class NamedSurfaceTexture(val name: String, val surfaceTexture: SurfaceTexture)

    companion object {
        private const val TAG = "GLByteBufferTexturesRenderer"
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        // GL_TEXTURE_EXTERNAL_OES textures may require up to 3 texture units
        private const val EXTERNAL_TEXTURE_UNIT_COUNT_REQUIREMENT = 3
    }

    private val triangleVerticesData = floatArrayOf(
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0f, 1f, 1f,
        1.0f, -1.0f, 0f, 0f, 1f,
        -1.0f, 1.0f, 0f, 1f, 0f,
        1.0f, 1.0f, 0f, 0f, 0f
    )

    private val triangleVertices: FloatBuffer

    private val mVPMatrix = FloatArray(16)
    private val sTMatrix = FloatArray(16)

    private var programHandle = 0
    private var aPositionHandle = 0
    private var aTextureHandle = 0
    private var uMVPMatrixHandle = 0
    private var uSTMatrixHandle = 0

    private val vertexShader = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    init {
        triangleVertices = ByteBuffer.allocateDirect(
                triangleVerticesData.size * FLOAT_SIZE_BYTES
            )
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)
        Matrix.setIdentityM(sTMatrix, 0)
    }

    private var textureIds: Map<String, Int> = mapOf()

    // Texture name -> texture
    private var surfaceTextures: List<NamedSurfaceTexture> = listOf()
    private var surfaces: Map<String, Surface> = mapOf()

    private var texturesToUpdate: MutableSet<String> = mutableSetOf()
    private val byteBuffers: MutableMap<String, ByteBuffer> = mutableMapOf()

    override fun onDrawFrame(gl: GL10?) {
        // Clear Frame
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programHandle)
        checkGlError("glUseProgram")

        synchronized(this) {
            Log.i(
                TAG,
                "Checking texture updates toUpdate: ${texturesToUpdate.size}, textures: ${surfaceTextures.size}"
            )
            if (texturesToUpdate.size == surfaceTextures.size) {
                // All textures released a frame
                surfaceTextures.forEachIndexed { index, (textureName, surfaceTexture) ->
                    val textureHandle = GLES20.glGetUniformLocation(
                        programHandle,
                        textureName
                    )
                    val textureUnitOffset = index * EXTERNAL_TEXTURE_UNIT_COUNT_REQUIREMENT
                    GLES20.glUniform1i(textureHandle, textureUnitOffset)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureUnitOffset)

                    GLES20.glBindTexture(
                        GLES20.GL_TEXTURE_2D,
                        textureIds[textureName] ?: error("Texture id for $textureName not found")
                    )
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_RGBA,
                        350,
                        350,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        byteBuffers[textureName]
                    )
//                    surfaceTexture.getTransformMatrix(sTMatrix)
//                    surfaceTexture.updateTexImage()
                }
                texturesToUpdate.clear()
                onCompleteFrameRendered(this)
            }
        }

        // Render Frame
        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError("glVertexAttribPointer maPosition")

        GLES20.glEnableVertexAttribArray(aPositionHandle)
        checkGlError("glEnableVertexAttribArray aPositionHandle")

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError("glVertexAttribPointer aTextureHandle")

        GLES20.glEnableVertexAttribArray(aTextureHandle)
        checkGlError("glEnableVertexAttribArray aTextureHandle")

        Matrix.setIdentityM(mVPMatrix, 0)
        GLES20.glUniformMatrix4fv(
            uMVPMatrixHandle,
            1,
            false,
            mVPMatrix,
            0
        )
        GLES20.glUniformMatrix4fv(
            uSTMatrixHandle,
            1,
            false,
            sTMatrix,
            0
        )
        GLES20.glDrawArrays(
            GLES20.GL_TRIANGLE_STRIP,
            0,
            4
        )
        checkGlError("glDrawArrays")

        GLES20.glFinish()
    }

    private fun checkGlError(operation: String) {
        GLES20.glGetError().let { glError ->
            if (glError != GLES20.GL_NO_ERROR) {
                throw RuntimeException("$operation: glError $glError")
            }
        }
    }

    private fun createProgram(fragmentShader: String, vertexShader: String): Int {
        val vertexShaderHandle = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        if (vertexShaderHandle == 0) return 0

        val pixelShaderHandle = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        if (pixelShaderHandle == 0) return 0

        val programHandle = GLES20.glCreateProgram()
        if (programHandle <= 0) return 0

        GLES20.glAttachShader(programHandle, vertexShaderHandle)
        checkGlError("glAttachShader")

        GLES20.glAttachShader(programHandle, pixelShaderHandle)
        checkGlError("glAttachShader")

        GLES20.glLinkProgram(programHandle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(
            programHandle,
            GLES20.GL_LINK_STATUS,
            status,
            0
        )
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(
                TAG,
                GLES20.glGetProgramInfoLog(programHandle)
            )
            GLES20.glDeleteProgram(programHandle)
            return 0
        }
        return programHandle
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        if (shader == 0) return 0

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            status,
            0
        )
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not compile shader $shaderType:")
            Log.e(
                TAG,
                GLES20.glGetShaderInfoLog(shader)
            )
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Init OpenGL program
        programHandle = createProgram(fragmentShader, vertexShader)
        if (programHandle <= 0) throw RuntimeException("Could not link OpenGL program")

        aPositionHandle =
            GLES20.glGetAttribLocation(programHandle, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (aPositionHandle == -1) throw RuntimeException("Could not get attrib location for aPosition")

        aTextureHandle = GLES20.glGetAttribLocation(
            programHandle,
            "aTextureCoord"
        )
        checkGlError("glGetAttribLocation aTextureCoord")
        if (aTextureHandle == -1) throw RuntimeException("Could not get attrib location for aTextureCoord")

        uMVPMatrixHandle = GLES20.glGetUniformLocation(
            programHandle,
            "uMVPMatrix"
        )
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (uMVPMatrixHandle == -1) throw RuntimeException("Could not get attrib location for uMVPMatrix")

        uSTMatrixHandle = GLES20.glGetUniformLocation(
            programHandle,
            "uSTMatrix"
        )
        checkGlError("glGetUniformLocation uSTMatrix")
        if (uSTMatrixHandle == -1) throw RuntimeException("Could not get attrib location for uSTMatrix")

        prepareSurfaces()
    }

    private fun prepareSurfaces() {
        // Creates the textures for the videos
        val textures = IntArray(textureNames.size)
        GLES20.glGenTextures(textureNames.size, textures, 0)

        textureIds = textureNames
            .mapIndexed { index, name -> name to textures[index] }
            .toMap()

        textureIds.values.forEach { textureId ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                textureId
            )
            checkGlError("glBindTexture $textureId")

            GLES20.glTexParameterf(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST.toFloat()
            )
            GLES20.glTexParameterf(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat()
            )
        }

        surfaceTextures = textureIds.map { (name, textureId) ->
            val surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setOnFrameAvailableListener(this)
            NamedSurfaceTexture(
                name,
                surfaceTexture
            )
        }

        surfaces = surfaceTextures.map { (name, surfaceTexture) ->
            name to Surface(surfaceTexture)
        }.toMap()

        onSurfacePreparedListener(this, surfaces)
    }

    @Synchronized
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        Log.i("LoopingVideoRenderer", "On Frame Available")
//        val updatedTextureName =
//            surfaceTextures.find { (_, texture) -> surfaceTexture == texture }!!.name
//        texturesToUpdate.add(updatedTextureName)
    }

    @Synchronized
    override fun onByteBufferReleased(
        name: String,
        frameTimestampMillis: Long,
        buffer: ByteBuffer
    ) {
        Log.i(
            TAG,
            "On Frame Released for $name at timestamp $frameTimestampMillis"
        )
        byteBuffers[name] = buffer
        texturesToUpdate.add(name)
    }
}

interface OnByteBufferReleasedListener {
    fun onByteBufferReleased(name: String, frameTimestampMillis: Long, buffer: ByteBuffer)
}
