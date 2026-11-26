package com.ai.playtv.base.example

import android.content.Context
import android.net.Uri
import android.widget.EditText
import android.widget.TextView
import com.ai.playtv.base.storage.AppDirectories
import com.ai.playtv.base.storage.AtomicFileStore
import com.ai.playtv.base.ui.KeyboardController
import com.ai.playtv.base.util.TimeFormats
import com.ai.playtv.base.util.applyFontSizeAndLineHeight
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 编译型调用示例，未自动执行；调用方传入真实选择器结果和已捕获的作用域。 */
class FoundationUsageExamples(context: Context, private val capturedScope: String) {
    private val application = context.applicationContext
    private val importsLock = Mutex()
    private var imports: AtomicFileStore? = null

    suspend fun importDocument(uri: Uri, internalName: String): java.io.File {
        val store = importsLock.withLock {
            imports ?: AtomicFileStore(AppDirectories(application).files(capturedScope, "imports")).also { imports = it }
        }
        return store.importUri(application.contentResolver, uri, internalName, maxBytes = 20L * 1024 * 1024)
    }

    /** 控制器由宿主持有，并在 onDestroyView/onDestroy 调用 close；这里不启动全局任务。 */
    fun createKeyboardController(input: EditText) = KeyboardController(input)
    fun bindDuration(label: TextView, seconds: Long) {
        label.applyFontSizeAndLineHeight(16f, 24)
        label.text = TimeFormats.durationSeconds(seconds)
    }
}
