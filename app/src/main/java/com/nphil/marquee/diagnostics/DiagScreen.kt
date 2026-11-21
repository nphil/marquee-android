package com.nphil.marquee.diagnostics

import java.util.concurrent.CopyOnWriteArrayList

/** What is on the screen, for the log and a report: the tab, the page open on it and the sheets up. */
internal object DiagScreen {
	@Volatile
	private var tab = "-"

	@Volatile
	private var path: String? = null
	private val sheets = CopyOnWriteArrayList<String>()

	val summary: String get() = summary(tab, path, sheets)

	fun summary(tab: String, path: String?, sheets: List<String>): String =
		"tab=$tab path=${path ?: "root"} sheets=${sheets.joinToString(",").ifEmpty { "-" }}"

	fun tab(name: String) {
		if (tab == name) return
		tab = name
		path = null
		Diag.info(DiagCategory.Nav, "tab", "name" to name)
	}

	fun path(name: String?) {
		if (path == name) return
		path = name
		Diag.info(DiagCategory.Nav, "path", "name" to (name ?: "root"))
	}

	fun presentation(name: String, shown: Boolean) {
		if (shown) sheets += name else sheets.remove(name)
		Diag.info(DiagCategory.Nav, if (shown) "present" else "dismiss", "sheet" to name, "open" to sheets.size)
	}
}
