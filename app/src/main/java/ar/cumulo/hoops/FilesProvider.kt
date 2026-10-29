package ar.cumulo.hoops

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Comparte archivos temporales de la app con otras apps, sin librerías extra:
 *  - content://ar.cumulo.hoops.files/camera/...  la cámara escribe ahí la foto que se saca
 *  - content://ar.cumulo.hoops.files/share/...   imagen o planilla que se comparte por WhatsApp, mail, etc.
 * Solo da acceso a esas dos carpetas del caché, y únicamente a las apps a las que se les
 * otorga permiso puntual para ese archivo.
 */
class FilesProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val segs = uri.pathSegments
        if (segs.size != 2 || segs[0] !in FOLDERS) return null
        val dir = File(context!!.cacheDir, segs[0])
        val f = File(dir, segs[1])
        return if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { c ->
                when (c) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "ar.cumulo.hoops.files"
        private val FOLDERS = setOf("camera", "share")

        fun uri(folder: String, name: String): Uri = Uri.parse("content://$AUTHORITY/$folder/${Uri.encode(name)}")
    }
}
