package io.github.whoxllm.orcamicro.core.mesh

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeshTest {
    @Test
    fun `binary STL survives a write and read`() {
        val cube = TestMeshes.box(10f, 20f, 30f)
        val bytes = ByteArrayOutputStream().also { StlWriter.write(cube, it) }.toByteArray()
        assertEquals(84 + 50 * 12, bytes.size)
        assertContentEquals(cube.positions, StlReader.read(bytes).positions)
    }

    @Test
    fun `ASCII STL is read`() {
        val text = """
            solid tri
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1.5 0 0
                  vertex 0 2e1 -3
                endloop
              endfacet
            endsolid tri
        """.trimIndent()
        val mesh = StlReader.read(text.toByteArray())
        assertEquals(1, mesh.triangleCount)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1.5f, 0f, 0f, 0f, 20f, -3f), mesh.positions)
    }

    @Test
    fun `binary STL whose header starts with solid is still read as binary`() {
        val cube = TestMeshes.box(1f, 1f, 1f)
        val bytes = ByteArrayOutputStream().also { StlWriter.write(cube, it) }.toByteArray()
        "solid but binary".toByteArray().copyInto(bytes)
        assertEquals(12, StlReader.read(bytes).triangleCount)
    }

    @Test
    fun `files that are not STL are rejected`() {
        assertFailsWith<StlFormatException> { StlReader.read("hello world, not a mesh".toByteArray()) }
        assertFailsWith<StlFormatException> { StlReader.read("solid empty\nendsolid empty".toByteArray()) }
    }

    @Test
    fun `bounds cover every vertex`() {
        val b = TestMeshes.box(10f, 20f, 30f, x = -5f, y = 2f, z = 1f).bounds()
        assertEquals(Vec3(-5f, 2f, 1f), b.min)
        assertEquals(Vec3(5f, 22f, 31f), b.max)
        assertEquals(Vec3(0f, 12f, 16f), b.center)
    }
}

class PlacementTest {
    private val box = TestMeshes.box(10f, 20f, 30f, x = 100f, y = 100f, z = 5f)

    @Test
    fun `place centres the footprint and drops onto the bed`() {
        val b = Placement(centerX = 110f, centerY = 90f).place(box).bounds()
        assertNear(Vec3(105f, 80f, 0f), b.min)
        assertNear(Vec3(115f, 100f, 30f), b.max)
    }

    @Test
    fun `rotating 90 degrees about X swaps depth and height`() {
        val size = Placement(rotationX = 90f).place(box).bounds().size
        assertNear(Vec3(10f, 30f, 20f), size)
    }

    @Test
    fun `rotations combine X then Y then Z`() {
        val size = Placement(rotationX = 90f, rotationZ = 90f).place(box).bounds().size
        assertNear(Vec3(30f, 10f, 20f), size)
    }

    @Test
    fun `scale is uniform`() {
        val size = Placement(scalePercent = 50f).place(box).bounds().size
        assertNear(Vec3(5f, 10f, 15f), size)
    }

    @Test
    fun `orient keeps the model centred on the origin`() {
        val b = Placement(rotationY = 45f).orient(box).bounds()
        assertNear(Vec3(0f, 0f, 0f), b.center)
    }

    private fun assertNear(expected: Vec3, actual: Vec3) {
        val ok = kotlin.math.abs(expected.x - actual.x) < 1e-3 &&
            kotlin.math.abs(expected.y - actual.y) < 1e-3 &&
            kotlin.math.abs(expected.z - actual.z) < 1e-3
        kotlin.test.assertTrue(ok, "expected $expected but was $actual")
    }
}

object TestMeshes {
    /** An axis-aligned box with its minimum corner at (x, y, z), wound outward. */
    fun box(w: Float, d: Float, h: Float, x: Float = 0f, y: Float = 0f, z: Float = 0f): Mesh {
        val v = Array(8) { i ->
            floatArrayOf(x + if (i and 1 != 0) w else 0f, y + if (i and 2 != 0) d else 0f, z + if (i and 4 != 0) h else 0f)
        }
        val quads = listOf(
            intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 6, 7, 3), intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5),
        )
        val out = ArrayList<Float>()
        for (q in quads) {
            for (t in listOf(intArrayOf(q[0], q[1], q[2]), intArrayOf(q[0], q[2], q[3]))) {
                for (i in t) out.addAll(v[i].toList())
            }
        }
        return Mesh(out.toFloatArray())
    }
}
