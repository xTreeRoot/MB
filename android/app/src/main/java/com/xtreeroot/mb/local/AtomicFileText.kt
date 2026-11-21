package com.xtreeroot.mb.local

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeAtomic(file: File, text: String) {
    val parent = checkNotNull(file.parentFile)
    parent.mkdirs()
    val temporary = File.createTempFile("index-", ".part", parent)
    try {
        temporary.outputStream().use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
