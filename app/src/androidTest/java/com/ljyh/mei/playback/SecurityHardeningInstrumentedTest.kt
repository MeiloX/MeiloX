package com.ljyh.mei.playback

import android.content.Intent
import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.recognition.NeteaseFingerprintGenerator
import com.ljyh.mei.widget.MusicWidgets
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityHardeningInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun widgetCommandTokenAuthorizesValidIntentsAndRejectsPoisonedExtras() {
        val authorized = MusicWidgets.authorize(
            context,
            Intent(context, MusicService::class.java).setAction(MusicService.ACTION_TOGGLE_PLAYBACK),
        )
        assertTrue(MusicWidgets.isWidgetCommand(context, authorized))
        assertFalse(
            MusicWidgets.isWidgetCommand(
                context,
                Intent(context, MusicService::class.java).setAction(MusicService.ACTION_TOGGLE_PLAYBACK),
            ),
        )

        val raw = Intent(context, MusicService::class.java)
            .setAction(MusicService.ACTION_TOGGLE_PLAYBACK)
            .putExtra("evil", PoisonParcelable())
        val parcel = Parcel.obtain()
        val poisoned = try {
            raw.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        assertFalse(MusicWidgets.isWidgetCommand(context, poisoned))
    }

    @Test
    fun hardenedFingerprintWebViewGeneratesAudioFingerprint() = runBlocking {
        val generator = NeteaseFingerprintGenerator(context)
        try {
            val samples = FloatArray(24_000) { index ->
                (0.25 * sin(2.0 * PI * 440.0 * index / 8_000.0)).toFloat()
            }
            val fingerprint = withTimeout(15_000L) { generator.generate(samples) }
            assertTrue(fingerprint.isNotBlank())
            assertTrue(Base64.decode(fingerprint, Base64.DEFAULT).isNotEmpty())
        } finally {
            generator.release()
        }
    }

    private class PoisonParcelable : Parcelable {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) {}

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<PoisonParcelable> {
                override fun createFromParcel(source: Parcel): PoisonParcelable =
                    throw BadParcelableException("Untrusted external Parcelable")

                override fun newArray(size: Int): Array<PoisonParcelable?> = arrayOfNulls(size)
            }
        }
    }
}
