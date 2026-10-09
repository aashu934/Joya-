package com.example.jarvis
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
class AutoSendService : AccessibilityService() {
    companion object {
        @Volatile
        var armedUntil: Long = 0L
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val btn = findSend(root, pkg) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0L
        }
    }
    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
        val c = byId?.firstOrNull()?.let { clickableOf(it) }
        if (c != null) return c
        for (label in listOf("Send", "भेजें")) {
            val list = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (n in list) {
                val d = n.contentDescription?.toString() ?: ""
                if (d.equals(label, ignoreCase = true)) {
                    val k = clickableOf(n)
                    if (k != null) return k
                }
            }
        }
        return null
    }
    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 3) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }
    override fun onInterrupt() {}
}
