package com.slideindex.app.shake

import com.slideindex.app.gesture.GestureAction

fun resolveShakeAction(
    shake: ShakeGestureSettings,
    type: ShakeGestureType,
    foregroundPackage: String?,
    lockScreenActive: Boolean,
    screenOn: Boolean,
): GestureAction {
    // 锁屏（亮屏或熄屏）只认锁屏动作表：显式“无”就是关闭，不回退到基础动作。
    if (!screenOn || lockScreenActive) {
        return shake.lockScreenActions[type] ?: GestureAction.None
    }

    if (shake.independentAppShakeEnabled && foregroundPackage != null) {
        val perApp = shake.perAppActions[foregroundPackage]
        if (perApp != null && perApp.containsKey(type)) {
            return perApp.getValue(type)
        }
    }

    return shake.actionFor(type)
}
