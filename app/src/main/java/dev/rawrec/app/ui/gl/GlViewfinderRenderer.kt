package dev.rawrec.app.ui.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Surface
import dev.rawrec.app.util.AppLog
import dev.rawrec.tool.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * High-performance OpenGL ES 3.0 Viewfinder Renderer for Android Camera2 preview.
 *
 * Implements:
 * 1. Zero-latency GL_TEXTURE_EXTERNAL_OES hardware camera preview rendering.
 * 2. Real-time 3D .cube LUT trilinear sampling on the GPU via GL_TEXTURE_3D.
 * 3. Hardware-accelerated GLSL scopes: Focus Peaking (Laplacian edge filter),
 *    False Color (IRE luma ramp), and animated high-exposure Zebras.
 */
class GlViewfinderRenderer(
    private val onPreviewSurfaceAvailable: (Surface) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val tag = "GlViewfinderRenderer"

    private var glSurfaceView: GLSurfaceView? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    private var oesTextureId: Int = 0
    private var lutTextureId: Int = 0
    private var programId: Int = 0

    private val stMatrix = FloatArray(16)
    @Volatile private var frameAvailable = false

    private val vao = IntArray(1)
    private val vbo = IntArray(1)

    // Viewport dimensions
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1

    // Scope controls
    @Volatile var peakingActive: Boolean = false
    @Volatile var falseColorActive: Boolean = false
    @Volatile var zebrasActive: Boolean = false

    // 3D LUT State
    @Volatile private var pendingLut: CubeLut? = null
    @Volatile private var lutApplied: Boolean = false

    // Uniform locations
    private var uSTMatrixLoc = -1
    private var uTexelSizeLoc = -1
    private var uAnimTimeLoc = -1
    private var uUseLutLoc = -1
    private var uPeakingActiveLoc = -1
    private var uFalseColorActiveLoc = -1
    private var uZebrasActiveLoc = -1
    private var uOesTexLoc = -1
    private var uLut3DLoc = -1

    private val startTimeMs = SystemClock.uptimeMillis()

    fun attachView(view: GLSurfaceView) {
        glSurfaceView = view
    }

    fun setLut(lut: CubeLut?) {
        pendingLut = lut
        glSurfaceView?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        AppLog.i(tag, "onSurfaceCreated GLES30: ${GLES30.glGetString(GLES30.GL_VERSION)}")

        // Generate OES External Texture for Camera2
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Build SurfaceTexture and notify camera engine
        val st = SurfaceTexture(oesTextureId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        val surf = Surface(st)
        previewSurface = surf
        onPreviewSurfaceAvailable(surf)

        // Build Shader Program
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            AppLog.e(tag, "Failed building GLES 3.0 Viewfinder shader program")
            return
        }

        uSTMatrixLoc = GLES30.glGetUniformLocation(programId, "uSTMatrix")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
        uAnimTimeLoc = GLES30.glGetUniformLocation(programId, "uAnimTime")
        uUseLutLoc = GLES30.glGetUniformLocation(programId, "uUseLut")
        uPeakingActiveLoc = GLES30.glGetUniformLocation(programId, "uPeakingActive")
        uFalseColorActiveLoc = GLES30.glGetUniformLocation(programId, "uFalseColorActive")
        uZebrasActiveLoc = GLES30.glGetUniformLocation(programId, "uZebrasActive")
        uOesTexLoc = GLES30.glGetUniformLocation(programId, "uOesTex")
        uLut3DLoc = GLES30.glGetUniformLocation(programId, "uLut3D")

        // Setup Fullscreen Quad: pos.xy, tex.uv
        val quadData = floatArrayOf(
            // x,     y,     u,    v
            -1.0f, -1.0f,  0.0f, 0.0f,
             1.0f, -1.0f,  1.0f, 0.0f,
            -1.0f,  1.0f,  0.0f, 1.0f,
             1.0f,  1.0f,  1.0f, 1.0f
        )
        val quadBuffer = ByteBuffer.allocateDirect(quadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadData)
        quadBuffer.position(0)

        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadData.size * 4, quadBuffer, GLES30.GL_STATIC_DRAW)

        // Attribute 0: vec2 aPosition
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)

        // Attribute 1: vec2 aTexCoord
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        AppLog.i(tag, "onSurfaceChanged: ${width}x${height}")
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable = true
        glSurfaceView?.requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        synchronized(this) {
            if (frameAvailable) {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                frameAvailable = false
            }
        }

        // Upload or release 3D LUT if state changed
        checkPendingLut()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        // Bind OES Camera Texture to Unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(uOesTexLoc, 0)

        // Bind 3D LUT Texture to Unit 1
        if (lutApplied && lutTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(uLut3DLoc, 1)
            GLES30.glUniform1i(uUseLutLoc, 1)
        } else {
            GLES30.glUniform1i(uUseLutLoc, 0)
        }

        // Pass Uniforms
        GLES30.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
        GLES30.glUniform2f(uTexelSizeLoc, 1.0f / viewWidth, 1.0f / viewHeight)

        val elapsedSec = (SystemClock.uptimeMillis() - startTimeMs) / 1000.0f
        GLES30.glUniform1f(uAnimTimeLoc, elapsedSec)

        GLES30.glUniform1i(uPeakingActiveLoc, if (peakingActive) 1 else 0)
        GLES30.glUniform1i(uFalseColorActiveLoc, if (falseColorActive) 1 else 0)
        GLES30.glUniform1i(uZebrasActiveLoc, if (zebrasActive) 1 else 0)

        // Render Quad
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private fun checkPendingLut() {
        val lut = pendingLut
        if (lut != null) {
            uploadLut3D(lut)
            pendingLut = null
        } else if (lutApplied && pendingLut == null && lutTextureId != 0) {
            deleteLut3D()
        }
    }

    private fun uploadLut3D(lut: CubeLut) {
        if (!lut.is3D) return
        val size = lut.size
        val table = lut.domainMin // table is private in CubeLut, extract samples via trilinear
        val numFloats = size * size * size * 3
        val floatBuf = ByteBuffer.allocateDirect(numFloats * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val rgbOut = DoubleArray(3)
        for (b in 0 until size) {
            val nb = b.toDouble() / (size - 1)
            for (g in 0 until size) {
                val ng = g.toDouble() / (size - 1)
                for (r in 0 until size) {
                    val nr = r.toDouble() / (size - 1)
                    lut.sample(nr, ng, nb, rgbOut)
                    floatBuf.put(rgbOut[0].toFloat())
                    floatBuf.put(rgbOut[1].toFloat())
                    floatBuf.put(rgbOut[2].toFloat())
                }
            }
        }
        floatBuf.position(0)

        if (lutTextureId == 0) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            lutTextureId = tex[0]
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            size, size, size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, floatBuf
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        lutApplied = true
        AppLog.i(tag, "Uploaded 3D LUT: ${lut.title} (${size}x${size}x${size})")
    }

    private fun deleteLut3D() {
        if (lutTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = 0
        }
        lutApplied = false
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        previewSurface?.release()
        previewSurface = null
        deleteLut3D()
        if (oesTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (vao[0] != 0) {
            GLES30.glDeleteVertexArrays(1, vao, 0)
            vao[0] = 0
        }
        if (vbo[0] != 0) {
            GLES30.glDeleteBuffers(1, vbo, 0)
            vbo[0] = 0
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vert == 0 || frag == 0) return 0

        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vert)
        GLES30.glAttachShader(prog, frag)
        GLES30.glLinkProgram(prog)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            AppLog.e(tag, "Link error: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            AppLog.e(tag, "Shader compile error ($type): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

uniform mat4 uSTMatrix;
out vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition.x, aPosition.y, 0.0, 1.0);
    vTexCoord = (uSTMatrix * vec4(aTexCoord.x, aTexCoord.y, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

uniform samplerExternalOES uOesTex;
uniform sampler3D uLut3D;

uniform int uUseLut;
uniform int uPeakingActive;
uniform int uFalseColorActive;
uniform int uZebrasActive;
uniform float uAnimTime;
uniform vec2 uTexelSize;

void main() {
    vec4 base = texture(uOesTex, vTexCoord);
    vec3 rgb = base.rgb;

    // 1. Apply Live 3D LUT
    if (uUseLut == 1) {
        rgb = texture(uLut3D, clamp(rgb, 0.0, 1.0)).rgb;
    }

    // 2. Hardware False Color (Luma IRE Mapping)
    if (uFalseColorActive == 1) {
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        if (luma < 0.05) {
            rgb = vec3(0.5, 0.0, 0.5); // Purple (deep shadow underexposure)
        } else if (luma < 0.12) {
            rgb = vec3(0.0, 0.0, 0.8); // Blue (shadows)
        } else if (luma >= 0.38 && luma <= 0.44) {
            rgb = vec3(0.0, 0.85, 0.0); // Green (skin midtone / 40 IRE)
        } else if (luma >= 0.52 && luma <= 0.58) {
            rgb = vec3(0.92, 0.38, 0.60); // Pink (skin highlight / 55 IRE)
        } else if (luma >= 0.70 && luma <= 0.75) {
            rgb = vec3(0.95, 0.95, 0.0); // Yellow (75 IRE)
        } else if (luma >= 0.98) {
            rgb = vec3(1.0, 0.0, 0.0); // Red (100 IRE clipping)
        } else {
            rgb = vec3(luma * 0.8); // Neutral monochrome baseline
        }
    }

    // 3. Hardware Focus Peaking (3x3 Laplacian Edge Filter)
    if (uPeakingActive == 1) {
        float l0 = dot(texture(uOesTex, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float l1 = dot(texture(uOesTex, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float l2 = dot(texture(uOesTex, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
        float l3 = dot(texture(uOesTex, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
        float c  = dot(texture(uOesTex, vTexCoord).rgb, vec3(0.299, 0.587, 0.114));
        float edge = abs(l0 + l1 + l2 + l3 - 4.0 * c);
        if (edge > 0.08) {
            rgb = vec3(0.0, 0.9, 1.0); // Electric Cyan edge highlights
        }
    }

    // 4. Hardware Animated Zebras (>95 IRE Highlight Warning)
    if (uZebrasActive == 1) {
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        if (luma > 0.92) {
            float stripe = mod(gl_FragCoord.x + gl_FragCoord.y - uAnimTime * 24.0, 16.0);
            if (stripe < 5.0) {
                rgb = vec3(1.0, 0.1, 0.2); // Tally Crimson zebra stripes
            }
        }
    }

    fragColor = vec4(rgb, 1.0);
}
"""
    }
}
