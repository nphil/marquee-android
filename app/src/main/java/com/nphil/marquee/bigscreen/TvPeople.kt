package com.nphil.marquee.bigscreen

import com.nphil.marquee.core.PlexDirectory
import com.nphil.marquee.core.PlexHub

internal fun List<PlexHub>.peopleIn(role: String? = null): Sequence<PlexDirectory> = asSequence()
	.filter { hub -> if (role == null) hub.type in personRoles else hub.type == role }
	.flatMap { it.directories.asSequence() }
	.filter { it.id?.toLongOrNull()?.let { n -> n > 0 } == true && it.personName != null }

internal val PlexDirectory.personName: String? get() = tag?.takeIf { it.isNotBlank() } ?: title.takeIf { it.isNotBlank() }

// Id-less title credits may name two different people. Never guess between their filmographies.
internal fun List<PlexHub>.uniqueCredit(name: String, role: String): String? = peopleIn(role)
	.filter { it.personName?.trim().equals(name.trim(), ignoreCase = true) }
	.mapNotNull { it.id }.distinct().take(2).singleOrNull()

private val personRoles = setOf("actor", "director", "writer")
