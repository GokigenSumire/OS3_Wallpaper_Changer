package dev.abdus.apps.shimmer.ui.gallery

import dev.abdus.apps.shimmer.p1.DirectoryTreeNode

/** Who decides whether a directory level plays. */
enum class DirectoryControl {
    /** The root level is the source itself; the card's source switch controls it. */
    SOURCE_SWITCH,

    /** A stopped ancestor covers this level; its own record is kept and applies again later. */
    PARENT,

    /** The level has its own stop/resume button. */
    TOGGLE,
}

/** One folder row on a gallery source card. */
data class DirectoryRow(
    /** Path relative to the source root; empty for the root level. */
    val path: String,
    val name: String,
    val depth: Int,
    val totalCount: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    /** This level's own stop record (for the root level: the source switch is off). */
    val stopped: Boolean,
    val stateLabel: String,
    val control: DirectoryControl,
)

data class DirectoryRows(val rows: List<DirectoryRow>, val truncated: Boolean)

/**
 * Folder rows for the gallery tab, using the same rules as the P1 sources page so both pages
 * agree: the root level starts collapsed, expanding a level shows only its next level, and a
 * stopped ancestor turns its descendants into "由上级控制" rows.
 */
object GalleryDirectoryRows {
    /** Rows listed on one card; deeper libraries are browsed by collapsing levels or on the P1 page. */
    const val ROW_LIMIT = 300

    fun <T> rows(
        tree: DirectoryTreeNode<T>,
        rootName: String,
        sourceEnabled: Boolean,
        excluded: Set<String>,
        expanded: Set<String>,
        limit: Int = ROW_LIMIT,
    ): DirectoryRows {
        val rows = ArrayList<DirectoryRow>()
        var truncated = false

        fun visit(node: DirectoryTreeNode<T>, inheritedStop: Boolean) {
            if (rows.size >= limit) {
                truncated = true
                return
            }
            val isRoot = node.depth == 0
            val stopped = if (isRoot) !sourceEnabled else node.path in excluded
            val isExpanded = node.path in expanded && node.children.isNotEmpty()
            rows += DirectoryRow(
                path = node.path,
                name = if (isRoot) rootName else node.name,
                depth = node.depth,
                totalCount = node.totalCount,
                hasChildren = node.children.isNotEmpty(),
                expanded = isExpanded,
                stopped = stopped,
                stateLabel = stateLabel(isRoot, stopped, inheritedStop),
                control = when {
                    inheritedStop -> DirectoryControl.PARENT
                    isRoot -> DirectoryControl.SOURCE_SWITCH
                    else -> DirectoryControl.TOGGLE
                },
            )
            if (!isExpanded) return
            for (child in node.children) {
                visit(child, inheritedStop || stopped)
                if (truncated) return
            }
        }

        visit(tree, inheritedStop = false)
        return DirectoryRows(rows, truncated)
    }

    /** Same wording as the P1 sources page. */
    fun stateLabel(isRoot: Boolean, stopped: Boolean, inheritedStop: Boolean): String = when {
        inheritedStop && stopped -> "受上级停用影响（本层也已停用）"
        inheritedStop -> "受上级停用影响"
        isRoot && stopped -> "来源已停用"
        stopped -> "已停用"
        else -> "播放中"
    }
}
