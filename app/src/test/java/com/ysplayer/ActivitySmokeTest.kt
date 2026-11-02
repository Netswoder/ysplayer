package com.ysplayer

import android.os.Looper
import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaSessionService
import com.ysplayer.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = YsApplication::class)
class ActivitySmokeTest {
    @Test fun createsComposeActivityAndClosesCleanly() {
        val app = RuntimeEnvironment.getApplication() as YsApplication
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            // Robolectric does not bind manifest services automatically. Supply the real
            // MediaSession binder instead of its default null placeholder.
            val binder = service.get().onBind(Intent(app, PlaybackService::class.java).setAction(MediaSessionService.SERVICE_INTERFACE))
            assertNotNull(binder)
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
            try {
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(activity.get().window.decorView)
                assertFalse(activity.get().isFinishing)
            } finally { activity.pause().stop().destroy() }
        } finally {
            service.destroy()
            app.database.close()
        }
    }
}
