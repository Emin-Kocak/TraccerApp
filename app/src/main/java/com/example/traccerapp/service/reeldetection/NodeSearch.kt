package com.example.traccerapp.service.reeldetection

import android.view.accessibility.AccessibilityNodeInfo

/**
 * root altındaki node ağacında predicate'i sağlayan ilk node'u arar. Ziyaret edilen çocuk
 * node'lar recycle edilir (root hariç — çağıran taraf sorumlu), her node tam olarak bir kez
 * recycle edilir — WindowLeaked/leak önlenir.
 */
private inline fun findMatchingNode(
    root: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean
): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        val isRoot = node === root
        if (predicate(node)) {
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

/**
 * contentDescription'ı descParts listesinden herhangi birini İÇEREN bir node var mı arar.
 * Instagram ve YouTube Shorts view-id atamıyor (Litho/benzeri render, cihaz teşhisi: id sayısı
 * hep 0) — TalkBack açıklaması tek kalan sinyal, bkz. InstagramReelDetector/YouTubeShortsDetector.
 */
internal fun containsContentDescription(root: AccessibilityNodeInfo, descParts: List<String>): Boolean =
    findMatchingNode(root) { node ->
        node.contentDescription?.toString()?.let { d -> descParts.any { d.contains(it) } } ?: false
    }
