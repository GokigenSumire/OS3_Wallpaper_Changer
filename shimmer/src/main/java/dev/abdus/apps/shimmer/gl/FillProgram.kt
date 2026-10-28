package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

/**
 * Draws the P3 blurred fill layer (REQ-EDIT-04).
 *
 * It is deliberately not the effect shader: the fill carries no duotone, grain or touch effect.
 * With a foreground clip it discards fragments under the foreground quad, so a crossfade blends
 * only "old composite" against "new composite" and never the new fill through the new image.
 */
class FillProgram {
    val program: Int
    val attribPosition: Int
    val attribTexCoords: Int
    private val uniformMvp: Int
    private val uniformTexture: Int
    private val uniformAlpha: Int
    private val uniformDim: Int
    private val uniformClipToQuad: Int
    private val uniformClipEnabled: Int

    init {
        val vertexShader = ShaderCompiler.compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = ShaderCompiler.linkProgram(vertexShader, fragmentShader)
        attribPosition = GLES30.glGetAttribLocation(program, "aPosition")
        attribTexCoords = GLES30.glGetAttribLocation(program, "aTexCoords")
        uniformMvp = GLES30.glGetUniformLocation(program, "uMVPMatrix")
        uniformTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uniformAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uniformDim = GLES30.glGetUniformLocation(program, "uDimAmount")
        uniformClipToQuad = GLES30.glGetUniformLocation(program, "uClipToQuad")
        uniformClipEnabled = GLES30.glGetUniformLocation(program, "uClipEnabled")
    }

    /**
     * @param clipToQuad column-major 3×3 map from clip space to the foreground quad's [-1, 1]²,
     * or null to draw the whole fill.
     */
    fun draw(texture: Int, mvp: FloatArray, clipToQuad: FloatArray?, dimAmount: Float, alpha: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uniformMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uniformAlpha, alpha)
        GLES30.glUniform1f(uniformDim, dimAmount)
        GLES30.glUniform1i(uniformClipEnabled, if (clipToQuad != null) 1 else 0)
        if (clipToQuad != null) GLES30.glUniformMatrix3fv(uniformClipToQuad, 1, false, clipToQuad, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniformTexture, 0)
        QuadMesh.draw(attribPosition, attribTexCoords)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }

    companion object {
        private val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uMVPMatrix;
            in vec4 aPosition;
            in vec2 aTexCoords;
            out vec2 vTexCoords;
            out vec2 vClip;
            void main() {
                vTexCoords = aTexCoords;
                gl_Position = uMVPMatrix * aPosition;
                vClip = gl_Position.xy / gl_Position.w;
            }
        """.trimIndent()

        // The discard edge sits just inside the foreground, so the foreground always overdraws
        // the seam instead of leaving a one-pixel gap of clear colour between the layers.
        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform float uDimAmount;
            uniform mat3 uClipToQuad;
            uniform int uClipEnabled;
            in vec2 vTexCoords;
            in vec2 vClip;
            out vec4 fragColor;
            void main() {
                if (uClipEnabled == 1) {
                    vec2 q = (uClipToQuad * vec3(vClip, 1.0)).xy;
                    if (max(abs(q.x), abs(q.y)) < 0.995) discard;
                }
                vec3 color = texture(uTexture, vTexCoords).rgb * (1.0 - uDimAmount);
                fragColor = vec4(clamp(color, 0.0, 1.0), uAlpha);
            }
        """.trimIndent()
    }
}
