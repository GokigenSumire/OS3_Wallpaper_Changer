package dev.abdus.apps.shimmer.p1

/** Display name of the level that holds files stored directly under the source root. */
const val DIRECTORY_TREE_ROOT_NAME = "根目录"

/**
 * One directory level of a source.
 *
 * P1 and P2 both need the same shape: a folder collapses as a whole and expanding it reveals
 * only its own next level, not every descendant at once.  Building the level structure here
 * keeps the two pages consistent and keeps the recursion out of the view code.
 */
data class DirectoryTreeNode<T>(
    /** Path relative to the source root, `/`-separated; empty for the root level. */
    val path: String,
    /** Last path segment, or [DIRECTORY_TREE_ROOT_NAME] for the root level. */
    val name: String,
    /** 0 for the root level. */
    val depth: Int,
    /** Items stored directly in this directory, in input order. */
    val directItems: List<T>,
    /** Immediate child directories, in first-seen order. */
    val children: List<DirectoryTreeNode<T>>,
    /** [directItems] plus every descendant item. */
    val totalCount: Int,
)

/**
 * Groups [entries] into one node per directory level.
 *
 * Intermediate directories are created even when they hold no files of their own, so
 * `1/1-1/a.jpg` produces `1/` and `1/1-1/` instead of a single flat `1/1-1` entry.  Child order
 * follows the order entries are first seen, which keeps the scanner's sorted walk for natural
 * order and the catalog order for random playback.
 */
fun <T> buildDirectoryTree(
    entries: List<T>,
    rootName: String = DIRECTORY_TREE_ROOT_NAME,
    relativePathOf: (T) -> String,
): DirectoryTreeNode<T> {
    val direct = LinkedHashMap<String, MutableList<T>>()
    val children = LinkedHashMap<String, LinkedHashSet<String>>()
    direct[""] = mutableListOf()
    children[""] = LinkedHashSet()
    entries.forEach { entry ->
        var path = ""
        directorySegments(relativePathOf(entry)).forEach { segment ->
            val childPath = if (path.isEmpty()) segment else "$path/$segment"
            children.getValue(path).add(childPath)
            direct.getOrPut(childPath) { mutableListOf() }
            children.getOrPut(childPath) { LinkedHashSet() }
            path = childPath
        }
        direct.getValue(path).add(entry)
    }
    return buildNode("", rootName, 0, direct, children)
}

private fun <T> buildNode(
    path: String,
    name: String,
    depth: Int,
    direct: Map<String, List<T>>,
    children: Map<String, Set<String>>,
): DirectoryTreeNode<T> {
    val childNodes = children[path].orEmpty().map { childPath ->
        buildNode(childPath, childPath.substringAfterLast('/'), depth + 1, direct, children)
    }
    val own = direct[path].orEmpty()
    return DirectoryTreeNode(
        path = path,
        name = name,
        depth = depth,
        directItems = own,
        children = childNodes,
        totalCount = own.size + childNodes.sumOf { it.totalCount },
    )
}

/**
 * Directory segments of a file path relative to the source root, excluding the file name.
 *
 * A path is always treated as a file path, so `1/1-1/a.jpg` yields `1` and `1-1`.  Backslashes
 * are accepted because some providers report Windows-style separators.
 */
fun directorySegments(relativePath: String): List<String> {
    val normalized = relativePath.replace('\\', '/').trim('/')
    if (normalized.isEmpty()) return emptyList()
    return normalized.split('/').dropLast(1).filter { it.isNotBlank() }
}

/**
 * True when [descendantPath] is [directoryPath] itself or sits under it.
 *
 * Plain prefix comparison, never SQL `LIKE`: real folder names contain `%` and `_`.
 */
fun isWithinDirectory(descendantPath: String, directoryPath: String): Boolean {
    if (directoryPath.isEmpty()) return true
    return descendantPath == directoryPath || descendantPath.startsWith("$directoryPath/")
}
