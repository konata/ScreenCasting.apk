package play.ground

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
import android.hardware.display.DisplayManagerGlobal
import android.hardware.display.IDisplayManager
import android.hardware.display.IVirtualDisplayCallback
import android.hardware.display.VirtualDisplayConfig
import android.media.MediaRecorder
import android.media.projection.IMediaProjection
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebChromeClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.delay
import org.jetbrains.anko.button
import org.jetbrains.anko.displayManager
import org.jetbrains.anko.intentFor
import org.jetbrains.anko.mediaProjectionManager
import org.jetbrains.anko.notificationManager
import org.jetbrains.anko.sdk27.coroutines.onClick
import org.jetbrains.anko.textColor
import org.jetbrains.anko.verticalLayout


class IndexActivity : AppCompatActivity() {
  companion object {
    const val ScreenCastingRequestCode = 110
    const val NotificationRequestCode = 111
    private const val TAG = "natsuki"
    const val ChannelId = "ChannelId"
    const val ChannelName = "ScreenCasting"
  }

  private lateinit var save: Button
  private var privilegedDisplayId = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    notificationManager.createNotificationChannel(
      NotificationChannel(ChannelId, ChannelName, NotificationManager.IMPORTANCE_HIGH)
    )

    verticalLayout {

      // 1. foreground service needs to post notification
      button("1. request notification") {
        onClick {
          requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NotificationRequestCode
          )
        }
      }

      // a. hook `mDm` and replace the callback param (a.k.a appToken in server side)
      button("a. hook mDm") {
        textColor = Color.RED
        onClick {
          // substitute displayManager.mGlobal.mDm
          val global = DisplayManager::class.java.declaredFields.first {
            it.name == "mGlobal"
          }.run {
            isAccessible = true
            get(displayManager) as DisplayManagerGlobal
          }

          val (mDmField, origin) = DisplayManagerGlobal::class.java.declaredFields.first {
            it.name == "mDm"
          }.run {
            isAccessible = true
            this to (get(global) as IDisplayManager)
          }

          mDmField.set(global, object : IDisplayManager by origin {
            var uniqueToken: IVirtualDisplayCallback? = null
            override fun createVirtualDisplay(
              virtualDisplayConfig: VirtualDisplayConfig?,
              callback: IVirtualDisplayCallback?,
              projectionToken: IMediaProjection?,
              packageName: String?
            ): Int {
              Log.d(TAG, "createVirtualDisplay:  name = ${virtualDisplayConfig?.name}")
              // if hooked, always use the uniqueToken for all calls
              uniqueToken = uniqueToken ?: callback
              return origin.createVirtualDisplay(
                virtualDisplayConfig, uniqueToken, projectionToken, packageName
              )
            }
          })
        }
      }

      // 2. start media projection request
      button("2. request media projection") {
        onClick {
          startService(intentFor<RecorderService>())
          (this@IndexActivity).startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(), ScreenCastingRequestCode
          )
        }
      }

      // b. insert a secondary display to override appToken in server side
      button("b. secondary display") {
        textColor = Color.RED
        onClick {
          displayManager.createVirtualDisplay(
            "secondary",
            10,
            10,
            resources.displayMetrics.densityDpi,
            null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
          )
        }
      }

      save = button("3. save recording") {}
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == ScreenCastingRequestCode) {
      val title = "screen-casting-${System.currentTimeMillis()}"
      val uri =
        contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.TITLE, title)
        })


      val (w, h) = resources.displayMetrics.run { listOf(widthPixels, heightPixels) }
        .map { (it / 8) * 2 }

      val recorder = MediaRecorder(this).apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setVideoSize(w, h)
        setVideoFrameRate(30)
        setVideoEncodingBitRate(3 * w * h)
        setOutputFile(contentResolver.openFileDescriptor(uri, "rw").fileDescriptor)
        prepare()
      }

      val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
      privilegedDisplayId = mediaProjection.createVirtualDisplay(
        "screen-casting",
        w,
        h,
        resources.displayMetrics.densityDpi,
        VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        recorder.surface,
        null,
        null
      ).display.displayId

      recorder.start()

      save.onClick {
        recorder.stop()

        // toggle this to stop mediaProjections
        // mediaProjection.stop()

        startActivity(
          Intent(
            Intent.ACTION_VIEW, uri
          ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
      }
    }
  }
}


class RecorderService : Service() {
  companion object {
    const val ForegroundServiceId = 112
  }

  override fun onCreate() {
    super.onCreate()
    startForeground(
      ForegroundServiceId,
      Notification.Builder(this, IndexActivity.ChannelId).setSmallIcon(R.drawable.cat)
        .setContentText("foobar").setContentTitle("title").build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    )
  }

  override fun onBind(intent: Intent?) = null
}