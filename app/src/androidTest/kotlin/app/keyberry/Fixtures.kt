package app.keyberry

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * The KDBX fixtures as content:// documents, the only kind the app opens. Each is copied into the
 * app's cache and served by the FileProvider that debug builds declare.
 */
object Fixtures {
    private const val AUTHORITY = "app.keyberry.fixtures"

    fun uri(name: String): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fixtures/$name")
        file.parentFile?.mkdirs()
        val source = javaClass.getResourceAsStream("/kdbx/$name") ?: throw FileNotFoundException(name)
        source.use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }
}
