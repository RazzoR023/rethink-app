/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.fragment.RulesImportHelper

/** A dialog the rules screen is showing (one at a time). */
sealed interface RulesDialog {
    data class Editor(val draft: RuleDraft) : RulesDialog
    data class ConfirmDelete(val item: RuleItem) : RulesDialog
    data class ConfirmDeleteSelected(val kind: RuleKind, val count: Int) : RulesDialog
    data class ConfirmDeleteAll(val kind: RuleKind) : RulesDialog
    /** debug builds: a parsed import file, waiting for block / trust and confirmation */
    data class Import(val kind: RuleKind, val parsed: RulesImportHelper.ParsedFile, val action: RuleAction) : RulesDialog
    data class ImportSummary(val imported: Int, val duplicates: Int, val invalid: Int) : RulesDialog
}

/** Where the rule proxy picker writes its choice. */
sealed interface PickerTarget {
    /** an existing rule; the choice is saved right away */
    data class Rule(val item: RuleItem) : PickerTarget
    /** the open add / edit dialog; the choice is saved with the rule */
    data object Draft : PickerTarget
}

/** Search text and multi-selection of one tab (IP or domain). */
class RulesTabState {
    var query by mutableStateOf("")
    internal var lastQuery: String? = null
    /** selected rules by key; non-empty = selection mode */
    val selected = mutableStateMapOf<String, RuleItem>()
    val selecting: Boolean get() = selected.isNotEmpty()

    fun toggle(item: RuleItem) {
        if (selected.containsKey(item.key)) selected.remove(item.key) else selected[item.key] = item
    }

    fun clearSelection() = selected.clear()

    /** Sets the search text; [onQuery] (the view model filter) only runs when it changed. */
    fun search(value: String, onQuery: (String) -> Unit) {
        query = value
        if (lastQuery != value) onQuery(value)
        lastQuery = value
    }

    companion object {
        val Saver = Saver<RulesTabState, String>(save = { it.query }, restore = { RulesTabState().apply { query = it } })
    }
}

/**
 * Observable state of the rules screen; CustomRuleActions does the I/O and updates it.
 * Replaces the per-view shims of the Compose port.
 */
class RulesScreenState(val scope: RulesScope) {
    val ip = RulesTabState()
    val domain = RulesTabState()

    fun tab(kind: RuleKind) = if (kind == RuleKind.IP) ip else domain

    var dialog by mutableStateOf<RulesDialog?>(null)

    /** the rule whose details sheet is open */
    var details by mutableStateOf<RuleItem?>(null)

    var picker by mutableStateOf<PickerTarget?>(null)

    /** every proxy with its live status; null until loaded */
    var proxies by mutableStateOf<List<ProxyChoices.Choice>?>(null)

    /** app name per uid, loaded on demand */
    val appNames = mutableStateMapOf<Int, String>()

    fun openEditor(kind: RuleKind, item: RuleItem? = null) {
        dialog = RulesDialog.Editor(RuleDraft(kind, item?.uid ?: scope.uid, item))
    }

    fun updateDraft(f: (RuleDraft) -> RuleDraft) {
        val d = dialog as? RulesDialog.Editor ?: return
        dialog = RulesDialog.Editor(f(d.draft))
    }

    val draft: RuleDraft? get() = (dialog as? RulesDialog.Editor)?.draft

    /** Replaces [item] wherever it is shown after a write (details sheet, selection). */
    fun refreshed(item: RuleItem) {
        if (details?.key == item.key) details = item
        tab(item.kind).selected.computeIfPresent(item.key) { _, _ -> item }
    }

    /** Forgets [key] after it was deleted. */
    fun removed(kind: RuleKind, key: String) {
        if (details?.key == key) details = null
        tab(kind).selected.remove(key)
    }
}
