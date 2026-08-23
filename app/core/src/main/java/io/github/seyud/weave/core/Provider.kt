package io.github.seyud.weave.core

import android.os.Bundle
import io.github.seyud.weave.core.base.BaseProvider
import io.github.seyud.weave.core.su.SuCallbackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Provider : BaseProvider() {

    // Fire-and-forget scope: su log/notify must NEVER run synchronously on the
    // binder thread. Blocking here stalls the caller's `content call` process
    // (forked per su session by the daemon); when this app is frozen/killed
    // under memory pressure those callers pile up as ~150MB orphaned
    // app_process zombies that even SIGTERM cannot reap.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            SuCallbackHandler.LOG, SuCallbackHandler.NOTIFY -> {
                if (extras != null) {
                    // Materialize the parcel-backed Bundle NOW: the underlying
                    // parcel is recycled once call() returns, touching lazy keys
                    // later on a worker thread would crash/hang.
                    val safe = Bundle()
                    safe.putAll(extras)
                    val ctx = context!!.applicationContext
                    scope.launch { SuCallbackHandler.run(ctx, method, safe) }
                }
                Bundle.EMPTY
            }
            else -> Bundle.EMPTY
        }
    }
}
