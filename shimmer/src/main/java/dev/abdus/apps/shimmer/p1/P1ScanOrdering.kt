package dev.abdus.apps.shimmer.p1

/**
 * 预计算派生比较键，避免排序每次比较都重新分配小写字符串。
 * 保留原顺序契约：文件先于目录，再按小写名、原名、全局身份排列。
 * String.lowercase() 与原实现相同，使用无 Locale 参数的语义。
 */
internal fun orderP1TreeNodes(
    nodes: Iterable<P1TreeNode>,
    checkActive: () -> Unit = {},
    lowercase: (String) -> String = { it.lowercase() },
): List<P1TreeNode> {
    val entries = mutableListOf<TreeOrderEntry>()
    for (node in nodes) {
        checkActive()
        val name = node.name
        entries += TreeOrderEntry(node, node.isDirectory, lowercase(name), name, node.id)
    }
    checkActive()
    entries.sortWith(compareBy({ it.isDirectory }, { it.foldedName }, { it.name }, { it.id }))
    checkActive()
    return entries.map { entry ->
        checkActive()
        entry.node
    }
}

/** 最终路径排序独立于树遍历排序，不能用遍历顺序替代。相同比较键维持原输入次序。 */
internal fun orderP1ScannedImages(
    images: Iterable<P1ScannedImage>,
    checkActive: () -> Unit = {},
    lowercase: (String) -> String = { it.lowercase() },
): List<P1ScannedImage> {
    val entries = mutableListOf<ImageOrderEntry>()
    for (image in images) {
        checkActive()
        entries += ImageOrderEntry(image, lowercase(image.relativePath), image.relativePath, image.stableKey)
    }
    checkActive()
    entries.sortWith(compareBy({ it.foldedPath }, { it.path }, { it.stableKey }))
    checkActive()
    return entries.map { entry ->
        checkActive()
        entry.image
    }
}

private class TreeOrderEntry(
    val node: P1TreeNode,
    val isDirectory: Boolean,
    val foldedName: String,
    val name: String,
    val id: String,
)

private class ImageOrderEntry(
    val image: P1ScannedImage,
    val foldedPath: String,
    val path: String,
    val stableKey: String,
)
