package com.slideindex.app.shake

import com.slideindex.app.gesture.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeActionResolverTest {

    @Test
    fun resolveShakeAction_usesBasicActionByDefault() {
        val shake = ShakeGestureSettings()
        assertEquals(
            GestureAction.Back,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                "com.example.app",
                lockScreenActive = false,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_usesLockScreenAction() {
        val shake = ShakeGestureSettings(
            lockScreenActions = mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.Home),
        )
        assertEquals(
            GestureAction.Home,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                "com.example.app",
                lockScreenActive = true,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_lockScreenNoneDoesNotFallThrough() {
        val shake = ShakeGestureSettings(
            lockScreenActions = mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.None),
            independentAppShakeEnabled = true,
            perAppActions = mapOf(
                "com.example.app" to mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.Recents),
            ),
        )
        assertEquals(
            GestureAction.None,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                "com.example.app",
                lockScreenActive = true,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_usesLockScreenActionWhenScreenOff() {
        val shake = ShakeGestureSettings(
            lockScreenActions = mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.Flashlight),
        )
        assertEquals(
            GestureAction.Flashlight,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                foregroundPackage = null,
                lockScreenActive = true,
                screenOn = false,
            ),
        )
    }

    @Test
    fun resolveShakeAction_screenOffDoesNotFallBackToBasicAction() {
        val shake = ShakeGestureSettings(
            basicActions = mapOf(ShakeGestureType.FORWARD_FLIP to GestureAction.Flashlight),
        )
        assertEquals(
            GestureAction.None,
            resolveShakeAction(
                shake,
                ShakeGestureType.FORWARD_FLIP,
                foregroundPackage = null,
                lockScreenActive = true,
                screenOn = false,
            ),
        )
    }

    @Test
    fun resolveShakeAction_screenOffExplicitNoneStaysNone() {
        val shake = ShakeGestureSettings(
            basicActions = mapOf(ShakeGestureType.FORWARD_FLIP to GestureAction.Flashlight),
            lockScreenActions = mapOf(ShakeGestureType.FORWARD_FLIP to GestureAction.None),
        )
        assertEquals(
            GestureAction.None,
            resolveShakeAction(
                shake,
                ShakeGestureType.FORWARD_FLIP,
                foregroundPackage = null,
                lockScreenActive = true,
                screenOn = false,
            ),
        )
    }

    @Test
    fun resolveShakeAction_usesPerAppActionWhenIndependentEnabled() {
        val shake = ShakeGestureSettings(
            independentAppShakeEnabled = true,
            perAppActions = mapOf(
                "com.example.app" to mapOf(ShakeGestureType.RIGHT_FLIP to GestureAction.Home),
            ),
        )
        assertEquals(
            GestureAction.Home,
            resolveShakeAction(
                shake,
                ShakeGestureType.RIGHT_FLIP,
                "com.example.app",
                lockScreenActive = false,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_perAppNoneDoesNotFallThrough() {
        val shake = ShakeGestureSettings(
            independentAppShakeEnabled = true,
            perAppActions = mapOf(
                "com.example.app" to mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.None),
            ),
        )
        assertEquals(
            GestureAction.None,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                "com.example.app",
                lockScreenActive = false,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_perAppMissingTypeInheritsBasic() {
        val shake = ShakeGestureSettings(
            independentAppShakeEnabled = true,
            basicActions = mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.Home),
            perAppActions = mapOf(
                "com.example.app" to mapOf(ShakeGestureType.RIGHT_FLIP to GestureAction.Recents),
            ),
        )
        assertEquals(
            GestureAction.Home,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                "com.example.app",
                lockScreenActive = false,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_ignoresPerAppWhenForegroundMissing() {
        val shake = ShakeGestureSettings(
            independentAppShakeEnabled = true,
            perAppActions = mapOf(
                "com.example.app" to mapOf(ShakeGestureType.RIGHT_FLIP to GestureAction.Home),
            ),
        )
        assertEquals(
            GestureAction.Recents,
            resolveShakeAction(
                shake,
                ShakeGestureType.RIGHT_FLIP,
                foregroundPackage = null,
                lockScreenActive = false,
                screenOn = true,
            ),
        )
    }

    @Test
    fun resolveShakeAction_ignoresPerAppOnLockScreen() {
        val shake = ShakeGestureSettings(
            independentAppShakeEnabled = true,
            perAppActions = mapOf(
                "com.example.app" to mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.Home),
            ),
            lockScreenActions = mapOf(ShakeGestureType.LEFT_FLIP to GestureAction.Flashlight),
        )
        assertEquals(
            GestureAction.Flashlight,
            resolveShakeAction(
                shake,
                ShakeGestureType.LEFT_FLIP,
                "com.example.app",
                lockScreenActive = true,
                screenOn = true,
            ),
        )
    }

    @Test
    fun workMode_allowsExpectedStates() {
        assertTrue(ShakeWorkMode.ALWAYS.allows(screenOn = true, keyguardLocked = false))
        assertTrue(ShakeWorkMode.ALWAYS.allows(screenOn = true, keyguardLocked = true))
        assertTrue(ShakeWorkMode.ALWAYS.allows(screenOn = false, keyguardLocked = true))

        assertTrue(ShakeWorkMode.UNLOCKED.allows(screenOn = true, keyguardLocked = false))
        assertFalse(ShakeWorkMode.UNLOCKED.allows(screenOn = true, keyguardLocked = true))
        assertFalse(ShakeWorkMode.UNLOCKED.allows(screenOn = false, keyguardLocked = false))

        assertTrue(ShakeWorkMode.LOCKED_SCREEN_ON.allows(screenOn = true, keyguardLocked = true))
        assertFalse(ShakeWorkMode.LOCKED_SCREEN_ON.allows(screenOn = true, keyguardLocked = false))
        assertFalse(ShakeWorkMode.LOCKED_SCREEN_ON.allows(screenOn = false, keyguardLocked = true))

        assertTrue(ShakeWorkMode.SCREEN_OFF.allows(screenOn = false, keyguardLocked = true))
        assertTrue(ShakeWorkMode.SCREEN_OFF.allows(screenOn = false, keyguardLocked = false))
        assertFalse(ShakeWorkMode.SCREEN_OFF.allows(screenOn = true, keyguardLocked = true))
    }
}
