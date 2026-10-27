package com.slideindex.app.settings

data class SettingsBackupPreview(
    val formatVersion: Int,
    val exportedAtEpochMs: Long,
    val appVersionName: String,
    val totalPreferencesCount: Int,
    val domains: Set<SettingsDomain>,
    /** 备份里每个模块的内容概况，用于「选择要恢复的模块」。 */
    val moduleSummaries: Map<SettingsModule, SettingsBackupModuleSummary> = emptyMap(),
    val hasOtpRecords: Boolean,
    val hasNotificationHistory: Boolean,
    val hasNotificationFilterRules: Boolean = false,
    val hasNotificationFilterPreferences: Boolean = false,
    val hasOtpAutoFillStats: Boolean = false,
    val hasShellOutputHistory: Boolean = false,
    val hasSearchPanelHistory: Boolean = false,
    val hasClipboardDirectory: Boolean = false,
    val hasShareImageOcrHistoryDirectory: Boolean = false,
    val hasCloudLlmConfig: Boolean = false,
    val importDiff: SettingsBackupImportDiff = SettingsBackupImportDiff(emptyList(), emptyList()),
)

/**
 * 一个模块在备份里的内容概况。
 *
 * 把「设置项」与「历史/文件」分开计数：前者是用户能感知的开关，后者是数据，
 * 混成同一个数字会让「Shell 命令 · 3 项」看起来像三个开关。
 */
data class SettingsBackupModuleSummary(
    /** 该模块的偏好键数量。 */
    val settingsCount: Int = 0,
    /** 是否带该模块的历史/记录分区（验证码记录、通知历史、Shell 输出历史等）。 */
    val hasHistory: Boolean = false,
    /** 是否带该模块的文件目录（图标、剪贴板历史、收纳等）。 */
    val hasFiles: Boolean = false,
)

enum class SettingsDomain {
    EDGE_GESTURES,
    SHAKE_GESTURES,
    MESSAGE_DANMAKU,
    OTP_AUTO_INPUT,
    FLOATING_POINTER,
    WIDGET_PANEL,
    QUICK_LAUNCHER,
    FREE_WINDOW,
    GENERAL,
}

fun mapPreferenceKeyToDomain(key: String): SettingsDomain {
    return when {
        key.startsWith("edge_") || key.startsWith("left_") || key.startsWith("right_") || key.startsWith("trigger_") || key.startsWith("gesture_") || key == "intercept_system_back_gesture" || key == "limit_max_intercept_length" || key == "short_swipe_distance_dp" || key == "long_swipe_distance_dp" || key == "animation_styles" || key == "index_height_fraction" || key == "apps_per_row" || key == "panel_opacity" -> SettingsDomain.EDGE_GESTURES
        key.startsWith("shake_") || key.startsWith("hold_shake_") || key.startsWith("lock_screen_shake_") ||
            key.startsWith("independent_app_shake_") || key.startsWith("face_down_") -> SettingsDomain.SHAKE_GESTURES
        key.startsWith("message_") -> SettingsDomain.MESSAGE_DANMAKU
        key.startsWith("otp_") -> SettingsDomain.OTP_AUTO_INPUT
        key.startsWith("floating_pointer_") -> SettingsDomain.FLOATING_POINTER
        key.startsWith("widget_panel_") -> SettingsDomain.WIDGET_PANEL
        key.startsWith("quick_launcher") -> SettingsDomain.QUICK_LAUNCHER
        key.startsWith("free_window_") || key.startsWith("app_launch_policy") -> SettingsDomain.FREE_WINDOW
        else -> SettingsDomain.GENERAL
    }
}
