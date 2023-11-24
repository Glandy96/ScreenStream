package info.dvkr.screenstream.webrtc

import android.app.ActivityManager
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AbstractService
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.internal.WebRtcError
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.ConnectException
import java.net.UnknownHostException

public class WebRtcService : AbstractService() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, WebRtcService::class.java)

        @Throws(ServiceStartNotAllowedException::class)
        internal fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("WebRtcService.startService", "Run intent: ${intent.extras}"))
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            XLog.i(getLog("MjpegService.startService", "RunningAppProcessInfo.importance: $importance"))
            context.startService(intent)
        }
    }

    override val notificationIdForeground: Int = 200
    override val notificationIdError: Int = 210

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcService.onStartCommand: intent = null"))
            return START_NOT_STICKY
        }
        val webRtcEvent = WebRtcEvent.Intentable.fromIntent(intent) ?: run {
            XLog.e(getLog("onStartCommand"), IllegalArgumentException("WebRtcService.onStartCommand: WebRtcEvent = null, startId: $startId, $intent"))
            return START_NOT_STICKY
        }
        XLog.d(getLog("onStartCommand", "WebRtcEvent: $webRtcEvent, startId: $startId"))

        val success = when (webRtcEvent) {
            is WebRtcEvent.Intentable.StartService -> streamingModulesManager.sendEvent(WebRtcEvent.CreateStreamingService(this))
            is WebRtcEvent.Intentable.StopStream -> streamingModulesManager.sendEvent(webRtcEvent)
            WebRtcEvent.Intentable.RecoverError -> streamingModulesManager.sendEvent(webRtcEvent)
        }

        if (success.not()) { // No active module
            XLog.w(getLog("onStartCommand", "No active module. Stop self, startId: $startId"))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        streamingModulesManager.deactivate(WebRtcStreamingModule.Id)
        super.onDestroy()
    }

    @Throws(WebRtcError.NotificationPermissionRequired::class, IllegalStateException::class)
    internal fun startForeground() {
        XLog.d(getLog("startForeground"))

        if (notificationHelper.notificationPermissionGranted(this).not()) throw WebRtcError.NotificationPermissionRequired

        val stopIntent = WebRtcEvent.Intentable.StopStream("WebRtcService. User action: Notification").toIntent(this)
        startForeground(stopIntent)
    }

    internal fun showErrorNotification(error: WebRtcError) {
        if (error is WebRtcError.NetworkError && (error.cause is UnknownHostException || error.cause is ConnectException) ) {
            XLog.i(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
        } else {
            XLog.d(getLog("showErrorNotification", "${error.javaClass.simpleName} ${error.cause}"))
            XLog.e(getLog("showErrorNotification"), error) //TODO Wait for prod logs
        }

        val message = error.toString(this)
        val recoverIntent = WebRtcEvent.Intentable.RecoverError.toIntent(this)
        showErrorNotification(message, recoverIntent)
    }
}