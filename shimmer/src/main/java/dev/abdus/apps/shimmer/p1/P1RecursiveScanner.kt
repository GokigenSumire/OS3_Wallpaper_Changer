package dev.abdus.apps.shimmer.p1

import java.util.concurrent.CancellationException

/**
 * Deterministic, provider-neutral recursive image enumeration.
 *
 * The Android SAF and file-provider adapters will translate their nodes into P1TreeNode. This
 * class deliberately returns Failed/Cancelled instead of a partial Complete result so callers
 * can preserve the last committed image configuration.
 */
class P1RecursiveScanner(
    private val isImage: (P1TreeNode) -> Boolean = ::defaultIsImage,
) {
    fun scan(
        source: P1SourceSpec,
        root: P1TreeNode,
        checkActive: () -> Unit = {},
        onProgress: (P1ScanProgress) -> Unit = {},
    ): P1ScanOutcome {
        val visited = linkedSetOf<String>()
        val images = mutableListOf<P1ScannedImage>()

        fun failure(reason: P1ScanFailure): P1ScanOutcome.Failed =
            P1ScanOutcome.Failed(reason, visited.size, images.size)

        fun walk(node: P1TreeNode, path: String, isRoot: Boolean) {
            checkActive()
            if (!visited.add(node.id)) return
            val currentPath = if (path.isEmpty()) node.name else "$path/${node.name}"
            onProgress(P1ScanProgress(visited.size, images.size, currentPath))

            if (node.isDirectory) {
                if (!isRoot && !source.includeSubdirectories) return
                val children = try {
                    // 逐项保留取消检查，在收集时构建缓存键，排序不再反复转换小写字符串。
                    orderP1TreeNodes(node.children(checkActive), checkActive)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (security: SecurityException) {
                    throw P1TreeReadException(P1ScanFailure.PERMISSION)
                } catch (failed: P1TreeReadException) {
                    throw failed
                } catch (_: Exception) {
                    throw P1TreeReadException(P1ScanFailure.READ)
                }

                // Provider order is not a stable ordering contract. Sort before walking so
                // rescans produce the same order for the same tree and names.
                children.forEach { child -> walk(child, currentPath.takeUnless { isRoot } ?: "", false) }
                return
            }

            if (!isImage(node)) return
            val relativePath = currentPath
            images += P1ScannedImage(
                // A document identity is global across sources. Source membership is carried by
                // source_image_links, so overlapping sources share one image record.
                stableKey = "image:${node.id}",
                sourceId = source.sourceId,
                uri = node.uri,
                displayName = node.name,
                relativePath = relativePath,
                sizeBytes = node.sizeBytes,
            )
            onProgress(P1ScanProgress(visited.size, images.size, currentPath))
        }

        return try {
            walk(root, "", true)
            checkActive()
            P1ScanOutcome.Complete(
                images = orderP1ScannedImages(images, checkActive),
                visitedNodes = visited.size,
            )
        } catch (_: CancellationException) {
            P1ScanOutcome.Cancelled(visited.size, images.size)
        } catch (failed: P1TreeReadException) {
            failure(failed.failure)
        } catch (_: SecurityException) {
            failure(P1ScanFailure.PERMISSION)
        } catch (_: Exception) {
            failure(P1ScanFailure.READ)
        }
    }

    private companion object {
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

        fun defaultIsImage(node: P1TreeNode): Boolean {
            if (node.isDirectory) return false
            if (dev.abdus.apps.shimmer.media.MediaFileTypes.isVideo(node.name, node.mimeType)) return true
            if (node.mimeType?.startsWith("image/", ignoreCase = true) == true) return true
            return node.name.substringAfterLast('.', "").lowercase() in imageExtensions
        }
    }
}
