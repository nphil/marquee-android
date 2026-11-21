package com.nphil.marquee.diagnostics

import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.ui.theme.ThemeChoice
import com.nphil.marquee.ui.theme.ThemeMode

/** The state a report carries above the journal. The bridge writes who sent it and the build before this. */
internal object DiagReportText {
	class Facts(
		val phone: String,
		val power: String,
		val network: String,
		val memory: String,
		val server: String,
		val theme: String,
		val diagnostics: String,
		val accessibility: String,
	)

	fun details(facts: Facts, note: String, screen: String): String = listOf(
		"Phone: ${facts.phone}",
		"Power: ${facts.power}",
		"Network: ${facts.network}",
		"Memory: ${facts.memory}",
		"Note: ${note.trim().ifEmpty { "-" }.replace("\n", "\n  ")}",
		"",
		"Screen: $screen",
		"Server: ${facts.server}",
		"Diagnostic Logs: ${facts.diagnostics}",
		"",
		"==== Settings ====",
		"Theme: ${facts.theme}",
		"Accessibility: ${facts.accessibility}",
	).joinToString("\n")

	/** The chosen server's route, protocol and version; its name and address stay out. */
	fun server(server: PlexServer?, address: String?): String {
		if (server == null) return "-"
		val connection = server.connections.firstOrNull { it.uri == address }
		val route = when {
			connection == null -> "route unknown"
			connection.isRelay -> "relay"
			connection.isLocal -> "local"
			else -> "remote"
		}
		val scheme = address?.substringBefore("://", "")?.ifEmpty { null } ?: "-"
		return "$route · $scheme · version ${server.productVersion ?: "unknown"}"
	}

	fun theme(choice: ThemeChoice): String =
		if (choice.matchSystem) "matches the system · light ${choice.lightId} · dark ${choice.darkId}"
		else "${choice.fixedMode.name.lowercase()} ${choice.id(choice.fixedMode)} only"

	fun diagnostics(config: DiagConfig): String =
		"live ${on(config.live)} · ${if (config.detailed) "Detailed" else "Standard"} · floating button ${on(config.button)}"

	private fun on(value: Boolean) = if (value) "on" else "off"

	/** The theme's name for the log's `theme.change` line. */
	fun themeSlots(choice: ThemeChoice): String =
		"match=${on(choice.matchSystem)} light=${choice.id(ThemeMode.Light)} dark=${choice.id(ThemeMode.Dark)} fixed=${choice.fixedMode.name.lowercase()}"
}
