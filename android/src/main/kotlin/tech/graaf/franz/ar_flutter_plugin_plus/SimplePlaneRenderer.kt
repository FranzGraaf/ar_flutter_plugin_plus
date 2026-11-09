package tech.graaf.franz.ar_flutter_plugin_plus

import android.opengl.GLES20
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class SimplePlaneRenderer {
    private var program = 0
    private var positionHandle = 0
    private var modelHandle = 0
    private var viewHandle = 0
    private var projHandle = 0
    private var colorHandle = 0

    fun initialize() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        modelHandle = GLES20.glGetUniformLocation(program, "u_Model")
        viewHandle = GLES20.glGetUniformLocation(program, "u_View")
        projHandle = GLES20.glGetUniformLocation(program, "u_Proj")
        colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
    }

    fun draw(planes: Iterable<Plane>, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.subsumedBy != null) continue

            val polygon = plane.polygon ?: continue
            if (polygon.remaining() < 6) continue

            val vertices = buildTriangleFanVertices(polygon)
            val vertexBuffer = createFloatBuffer(vertices)

            val modelMatrix = FloatArray(16)
            plane.centerPose.toMatrix(modelMatrix, 0)

            GLES20.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(viewHandle, 1, false, viewMatrix, 0)
            GLES20.glUniformMatrix4fv(projHandle, 1, false, projectionMatrix, 0)
            GLES20.glUniform4f(colorHandle, 0.2f, 0.7f, 1.0f, 0.35f)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertices.size / 3)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun buildTriangleFanVertices(polygon: FloatBuffer): FloatArray {
        val pointCount = polygon.remaining() / 2
        val vertices = FloatArray((pointCount + 1) * 3)
        // Center vertex at origin of plane local space
        vertices[0] = 0f
        vertices[1] = 0f
        vertices[2] = 0f
        var idx = 3
        while (polygon.hasRemaining()) {
            val x = polygon.get()
            val z = polygon.get()
            vertices[idx++] = x
            vertices[idx++] = 0f
            vertices[idx++] = z
        }
        polygon.rewind()
        return vertices
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(data.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.position(0)
        return fb
    }

    private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_Model;
            uniform mat4 u_View;
            uniform mat4 u_Proj;
            attribute vec3 a_Position;
            varying vec2 v_Local;
            void main() {
                v_Local = a_Position.xz;
                gl_Position = u_Proj * u_View * u_Model * vec4(a_Position, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            varying vec2 v_Local;
            // [gltf patch] grade de quadrados de 10 cm em vez de polígono liso.
            void main() {
                vec2 cell = abs(fract(v_Local / 0.10 + 0.5) - 0.5) * 0.10;
                float line = 1.0 - step(0.0022, min(cell.x, cell.y));
                float alpha = mix(u_Color.a * 0.18, 0.85, line);
                gl_FragColor = vec4(mix(u_Color.rgb, vec3(1.0), line * 0.6), alpha);
            }
        """
    }
}
