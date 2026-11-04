package cc.novelia.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击分区映射的行为基线。
 *
 * 默认方案沿用 FBReaderJ 的分区思路：横向与纵向翻页同时生效。
 * 这些断言用于防止移植过程中坐标或动作被改错。
 */
class ReaderTapZoneMapTest {
    private val width = 900
    private val height = 1800

    private fun centerOf(map: ReaderTapZoneMap, column: Int, row: Int): Pair<Int, Int> {
        val cellWidth = width / map.columns
        val cellHeight = height / map.rows
        return (column * cellWidth + cellWidth / 2) to (row * cellHeight + cellHeight / 2)
    }

    private fun actionAt(map: ReaderTapZoneMap, column: Int, row: Int, kind: ReaderTapKind = ReaderTapKind.SingleTap): ReaderTapAction {
        val (x, y) = centerOf(map, column, row)
        return map.getActionByCoordinates(x, y, width, height, kind)
    }

    @Test fun defaultTurnsHorizontallyOnSideColumns() {
        val map = ReaderTapZoneMap.Default
        assertEquals(ReaderTapAction.PreviousPage, actionAt(map, 0, 1))
        assertEquals(ReaderTapAction.NextPage, actionAt(map, 2, 1))
    }

    @Test fun defaultTurnsVerticallyOnMiddleColumn() {
        val map = ReaderTapZoneMap.Default
        assertEquals(ReaderTapAction.PreviousPage, actionAt(map, 1, 0))
        assertEquals(ReaderTapAction.NextPage, actionAt(map, 1, 2))
        assertEquals(ReaderTapAction.Menu, actionAt(map, 1, 1))
    }

    @Test fun defaultCoversBothDirectionsInBothAxes() {
        val map = ReaderTapZoneMap.Default
        assertEquals(ReaderTapAction.PreviousPage, actionAt(map, 0, 0))
        assertEquals(ReaderTapAction.NextPage, actionAt(map, 2, 2))
        assertEquals(ReaderTapAction.PreviousPage, actionAt(map, 1, 0))
        assertEquals(ReaderTapAction.NextPage, actionAt(map, 1, 2))
    }

    @Test fun defaultHasThreeByThreeGrid() {
        val map = ReaderTapZoneMap.Default
        assertEquals(3, map.columns)
        assertEquals(3, map.rows)
        assertEquals(9, map.cellCount)
    }

    @Test fun withActionChangesOnlyTheTargetCell() {
        val map = ReaderTapZoneMap.Default
        val edited = map.withAction(1, 1, double = false, ReaderTapAction.NextPage)
        assertEquals(ReaderTapAction.NextPage, edited.singleAt(1, 1))
        assertEquals(ReaderTapAction.PreviousPage, edited.singleAt(0, 1))
        assertEquals(ReaderTapAction.NextPage, edited.singleAt(2, 1))
        assertEquals(ReaderTapAction.Menu, map.singleAt(1, 1))
    }

    @Test fun bothSidesCanBeConfiguredToTurnForward() {
        val map = ReaderTapZoneMap.Default
            .withAction(0, 1, double = false, ReaderTapAction.NextPage)
            .withAction(0, 0, double = false, ReaderTapAction.NextPage)
            .withAction(0, 2, double = false, ReaderTapAction.NextPage)
        assertEquals(ReaderTapAction.NextPage, actionAt(map, 0, 1))
        assertEquals(ReaderTapAction.NextPage, actionAt(map, 2, 1))
    }

    @Test fun writingNoneMarksTheCellAsExplicitlyDisabled() {
        val edited = ReaderTapZoneMap.Default.withAction(0, 1, double = false, ReaderTapAction.None)
        assertEquals(ReaderTapAction.None, edited.singleAt(0, 1))
        assertEquals(ReaderTapAction.None, edited.singleActions[1 * 3 + 0])
    }

    @Test fun doubleTapActionsAreSeparateFromSingleTap() {
        val map = ReaderTapZoneMap.Default.withAction(0, 0, true, ReaderTapAction.Navigate)
        assertEquals(ReaderTapAction.PreviousPage, actionAt(map, 0, 0, ReaderTapKind.SingleNotDoubleTap))
        assertEquals(ReaderTapAction.Navigate, actionAt(map, 0, 0, ReaderTapKind.DoubleTap))
    }

    @Test fun singleTapNeverFallsBackToDoubleTapWhenOnlyDoubleIsConfigured() {
        val map = ReaderTapZoneMap.Default.withAction(1, 1, double = false, ReaderTapAction.None)
            .withAction(1, 1, double = true, ReaderTapAction.Menu)
        assertEquals(ReaderTapAction.None, actionAt(map, 1, 1, ReaderTapKind.SingleNotDoubleTap))
        assertEquals(ReaderTapAction.Menu, actionAt(map, 1, 1, ReaderTapKind.DoubleTap))
        assertEquals(ReaderTapAction.None, actionAt(map, 1, 1, ReaderTapKind.SingleTap))
    }

    @Test fun coordinatesAreClampedToTheLastCell() {
        val map = ReaderTapZoneMap.Default
        assertEquals(ReaderTapAction.NextPage, map.getActionByCoordinates(width - 1, height - 1, width, height, ReaderTapKind.SingleTap))
        assertEquals(ReaderTapAction.PreviousPage, map.getActionByCoordinates(0, 0, width, height, ReaderTapKind.SingleTap))
    }

    @Test fun negativeOrOutOfRangeCoordinatesAreClampedNotDropped() {
        val map = ReaderTapZoneMap.Default
        assertEquals(ReaderTapAction.PreviousPage, map.getActionByCoordinates(-50, -50, width, height, ReaderTapKind.SingleTap))
        assertEquals(ReaderTapAction.NextPage, map.getActionByCoordinates(width + 500, height + 500, width, height, ReaderTapKind.SingleTap))
    }

    @Test fun zeroSizedViewportYieldsNoAction() {
        val map = ReaderTapZoneMap.Default
        assertEquals(ReaderTapAction.None, map.getActionByCoordinates(0, 0, 0, 0, ReaderTapKind.SingleTap))
        assertEquals(ReaderTapAction.None, map.getActionByCoordinates(0, 0, 0, height, ReaderTapKind.SingleTap))
        assertEquals(ReaderTapAction.None, map.getActionByCoordinates(0, 0, width, 0, ReaderTapKind.SingleTap))
    }

    @Test fun encodeDecodeRoundTripsDefaultMap() {
        val map = ReaderTapZoneMap.Default
        assertEquals(map, ReaderTapZoneMap.decode(ReaderTapZoneMap.encode(map)))
    }

    @Test fun encodeDecodeRoundTripsCustomMaps() {
        val custom = ReaderTapZoneMap.Default
            .withAction(0, 0, double = false, ReaderTapAction.Menu)
            .withAction(2, 2, double = true, ReaderTapAction.Navigate)
        assertEquals(custom, ReaderTapZoneMap.decode(ReaderTapZoneMap.encode(custom)))
    }

    @Test fun malformedEncodedValueFallsBackToDefault() {
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode(""))
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode("垃圾数据"))
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode("a|b|c|d|e"))
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode("x|3|3|0:不存在的动作|"))
    }

    @Test fun byNameAlwaysReturnsDefault() {
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.byName("任何名称"))
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.byName(""))
    }

    @Test fun resizedBoundariesDriveHitTestingAndPersist() {
        val map = ReaderTapZoneMap.Default.withColumnBoundary(1, .2f).withRowBoundary(2, .8f)
        assertEquals(4, map.cellAt(250f, 1300f, width, height))
        assertEquals(ReaderTapAction.Menu, map.getActionByCoordinates(250, 1300, width, height, ReaderTapKind.SingleTap))
        assertEquals(map, ReaderTapZoneMap.decode(ReaderTapZoneMap.encode(map)))
    }

    @Test fun boundariesCannotCrossOrShrinkCellsToZero() {
        val map = ReaderTapZoneMap.Default.withColumnBoundary(1, 5f).withRowBoundary(2, -5f)
        assertTrue(map.columnEdges.zipWithNext().all { (a, b) -> b - a >= ReaderTapZoneMap.MIN_SPAN - .00001f })
        assertTrue(map.rowEdges.zipWithNext().all { (a, b) -> b - a >= ReaderTapZoneMap.MIN_SPAN - .00001f })
        assertEquals(map, map.withColumnBoundary(1, Float.NaN))
    }

    @Test fun onlyOrthogonallyAdjacentMatchingActionPairsMerge() {
        val map = ReaderTapZoneMap.Default.withAction(0, 0, true, ReaderTapAction.Navigate)
            .withAction(1, 0, true, ReaderTapAction.Navigate)
        assertEquals(listOf(0, 1), map.regionFor(0).cells)
        assertEquals(listOf(3, 6), map.regionFor(3).cells) // 单击相同，双击不同。
        val diagonal = ReaderTapZoneMap().withAction(0, 0, false, ReaderTapAction.Menu)
            .withAction(1, 1, false, ReaderTapAction.Menu)
        assertEquals(listOf(0), diagonal.regionFor(0).cells)
        val joined = diagonal.withAction(1, 0, false, ReaderTapAction.Menu)
        assertEquals(listOf(0, 1, 4), joined.regionFor(0).cells)
        assertEquals(9, joined.mergedRegions().sumOf { it.cells.size })
    }

    @Test fun editingOneCellCanSplitAMergedRegion() {
        val map = ReaderTapZoneMap.Default.withAction(1, 0, false, ReaderTapAction.None)
        assertEquals(listOf(0, 3, 6), map.regionFor(0).cells)
        assertEquals(listOf(1), map.regionFor(1).cells)
        assertEquals(ReaderTapAction.None, map.singleAt(1, 0))
    }

    @Test fun legacyDefaultAndMalformedBoundariesHaveSafeFallbacks() {
        val legacy = ReaderTapZoneMap.encode(ReaderTapZoneMap.Default.withAction(1, 1, false, ReaderTapAction.None)).split('|').take(5).joinToString("|")
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode(legacy))
        listOf("NaN,0.7", "0.8,0.2", "0.3", "0.01,0.7").forEach { stops ->
            assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode("custom|3|3|0:Menu||$stops|"))
        }
    }

    @Test fun pageTurningOptionsResolveAndClampSpeed() {
        val options = ReaderPageTurningOptions(animationSpeed = 99)
        assertEquals(ReaderTapZoneMap.Default, options.resolvedTapZoneMap)
        assertEquals(1f, options.animationFraction, 0f)
        assertEquals(0f, ReaderPageTurningOptions(animationSpeed = 1).animationFraction, 0f)
    }

    @Test fun oldDefaultsBecomeImmediateButCustomDoubleActionsAndGeometryArePreserved() {
        val legacy = ReaderTapZoneMap.Default.copy(doubleActions = mapOf(
            0 to ReaderTapAction.Navigate, 1 to ReaderTapAction.Navigate, 2 to ReaderTapAction.Navigate,
            6 to ReaderTapAction.Menu, 7 to ReaderTapAction.Menu, 8 to ReaderTapAction.Menu,
        ))
        assertTrue(ReaderTapZoneMap.Default.doubleActions.isEmpty())
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode(ReaderTapZoneMap.encode(legacy)))
        val oldFiveParts = ReaderTapZoneMap.encode(legacy.withAction(1, 1, false, ReaderTapAction.None)).split('|').take(5).joinToString("|")
        assertEquals(ReaderTapZoneMap.Default, ReaderTapZoneMap.decode(oldFiveParts))
        for(custom in listOf(legacy.withColumnBoundary(1, .2f), legacy.withAction(1, 1, false, ReaderTapAction.None),
            legacy.withAction(0, 0, true, ReaderTapAction.Menu))) {
            assertEquals(custom, ReaderTapZoneMap.decode(ReaderTapZoneMap.encode(custom)))
        }
    }

    @Test fun fingerScrollingCapabilitiesMatchTheirNames() {
        assertTrue(ReaderFingerScrolling.TapAndFlick.supportsTap)
        assertTrue(ReaderFingerScrolling.TapAndFlick.supportsFlick)
        assertTrue(ReaderFingerScrolling.Tap.supportsTap)
        assertTrue(ReaderFingerScrolling.Tap.supportsFlick.not())
        assertTrue(ReaderFingerScrolling.Flick.supportsFlick)
        assertTrue(ReaderFingerScrolling.Flick.supportsTap.not())
    }
}
