package dev.ii.tools.ui

/**
 * Pure layout rule for a home card, kept free of Android dependencies so the merge
 * logic can be verified directly on the JVM.
 */
data class HomeCardRule(
    val id: String,
    val removable: Boolean = true,
    val defaultVisible: Boolean = true,
)

/**
 * Persisted home layout: [visible] holds the ordered card ids currently on the home
 * screen; [known] records every card id the user's layout has already seen so future
 * app updates can tell newly introduced cards apart from cards the user hid.
 */
data class HomeLayout(
    val visible: List<String>,
    val known: Set<String>,
)

/**
 * Merges a stored layout ([storedVisible], [storedKnown]) with the current app's
 * [rules] (in default display order).
 *
 * - First run (`null` stored state): returns all cards with `defaultVisible` (or
 *   non-removable) in [rules] order.
 * - Existing user: drops retired ids, keeps the user's order and removals for known
 *   cards, ensures non-removable cards stay present, and inserts any unknown card with
 *   `defaultVisible` at its default position relative to its nearest surviving neighbors.
 */
fun mergeHomeLayout(
    rules: List<HomeCardRule>,
    storedVisible: List<String>?,
    storedKnown: Set<String>?,
): HomeLayout {
    val allKnown = rules.mapTo(LinkedHashSet(rules.size)) { it.id }
    if (storedVisible == null || storedKnown == null) {
        return HomeLayout(
            visible = rules.filter { it.defaultVisible || !it.removable }.map { it.id },
            known = allKnown,
        )
    }

    val ruleById = rules.associateBy { it.id }
    val result = storedVisible.filter { it in ruleById }.distinct().toMutableList()

    rules.forEachIndexed { idx, rule ->
        val isNew = rule.id !in storedKnown
        val shouldInsert = (!rule.removable || (isNew && rule.defaultVisible)) && rule.id !in result
        if (shouldInsert) {
            insertAtDefaultPosition(result, rules, idx, rule.id)
        }
    }

    return HomeLayout(visible = result, known = allKnown)
}

private fun insertAtDefaultPosition(
    target: MutableList<String>,
    rules: List<HomeCardRule>,
    defaultIndex: Int,
    id: String,
) {
    for (i in defaultIndex - 1 downTo 0) {
        val pos = target.indexOf(rules[i].id)
        if (pos >= 0) {
            target.add(pos + 1, id)
            return
        }
    }
    for (i in defaultIndex + 1 until rules.size) {
        val pos = target.indexOf(rules[i].id)
        if (pos >= 0) {
            target.add(pos, id)
            return
        }
    }
    target.add(id)
}
