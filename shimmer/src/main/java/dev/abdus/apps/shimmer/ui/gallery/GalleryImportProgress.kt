package dev.abdus.apps.shimmer.ui.gallery

import dev.abdus.apps.shimmer.p1.P1ScanPhase
import dev.abdus.apps.shimmer.p1.P1ScanProgress

/** 来源导入的三阶段说明，只显示真实计数与经过时间，不预测剩余速度。 */
data class GalleryImportProgress(val summary: String, val elapsedLabel: String?, val currentPath: String?) {
    companion object {
        fun from(progress: P1ScanProgress?): GalleryImportProgress {
            progress ?: return GalleryImportProgress("正在导入…", null, null)
            val summary = when (progress.phase) {
                P1ScanPhase.ENUMERATION -> "枚举目录：已发现 ${progress.discoveredImages} 张，已检查 ${progress.visitedNodes} 项"
                P1ScanPhase.METADATA -> "校验图片：已检查 ${progress.processedImages} / ${progress.totalImages} 张"
                P1ScanPhase.COMMIT -> "提交配置：共 ${progress.totalImages} 张"
            }
            return GalleryImportProgress(
                summary = summary,
                elapsedLabel = "本次已用 ${progress.elapsedMillis.coerceAtLeast(0) / 1000} 秒",
                currentPath = progress.currentPath.takeIf { progress.phase == P1ScanPhase.ENUMERATION && it.isNotBlank() },
            )
        }
    }
}
