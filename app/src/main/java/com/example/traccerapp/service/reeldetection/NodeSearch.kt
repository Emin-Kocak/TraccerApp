package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/**
 * root altındaki node ağacında viewIdResourceName'i idParts listesinden herhangi
 * birini İÇEREN bir node var mı arar (tam eşleşme değil, contains — Instagram/YouTube
 * view-id'leri paket önekiyle gelir, örn. "com.instagram.android:id/clips_viewer_container").
 * Ziyaret edilen çocuk node'lar recycle edilir (root hariç — çağıran taraf sorumlu),
 * WindowLeaked/leak önlenir (mevcut AppAccessibilityService.dumpNode ile aynı disiplin).
 */
internal fun containsViewId(root: AccessibilityNodeInfo, idParts: List<String>): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        val isRoot = node === root
        val viewId = node.viewIdResourceName
        val matched = viewId != null && idParts.any { viewId.contains(it) }
        if (matched) {
            if (!isRoot) node.recycle()
            while (stack.isNotEmpty()) {
                val leftover = stack.removeLast()
                if (leftover !== root) leftover.recycle()
            }
            return true
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isRoot) node.recycle()
    }
    return false
}
