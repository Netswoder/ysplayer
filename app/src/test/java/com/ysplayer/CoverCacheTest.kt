package com.ysplayer

import android.app.Application
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.ysplayer.data.Work
import com.ysplayer.ui.coverRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverCacheTest {
    @Test fun prefetchedCoverIsReusedFromMemoryAndChangingCoverUsesANewKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "cover-cache-test.png")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Robolectric.buildContentProvider(CoverFixtureProvider::class.java).create("cover-test").get().apply { image = file }
        val loader = ImageLoader.Builder(context).memoryCache { MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build() }.build()
        try {
            val work = Work("one", "One", autoCover = "content://cover-test/one.png")
            val request = coverRequest(context, work, 640).newBuilder().allowHardware(false).build()
            val first = awaitImage { loader.execute(request) }
            assertTrue((first as? ErrorResult)?.throwable?.stackTraceToString(), first is SuccessResult)
            val cached = awaitImage { loader.execute(request) } as SuccessResult
            assertEquals(DataSource.MEMORY_CACHE, cached.dataSource)
            assertTrue(cached.drawable.intrinsicWidth <= 640)
            assertNotEquals(request.memoryCacheKey, coverRequest(context, work.copy(cover = "file:///manual.jpg"), 640).memoryCacheKey)
            assertNotEquals(request.memoryCacheKey, coverRequest(context, work, 320).memoryCacheKey)
        } finally { loader.shutdown(); file.delete() }
    }
    private fun <T> awaitImage(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val task = scope.async { block() }
        try {
            // Coil resumes on Android's main dispatcher. Pump Robolectric's paused looper.
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!task.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            check(task.isCompleted) { "Image loader timed out" }
            return runBlocking { task.await() }
        } finally { scope.cancel() }
    }
}

class CoverFixtureProvider : ContentProvider() {
    lateinit var image: File
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun openAssetFile(uri: Uri, mode: String) = AssetFileDescriptor(ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
}
