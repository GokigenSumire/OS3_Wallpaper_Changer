package dev.abdus.apps.shimmer.p5

import java.util.Locale

/** Chinese labels and the diagnostics text of the P5 page, kept pure for unit tests. */
object TransitionReport {
    val CHOICE_LABELS: Map<TransitionChoice, String> = linkedMapOf(
        TransitionChoice.NONE to "无动画",
        TransitionChoice.CROSSFADE to "淡入淡出",
        TransitionChoice.FADE_THROUGH to "淡出到背景再淡入",
        TransitionChoice.SLIDE to "方向滑动",
        TransitionChoice.ZOOM to "缩放",
        TransitionChoice.RANDOM to "随机效果",
    )

    val DIRECTION_LABELS: Map<SlideDirectionChoice, String> = linkedMapOf(
        SlideDirectionChoice.FOLLOW to "跟随播放方向",
        SlideDirectionChoice.LEFT to "向左（新图从右侧进入）",
        SlideDirectionChoice.RIGHT to "向右（新图从左侧进入）",
        SlideDirectionChoice.UP to "向上（新图从下方进入）",
        SlideDirectionChoice.DOWN to "向下（新图从上方进入）",
        SlideDirectionChoice.RANDOM to "每次随机",
    )

    fun verdictLabel(verdict: Verdict): String = when (verdict) {
        Verdict.PASS -> "PASS"
        Verdict.FAIL -> "FAIL"
        Verdict.INSUFFICIENT -> "INSUFFICIENT（样本不足）"
    }

    fun settingsLine(settings: TransitionSettings): String {
        val normalized = settings.normalized()
        val direction = if (normalized.choice == TransitionChoice.SLIDE || normalized.choice == TransitionChoice.RANDOM) {
            "，滑动方向 ${DIRECTION_LABELS.getValue(normalized.direction)}"
        } else ""
        return "当前设置：${CHOICE_LABELS.getValue(normalized.choice)}，时长 ${normalized.durationMillis} ms$direction"
    }

    fun effectName(effect: TransitionEffect): String = when (effect) {
        TransitionEffect.NONE -> "无动画"
        TransitionEffect.CROSSFADE -> "淡入淡出"
        TransitionEffect.FADE_THROUGH -> "淡出到背景"
        TransitionEffect.SLIDE -> "滑动"
        TransitionEffect.ZOOM -> "缩放"
    }

    /** Full diagnostics in the frozen P5 terms (X1–X5, X7); X6 and the visual part of X5 are judged by the user. */
    fun format(snapshot: TransitionStats.Snapshot, settings: TransitionSettings): String = buildString {
        appendLine(settingsLine(settings))
        appendLine("过渡 ${snapshot.transitions} 次（被打断 ${snapshot.interrupted} 次、跨越不可见 ${snapshot.paused} 次，均不计入 X1～X3）")
        val covered = TransitionEffect.ANIMATED.joinToString("，") { "${effectName(it)} ${snapshot.completedByEffect[it] ?: 0}" }
        appendLine("各效果完整完成：$covered（X1、X3 要求各 ≥ ${TransitionStats.MIN_PER_EFFECT} 次）")
        appendLine(
            "X1 过渡帧 CPU 绘制：${snapshot.drawSamples} 帧，p95 ${ms(snapshot.drawP95Millis)}，p99 ${ms(snapshot.drawP99Millis)}，" +
                "超 16.7 ms ${percent(snapshot.drawOverBudgetFraction)}（阈值 p95 ≤ 8.0 ms、p99 ≤ 16.7 ms、超限 ≤ 1%）→ ${verdictLabel(snapshot.x1)}",
        )
        appendLine(
            "X2 过渡内帧间隔：${snapshot.intervalSamples} 个，p95 ${ms(snapshot.intervalP95Millis)}，最大 ${ms(snapshot.intervalMaxMillis)}" +
                "（阈值 p95 ≤ 20.0 ms、最大 ≤ 100 ms）→ ${verdictLabel(snapshot.x2)}",
        )
        appendLine(
            "X3 完成超时：p95 ${snapshot.overshootP95Millis?.let { "$it ms" } ?: "—"}，最大 ${snapshot.overshootMaxMillis?.let { "$it ms" } ?: "—"}" +
                "（阈值 p95 ≤ ${TransitionStats.OVERSHOOT_P95_MS} ms）→ ${verdictLabel(snapshot.x3)}",
        )
        appendLine(
            "X4 资源：残留 ${snapshot.residualTransitions} 次，图片层峰值 ${snapshot.peakLayers}（≤ ${TransitionStats.MAX_LAYERS}），" +
                "离屏缓冲峰值 ${snapshot.peakTargets}（≤ ${TransitionStats.MAX_TARGETS}）→ ${verdictLabel(snapshot.x4)}",
        )
        appendLine("X5 旧图闪回：stale_frames=${snapshot.staleFrames} → ${verdictLabel(snapshot.x5)}（目视部分按 TC-FX-02 由用户判定）")
        appendLine("X6 稳定性：由用户判定（无崩溃、无 ANR、无无法恢复的黑屏）")
        val growth = snapshot.heapGrowth?.let { "${mib(it.bytes)}（同一图片相隔 ${it.span} 次过渡）" } ?: "暂无可比样本"
        appendLine("X7 Native 堆增长：$growth（阈值 ≤ 64 MiB，需 ≥ ${TransitionStats.MIN_TRANSITIONS} 次过渡）→ ${verdictLabel(snapshot.x7)}")
        appendLine("fbo_fallback=${snapshot.targetFallbacks}")
        appendLine("最近过渡：")
        if (snapshot.recent.isEmpty()) appendLine("（无）") else snapshot.recent.forEach { appendLine(it) }
    }.trimEnd()

    private fun ms(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f ms", it) } ?: "—"

    private fun percent(fraction: Double): String = String.format(Locale.ROOT, "%.2f%%", fraction * 100.0)

    private fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
}
