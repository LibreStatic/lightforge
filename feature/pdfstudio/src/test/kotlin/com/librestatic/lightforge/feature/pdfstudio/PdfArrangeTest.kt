package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

private fun Double.approx(other: Double, eps: Double = 1e-6) = abs(this - other) < eps

class PdfArrangeTest {
    private val a = PdfArrange.Bounds(0.0, 0.0, 10.0, 10.0)
    private val b = PdfArrange.Bounds(20.0, 5.0, 10.0, 20.0)
    private val c = PdfArrange.Bounds(40.0, 30.0, 5.0, 5.0)

    @Test
    fun unionCoversEveryBox() {
        val u = PdfArrange.union(listOf(a, b, c))
        assertTrue(u.x.approx(0.0))
        assertTrue(u.y.approx(0.0))
        assertTrue(u.right.approx(45.0))
        assertTrue(u.bottom.approx(35.0))
    }

    @Test
    fun groupBoundsMixesImagesAndTexts() {
        val group = PdfArrange.groupBounds(listOf(a), listOf(b))
        assertTrue(group.right.approx(30.0))
        assertTrue(group.bottom.approx(25.0))
    }

    @Test
    fun alignLeftMovesMemberLeftEdgeToGroupLeftEdge() {
        val group = PdfArrange.union(listOf(a, b))
        val (dx, dy) = PdfArrange.alignDelta(b, group, PdfGeometry.Align.Left)
        assertTrue((b.x + dx).approx(group.x))
        assertTrue(dy.approx(0.0))
    }

    @Test
    fun alignCenterCentersOnGroupMidline() {
        val group = PdfArrange.union(listOf(a, b))
        val (dx, _) = PdfArrange.alignDelta(a, group, PdfGeometry.Align.Center)
        assertTrue((a.centerX + dx).approx(group.centerX))
    }

    @Test
    fun alignRightMovesMemberRightEdgeToGroupRightEdge() {
        val group = PdfArrange.union(listOf(a, b))
        val (dx, _) = PdfArrange.alignDelta(a, group, PdfGeometry.Align.Right)
        assertTrue((a.right + dx).approx(group.right))
    }

    @Test
    fun alignTopMiddleBottomOnlyMoveY() {
        val group = PdfArrange.union(listOf(a, b))
        val (dxTop, dyTop) = PdfArrange.alignDelta(b, group, PdfGeometry.Align.Top)
        assertTrue(dxTop.approx(0.0))
        assertTrue((b.y + dyTop).approx(group.y))
        val (_, dyMid) = PdfArrange.alignDelta(a, group, PdfGeometry.Align.Middle)
        assertTrue((a.centerY + dyMid).approx(group.centerY))
        val (_, dyBottom) = PdfArrange.alignDelta(a, group, PdfGeometry.Align.Bottom)
        assertTrue((a.bottom + dyBottom).approx(group.bottom))
    }

    @Test
    fun distributeHorizontalNeedsAtLeastThree() {
        assertTrue(PdfArrange.distributeHorizontal(listOf("a" to a, "b" to b)).isEmpty())
    }

    @Test
    fun distributeHorizontalEqualizesGaps() {
        // a: [0,10], b: [20,30] (x=20,w=10), c: [40,45] (x=40,w=5) -> distributing keeps a and c
        // fixed and repositions b so both gaps are equal.
        val members = listOf("a" to a, "b" to b, "c" to c)
        val result = PdfArrange.distributeHorizontal(members)
        assertEquals(1, result.size)
        val newBx = result.getValue("b")
        // span = (40 + 5) - 0 = 45; total widths = 10+10+5=25; gaps=2 -> gap = 10
        // b's new x = a.right + gap = 10 + 10 = 20 (unchanged here, but assert via formula)
        assertTrue(newBx.approx(20.0))
    }

    @Test
    fun distributeVerticalEqualizesGaps() {
        val d1 = PdfArrange.Bounds(0.0, 0.0, 5.0, 10.0)
        val d2 = PdfArrange.Bounds(0.0, 50.0, 5.0, 10.0)
        val d3 = PdfArrange.Bounds(0.0, 100.0, 5.0, 5.0)
        val result = PdfArrange.distributeVertical(listOf("d1" to d1, "d2" to d2, "d3" to d3))
        assertEquals(1, result.size)
        // span = (100+5)-0=105; total heights=25; gap=(105-25)/2=40; new d2.y = d1.bottom+gap = 10+40=50
        assertTrue(result.getValue("d2").approx(50.0))
    }

    @Test
    fun distributeIsStableOnTies() {
        val t1 = PdfArrange.Bounds(0.0, 0.0, 5.0, 5.0)
        val tMidA = PdfArrange.Bounds(20.0, 0.0, 5.0, 5.0)
        val tMidB = PdfArrange.Bounds(20.0, 0.0, 5.0, 5.0) // exact tie with tMidA
        val t4 = PdfArrange.Bounds(40.0, 0.0, 5.0, 5.0)
        val result =
            PdfArrange.distributeHorizontal(
                listOf("t1" to t1, "midA" to tMidA, "midB" to tMidB, "t4" to t4)
            )
        // 4 members -> t1 and t4 fixed, midA/midB (tied) both repositioned; stable sort keeps
        // "midA" before "midB" so it lands at the earlier slot.
        assertEquals(2, result.size)
        assertTrue(result.getValue("midA") < result.getValue("midB"))
    }

    @Test
    fun distributeMixedImagesAndTextsSameAsPlainBounds() {
        val image = PdfImage(asset = "x".repeat(64), x = 0.0, y = 0.0, width = 10.0, height = 10.0)
        val text = PdfText(text = "hi", x = 20.0, y = 0.0, width = 10.0, height = 10.0)
        val other = PdfImage(asset = "x".repeat(64), x = 50.0, y = 0.0, width = 10.0, height = 10.0)
        val members =
            listOf(
                image.id to PdfArrange.boundsOf(image),
                text.id to PdfArrange.boundsOf(text),
                other.id to PdfArrange.boundsOf(other),
            )
        val result = PdfArrange.distributeHorizontal(members)
        assertEquals(1, result.size)
        assertTrue(result.containsKey(text.id))
    }

    @Test
    fun clampGroupMoveKeepsEveryMemberOnPage() {
        val members = listOf(a, PdfArrange.Bounds(90.0, 90.0, 10.0, 10.0))
        // Page is 100x100; second member is flush against the bottom-right corner already.
        val (dx, dy) = PdfArrange.clampGroupMove(20.0, 20.0, members, 100.0, 100.0)
        assertTrue(dx.approx(0.0))
        assertTrue(dy.approx(0.0))
    }

    @Test
    fun clampGroupMoveAllowsPartialMoveWithinRoom() {
        val members = listOf(PdfArrange.Bounds(0.0, 0.0, 10.0, 10.0))
        val (dx, dy) = PdfArrange.clampGroupMove(-100.0, 5.0, members, 100.0, 100.0)
        assertTrue(dx.approx(0.0)) // can't go left of 0
        assertTrue(dy.approx(5.0)) // plenty of room down
    }

    @Test
    fun clampGroupMoveAxesAreIndependent() {
        // One member has room only on x, another only on y.
        val wideRoomX = PdfArrange.Bounds(0.0, 90.0, 10.0, 10.0) // no room down, plenty right
        val wideRoomY = PdfArrange.Bounds(90.0, 0.0, 10.0, 10.0) // no room right, plenty down
        val (dx, dy) = PdfArrange.clampGroupMove(50.0, 50.0, listOf(wideRoomX, wideRoomY), 100.0, 100.0)
        assertTrue(dx.approx(0.0)) // wideRoomY blocks x
        assertTrue(dy.approx(0.0)) // wideRoomX blocks y
    }

    @Test
    fun emptyMemberListLeavesMoveUnclamped() {
        val (dx, dy) = PdfArrange.clampGroupMove(5.0, -5.0, emptyList(), 100.0, 100.0)
        assertTrue(dx.approx(5.0))
        assertTrue(dy.approx(-5.0))
    }
}
