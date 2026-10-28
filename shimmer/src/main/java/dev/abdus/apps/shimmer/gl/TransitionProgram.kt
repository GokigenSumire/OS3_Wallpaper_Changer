package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

/**
 * P5 transition passes: an offscreen composite drawn back with opacity and scale about the screen
 * centre, and a solid black layer. Written for this project; no third-party shader is copied.
 */
class TransitionProgram {
    private val program: Int
    private val attribPosition: Int
    private val attribTexCoords: Int
    private val uniformScale: Int
    private val uniformTexture: Int
    private val uniformAlpha: Int
    private val uniformSolid: Int

    init {
        val vertexShader = ShaderCompiler.compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = ShaderCompiler.linkProgram(vertexShader, fragmentShader)
        attribPosition = GLES30.glGetAttribLocation(program, "aPosition")
        attribTexCoords = GLES30.glGetAttribLocation(program, "aTexCoords")
        uniformScale = GLES30.glGetUniformLocation(program, "uScale")
        uniformTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uniformAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uniformSolid = GLES30.glGetUniformLocation(program, "uSolid")
    }

    /** Draws [texture] (a surface-sized target) over the whole screen, scaled about the centre. */
    fun drawTexture(texture: Int, alpha: Float, scale: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uniformScale, scale)
        GLES30.glUniform1f(uniformAlpha, alpha)
        GLES30.glUniform1i(uniformSolid, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniformTexture, 0)
        QuadMesh.draw(attribPosition, attribTexCoords)
    }

    /** Draws solid black over the whole screen with [alpha]. */
    fun drawBlack(alpha: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uniformScale, 1f)
        GLES30.glUniform1f(uniformAlpha, alpha)
        GLES30.glUniform1i(uniformSolid, 1)
        QuadMesh.draw(attribPosition, attribTexCoords)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }

    companion object {
        // A render target's first row is the bottom of the screen, so v follows clip-space y.
        private val VERTEX_SHADER = """
            #version 300 es
            uniform float uScale;
            in vec4 aPosition;
            in vec2 aTexCoords;
            out vec2 vTexCoords;
            void main() {
                vTexCoords = vec2(aTexCoords.x, 1.0 - aTexCoords.y);
                gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform int uSolid;
            in vec2 vTexCoords;
            out vec4 fragColor;
            void main() {
                if (uSolid == 1) {
                    fragColor = vec4(0.0, 0.0, 0.0, uAlpha);
                } else {
                    fragColor = vec4(texture(uTexture, vTexCoords).rgb, uAlpha);
                }
            }
        """.trimIndent()
    }
}
