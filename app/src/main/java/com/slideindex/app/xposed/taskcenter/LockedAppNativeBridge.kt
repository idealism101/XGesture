package com.slideindex.app.xposed.taskcenter

import android.content.Context
import android.content.pm.PackageManager

/**
 * 应用锁定的原生写入桥：miui.process.ProcessManager.updateApplicationLockedState(pkg, userId, locked)。
 *
 * 设备实测（OS1）：这是系统自己的锁定入口（桌面 / 设置页走同一条），写完 system_server 会反向
 * 通知 launcher 刷新 Task.isLocked，通知链完整。反射即可，无需手写 Binder transaction。
 * 若类 / 方法不存在则降级（由 LockedAppStore 退回直接写 Settings）。
 */
object LockedAppNativeBridge {
    private const val PROCESS_MANAGER = "miui.process.ProcessManager"

    /**
     * @return 是否成功走到原生接口。false 表示需要由调用方降级。
     */
    fun setLocked(packageName: String, userId: Int, shouldLock: Boolean): Boolean {
        return try {
            val cls = Class.forName(PROCESS_MANAGER)
            val method = cls.getMethod("updateApplicationLockedState", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(null, packageName, userId, shouldLock)
            TaskCenterLog.i("LockedAppNativeBridge setLocked pkg=$packageName userId=$userId locked=$shouldLock")
            true
        } catch (error: Throwable) {
            TaskCenterLog.i("LockedAppNativeBridge unavailable err=${error.javaClass.simpleName}")
            false
        }
    }

    /** 该 ROM 是否提供原生锁定接口（用于功能自检）。 */
    fun isAvailable(): Boolean = try {
        Class.forName(PROCESS_MANAGER).getMethod(
            "updateApplicationLockedState",
            String::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ) != null
    } catch (_: Throwable) {
        false
    }
}
