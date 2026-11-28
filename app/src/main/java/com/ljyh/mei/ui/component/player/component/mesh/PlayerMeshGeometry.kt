package com.ljyh.mei.ui.component.player.component.mesh

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val MeshRestOverscan = .25f

internal fun meshOverscan(pinchMix: Float): Float {
    val resting = 1f - pinchMix
    return MeshRestOverscan * resting * resting
}

internal fun meshMidpointWeight(warpMix: Float): Float {
    val weight = 4f * warpMix * (1f - warpMix)
    return weight * weight * weight
}

internal const val CompactMeshVariantCount = 4
internal const val WideMeshVariantCount = 5
private val CompactMeshDraws = intArrayOf(0, 1, 2, 2, 3)
internal val CompactMeshDrawCount: Int get() = CompactMeshDraws.size

internal fun compactMeshVariantForDraw(slot: Int): Int = CompactMeshDraws[slot]

internal class PlayerMeshGeometry(
    subdivision: Int,
    val compactVariant: Int = 0,
    val wideVariant: Int = 0,
) {
    val cells = subdivision.coerceIn(4, 50)
    val indices = ShortArray(cells * cells * 6).also { indices ->
        var index = 0
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                val topLeft = row * (cells + 1) + column
                val bottomLeft = topLeft + cells + 1
                indices[index++] = topLeft.toShort()
                indices[index++] = (topLeft + 1).toShort()
                indices[index++] = bottomLeft.toShort()
                indices[index++] = (topLeft + 1).toShort()
                indices[index++] = (bottomLeft + 1).toShort()
                indices[index++] = bottomLeft.toShort()
            }
        }
    }
}

/** Authored deformation fields over a grid that continues to cover the viewport. */
internal class PlayerMeshViewport(geometry: PlayerMeshGeometry, val size: IntSize) {
    val indices = geometry.indices
    private val side = geometry.cells + 1
    val flatPositions = FloatArray(side * side * 2)
    val textureCoordinates = FloatArray(flatPositions.size)
    val firstOffset = FloatArray(flatPositions.size)
    val lastOffset = FloatArray(flatPositions.size)
    val midpointCorrection = FloatArray(flatPositions.size)
    private val restingPositions by lazy(LazyThreadSafetyMode.NONE) {
        FloatArray(flatPositions.size).also { writePositions(it, 0f, 0f) }
    }
    private val interpolated by lazy(LazyThreadSafetyMode.NONE) { FloatArray(flatPositions.size) }
    private var lastWarpMix = -1f
    private var lastPinchMix = -1f

    init {
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val wide = width > height
        val textureSide = maxOf(width, height)
        val textureWidth = width / textureSide
        val textureHeight = height / textureSide
        val (first, last) = if (wide) {
            WideStart to WideEnds[geometry.wideVariant]
        } else {
            CompactFolds[geometry.compactVariant]
        }
        val midpointRegions = if (wide) {
            WideMidpointRegions[geometry.wideVariant]
        } else {
            CompactMidpointRegions[geometry.compactVariant]
        }

        for (row in 0..geometry.cells) {
            val y = row.toFloat() / geometry.cells
            for (column in 0..geometry.cells) {
                val x = column.toFloat() / geometry.cells
                val index = 2 * (row * side + column)
                val firstPoint = first.map(x, y)
                val lastPoint = last.map(x, y)
                flatPositions[index] = x * width
                flatPositions[index + 1] = y * height
                textureCoordinates[index] = .5f + (x - .5f) * textureWidth
                textureCoordinates[index + 1] = .5f + (y - .5f) * textureHeight
                firstOffset[index] = (firstPoint.x - x) * width
                firstOffset[index + 1] = (firstPoint.y - y) * height
                lastOffset[index] = (lastPoint.x - x) * width
                lastOffset[index + 1] = (lastPoint.y - y) * height
                if (midpointRegions != null) {
                    // Local compression at mid-travel keeps the broad artwork drift linear.
                    val middle = (firstPoint + lastPoint) * .5f
                    var corrected = middle
                    for (region in midpointRegions) corrected = region.map(corrected)
                    midpointCorrection[index] = (corrected.x - middle.x) * width
                    midpointCorrection[index + 1] = (corrected.y - middle.y) * height
                }
            }
        }
    }

    fun positions(warpMix: Float, pinchMix: Float): FloatArray {
        if (pinchMix == 0f) return restingPositions
        val output = interpolated
        if (lastWarpMix != warpMix || lastPinchMix != pinchMix) {
            writePositions(output, warpMix, pinchMix)
            lastWarpMix = warpMix
            lastPinchMix = pinchMix
        }
        return output
    }

    private fun writePositions(output: FloatArray, warpMix: Float, pinchMix: Float) {
        val overscan = meshOverscan(pinchMix)
        val middleWeight = meshMidpointWeight(warpMix)
        val centerX = size.width * .5f
        val centerY = size.height * .5f
        for (i in output.indices step 2) {
            val dx = firstOffset[i] + (lastOffset[i] - firstOffset[i]) * warpMix + midpointCorrection[i] * middleWeight
            val dy = firstOffset[i + 1] + (lastOffset[i + 1] - firstOffset[i + 1]) * warpMix +
                midpointCorrection[i + 1] * middleWeight
            val x = flatPositions[i]
            val y = flatPositions[i + 1]
            output[i] = x + (x - centerX) * overscan + dx * pinchMix
            output[i + 1] = y + (y - centerY) * overscan + dy * pinchMix
        }
    }

    private class PinchRegion(
        private val center: Offset,
        angleDegrees: Float,
        private val normalRadius: Float,
        private val tangentRadius: Float,
        private val strength: Float,
    ) {
        private val angle = angleDegrees * (PI.toFloat() / 180f)
        private val nx = cos(angle)
        private val ny = sin(angle)

        fun map(point: Offset): Offset {
            val dx = point.x - center.x
            val dy = point.y - center.y
            val normal = dx * nx + dy * ny
            val tangent = -dx * ny + dy * nx
            val s = normal / normalRadius
            val t = tangent / tangentRadius
            val weight = (1f - s * s - t * t).coerceAtLeast(0f)
            // A region pinches across its own axis and fades to zero slope at its edge.
            val pull = strength * normal * weight * weight
            return Offset(point.x - nx * pull, point.y - ny * pull)
        }
    }

    private class Deformation(
        private val driftX: Float,
        private val driftY: Float,
        vararg regions: PinchRegion,
    ) {
        private val regions = regions

        // Redistribute both axes while keeping their ends fixed, then add local compression.
        fun map(x: Float, y: Float): Offset {
            var point = Offset(x / (x + driftX * (1f - x)), y / (y + driftY * (1f - y)))
            for (region in regions) point = region.map(point)
            return point
        }
    }

    private companion object {
        val CompactMidpointRegions = mapOf(
            2 to listOf(PinchRegion(Offset(.746f, .72f), 18f, .145f, .17f, .58f)),
        )
        val WideMidpointRegions = mapOf(
            0 to listOf(
                PinchRegion(Offset(.31f, .43f), 37f, .14f, .19f, .68f),
                PinchRegion(Offset(.64f, .52f), -43f, .15f, .20f, .74f),
                PinchRegion(Offset(.75f, .72f), 57f, .11f, .16f, .70f),
            ),
            1 to listOf(PinchRegion(Offset(.53f, .53f), 78f, .16f, .28f, .74f)),
            4 to listOf(
                PinchRegion(Offset(.60f, .46f), 84f, .16f, .24f, .69f),
                PinchRegion(Offset(.41f, .69f), 59f, .15f, .20f, .70f),
            ),
        )

        val CompactFolds = listOf(
            // An upper-left fold with a second region that develops lower down.
            Deformation(
                driftX = 2.42f, driftY = .52f,
                PinchRegion(Offset(.27f, .30f), 19f, .20f, .26f, .88f),
                PinchRegion(Offset(.55f, .64f), -64f, .19f, .23f, .72f),
            ) to Deformation(
                driftX = 1.40f, driftY = .45f,
                PinchRegion(Offset(.29f, .34f), 24f, .18f, .24f, .88f),
                PinchRegion(Offset(.47f, .67f), -53f, .21f, .26f, .88f),
            ),
            // Three diagonal regions spread across the center.
            Deformation(
                driftX = 1.72f, driftY = 1.70f,
                PinchRegion(Offset(.32f, .27f), -29f, .19f, .23f, .86f),
                PinchRegion(Offset(.63f, .43f), 51f, .19f, .22f, .85f),
                PinchRegion(Offset(.42f, .62f), -56f, .19f, .25f, .85f),
            ) to Deformation(
                driftX = 2.68f, driftY = 2.53f,
                PinchRegion(Offset(.36f, .31f), -42f, .18f, .22f, .86f),
                PinchRegion(Offset(.69f, .49f), 61f, .19f, .21f, .86f),
                PinchRegion(Offset(.39f, .57f), -70f, .17f, .23f, .84f),
            ),
            // The central crease straightens while a short lower-right crease persists.
            Deformation(
                driftX = 3.85f, driftY = .42f,
                PinchRegion(Offset(.30f, .51f), -34f, .17f, .26f, .85f),
                PinchRegion(Offset(.59f, .41f), 56f, .18f, .22f, .85f),
                PinchRegion(Offset(.71f, .72f), 13f, .16f, .20f, .87f),
            ) to Deformation(
                driftX = 2.49f, driftY = .39f,
                PinchRegion(Offset(.39f, .53f), -9f, .19f, .29f, .93f),
                PinchRegion(Offset(.68f, .43f), 48f, .18f, .21f, .90f),
                PinchRegion(Offset(.78f, .725f), 7f, .17f, .20f, .95f),
            ),
            // A broad lower fold gives way to separate upper and lower regions.
            Deformation(
                driftX = 4.40f, driftY = .43f,
                PinchRegion(Offset(.39f, .64f), 57f, .20f, .29f, .89f),
                PinchRegion(Offset(.69f, .51f), -49f, .17f, .23f, .83f),
            ) to Deformation(
                driftX = 2.28f, driftY = .24f,
                PinchRegion(Offset(.27f, .39f), -42f, .17f, .22f, .84f),
                PinchRegion(Offset(.53f, .76f), 68f, .17f, .20f, .86f),
                PinchRegion(Offset(.71f, .68f), 13f, .18f, .23f, .88f),
            ),
        )

        // The wide paths share a shallow starting field and diverge toward distinct shapes.
        val WideStart = Deformation(
            driftX = 1.38f, driftY = 1.34f,
            PinchRegion(Offset(.32f, .34f), 60f, .19f, .23f, .52f),
            PinchRegion(Offset(.63f, .61f), -36f, .19f, .23f, .53f),
        )
        val WideEnds = listOf(
            // Opposing diagonals, with the largest travel along X.
            Deformation(
                driftX = .50f, driftY = 1.21f,
                PinchRegion(Offset(.32f, .37f), 45f, .17f, .22f, .85f),
                PinchRegion(Offset(.61f, .47f), -41f, .17f, .22f, .87f),
                PinchRegion(Offset(.75f, .72f), 61f, .15f, .20f, .77f),
            ),
            // A broad center fold plus a smaller left fold, traveling primarily along Y.
            Deformation(
                driftX = 1.22f, driftY = .45f,
                PinchRegion(Offset(.56f, .53f), 81f, .18f, .32f, .88f),
                PinchRegion(Offset(.27f, .39f), -45f, .17f, .19f, .82f),
            ),
            // The separate start regions converge into a shallower central band.
            Deformation(
                driftX = .65f, driftY = 1.01f,
                PinchRegion(Offset(.47f, .53f), 76f, .17f, .31f, .86f),
                PinchRegion(Offset(.68f, .61f), 43f, .16f, .20f, .75f),
            ),
            // A central rising diagonal with support above and below it.
            Deformation(
                driftX = .70f, driftY = .68f,
                PinchRegion(Offset(.40f, .49f), 49f, .19f, .29f, .87f),
                PinchRegion(Offset(.70f, .35f), 31f, .17f, .21f, .83f),
                PinchRegion(Offset(.51f, .68f), 84f, .16f, .23f, .81f),
            ),
            // A wide horizontal fold with a small upper-right diagonal.
            Deformation(
                driftX = .95f, driftY = .52f,
                PinchRegion(Offset(.55f, .49f), 88f, .17f, .30f, .86f),
                PinchRegion(Offset(.73f, .28f), -53f, .15f, .18f, .83f),
                PinchRegion(Offset(.34f, .67f), 73f, .15f, .22f, .78f),
            ),
        )
    }
}
