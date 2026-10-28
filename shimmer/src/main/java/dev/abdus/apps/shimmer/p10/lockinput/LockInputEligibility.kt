package dev.abdus.apps.shimmer.p10.lockinput

/**
 * 锁屏输入适配器挂接前的纯准入策略，不注册 Hook、不读取系统对象，也不发送播放命令。
 *
 * 所有状态必须由同一次、同一目标代次的可信运行时快照提供；缺失值不是 false。
 * 允许结果只表示可以观察非敏感动作元数据，不证明区域识别、三手势或认证已经可用。
 */
object LockInputEligibility {
    enum class Fact(val required: Boolean) {
        RUNTIME_IDENTITY_VERIFIED(true),
        PROJECT_LOCK_TARGET_SELECTED(true),
        ROOT_VIEW_VISIBLE(true),
        ROOT_VIEW_ATTACHED(true),
        KEYGUARD_SHOWING(true),
        SCREEN_INTERACTIVE(true),
        VERIFIED_BLANK_REGION(true),
        PRIMARY_BOUNCER_SHOWING(false),
        PRIMARY_BOUNCER_SHOWING_SOON(false),
        ALTERNATE_BOUNCER_VISIBLE(false),
        DOZING(false),
        OCCLUDED(false),
        UNLOCK_TRANSITION(false),
        REMOTE_INPUT_ACTIVE(false),
        CONTROL_CENTER_EXPANDED(false),
        NOTIFICATION_CONTROL_HIT(false),
        SHORTCUT_CONTROL_HIT(false),
        SYSTEM_GESTURE_OWNED(false),
    }

    /** 防止调用者随后修改原 Map，悄悄改变一份已经冻结的证据。 */
    class Evidence(values: Map<Fact, Boolean> = emptyMap()) {
        private val frozenValues = values.toMap()
        fun value(fact: Fact): Boolean? = frozenValues[fact]
    }

    data class Sample(
        val packageName: String,
        val processName: String,
        val displayId: Int,
        val targetGeneration: Long,
        val expectedTargetGeneration: Long,
        val actionMasked: Int,
        val pointerCount: Int,
        val downTimeUptimeMs: Long,
        val eventTimeUptimeMs: Long,
        val evidence: Evidence,
    )

    enum class Reason {
        ALLOW_METADATA_OBSERVATION,
        RESET_ONLY,
        REJECT_SCOPE,
        REJECT_STALE_TARGET,
        REJECT_MALFORMED_EVENT,
        REJECT_UNKNOWN_FACT,
        REJECT_UNSAFE_FACT,
    }

    data class Decision(val reason: Reason, val failedFact: Fact? = null) {
        val allowed: Boolean get() = reason == Reason.ALLOW_METADATA_OBSERVATION
    }

    // 与 MotionEvent 的公开 actionMasked 常量保持一致；这里不依赖 Android 运行时。
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val ACTION_CANCEL = 3
    private const val ACTION_POINTER_DOWN = 5
    private const val ACTION_POINTER_UP = 6

    fun evaluate(sample: Sample): Decision {
        if (sample.packageName != "com.android.systemui" ||
            sample.processName != "com.android.systemui" || sample.displayId != 0
        ) return Decision(Reason.REJECT_SCOPE)

        if (sample.targetGeneration <= 0 ||
            sample.targetGeneration != sample.expectedTargetGeneration
        ) return Decision(Reason.REJECT_STALE_TARGET)

        for (fact in Fact.entries) {
            val actual = sample.evidence.value(fact)
                ?: return Decision(Reason.REJECT_UNKNOWN_FACT, fact)
            if (actual != fact.required) return Decision(Reason.REJECT_UNSAFE_FACT, fact)
        }

        // CANCEL 只清空适配器自己的待判手势，绝不复制、注入或消费系统取消事件。
        if (sample.actionMasked == ACTION_CANCEL) return Decision(Reason.RESET_ONLY)

        val pointersValid = when (sample.actionMasked) {
            ACTION_DOWN, ACTION_UP -> sample.pointerCount == 1
            ACTION_MOVE -> sample.pointerCount in 1..2
            ACTION_POINTER_DOWN, ACTION_POINTER_UP -> sample.pointerCount == 2
            else -> false
        }
        if (!pointersValid || sample.downTimeUptimeMs < 0 ||
            sample.eventTimeUptimeMs < sample.downTimeUptimeMs
        ) return Decision(Reason.REJECT_MALFORMED_EVENT)

        return Decision(Reason.ALLOW_METADATA_OBSERVATION)
    }
}
