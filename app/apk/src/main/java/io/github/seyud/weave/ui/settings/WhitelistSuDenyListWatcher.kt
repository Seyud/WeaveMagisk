package io.github.seyud.weave.ui.settings

import io.github.seyud.weave.core.su.SuEvents
import io.github.seyud.weave.ui.superuser.SuperuserModeSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 白名单模式（本地 DenyList 同步）下的 su 策略 → DenyList 对账观察者。
 *
 * 以策略表为唯一真值源：任何路径产生的授权/撤销（su 弹窗、超级用户页、自动响应、
 * 开机策略恢复）都会触发 [SuEvents.policyChanged]，此处 debounce 后做一次双向对账，
 * 保证"已授权应用不在 DenyList、未授权应用在 DenyList"。
 *
 * 无进程常驻 Application 类，采用幂等的懒启动：由 MainActivity / SuRequestActivity
 * 在 onCreate 时调用 [start]。Zygisk Next 活跃或黑名单模式下为空操作。
 */
internal object WhitelistSuDenyListWatcher {

    private const val POLICY_SYNC_DEBOUNCE_MS = 500L

    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modeSync = SuperuserModeSyncCoordinator()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            watcherScope.launch {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                SuEvents.policyChanged.debounce(POLICY_SYNC_DEBOUNCE_MS).collect {
                    runCatching { reconcileNow() }
                }
            }
        }
    }

    /** 立即执行一次门控对账（也可供授权路径绕过 debounce 主动调用） */
    suspend fun reconcileNow() {
        if (!isLocalWhitelistDenyListSyncActive(modeSync)) return
        runCatching {
            WhitelistModeDenyListCoordinator().syncPoliciesToDenyList()
        }
    }
}
