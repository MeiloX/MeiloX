package com.ljyh.mei.ui.screen.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonObject
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.ljyh.mei.R
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.platform.SourcePlatformRequest
import com.ljyh.mei.source.platform.authorizeQQMusicQr
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.MeiloXStandalonePageScope
import io.github.neoruaa.glasmio.core.LocalGlassColors
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
internal fun QQMusicQrLogin(request: SourcePlatformRequest) {
    var image by remember(request) { mutableStateOf<Bitmap?>(null) }
    var scanned by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) {
        try {
            request.scope.ensureCurrent()
            val arguments = request.effect.parameters.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: invalidQr()
            if (arguments.keySet() != setOf("qrcodeId", "imageBase64")) invalidQr()
            val qrcodeId = arguments.string("qrcodeId", 256)
            val bitmap = decodeQQMusicQr(arguments.string("imageBase64", 48 * 1024))
            request.scope.ensureCurrent()
            val result = try {
                authorizeQQMusicQr(qrcodeId, onReady = { image = bitmap }, onScanned = { scanned = true })
            } catch (error: SourceException) {
                Timber.tag("QQMusicQrLogin").w("Authorization channel failed: %s", error.code)
                throw error
            }
            request.scope.ensureCurrent()
            request.finish(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SourceException) {
            request.fail(error.code)
        } catch (_: Exception) {
            request.fail(SourceErrorCode.INVALID_RESPONSE)
        }
    }

    val close = { request.fail(SourceErrorCode.CANCELLED) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MeiloXStandalonePageScope {
            MeiloXPinnedPage(title = stringResource(R.string.source_login_qr), onNavigateBack = close) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        val bitmap = image
                        if (bitmap == null) {
                            CircularProgressIndicator()
                        } else {
                            Image(
                                painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap(), filterQuality = FilterQuality.None) },
                                contentDescription = stringResource(R.string.qq_music_login_qr_image),
                                modifier = Modifier.size(232.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(12.dp),
                            )
                        }
                        Text(
                            text = stringResource(when {
                                scanned -> R.string.qq_music_login_qr_confirm
                                bitmap == null -> R.string.qq_music_login_qr_connecting
                                else -> R.string.qq_music_login_qr_scan
                            }),
                            color = LocalGlassColors.current.secondaryContent,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun decodeQQMusicQr(encoded: String): Bitmap = withContext(Dispatchers.Default) {
    val bytes = Base64.getDecoder().decode(encoded)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType != "image/png" || bounds.outWidth != bounds.outHeight || bounds.outWidth !in 64..512) invalidQr()
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: invalidQr()
    // Small provider images can leave fewer than two pixels per QR module. Enlarge
    // without interpolation and pad the quiet zone before handing it to the detector.
    val size = bitmap.width * ((512 + bitmap.width - 1) / bitmap.width)
    val inset = 32
    val scanBitmap = Bitmap.createBitmap(size + inset * 2, size + inset * 2, Bitmap.Config.ARGB_8888)
    Canvas(scanBitmap).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(bitmap, null, Rect(inset, inset, inset + size, inset + size), null)
    }
    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    try {
        val barcodes = suspendCancellableCoroutine { continuation ->
            scanner.process(InputImage.fromBitmap(scanBitmap, 0)).addOnCompleteListener { result ->
                if (result.isSuccessful) continuation.resumeWith(Result.success(result.result))
                else {
                    Timber.tag("QQMusicQrLogin").w("QR detector failed")
                    continuation.resumeWith(Result.failure(SourceException(SourceErrorCode.INVALID_RESPONSE)))
                }
            }
        }
        val rawValue = barcodes.singleOrNull()?.rawValue ?: run {
            Timber.tag("QQMusicQrLogin").w("QR detector did not find a single code")
            invalidQr()
        }
        val uri = Uri.parse(rawValue)
        if (uri.scheme != "https" || uri.host != "c6.y.qq.com" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
            uri.path != "/base/fcgi-bin/u" || uri.fragment != null || uri.queryParameterNames != setOf("__") ||
            uri.getQueryParameter("__").isNullOrBlank()) {
            Timber.tag("QQMusicQrLogin").w("QR destination rejected")
            invalidQr()
        }
    } finally {
        scanner.close()
    }
    bitmap
}

private fun JsonObject.string(key: String, maxLength: Int): String = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    ?.takeIf { it.length in 1..maxLength } ?: invalidQr()

private fun invalidQr(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)
