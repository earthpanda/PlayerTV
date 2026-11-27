package com.ai.playtv.service.homecontent

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The only public navigation actions understood by the current source-entry screen. */
object HomeContentActions {
    const val EXTRA = "com.gentv.tv.home.ACTION_ID"
    const val SHARE_CODE = "share-code"
    const val HISTORY = "history"
    const val SEARCH = "search"
    const val SOURCE_ENTRY = "com.ai.playtv.ui.test.sourceentry.SourceEntryActivity"
}

/** Bound GHCP provider: exposes only a public shortcut, never saved sources or viewing history. */
class HomeContentService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var endpoint: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("playtv-home-content").also { it.start() }
        endpoint = Messenger(Handler(worker.looper, ::handleRequest))
    }

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handleRequest(request: Message): Boolean {
        if (request.what != 1 || request.replyTo == null) return true
        if (packageManager.getPackagesForUid(request.sendingUid)?.contains("com.gentv.tv") != true) return true
        if (request.data.getInt("protocolMajor", 0) != 1) return true

        val locale = request.data.getString("locale").orEmpty()
        val chinese = if (locale.isEmpty()) Locale.getDefault().language == "zh"
            else Locale.forLanguageTag(locale).language == "zh"
        val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        val items = JSONArray()
        launch?.component?.let { component ->
            val activity = component.flattenToShortString()
            if (component.className == HomeContentActions.SOURCE_ENTRY) {
                fun addAction(id: String, title: String, subtitle: String) {
                    items.put(JSONObject().put("id", id).put("title", title).put("subtitle", subtitle)
                        .put("activity", activity).put("actionId", id))
                }
                addAction(HomeContentActions.SHARE_CODE,
                    if (chinese) "输入分享码" else "Enter share code",
                    if (chinese) "通过分享码解析播放源" else "Open a shared playback source")
                addAction(HomeContentActions.HISTORY,
                    if (chinese) "播放记录" else "History",
                    if (chinese) "查看历史播放源" else "View source history")
                addAction(HomeContentActions.SEARCH,
                    if (chinese) "搜索" else "Search",
                    if (chinese) "搜索已保存的频道和影片" else "Search saved channels and videos")
            } else {
                // Other build variants do not yet have the source-entry navigation.
                items.put(JSONObject().put("id", "open-playtv")
                    .put("title", if (chinese) "打开 PlayTV" else "Open PlayTV")
                    .put("activity", activity))
            }
        }
        val snapshot = JSONObject().put("protocolMajor", 1).put("snapshotId", "playtv-shortcuts-2")
            .put("template", "summary").put("privacy", "public").put("ttlSeconds", 300)
            .put("title", if (chinese) "Gen 视频" else "Gen Video").put("items", items)
        val response = Message.obtain(null, 2).apply {
            arg1 = request.arg1
            data = Bundle().apply { putString("snapshot", snapshot.toString()) }
        }
        try {
            request.replyTo.send(response)
        } catch (_: RemoteException) {
            // The host may have left the home screen while the reply was being prepared.
        }
        return true
    }
}
