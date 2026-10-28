package dev.abdus.apps.shimmer.p10.editor

import android.net.Uri
import android.view.View
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge

/** Rendering-only adapter; has no repository, wallpaper Engine or persistence authority. */
interface LockEditorPreview {
    val view: View
    fun show(nextImage: LiveEditBridge.EditImage, nextUri: Uri, transform: ImageTransform)
    fun preview(transform: ImageTransform, inputUptimeMillis: Long)
    fun resumePreview()
    fun pausePreview()
    fun closePreview()
}
