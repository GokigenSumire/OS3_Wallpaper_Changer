package dev.abdus.apps.shimmer.p0

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** Refuses a populated target; append mode cannot truncate an existing exported document. */
internal object DiagnosticExportWriter {
    fun write(content: String, readTarget: () -> InputStream?, appendTarget: () -> OutputStream?) {
        val input = readTarget() ?: error("CANNOT_VERIFY_EMPTY_TARGET")
        input.use { check(it.read() == -1) { "EXISTING_DOCUMENT_NOT_OVERWRITTEN" } }
        val output = appendTarget() ?: error("NO_OUTPUT_STREAM")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }

    fun write(resolver: ContentResolver, uri: Uri, content: String) =
        write(content, { resolver.openInputStream(uri) }, { resolver.openOutputStream(uri, "wa") })
}
