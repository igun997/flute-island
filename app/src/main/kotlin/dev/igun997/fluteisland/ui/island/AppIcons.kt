package dev.igun997.fluteisland.ui.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small LRU of launcher icons for alerts and the app filter list. */
object AppIcons {
    private val cache = LruCache<String, ImageBitmap>(48)

    suspend fun load(context: Context, packageName: String, sizePx: Int = 96): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(packageName)
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, sizePx, sizePx)
                d.draw(Canvas(bmp))
                bmp.asImageBitmap().also { cache.put(packageName, it) }
            }.getOrNull()
        }
    }
}

@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) { value = AppIcons.load(context, packageName) }
    return icon
}
