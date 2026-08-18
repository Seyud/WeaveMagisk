package io.github.seyud.weave.core.base

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.os.BundleCompat
import io.github.seyud.weave.core.R
import io.github.seyud.weave.core.ktx.reflectField
import io.github.seyud.weave.core.ktx.toast
import io.github.seyud.weave.core.utils.RequestAuthentication
import io.github.seyud.weave.core.utils.RequestInstall

interface ContentResultCallback: ActivityResultCallback<Uri>, Parcelable {
    fun onActivityLaunch() {}
    fun onActivityCancel() {}
    // Make the result type explicitly non-null
    override fun onActivityResult(result: Uri)
}

interface UntrackedActivity

interface IActivityExtension {
    val extension: ActivityExtension
    fun withPermission(permission: String, callback: (Boolean) -> Unit) {
        extension.withPermission(permission, callback)
    }
    fun withAuthentication(callback: (Boolean) -> Unit) {
        extension.withAuthentication(callback)
    }
    fun getContent(type: String, callback: ContentResultCallback) {
        extension.getContent(type, callback)
    }
}

class ActivityExtension(private val activity: ComponentActivity) {

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val requestPermission = activity.registerForActivityResult(RequestPermission()) {
        permissionCallback?.invoke(it)
        permissionCallback = null
    }

    private var installCallback: ((Boolean) -> Unit)? = null
    private val requestInstall = activity.registerForActivityResult(RequestInstall()) {
        installCallback?.invoke(it)
        installCallback = null
    }

    private var authenticateCallback: ((Boolean) -> Unit)? = null
    private val requestAuthenticate = activity.registerForActivityResult(RequestAuthentication()) {
        authenticateCallback?.invoke(it)
        authenticateCallback = null
    }

    private var contentCallback: ContentResultCallback? = null
    private val getContent = activity.registerForActivityResult(GetContent()) {
        if (it != null) contentCallback?.onActivityResult(it)
        else contentCallback?.onActivityCancel()
        contentCallback = null
    }

    fun onCreate(savedInstanceState: Bundle?) {
        contentCallback = savedInstanceState?.let {
            BundleCompat.getParcelable(it, CONTENT_CALLBACK_KEY, ContentResultCallback::class.java)
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        contentCallback?.let {
            outState.putParcelable(CONTENT_CALLBACK_KEY, it)
        }
    }

    fun withPermission(permission: String, callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            permission == WRITE_EXTERNAL_STORAGE) {
            // We do not need external rw on R+
            callback(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            permission == POST_NOTIFICATIONS) {
            // All apps have notification permissions before T
            callback(true)
            return
        }
        if (permission == REQUEST_INSTALL_PACKAGES) {
            installCallback = callback
            requestInstall.launch(Unit)
        } else {
            permissionCallback = callback
            requestPermission.launch(permission)
        }
    }

    /**
     * 启动设备身份验证
     * 如果设备设置了锁屏凭据，会弹出系统身份验证对话框
     * 如果设备未设置锁屏凭据，直接回调 true
     *
     * @param callback 验证结果回调
     */
    fun withAuthentication(callback: (Boolean) -> Unit) {
        val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
        if (keyguardManager.isDeviceSecure) {
            authenticateCallback = callback
            requestAuthenticate.launch(Unit)
        } else {
            // 设备未设置锁屏凭据，直接返回验证成功
            callback(true)
        }
    }

    fun getContent(type: String, callback: ContentResultCallback) {
        contentCallback = callback
        try {
            getContent.launch(type)
            callback.onActivityLaunch()
        } catch (e: ActivityNotFoundException) {
            activity.toast(R.string.app_not_found, Toast.LENGTH_SHORT)
        }
    }

    companion object {
        private const val CONTENT_CALLBACK_KEY = "content_callback"
    }
}

val Activity.launchPackage: String? get() {
    return if (Build.VERSION.SDK_INT >= 34) {
        launchedFromPackage
    } else {
        Activity::class.java.reflectField("mReferrer").get(this) as String?
    }
}

fun Activity.relaunch() {
    startActivity(Intent(intent).setFlags(0))
    finish()
}
