package ru.medoed.vagdiag

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Минимальный FileProvider для отправки файлов журналов (filesDir/logs) в Telegram/почту.
 * Собственная реализация вместо androidx.core — проект не использует AndroidX-зависимости.
 * Поддерживает только чтение и временную раздачу URI (grantUriPermissions).
 */
class DiagLogFileProvider : android.content.ContentProvider() {

    private val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("log") ?: "text/plain"

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        val ctx = context ?: throw IOException("provider context is null")
        val file = resolve(ctx, uri)
        if (!file.isFile) throw FileNotFoundException("not a file: ${uri.path}")
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Разрешает получателям читать файл по URI без копирования. */
    override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? = null

    override fun query(
        uri: Uri, projection: Array<String?>?, selection: String?,
        selectionArgs: Array<String?>?, sortOrder: String?
    ): android.database.Cursor? = null

    override fun getType(uri: Uri): String = mime

    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int =
        throw UnsupportedOperationException("read-only provider")

    override fun update(
        uri: Uri, values: android.content.ContentValues?, selection: String?,
        selectionArgs: Array<String?>?
    ): Int = throw UnsupportedOperationException("read-only provider")

    companion object {
        /**
         * Строит content-URI для файла из filesDir/logs.
         * Формат пути: /<relativePath внутри logs/> — например content://…/diag_logs/vagdiag-x.log
         */
        fun uriForFile(ctx: Context, authority: String, file: File): Uri {
            val logsDir = File(ctx.filesDir, "logs").canonicalFile
            val canonical = file.canonicalFile
            if (!canonical.path.startsWith(logsDir.path)) {
                throw IllegalArgumentException("File $canonical is outside the shared logs directory")
            }
            val relative = canonical.path.removePrefix(logsDir.path).trimStart('/')
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath("diag_logs")
                .appendPath(relative)
                .build()
        }

        private fun resolve(ctx: Context, uri: Uri): File {
            val logsDir = File(ctx.filesDir, "logs").canonicalFile
            val segments = uri.pathSegments
            // segments[0] == "diag_logs", остальное — относительный путь
            val rel = segments.drop(1).joinToString("/")
            val candidate = File(logsDir, rel).canonicalFile
            if (!candidate.path.startsWith(logsDir.path)) {
                throw SecurityException("path traversal rejected: ${uri.path}")
            }
            return candidate
        }
    }
}
