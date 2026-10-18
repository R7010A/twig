package com.twig.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class OpenFilesShareTest {
    @Test
    fun `multiple streams carry URI grants and a common MIME type`() {
        // Robolectric's MIME map starts empty, unlike Android's system map.
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("png", "image/png")
            addExtensionMimeTypeMapping("txt", "text/plain")
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val files = listOf(XFile("file", "/a.jpg", false), XFile("smb-test", "/b.png", false))
        OpenFiles.share(activity, files)
        val chooser = shadowOf(activity).nextStartedActivity
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("image/*", send.type)
        val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(files.map { StreamProvider.uriFor(activity, it) }, streams)
        assertEquals(2, send.clipData!!.itemCount)
        streams.forEachIndexed { index, uri -> assertEquals(uri, send.clipData!!.getItemAt(index).uri) }
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        OpenFiles.share(activity, files + XFile("file", "/c.txt", false))
        val mixed = shadowOf(activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("*/*", mixed.type)
    }
}
