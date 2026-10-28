package dev.abdus.apps.shimmer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.abdus.apps.shimmer.p1.P1SourceIntents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun rememberFolderPicker(): FolderPicker {
    val context = LocalContext.current
    val picker = remember { FolderPickerImpl() }
    picker.launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            P1SourceIntents.persistReadPermission(context, it)
        }
        picker.onResult(uri)
    }
    return picker
}

interface FolderPicker {
    suspend fun pick(): Uri?
}

private class FolderPickerImpl : FolderPicker {
    lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Uri?>
    private var continuation: CancellableContinuation<Uri?>? = null

    fun onResult(uri: Uri?) {
        continuation?.resume(uri)
        continuation = null
    }

    override suspend fun pick(): Uri? = suspendCancellableCoroutine { cont ->
        continuation = cont
        launcher.launch(null)
        cont.invokeOnCancellation { continuation = null }
    }
}

interface ImagePicker {
    suspend fun pickSingle(): List<Uri>
    suspend fun pickMultiple(): List<Uri>
}

/** P1 single-image and multi-image picker backed by ACTION_OPEN_DOCUMENT. */
@Composable
fun rememberImagePicker(): ImagePicker {
    val context = LocalContext.current
    val picker = remember { ImagePickerImpl() }
    picker.launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = P1SourceIntents.selectedUris(result.resultCode, result.data)
        uris.forEach { P1SourceIntents.persistReadPermission(context, it) }
        picker.onResult(uris)
    }
    return picker
}

private class ImagePickerImpl : ImagePicker {
    lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    private var continuation: CancellableContinuation<List<Uri>>? = null

    fun onResult(uris: List<Uri>) {
        continuation?.resume(uris)
        continuation = null
    }

    override suspend fun pickSingle(): List<Uri> = pick(P1SourceIntents.openSingleImage())

    override suspend fun pickMultiple(): List<Uri> = pick(P1SourceIntents.openMultipleImages())

    private suspend fun pick(intent: Intent): List<Uri> = suspendCancellableCoroutine { cont ->
        continuation = cont
        launcher.launch(intent)
        cont.invokeOnCancellation { continuation = null }
    }
}
