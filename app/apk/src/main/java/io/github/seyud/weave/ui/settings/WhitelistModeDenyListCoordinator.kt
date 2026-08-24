package io.github.seyud.weave.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build
import androidx.core.os.ProcessCompat
import com.topjohnwu.superuser.Shell
import io.github.seyud.weave.core.AppContext
import io.github.seyud.weave.core.Config
import io.github.seyud.weave.core.data.magiskdb.PolicyDao
import io.github.seyud.weave.core.di.ServiceLocator
import io.github.seyud.weave.core.ktx.concurrentMap
import io.github.seyud.weave.core.model.su.SuPolicy
import io.github.seyud.weave.core.utils.InstalledPackageLoader
import io.github.seyud.weave.ui.deny.CmdlineListItem
import io.github.seyud.weave.ui.deny.ISOLATED_MAGIC
import io.github.seyud.weave.ui.deny.buildDenyListAppInfo
import io.github.seyud.weave.ui.deny.fetchProcesses
import io.github.seyud.weave.ui.superuser.isInstalledPackage
import io.github.seyud.weave.ui.superuser.isSystemApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DenyListEntryRecord(
    val packageName: String,
    val processName: String = packageName,
) {
    fun rawLine(): String = if (processName == packageName) packageName else "$packageName|$processName"

    companion object {
        fun parse(rawLine: String): DenyListEntryRecord? {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            val split = trimmed.split(Regex("\\|"), 2)
            val packageName = split[0].trim()
            if (packageName.isEmpty()) {
                return null
            }
            val processName = split.getOrElse(1) { packageName }.trim().ifEmpty { packageName }
            return DenyListEntryRecord(
                packageName = packageName,
                processName = processName,
            )
        }
    }
}

internal data class BlacklistDenyListSnapshot(
    val enabled: Boolean,
    val entries: List<DenyListEntryRecord>,
)

internal data class WhitelistModeDenyListResult(
    val success: Boolean,
    val denyListEnabled: Boolean,
)

/** 查询当前拥有 su 授权（policy >= ALLOW 且未过期）的 uid 集合 */
internal fun interface SuAllowedUidProvider {
    suspend fun allowedUids(): Set<Int>
}

internal class PolicyDaoSuAllowedUidProvider(
    private val policyDao: PolicyDao = ServiceLocator.policyDB,
) : SuAllowedUidProvider {
    override suspend fun allowedUids(): Set<Int> = runCatching {
        policyDao.fetchAll()
            .asSequence()
            // remain == 0 表示 until=0（永久有效），remain > 0 表示尚未到期的剩余秒数；
            // remain < 0 的行 daemon 不会命中，视为无效
            .filter { it.policy >= SuPolicy.ALLOW && it.remain >= 0 }
            .map { it.uid }
            .toSet()
    }.getOrDefault(emptySet())
}

/** 包名 ↔ uid 双向解析 */
internal interface WhitelistPackageResolver {
    suspend fun uidOf(packageName: String): Int?
    suspend fun packagesForUid(uid: Int): List<String>
}

internal class PackageManagerWhitelistPackageResolver(
    private val packageManager: PackageManager = AppContext.packageManager,
) : WhitelistPackageResolver {

    override suspend fun uidOf(packageName: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(MATCH_UNINSTALLED_PACKAGES.toLong()),
                ).uid
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, MATCH_UNINSTALLED_PACKAGES).uid
            }
        }.getOrNull()
    }

    override suspend fun packagesForUid(uid: Int): List<String> = withContext(Dispatchers.IO) {
        packageManager.getPackagesForUid(uid)?.toList().orEmpty()
    }
}

internal interface BlacklistDenyListSnapshotStore {
    fun get(): BlacklistDenyListSnapshot?
    fun set(snapshot: BlacklistDenyListSnapshot?)
}

private object ConfigBlacklistDenyListSnapshotStore : BlacklistDenyListSnapshotStore {
    override fun get(): BlacklistDenyListSnapshot? {
        if (!Config.suListModeDenyListSnapshotValid) {
            return null
        }
        return BlacklistDenyListSnapshot(
            enabled = Config.suListModeDenyListSnapshotEnabled,
            entries = Config.suListModeDenyListSnapshot
                .lineSequence()
                .mapNotNull(DenyListEntryRecord::parse)
                .toList(),
        )
    }

    override fun set(snapshot: BlacklistDenyListSnapshot?) {
        if (snapshot == null) {
            Config.suListModeDenyListSnapshot = ""
            Config.suListModeDenyListSnapshotEnabled = false
            Config.suListModeDenyListSnapshotValid = false
            return
        }
        Config.suListModeDenyListSnapshot = snapshot.entries.joinToString(separator = "\n") { it.rawLine() }
        Config.suListModeDenyListSnapshotEnabled = snapshot.enabled
        Config.suListModeDenyListSnapshotValid = true
    }
}

internal data class DenyListShellResult(
    val code: Int,
    val out: List<String>,
) {
    val isSuccess: Boolean
        get() = code == 0
}

internal interface DenyListShellRunner {
    fun run(command: String): DenyListShellResult
    fun runAll(commands: List<String>): DenyListShellResult
}

private object LibSuDenyListShellRunner : DenyListShellRunner {
    override fun run(command: String): DenyListShellResult {
        val result = Shell.cmd(command).exec()
        return DenyListShellResult(
            code = result.code,
            out = result.out,
        )
    }

    override fun runAll(commands: List<String>): DenyListShellResult {
        if (commands.isEmpty()) {
            return DenyListShellResult(code = 0, out = emptyList())
        }
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        return DenyListShellResult(
            code = result.code,
            out = result.out,
        )
    }
}

internal interface OrdinaryDenyListEntryProvider {
    suspend fun loadEntries(currentEntries: List<DenyListEntryRecord>): List<DenyListEntryRecord>
    suspend fun loadEntriesForPackage(
        packageName: String,
        currentEntries: List<DenyListEntryRecord>,
    ): List<DenyListEntryRecord>
}

internal class PackageManagerOrdinaryDenyListEntryProvider(
    private val packageManager: PackageManager = AppContext.packageManager,
) : OrdinaryDenyListEntryProvider {

    override suspend fun loadEntries(currentEntries: List<DenyListEntryRecord>): List<DenyListEntryRecord> =
        withContext(Dispatchers.IO) {
            val denyEntries = currentEntries.map { CmdlineListItem(it.rawLine()) }

            InstalledPackageLoader.loadApplications(
                flags = MATCH_UNINSTALLED_PACKAGES,
                packageManager = packageManager,
            ).items
                .asFlow()
                .filter { it.packageName != AppContext.packageName }
                .filter { isInstalledPackage(it) }
                .filter { ProcessCompat.isApplicationUid(it.uid) }
                .filterNot { isSystemApp(it) }
                .concurrentMap { appInfo -> buildEntriesForApplication(appInfo, denyEntries) }
                .toCollection(ArrayList<List<DenyListEntryRecord>>())
                .asSequence()
                .flatten()
                .distinct()
                .sortedWith(compareBy<DenyListEntryRecord>({ it.packageName }, { it.processName }))
                .toList()
        }

    override suspend fun loadEntriesForPackage(
        packageName: String,
        currentEntries: List<DenyListEntryRecord>,
    ): List<DenyListEntryRecord> = withContext(Dispatchers.IO) {
        val denyEntries = currentEntries.map { CmdlineListItem(it.rawLine()) }
        val applicationInfo = InstalledPackageLoader.loadApplications(
            flags = MATCH_UNINSTALLED_PACKAGES,
            packageManager = packageManager,
        ).items.firstOrNull { it.packageName == packageName }
            ?: return@withContext emptyList()
        if (!isOrdinaryApplication(applicationInfo)) {
            return@withContext emptyList()
        }
        buildEntriesForApplication(applicationInfo, denyEntries)
    }

    private fun isOrdinaryApplication(applicationInfo: ApplicationInfo): Boolean =
        applicationInfo.packageName != AppContext.packageName &&
            isInstalledPackage(applicationInfo) &&
            ProcessCompat.isApplicationUid(applicationInfo.uid) &&
            !isSystemApp(applicationInfo)

    private fun buildEntriesForApplication(
        applicationInfo: ApplicationInfo,
        denyEntries: List<CmdlineListItem>,
    ): List<DenyListEntryRecord> {
        val app = buildDenyListAppInfo(applicationInfo, packageManager, denyEntries)
        return buildList<DenyListEntryRecord> {
            add(DenyListEntryRecord(app.packageName))
            fetchProcesses(packageManager, app, denyEntries)
                .asSequence()
                .filter { it.defaultSelection }
                .map { DenyListEntryRecord(it.packageName, it.name) }
                .filterNot { it.processName == it.packageName }
                .forEach(::add)
        }
    }
}

internal class WhitelistModeDenyListCoordinator(
    private val shellRunner: DenyListShellRunner = LibSuDenyListShellRunner,
    private val entryProvider: OrdinaryDenyListEntryProvider = PackageManagerOrdinaryDenyListEntryProvider(),
    private val snapshotStore: BlacklistDenyListSnapshotStore = ConfigBlacklistDenyListSnapshotStore,
    private val allowedUidProvider: SuAllowedUidProvider = PolicyDaoSuAllowedUidProvider(),
    private val packageResolver: WhitelistPackageResolver = PackageManagerWhitelistPackageResolver(),
) {

    internal companion object {
        // 进程内全局串行化：coordinator 可能被 SettingsViewModel / SuperuserViewModel /
        // 安装广播接收器各自实例化，共享锁避免并发读-改-写竞争
        private val MUTEX = Mutex()
    }

    /**
     * 进入白名单模式：快照当前黑名单状态、开启 DenyList，并把目标条目与 su 授权状态双向对账：
     * - 补齐所有普通应用的排除条目
     * - 移除已授权（policy >= ALLOW）应用的全部排除条目，否则其进程被 unmount 拿不到 root
     */
    suspend fun applyWhitelistMode(): WhitelistModeDenyListResult = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val currentEntries = listEntries()
            val blacklistSnapshot = snapshotStore.get() ?: BlacklistDenyListSnapshot(
                enabled = isDenyListEnabled(),
                entries = currentEntries,
            ).also(snapshotStore::set)

            if (!blacklistSnapshot.enabled && !setDenyListEnabled(true)) {
                snapshotStore.set(null)
                return@withLock failureResult(blacklistSnapshot.enabled)
            }

            val (entriesToAdd, entriesToRemove) = computePolicySyncDiff(currentEntries)

            if (entriesToRemove.isNotEmpty() && !clearEntries(entriesToRemove)) {
                val rollback = restoreSnapshot(blacklistSnapshot)
                if (rollback.success) {
                    snapshotStore.set(null)
                }
                return@withLock rollback.copy(success = false)
            }

            if (!addEntries(entriesToAdd)) {
                val rollback = restoreSnapshot(blacklistSnapshot)
                if (rollback.success) {
                    snapshotStore.set(null)
                }
                return@withLock rollback.copy(success = false)
            }

            WhitelistModeDenyListResult(
                success = true,
                denyListEnabled = true,
            )
        }
    }

    suspend fun restoreBlacklistMode(): WhitelistModeDenyListResult = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val snapshot = snapshotStore.get()
            if (snapshot != null) {
                val result = restoreSnapshot(snapshot)
                if (result.success) {
                    snapshotStore.set(null)
                }
                return@withLock result
            }

            // Snapshot lost (e.g. app update/data migration cleared NO_MIGRATION keys).
            // Fallback: clear all DenyList entries that were added during whitelist mode.
            val currentEntries = listEntries()
            if (currentEntries.isNotEmpty()) {
                clearEntries(currentEntries)
            }
            if (!setDenyListEnabled(false)) {
                return@withLock failureResult(isDenyListEnabled())
            }
            WhitelistModeDenyListResult(
                success = true,
                denyListEnabled = false,
            )
        }
    }

    /**
     * 白名单模式下的策略表 → DenyList 增量对账（不动开关状态与快照），
     * 供策略变更事件触发，覆盖所有授权路径（弹窗/页面/自动响应/开机恢复）
     */
    suspend fun syncPoliciesToDenyList(): WhitelistModeDenyListResult = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val currentEntries = listEntries()
            val (entriesToAdd, entriesToRemove) = computePolicySyncDiff(currentEntries)
            applyDiff(entriesToAdd, entriesToRemove)
        }
    }

    /** 新装应用自动入列；已授权 uid（如共享 UID 场景）跳过，避免把同 uid 已授权应用搞没 root */
    suspend fun ensurePackageSynced(packageName: String): WhitelistModeDenyListResult = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            ensurePackageSyncedLocked(packageName)
        }
    }

    /** 授权后按 uid 移出 DenyList：解析该 uid 名下全部包名逐一清理 */
    suspend fun removePackagesForUid(uid: Int): WhitelistModeDenyListResult = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var result = WhitelistModeDenyListResult(success = true, denyListEnabled = isDenyListEnabled())
            for (packageName in packageResolver.packagesForUid(uid)) {
                val packageResult = removePackageSyncedLocked(packageName)
                result = if (!packageResult.success) packageResult else result
            }
            result
        }
    }

    /** 撤销授权后按 uid 加回 DenyList */
    suspend fun ensurePackagesSyncedForUid(uid: Int): WhitelistModeDenyListResult = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var result = WhitelistModeDenyListResult(success = true, denyListEnabled = isDenyListEnabled())
            for (packageName in packageResolver.packagesForUid(uid)) {
                val packageResult = ensurePackageSyncedLocked(packageName)
                result = if (!packageResult.success) packageResult else result
            }
            // 解析不到包名（应用可能刚被卸载）时无事可做，直接返回当前状态
            result
        }
    }

    /** 该包名是否仍存在 DenyList 条目（用于白名单模式下验证授权是否真实生效） */
    suspend fun hasEntriesForPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            listEntries().any { it.packageName == packageName }
        }
    }

    /** 计算策略表驱动的增量：需要补齐的条目 + 需要移除的已授权条目 */
    private suspend fun computePolicySyncDiff(
        currentEntries: List<DenyListEntryRecord>,
    ): Pair<List<DenyListEntryRecord>, List<DenyListEntryRecord>> {
        val allowedUids = allowedUidProvider.allowedUids()
        if (allowedUids.isEmpty()) {
            val targetEntries = entryProvider.loadEntries(currentEntries)
            val existingEntries = currentEntries.toMutableSet()
            val entriesToAdd = targetEntries.filter(existingEntries::add)
            return entriesToAdd to emptyList()
        }

        // 包名 → uid 解析缓存
        val uidCache = HashMap<String, Int?>()
        suspend fun resolveUid(packageName: String): Int? =
            uidCache.getOrPut(packageName) { packageResolver.uidOf(packageName) }

        suspend fun isGrantedEntry(entry: DenyListEntryRecord): Boolean {
            // isolated 条目无法可靠归属到包，保持原样
            if (entry.packageName == ISOLATED_MAGIC) return false
            val uid = resolveUid(entry.packageName) ?: return false
            return uid in allowedUids
        }

        val targetEntries = entryProvider.loadEntries(currentEntries).filterNot { isGrantedEntry(it) }
        val existingEntries = currentEntries.toMutableSet()
        val entriesToAdd = targetEntries.filter(existingEntries::add)
        val entriesToRemove = currentEntries.filter { isGrantedEntry(it) }
        return entriesToAdd to entriesToRemove
    }

    private suspend fun applyDiff(
        entriesToAdd: List<DenyListEntryRecord>,
        entriesToRemove: List<DenyListEntryRecord>,
    ): WhitelistModeDenyListResult {
        if (entriesToRemove.isNotEmpty() && !clearEntries(entriesToRemove)) {
            return failureResult(isDenyListEnabled())
        }
        if (entriesToAdd.isNotEmpty() && !addEntries(entriesToAdd)) {
            return failureResult(isDenyListEnabled())
        }
        return WhitelistModeDenyListResult(
            success = true,
            denyListEnabled = isDenyListEnabled(),
        )
    }

    private suspend fun ensurePackageSyncedLocked(packageName: String): WhitelistModeDenyListResult {
        val allowedUids = allowedUidProvider.allowedUids()
        val appUid = packageResolver.uidOf(packageName)
        if (appUid != null && appUid in allowedUids) {
            return WhitelistModeDenyListResult(
                success = true,
                denyListEnabled = isDenyListEnabled(),
            )
        }

        val currentEntries = listEntries()
        val targetEntries = entryProvider.loadEntriesForPackage(packageName, currentEntries)
        if (targetEntries.isEmpty()) {
            return WhitelistModeDenyListResult(
                success = true,
                denyListEnabled = isDenyListEnabled(),
            )
        }

        val wasEnabled = isDenyListEnabled()
        if (!wasEnabled && !setDenyListEnabled(true)) {
            return failureResult(wasEnabled)
        }

        val existingEntries = currentEntries.toMutableSet()
        val entriesToAdd = targetEntries.filter(existingEntries::add)
        if (entriesToAdd.isNotEmpty() && !addEntries(entriesToAdd)) {
            if (!wasEnabled) {
                setDenyListEnabled(false)
            }
            return failureResult(wasEnabled)
        }

        return WhitelistModeDenyListResult(
            success = true,
            denyListEnabled = true,
        )
    }

    /** 把某包在 DenyList 中的现存条目清掉（含 isolated 行），授权后恢复可见性 */
    private suspend fun removePackageSyncedLocked(packageName: String): WhitelistModeDenyListResult {
        val currentEntries = listEntries()
        val ownedRows = ownedPackageRows(packageName, currentEntries)
        if (ownedRows.isEmpty()) {
            return WhitelistModeDenyListResult(
                success = true,
                denyListEnabled = isDenyListEnabled(),
            )
        }
        if (!clearEntries(ownedRows)) {
            return failureResult(isDenyListEnabled())
        }
        return WhitelistModeDenyListResult(
            success = true,
            denyListEnabled = isDenyListEnabled(),
        )
    }

    /**
     * 计算某包"应当被清理"的现存条目：
     * - 包名主行及其全部进程行由一条 `rm PKG` 覆盖
     * - isolated 行挂在 "isolated" 包名下，需逐行枚举（取自 entryProvider 对该包的标准计算与现表交集）
     */
    private suspend fun ownedPackageRows(
        packageName: String,
        currentEntries: List<DenyListEntryRecord>,
    ): List<DenyListEntryRecord> {
        val directRows = currentEntries.filter { it.packageName == packageName }
        if (directRows.isEmpty()) {
            return emptyList()
        }
        val candidateIsolatedRows = entryProvider
            .loadEntriesForPackage(packageName, currentEntries)
            .filter { it.packageName == ISOLATED_MAGIC }
        val currentRawLines = currentEntries.mapTo(HashSet()) { it.rawLine() }
        val isolatedRows = candidateIsolatedRows.filter { it.rawLine() in currentRawLines }
        return directRows + isolatedRows
    }

    private fun restoreSnapshot(snapshot: BlacklistDenyListSnapshot): WhitelistModeDenyListResult {
        if (!clearEntries(listEntries())) {
            return failureResult(snapshot.enabled)
        }
        if (!addEntries(snapshot.entries)) {
            return failureResult(snapshot.enabled)
        }
        if (!setDenyListEnabled(snapshot.enabled)) {
            return failureResult(snapshot.enabled)
        }
        return WhitelistModeDenyListResult(
            success = true,
            denyListEnabled = snapshot.enabled,
        )
    }

    private fun listEntries(): List<DenyListEntryRecord> =
        shellRunner.run("magisk --denylist ls").out.mapNotNull(DenyListEntryRecord::parse)

    private fun clearEntries(entries: List<DenyListEntryRecord>): Boolean {
        val packageCommands = entries.asSequence()
            .map { it.packageName }
            .filter { it != ISOLATED_MAGIC }
            .distinct()
            .map { packageName -> "magisk --denylist rm ${shellQuote(packageName)}" }
            .toList()
        if (!shellRunner.runAll(packageCommands).isSuccess) {
            return false
        }

        val isolatedCommands = entries.asSequence()
            .filter { it.packageName == ISOLATED_MAGIC }
            .distinct()
            .map { entry ->
                "magisk --denylist rm ${shellQuote(entry.packageName)} ${shellQuote(entry.processName)}"
            }
            .toList()
        return shellRunner.runAll(isolatedCommands).isSuccess
    }

    private fun addEntries(entries: List<DenyListEntryRecord>): Boolean =
        shellRunner.runAll(
            entries.map { entry ->
                "magisk --denylist add ${shellQuote(entry.packageName)} ${shellQuote(entry.processName)}"
            },
        ).isSuccess

    private fun setDenyListEnabled(enabled: Boolean): Boolean {
        val command = if (enabled) "enable" else "disable"
        return shellRunner.run("magisk --denylist $command").isSuccess
    }

    private fun isDenyListEnabled(): Boolean =
        shellRunner.run("magisk --denylist status").isSuccess

    private fun failureResult(fallbackEnabled: Boolean): WhitelistModeDenyListResult =
        WhitelistModeDenyListResult(
            success = false,
            denyListEnabled = runCatching(::isDenyListEnabled).getOrDefault(fallbackEnabled),
        )

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
