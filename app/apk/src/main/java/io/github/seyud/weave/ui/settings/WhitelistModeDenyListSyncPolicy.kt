package io.github.seyud.weave.ui.settings

import android.content.Intent
import io.github.seyud.weave.core.Config
import io.github.seyud.weave.ui.superuser.SuperuserModeSyncCoordinator
import io.github.seyud.weave.ui.superuser.isWhitelistMode
import io.github.seyud.weave.ui.superuser.normalizeSuperuserListMode

internal fun shouldUseLocalWhitelistDenyListSync(
    currentMode: Int,
    zygiskNextActive: Boolean,
): Boolean = isWhitelistMode(currentMode) && !zygiskNextActive

/** 当前是否处于"本地 DenyList 模拟白名单"状态（白名单模式且 Zygisk Next 未运行） */
internal suspend fun isLocalWhitelistDenyListSyncActive(
    modeSync: SuperuserModeSyncCoordinator,
): Boolean = shouldUseLocalWhitelistDenyListSync(
    currentMode = normalizeSuperuserListMode(Config.suListMode),
    zygiskNextActive = modeSync.isZygiskNextActive(),
)

internal fun shouldQueuePassiveWhitelistReconcile(
    hasPendingLocalSync: Boolean,
    currentMode: Int,
    zygiskNextActive: Boolean,
): Boolean = !hasPendingLocalSync && shouldUseLocalWhitelistDenyListSync(currentMode, zygiskNextActive)

internal fun resolveAutoSyncPackageName(
    action: String?,
    packageName: String?,
    replacing: Boolean,
): String? {
    val resolvedPackageName = packageName ?: return null
    return when (action) {
        Intent.ACTION_PACKAGE_ADDED -> if (replacing) null else resolvedPackageName
        Intent.ACTION_PACKAGE_REPLACED -> resolvedPackageName
        else -> null
    }
}

internal fun resolveAutoSyncPackageName(intent: Intent): String? =
    resolveAutoSyncPackageName(
        action = intent.action,
        packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ?: intent.data?.schemeSpecificPart,
        replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
    )
