package com.slideindex.app.xposed.taskcenter

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.HashSet

/**
 * 应用锁定状态 —— 直读系统权威源头，不自建状态。
 *
 * 原生接口（设备实测）：MIUI 任务锁定是「应用级锁定」，权威状态存 Settings.System 的
 * `locked_apps` 键（String，JSON 数组），由 system_server 的 ProcessManagerService / ProcessPolicy 维护。
 * 写入首选 miui.process.ProcessManager.updateApplicationLockedState（见 LockedAppNativeBridge），
 * 通知链完整；该不可用时降级直接写 Settings.System（能改状态，但 launcher 缓存不刷新）。
 */
class LockedAppStore(private val context: Context, private val userId: Int) {
    private val resolver: ContentResolver = context.contentResolver
    private val mainThread = Handler(Looper.getMainLooper())

    @Volatile private var locked: Set<String> = Collections.emptySet()
    private var observer: ContentObserver? = null
    private var onChanged: Runnable? = null
    @Volatile private var released = false

    fun load(): Set<String> {
        val result = HashSet<String>()
        try {
            val raw = Settings.System.getString(resolver, KEY_LOCKED_APPS) ?: return result
            val groups = JSONArray(raw)
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                if (group.optInt(FIELD_USER, Int.MIN_VALUE) != userId) continue
                val packages = group.optJSONArray(FIELD_PACKAGES) ?: continue
                for (j in 0 until packages.length()) {
                    val name = packages.optString(j, null)
                    if (!name.isNullOrEmpty()) result.add(name)
                }
            }
        } catch (error: Throwable) {
            TaskCenterLog.i("LockedAppStore load failed err=${error.javaClass.simpleName}")
        }
        return result
    }

    fun snapshot(): Set<String> = locked

    fun isLocked(packageName: String): Boolean = !packageName.isNullOrEmpty() && locked.contains(packageName)

    fun refresh(): Boolean {
        val latest = load()
        if (latest == locked) return false
        locked = latest
        TaskCenterLog.i("LockedAppStore refresh userId=$userId count=${latest.size} pkgs=$latest")
        return true
    }

    fun setLocked(packageName: String, shouldLock: Boolean): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            val current = HashSet(locked)
            val changed = if (shouldLock) current.add(packageName) else current.remove(packageName)
            if (!changed) return true
            val nativeOk = LockedAppNativeBridge.setLocked(packageName, userId, shouldLock)
            var ok = nativeOk
            if (!nativeOk) {
                val raw = rewrite(current)
                ok = Settings.System.putString(resolver, KEY_LOCKED_APPS, raw)
                TaskCenterLog.i("LockedAppStore setLocked fallback-to-settings pkg=$packageName locked=$shouldLock ok=$ok")
            }
            if (ok) {
                locked = current
                TaskCenterLog.i("LockedAppStore setLocked pkg=$packageName locked=$shouldLock total=${current.size} via=${if (nativeOk) "ProcessManager" else "Settings"}")
            }
            ok
        } catch (error: Throwable) {
            TaskCenterLog.e("LockedAppStore setLocked failed pkg=$packageName", error)
            false
        }
    }

    private fun rewrite(currentUser: Set<String>): String {
        val groups = JSONArray()
        val raw = Settings.System.getString(resolver, KEY_LOCKED_APPS)
        var replaced = false
        if (!raw.isNullOrEmpty()) {
            val existing = JSONArray(raw)
            for (i in 0 until existing.length()) {
                val group = existing.optJSONObject(i) ?: continue
                if (group.optInt(FIELD_USER, Int.MIN_VALUE) == userId) {
                    groups.put(groupFor(currentUser))
                    replaced = true
                } else {
                    groups.put(group)
                }
            }
        }
        if (!replaced) groups.put(groupFor(currentUser))
        return groups.toString()
    }

    private fun groupFor(packages: Set<String>): JSONObject {
        val group = JSONObject()
        group.put(FIELD_USER, userId)
        val array = JSONArray()
        for (name in packages) array.put(name)
        group.put(FIELD_PACKAGES, array)
        return group
    }

    fun startObserving(listener: Runnable) {
        stopObserving()
        onChanged = listener
        observer = object : ContentObserver(mainThread) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (released) return
                if (refresh() && onChanged != null) onChanged!!.run()
            }
        }
        try {
            resolver.registerContentObserver(Settings.System.getUriFor(KEY_LOCKED_APPS), false, observer!!)
            refresh()
        } catch (error: Throwable) {
            TaskCenterLog.i("LockedAppStore observe failed err=${error.javaClass.simpleName}")
        }
    }

    fun stopObserving() {
        observer?.let { obs ->
            try {
                resolver.unregisterContentObserver(obs)
            } catch (_: Throwable) {
            }
        }
        observer = null
    }

    fun release() {
        released = true
        stopObserving()
        onChanged = null
        locked = Collections.emptySet()
    }

    companion object {
        const val DEFAULT_USER_ID = -100
        private const val KEY_LOCKED_APPS = "locked_apps"
        private const val FIELD_USER = "u"
        private const val FIELD_PACKAGES = "pkgs"

        fun currentUserId(): Int = try {
            val handle = Class.forName("android.os.UserHandle")
            val result = handle.getMethod("myUserId").invoke(null)
            if (result is Int) result else 0
        } catch (_: Throwable) {
            0
        }
    }
}
