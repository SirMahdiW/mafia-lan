package ir.mafia

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private var server: Server? = null
    private var web: WebView? = null
    private val port = 8080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        // در نسخهٔ قابل‌دیباگ، خطاهای کنسول جاوااسکریپت (مثل Uncaught (in promise)) در Logcat دیده می‌شود
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try { WebView.setWebContentsDebuggingEnabled(true) } catch (e: Exception) {}
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = Engine({ Server.lanIps(port) })
        server = Server(port, engine) { name ->
            try { assets.open(name).use { it.readBytes() } } catch (e: Exception) { null }
        }
        server?.start()
        setContentView(newWebView())
    }

    private fun newWebView(): WebView {
        val wv = WebView(this)
        web = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.setWebViewClient(object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.w(TAG, "web error: ${error?.description} for ${request?.url}")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // رندرر وب‌ویو کرش کرده؛ برنامه نباید بسته شود، فقط صفحه دوباره ساخته می‌شود.
                Log.e(TAG, "web renderer gone, didCrash=${detail?.didCrash()}")
                try { view?.destroy() } catch (e: Exception) {}
                web = null
                runOnUiThread { setContentView(newWebView()) }
                return true
            }
        })
        wv.loadUrl("http://127.0.0.1:$port/?host")
        return wv
    }

    /** ثبت خطاهای مهارنشده در Logcat تا کرش‌ها قابل‌ردیابی باشند. */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "uncaught exception in thread ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    override fun onDestroy() {
        server?.stop()
        try { web?.destroy() } catch (e: Exception) {}
        web = null
        super.onDestroy()
    }

    companion object { private const val TAG = "MafiaLAN" }
}
