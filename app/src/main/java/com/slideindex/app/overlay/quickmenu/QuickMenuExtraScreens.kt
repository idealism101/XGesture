package com.slideindex.app.overlay.quickmenu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.first
import com.slideindex.app.di.OverlayDependencyAccess
import com.slideindex.app.freezer.FreezerOverlayWindow
import com.slideindex.app.overlay.FloatBallStashPanel
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.overlay.WidgetPopupOverlayWindow
import com.slideindex.app.overlay.filebrowser.FileBrowserOverlayWindow
import com.slideindex.app.pagescroll.PageScrollActionRunner
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.overlay.ForegroundActivityInspectorOverlayWindow
import com.slideindex.app.overlay.PinToScreenDialog
import com.slideindex.app.overlay.StashPanelInitialTab
import com.slideindex.app.overlay.searchpanel.SearchPanelOverlayWindow
import com.slideindex.app.service.ShellCommandPanelTrampoline

/**
 * 「快捷菜单」的**预置入口**执行函数（前台活动探测、Shell 命令面板、冰箱…）。
 *
 * 这些入口的共同点：**不切页**。点击处（`QuickMenuOverlayWindow`）先调本函数，返回 true 就收起菜单，
 * 由被调起的面板/悬浮窗自己承载界面——所以它们不需要在 `QuickMenuPanel` 里渲染任何屏幕内容。
 *
 * 原则：**复用已有的正确性，不重写引擎**。前台活动探测用 `ForegroundActivityInspectorOverlayWindow`，
 * 冰箱用 `FreezerOverlayWindow`（动作列表里的 FREEZER_PANEL 就是它），Shell 命令面板走
 * `ShellCommandPanelTrampoline`（透明 Activity，避免与无障碍悬浮窗抢 z-order）。
 */

/** 面板内的复制：直接走系统剪贴板（不依赖别处的 helper，避免循环依赖）。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

internal fun quickMenuRunEntry(
    context: Context,
    tool: QuickMenuTool,
    settings: AppSettings,
): Boolean = when (tool) {
    QuickMenuTool.ENTRY_FOREGROUND_ACTIVITY -> runCatching {
        ForegroundActivityInspectorOverlayWindow.show(context)
        true
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_FREEZER -> runCatching {
        // 用**冰箱悬浮窗**（动作列表里的 FREEZER_PANEL 就是它），而不是打开一个 Activity 页面
        FreezerOverlayWindow.show(context)
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_SHELL_PANEL -> runCatching {
        // 面板的保存回调是**唯一**的落盘路径，所以先拿到 DI，拿不到就别假装能打开
        // （否则用户改完命令退出来什么都没存下）。
        val deps = OverlayDependencyAccess.overlayDependencies(context) ?: return@runCatching false
        ShellCommandPanelTrampoline.launch(
            context = context,
            continuousPick = false,
            onPrepare = {},
            onDismiss = {},
            // setShellCommands 是 suspend，所以走与组合无关的 QuickMenuWork（跨外部 Activity 存活）。
            onPersist = { commands -> QuickMenuWork.launch { deps.settingsRepository.setShellCommands(commands) } },
        )
        true
    }.getOrDefault(false)

    // 把已有能力收进菜单：三处都是**直接复用既有入口**，不重写引擎。
    QuickMenuTool.ENTRY_SEARCH_PANEL -> runCatching {
        SearchPanelOverlayWindow.show(context)
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_CLIPBOARD_PANEL -> runCatching {
        // 与动作列表里的「打开剪贴板」走同一条路：侧边面板直接落在剪贴板那一页。
        FloatBallStashPanel.toggle(context, initialTab = StashPanelInitialTab.Clipboard)
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_PIN_TO_SCREEN -> runCatching {
        PinToScreenDialog.show(context)
    }.getOrDefault(false)

    QuickMenuTool.ENTRY_STASH_PANEL -> runCatching {        //「闪念」= 侧边面板的另一档 tab（代码里叫 Stash，界面上叫 Ideas/闪念）
        FloatBallStashPanel.toggle(context, initialTab = StashPanelInitialTab.Stash)
    }.getOrDefault(false)

    //「页面滚动」：与动作层同一个入口（弹出竖条并开始滚动）。
    QuickMenuTool.PAGE_SCROLL -> runCatching {
        val centerY = context.resources.displayMetrics.heightPixels / 2f
        PageScrollActionRunner.toggle(context, PanelSide.RIGHT, centerY)
    }.getOrDefault(false)

    //「小组件面板」：与动作层同一个悬浮窗入口。设置是 Flow，放到工作协程里读，不阻塞点击。
    QuickMenuTool.WIDGET_PANEL -> runCatching {
        val deps = OverlayDependencyAccess.overlayDependencies(context) ?: return@runCatching false
        val appContext = context.applicationContext
        QuickMenuWork.launch {
            val depsSettings = deps.settingsRepository.settings.first()
            WidgetPopupOverlayWindow.show(appContext, depsSettings, PanelSide.RIGHT, null)
        }
        true
    }.getOrDefault(false)

    // 文件浏览器：独立浮窗；点一下先收起快捷菜单，再由它自己承载界面。
    QuickMenuTool.FILE_BROWSER -> runCatching {
        FileBrowserOverlayWindow.show(context, settings)
    }.getOrDefault(false)

    else -> false
}
