package com.example.acousticcamera.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * 摄像头预览 Composable
 *
 * 离开 Composition（用户关闭摄像头或导航回上一页）时，
 * onDispose 会自动 unbindAll()，释放摄像头资源，不会有资源泄漏。
 */
@Composable
fun CameraPreview(
    onPreviewViewReady: ((PreviewView?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(previewView) {
        onPreviewViewReady?.invoke(previewView)
        onDispose { onPreviewViewReady?.invoke(null) }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                future.get().unbindAll()
            }, ContextCompat.getMainExecutor(context))
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
