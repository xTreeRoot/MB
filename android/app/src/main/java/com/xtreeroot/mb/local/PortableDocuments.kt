package com.xtreeroot.mb.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

internal data class PortableDocument(val name: String, val uri: Uri, val size: Long, val mime: String)

internal class PortableDocuments(private val resolver: ContentResolver, private val tree: Uri) {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    fun folderName(): String? = runCatching {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty) else null
        }
    }.getOrNull()

    fun library(create: Boolean): PortableDirectory? {
        val candidates = children(rootId).filter { it.name == directoryName }
        if (candidates.size > 1) throw PortableFailure(PortableProblem.DuplicateFiles, directoryName)
        val existing = candidates.singleOrNull()
        if (existing != null) {
            if (existing.mime != DocumentsContract.Document.MIME_TYPE_DIR) throw PortableFailure(PortableProblem.FolderConflict)
            return PortableDirectory(DocumentsContract.getDocumentId(existing.uri))
        }
        if (!create) return null
        val folder = create(rootId, DocumentsContract.Document.MIME_TYPE_DIR, directoryName)
        return PortableDirectory(DocumentsContract.getDocumentId(folder))
    }

    inner class PortableDirectory(private val id: String) {
        fun files(): Map<String, PortableDocument> = children(id)
            .filterNot { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }.groupBy(PortableDocument::name)
            .mapValues { (name, entries) -> entries.singleOrNull() ?: throw PortableFailure(PortableProblem.DuplicateFiles, name) }

        fun open(document: PortableDocument): InputStream = resolver.openInputStream(document.uri)
            ?: throw PortableFailure(PortableProblem.Read, document.name)

        fun write(name: String, mime: String, verify: (() -> Unit)? = null, action: (OutputStream) -> Unit) {
            val uri = create(id, mime, name)
            try {
                val stream = resolver.openOutputStream(uri, "wt") ?: throw PortableFailure(PortableProblem.Write, name)
                stream.use(action)
                verify?.invoke()
            } catch (exception: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw exception
            }
        }

        fun delete(document: PortableDocument) { DocumentsContract.deleteDocument(resolver, document.uri) }
    }

    private fun create(parent: String, mime: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), mime, name)
            ?: throw PortableFailure(PortableProblem.Write, name)

    private fun children(parent: String): List<PortableDocument> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val query = resolver.query(uri, columns, null, null, null) ?: throw PortableFailure(PortableProblem.Permission)
        return query.use { cursor -> buildList {
            while (cursor.moveToNext()) add(PortableDocument(cursor.getString(1),
                DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                if (cursor.isNull(2)) -1 else cursor.getLong(2), cursor.getString(3).orEmpty()))
        } }
    }

    companion object {
        private const val directoryName = "MB Data"
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE)
    }
}
