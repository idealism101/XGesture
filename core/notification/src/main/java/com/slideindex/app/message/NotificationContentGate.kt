package com.slideindex.app.message

/**
 * 通知内容去重：所有提醒样式（悬浮球 / 侧边气泡 / 弹幕 / 贴边列表 / 贴边卡）共用。
 *
 * 判据是**内容指纹**而不是 `postTime`：TG 等应用刷新通知时会更新 postTime 但内容没变，
 * 用时间戳判断会把旧消息当成新消息重新弹出。
 *
 * 指纹相同 → 视为重复下发，调用方直接丢弃；
 * 指纹不同 → 放行，并记下该会话的最新指纹。
 */
object NotificationContentGate {
    private const val MAX_CACHED_CONVERSATIONS = 200

    private val lastSignatureByConversation = LinkedHashMap<String, String>()
    private val lock = Any()

    /** 内容指纹：标题 + 正文 + 条数 + 未读 + 末条发送者/文本，不含 postTime。 */
    fun signature(data: NotificationData): String {
        val last = data.messages.lastOrNull()
        return buildString {
            append(data.key).append('|')
            append(data.title).append('|')
            append(data.content).append('|')
            append(data.messages.size).append('|')
            append(data.badgeCount).append('|')
            if (last != null) append(last.sender).append(':').append(last.text)
        }
    }

    /** 返回 false 表示这条通知的内容和该会话上一次完全相同，应丢弃。 */
    fun accept(data: NotificationData): Boolean {
        val conversationKey = NotificationData.conversationIdentityKey(data).ifBlank { data.key }
        val signature = signature(data)
        synchronized(lock) {
            if (lastSignatureByConversation[conversationKey] == signature) return false
            lastSignatureByConversation[conversationKey] = signature
            while (lastSignatureByConversation.size > MAX_CACHED_CONVERSATIONS) {
                val eldest = lastSignatureByConversation.entries.firstOrNull() ?: break
                lastSignatureByConversation.remove(eldest.key)
            }
        }
        return true
    }

    internal fun resetForTesting() {
        synchronized(lock) { lastSignatureByConversation.clear() }
    }
}
