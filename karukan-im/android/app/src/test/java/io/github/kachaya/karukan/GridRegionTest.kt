package io.github.kachaya.karukan

import org.junit.Assert.assertEquals
import org.junit.Test

class GridRegionTest {

    @Test
    fun testGridRegionMappingWith343Ratio() {
        // 100x100 square with 3:4:3 ratio:
        // 0..30f = 0 (Left/Top), 30f..70f = 1 (Center), 70f..100f = 2 (Right/Bottom)
        assertEquals(GraffitiStrokeRecognizer.GridRegion.TOP_LEFT, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(15f, 15f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.TOP_CENTER, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(50f, 15f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.TOP_RIGHT, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(85f, 15f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.CENTER_LEFT, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(15f, 50f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.CENTER, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(50f, 50f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.CENTER_RIGHT, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(90f, 50f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.BOTTOM_LEFT, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(15f, 85f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.BOTTOM_CENTER, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(50f, 85f)))
        assertEquals(GraffitiStrokeRecognizer.GridRegion.BOTTOM_RIGHT, GraffitiStrokeRecognizer.getGridRegion(GraffitiStrokeRecognizer.Point(85f, 85f)))
    }
}
