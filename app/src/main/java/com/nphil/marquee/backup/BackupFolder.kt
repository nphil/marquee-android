package com.nphil.marquee.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/** What an automatic backup finds in the folder before it writes. */
internal object BackupFolderRule {
	const val CURRENT = "Marquee.marqueebackup"
	const val PREVIOUS = "Marquee (previous).marqueebackup"

	/** The backup already in the folder: when it was made, and whether it is this account's. */
	class Existing(val createdAt: Long, val belongs: Boolean)

	sealed interface Verdict {
		data object Write : Verdict

		/** The folder holds another Plex account's backup, which is never replaced. */
		data object OtherAccount : Verdict

		/** The folder holds a backup newer than anything this device saved or restored. */
		data class Newer(val createdAt: Long) : Verdict
	}

	fun verdict(existing: Existing?, knownNewest: Long, replacingNewer: Boolean): Verdict = when {
		existing == null -> Verdict.Write
		!existing.belongs -> Verdict.OtherAccount
		existing.createdAt > knownNewest && !replacingNewer -> Verdict.Newer(existing.createdAt)
		else -> Verdict.Write
	}
}

/** A folder the person chose with the system's picker, reached through the storage framework so Drive and network providers work. */
internal class BackupFolder(private val resolver: ContentResolver) {
	/** The backup file in [tree], or null when there is none. */
	fun read(tree: Uri, name: String = BackupFolderRule.CURRENT): ByteArray? {
		val file = find(tree, name) ?: return null
		return resolver.openInputStream(file)?.use { it.readBytes() } ?: throw FileNotFoundException()
	}

	/** Keeps the file already there as the previous copy, then replaces it. */
	fun write(tree: Uri, bytes: ByteArray, previous: ByteArray?) {
		if (previous != null) put(tree, BackupFolderRule.PREVIOUS, previous)
		put(tree, BackupFolderRule.CURRENT, bytes)
	}

	fun name(tree: Uri): String? {
		val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		return resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
			if (it.moveToFirst()) it.getString(0) else null
		}
	}

	private fun put(tree: Uri, name: String, bytes: ByteArray) {
		val target = find(tree, name) ?: create(tree, name)
		// "wt" truncates: a shorter file must not keep the tail of the longer one it replaces.
		(resolver.openOutputStream(target, "wt") ?: throw FileNotFoundException()).use { it.write(bytes) }
	}

	private fun create(tree: Uri, name: String): Uri {
		val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		return DocumentsContract.createDocument(resolver, parent, MIME, name) ?: throw FileNotFoundException()
	}

	private fun find(tree: Uri, name: String): Uri? {
		val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
		val cursor = resolver.query(children, columns, null, null, null) ?: throw FileNotFoundException()
		cursor.use {
			while (it.moveToNext()) {
				if (it.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
			}
		}
		return null
	}

	private companion object {
		// Not a type the provider knows, so it keeps the name as given instead of adding an extension.
		const val MIME = "application/octet-stream"
	}
}
