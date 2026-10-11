package cc.novelia.app.ui.components.documents

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import cc.novelia.app.files.exporting.bookFileMimeType

/** 按本次文件名声明实际格式，避免系统文件提供器为书籍追加 .bin。 */
internal class CreateBookDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).setType(bookFileMimeType(input))
}
