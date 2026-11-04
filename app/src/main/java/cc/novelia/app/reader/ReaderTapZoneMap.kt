/*
 * 改编自 FBReaderJ (https://github.com/geometer/FBReaderJ) v2.5.10
 * 原始文件：org/geometerplus/fbreader/fbreader/TapZoneMap.java
 * 原作者：FBReader.ORG Limited <contact@fbreader.org>
 * 原始许可：GPL-2.0-or-later（本项目按 GPL-3.0-only 使用）
 *
 * 修改内容：
 *  - 改写为 Kotlin，去掉 ZLStringOption / ZLFile / SAX 解析依赖
 *  - 只保留一套默认分区，不提供预设列表；用户逐格自由配置
 *  - 动作集合扩展为翻页、目录、工具栏、无操作，覆盖横向与纵向翻页
 *  - 坐标归一化扩展为可调行列边界，相同动作的相邻格子自动合并
 */
package cc.novelia.app.reader

import kotlinx.serialization.Serializable

/** 单个格子或单次滑动的动作。密封枚举保证穷举，设置界面可列出全部可选值。 */
@Serializable
enum class ReaderTapAction {
    /** 上一页 */
    PreviousPage,

    /** 下一页 */
    NextPage,

    /** 打开目录 */
    Navigate,

    /** 显示或收起工具栏 */
    Menu,

    /** 不执行阅读器点击动作；仍保留滑动、长按和控件操作。 */
    None;

    val isPageTurn: Boolean get() = this == PreviousPage || this == NextPage

    companion object {
        /** 设置界面里给用户选的顺序。 */
        val editable: List<ReaderTapAction> = listOf(PreviousPage, NextPage, Navigate, Menu, None)

        fun fromName(name: String): ReaderTapAction =
            entries.firstOrNull { it.name == name } ?: None
    }
}

/**
 * 一次点击的判定类型，对应 FBReader 的 TapZoneMap.Tap。
 *  - SingleTap / SingleNotDoubleTap：单击，绝不回退到双击动作
 *  - SingleNotDoubleTap：确认不是双击的单击
 *  - DoubleTap：双击
 */
enum class ReaderTapKind { SingleTap, SingleNotDoubleTap, DoubleTap }

/**
 * 一套点击分区方案。
 *
 * 网格为 [columns] × [rows] 个格子（默认 3 × 3，共 9 格），每格可独立配置单击与双击动作。
 * [singleActions] / [doubleActions] 以 `row * columns + column` 为键；
 * 缺省的格子等同于 [ReaderTapAction.None]。
 *
 * 这是不可变快照：用户修改时用 [withAction] 产生新实例，
 * 便于 Compose 依赖比较与设置界面回退。
 */
@Serializable
data class ReaderTapZoneMap(
    val name: String = DEFAULT_NAME,
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    /** 每格的单击动作，键为 `row * columns + column`。 */
    val singleActions: Map<Int, ReaderTapAction> = emptyMap(),
    /** 每格的双击动作，键同上。 */
    val doubleActions: Map<Int, ReaderTapAction> = emptyMap(),
    /** 内部分界线在视口中的比例；空列表表示等分，兼容旧方案。 */
    val columnStops: List<Float> = emptyList(),
    val rowStops: List<Float> = emptyList(),
) {
    /** 格子总数。 */
    val cellCount: Int get() = columns * rows
    val columnEdges: List<Float> get() = edges(columnStops, columns)
    val rowEdges: List<Float> get() = edges(rowStops, rows)

    fun cellAt(x: Float, y: Float, width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite()) return null
        val column = columnEdges.drop(1).indexOfFirst { x / width < it }.takeIf { it >= 0 } ?: columns - 1
        val row = rowEdges.drop(1).indexOfFirst { y / height < it }.takeIf { it >= 0 } ?: rows - 1
        return row * columns + column
    }

    fun cellBounds(cell: Int): ReaderTapRect {
        val column = cell % columns
        val row = cell / columns
        return ReaderTapRect(columnEdges[column], rowEdges[row], columnEdges[column + 1], rowEdges[row + 1])
    }

    fun withColumnBoundary(index: Int, fraction: Float): ReaderTapZoneMap =
        copy(columnStops = movedBoundary(columnEdges, index, fraction))

    fun withRowBoundary(index: Int, fraction: Float): ReaderTapZoneMap =
        copy(rowStops = movedBoundary(rowEdges, index, fraction))

    /** 保留底层格子；相邻且两种动作一致时组成一个区域，不跨过不同动作或对角线。 */
    fun mergedRegions(): List<ReaderTapRegion> {
        val remaining = (0 until cellCount).toMutableSet()
        return buildList {
            while (remaining.isNotEmpty()) {
                val first = remaining.first()
                val single = singleActions[first] ?: ReaderTapAction.None
                val double = doubleActions[first] ?: ReaderTapAction.None
                val cells = mutableListOf<Int>()
                val pending = ArrayDeque<Int>()
                pending.add(first)
                remaining.remove(first)
                while (pending.isNotEmpty()) {
                    val cell = pending.removeFirst()
                    cells += cell
                    val column = cell % columns
                    val row = cell / columns
                    val neighbors = listOfNotNull(
                        (cell - 1).takeIf { column > 0 }, (cell + 1).takeIf { column + 1 < columns },
                        (cell - columns).takeIf { row > 0 }, (cell + columns).takeIf { row + 1 < rows })
                    neighbors.filter { it in remaining && (singleActions[it] ?: ReaderTapAction.None) == single &&
                        (doubleActions[it] ?: ReaderTapAction.None) == double }.forEach {
                        remaining.remove(it); pending.add(it)
                    }
                }
                add(ReaderTapRegion(cells.sorted(), cells.sorted().map(::cellBounds), single, double))
            }
        }
    }

    fun regionFor(cell: Int): ReaderTapRegion = mergedRegions().first { cell in it.cells }

    fun withRegionAction(cells: List<Int>, double: Boolean, action: ReaderTapAction): ReaderTapZoneMap =
        cells.fold(this) { map, cell -> map.withAction(cell % columns, cell / columns, double, action) }

    /**
     * 把屏幕坐标换算成动作。
     *
     * 按当前行列边界查找归一化坐标，越界坐标夹到首尾格子。
     * 等分布局与原始 FBReader 算法一致；调整边界后按保存的比例命中。
     *
     * @return 该位置没有配置动作时返回 [ReaderTapAction.None]
     */
    fun getActionByCoordinates(x: Int, y: Int, width: Int, height: Int, kind: ReaderTapKind): ReaderTapAction {
        val cell = cellAt(x.toFloat(), y.toFloat(), width, height) ?: return ReaderTapAction.None
        return getActionByZone(cell % columns, cell / columns, kind)
    }

    /** 按格子索引查动作，越界或未配置时返回 [ReaderTapAction.None]。 */
    fun getActionByZone(column: Int, row: Int, kind: ReaderTapKind): ReaderTapAction {
        if (column !in 0 until columns || row !in 0 until rows) return ReaderTapAction.None
        val key = row * columns + column
        val single = singleActions[key] ?: ReaderTapAction.None
        val double = doubleActions[key] ?: ReaderTapAction.None
        return when (kind) {
            ReaderTapKind.SingleTap, ReaderTapKind.SingleNotDoubleTap -> single
            ReaderTapKind.DoubleTap -> double
        }
    }

    /** 读取某格的单击动作，供设置界面显示。 */
    fun singleAt(column: Int, row: Int): ReaderTapAction =
        singleActions[row * columns + column] ?: ReaderTapAction.None

    /** 读取某格的双击动作，供设置界面显示。 */
    fun doubleAt(column: Int, row: Int): ReaderTapAction =
        doubleActions[row * columns + column] ?: ReaderTapAction.None

    /**
     * 产生修改了某格动作的新方案。
     *
     * 显式写入 [ReaderTapAction.None] 而不是删键：保留用户「这一格确实不响应」的意图，
     * 也让编码往返后方案完全相等。
     */
    fun withAction(column: Int, row: Int, double: Boolean, action: ReaderTapAction): ReaderTapZoneMap {
        if (column !in 0 until columns || row !in 0 until rows) return this
        val key = row * columns + column
        val nextSingle = if (double) singleActions else singleActions + (key to action)
        val nextDouble = if (double) doubleActions + (key to action) else doubleActions
        return copy(singleActions = nextSingle, doubleActions = nextDouble)
    }

    /** 设置界面用的一句话说明，描述整张分区表的翻页方向。 */
    fun describe(): String {
        val cells = (0 until rows).flatMap { row -> (0 until columns).map { column -> singleAt(column, row) } }
        val forward = cells.count { it == ReaderTapAction.NextPage }
        val backward = cells.count { it == ReaderTapAction.PreviousPage }
        val menu = cells.count { it == ReaderTapAction.Menu }
        val direction = when {
            forward > 0 && backward > 0 -> "同时包含向前与向后翻页"
            forward > 0 -> "只翻下一页，回退请用滑动"
            backward > 0 -> "只向前翻页"
            else -> "没有配置翻页区域"
        }
        return "$direction；$menu 个格子显示工具栏，合并后共 ${mergedRegions().size} 个区域。"
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val DEFAULT_ROWS = 3
        const val DEFAULT_NAME = "default"

        /** FBReader 允许的最小/最大网格密度。 */
        const val MIN_GRID = 2
        const val MAX_GRID = 5
        const val MIN_SPAN = .08f

        private fun validStops(stops: List<Float>, count: Int): Boolean = stops.isEmpty() ||
            (stops.size == count - 1 && (listOf(0f) + stops + 1f).zipWithNext().all { (a, b) ->
                a.isFinite() && b.isFinite() && b - a >= MIN_SPAN - .00001f
            })

        private fun edges(stops: List<Float>, count: Int): List<Float> =
            if (stops.isNotEmpty() && validStops(stops, count)) listOf(0f) + stops + 1f
            else (0..count).map { it.toFloat() / count }

        private fun movedBoundary(edges: List<Float>, index: Int, fraction: Float): List<Float> {
            if (index !in 1 until edges.lastIndex || !fraction.isFinite()) return edges.drop(1).dropLast(1)
            return edges.toMutableList().apply {
                this[index] = fraction.coerceIn(edges[index - 1] + MIN_SPAN, edges[index + 1] - MIN_SPAN)
            }.drop(1).dropLast(1)
        }

        private fun map(
            single: Map<Pair<Int, Int>, ReaderTapAction> = emptyMap(),
            double: Map<Pair<Int, Int>, ReaderTapAction> = emptyMap(),
            columns: Int = DEFAULT_COLUMNS,
            rows: Int = DEFAULT_ROWS,
        ): ReaderTapZoneMap {
            fun index(entries: Map<Pair<Int, Int>, ReaderTapAction>) =
                entries.entries.associate { (pos, action) -> (pos.second * columns + pos.first) to action }
            return ReaderTapZoneMap(DEFAULT_NAME, columns, rows, index(single), index(double))
        }

        /**
         * 默认方案：四向翻页，横向与纵向同时生效，这是 FBReader 分区方案的核心优势。
         *
         * 左列与上中格翻上一页，右列与下中格翻下一页，正中显示工具栏。
         * 四个角同时属于一条列和一条行，这里归给横向（与 FBReader 一致：
         * 角格只承载一个动作，避免同一格出现两个冲突的翻页方向）。
         *
         * 默认不绑定双击，所有单击区域都在松手时立即响应；双击由用户主动配置。
         */
        val Default: ReaderTapZoneMap = map(
            single = mapOf(
                // 左列：上一页
                (0 to 0) to ReaderTapAction.PreviousPage, (0 to 1) to ReaderTapAction.PreviousPage, (0 to 2) to ReaderTapAction.PreviousPage,
                // 右列：下一页
                (2 to 0) to ReaderTapAction.NextPage, (2 to 1) to ReaderTapAction.NextPage, (2 to 2) to ReaderTapAction.NextPage,
                // 中列上下：纵向翻页，正中：工具栏
                (1 to 0) to ReaderTapAction.PreviousPage, (1 to 1) to ReaderTapAction.Menu, (1 to 2) to ReaderTapAction.NextPage,
            ),
        )

        private val legacyDefault = Default.copy(doubleActions = mapOf(
            0 to ReaderTapAction.Navigate, 1 to ReaderTapAction.Navigate, 2 to ReaderTapAction.Navigate,
            6 to ReaderTapAction.Menu, 7 to ReaderTapAction.Menu, 8 to ReaderTapAction.Menu,
        ))

        /**
         * 把方案编码成一行字符串，用于随阅读设置持久化。
         *
         * 格式：`名称|列数|行数|单击表|双击表|列分界|行分界`，兼容旧五段格式。
         * 解析失败时回退到 [Default]，保证旧版本数据或损坏值不会让阅读器不可用。
         */
        fun encode(map: ReaderTapZoneMap): String {
            fun table(values: Map<Int, ReaderTapAction>) =
                values.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.name}" }
            return listOf(map.name, map.columns.toString(), map.rows.toString(),
                table(map.singleActions), table(map.doubleActions), map.columnStops.joinToString(","), map.rowStops.joinToString(",")).joinToString("|")
        }

        /** 解析 [encode] 生成的字符串；任何异常都回退到 [Default]。 */
        fun decode(value: String): ReaderTapZoneMap {
            if (value.isBlank() || value.length > 2_048) return Default
            return try {
                val parts = value.split('|')
                if (parts.size != 5 && parts.size != 7) return Default
                val columns = parts[1].toIntOrNull()?.takeIf { it in MIN_GRID..MAX_GRID } ?: return Default
                val rows = parts[2].toIntOrNull()?.takeIf { it in MIN_GRID..MAX_GRID } ?: return Default
                fun table(raw: String): Map<Int, ReaderTapAction>? {
                    if (raw.isBlank()) return emptyMap()
                    val result = HashMap<Int, ReaderTapAction>()
                    for (entry in raw.split(',')) {
                        val pair = entry.split(':')
                        if (pair.size != 2) return null
                        val index = pair[0].toIntOrNull()?.takeIf { it in 0 until columns * rows } ?: return null
                        val action = ReaderTapAction.entries.firstOrNull { it.name == pair[1] } ?: return null
                        result[index] = action
                    }
                    return result
                }
                val single = table(parts[3]) ?: return Default
                val double = table(parts[4]) ?: return Default
                fun stops(raw: String, count: Int): List<Float>? {
                    if (raw.isBlank()) return emptyList()
                    val values = raw.split(',').map { it.toFloatOrNull() ?: return null }
                    return values.takeIf { validStops(it, count) }
                }
                val x = stops(parts.getOrElse(5) { "" }, columns) ?: return Default
                val y = stops(parts.getOrElse(6) { "" }, rows) ?: return Default
                val result = ReaderTapZoneMap(parts[0].ifBlank { DEFAULT_NAME }, columns, rows, single, double, x, y)
                // 仅迁移完整的旧默认方案；自定义动作、分区大小及显式禁用区域保持不变。
                when {
                    result == legacyDefault -> Default
                    parts.size == 5 && (result == legacyDefault.withAction(1, 1, false, ReaderTapAction.None) ||
                        result == Default.withAction(1, 1, false, ReaderTapAction.None)) -> Default
                    else -> result
                }
            } catch (_: RuntimeException) {
                Default
            }
        }

        /** 只有一套默认方案；未知名称回退到 [Default]。 */
        fun byName(name: String): ReaderTapZoneMap = Default
    }
}

/** 视口归一化矩形；配置、教程、命中测试与点击反馈共享相同边界。 */
data class ReaderTapRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class ReaderTapRegion(val cells: List<Int>, val bounds: List<ReaderTapRect>, val single: ReaderTapAction, val double: ReaderTapAction)
