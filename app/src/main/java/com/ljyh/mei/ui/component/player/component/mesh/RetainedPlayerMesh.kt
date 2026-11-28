package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Geometry shared by HWUI display-list recordings at one pixel size. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class RetainedPlayerMesh private constructor(private val mesh: Mesh) {
    private var appliedWarpMix = -1f
    private var appliedPinchMix = -1f

    fun draw(canvas: Canvas, paint: Paint, warpMix: Float, pinchMix: Float) {
        val phase = if (pinchMix == 0f) 0f else warpMix
        if (appliedWarpMix != phase || appliedPinchMix != pinchMix) {
            mesh.setFloatUniform("crease", phase, pinchMix, meshOverscan(pinchMix), meshMidpointWeight(phase))
            appliedWarpMix = phase
            appliedPinchMix = pinchMix
        }
        // DrawVertices uses this blender when a paint shader is supplied.
        canvas.drawMesh(mesh, BlendMode.MODULATE, paint)
    }

    companion object {
        private const val TAG = "RetainedPlayerMesh"
        private const val VertexStride = 40
        private val specification by lazy {
            val float2 = MeshSpecification.TYPE_FLOAT2
            try {
                MeshSpecification.make(
                    arrayOf(
                        MeshSpecification.Attribute(float2, 0, "pos"),
                        MeshSpecification.Attribute(float2, 8, "tex"),
                        MeshSpecification.Attribute(float2, 16, "firstOffset"),
                        MeshSpecification.Attribute(float2, 24, "lastOffset"),
                        MeshSpecification.Attribute(float2, 32, "midpointCorrection"),
                    ),
                    VertexStride,
                    arrayOf(MeshSpecification.Varying(float2, "tex")),
                    """
                    uniform float4 crease;
                    uniform float2 viewportCenter;
                    Varyings main(const Attributes a) {
                        Varyings v;
                        float2 offset = mix(a.firstOffset, a.lastOffset, crease.x) + a.midpointCorrection * crease.w;
                        v.position = a.pos + (a.pos - viewportCenter) * crease.z + offset * crease.y;
                        v.tex = a.tex;
                        return v;
                    }
                    """.trimIndent(),
                    "float2 main(const Varyings v) { return v.tex; }",
                )
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "HWUI rejected the player mesh specification", error)
                null
            }
        }

        fun prepare(viewport: PlayerMeshViewport): RetainedPlayerMesh? {
            val spec = specification ?: return null
            val positions = viewport.flatPositions
            val texture = viewport.textureCoordinates
            val indices = viewport.indices
            val vertexCount = positions.size / 2
            val vertices = ByteBuffer.allocateDirect(vertexCount * VertexStride).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in positions.indices step 2) {
                vertices.put(positions[i]).put(positions[i + 1])
                vertices.put(texture[i]).put(texture[i + 1])
                vertices.put(viewport.firstOffset[i]).put(viewport.firstOffset[i + 1])
                vertices.put(viewport.lastOffset[i]).put(viewport.lastOffset[i + 1])
                vertices.put(viewport.midpointCorrection[i]).put(viewport.midpointCorrection[i + 1])
            }
            vertices.flip()
            indexBuffer.put(indices).flip()
            val width = viewport.size.width.toFloat()
            val height = viewport.size.height.toFloat()
            val horizontalPad = width * MeshRestOverscan * .5f
            val verticalPad = height * MeshRestOverscan * .5f
            // At rest the grid extends past the viewport; both fold endpoints fit inside it.
            val bounds = RectF(-horizontalPad, -verticalPad, width + horizontalPad, height + verticalPad)
            return try {
                val mesh = Mesh(spec, Mesh.TRIANGLES, vertices, vertexCount, indexBuffer, bounds)
                mesh.setFloatUniform("viewportCenter", width * .5f, height * .5f)
                RetainedPlayerMesh(mesh)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "HWUI rejected a player mesh", error)
                null
            }
        }
    }
}
