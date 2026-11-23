package com.duascontas.pokeidle

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Janela flutuante com o jogo (ou com o site de Hunt). Cada janela usa uma subclasse
 * que roda em processo próprio, com dados de navegador separados.
 */
open class OverlayService : Service() {

    protected open val accountId: Int = 1
    protected open val titleText: String get() = "Conta $accountId"
    protected open val bubbleLabel: String get() = accountId.toString()
    protected open val startUrl: String get() = GAME_URL
    protected open val dataSuffix: String get() = "conta$accountId"
    protected open val isTool: Boolean get() = false
    protected open val keepAwake: Boolean get() = true

    companion object {
        const val GAME_URL = "https://pokeidle.io/app"
        const val HUNT_URL = "https://pokeidleguide.io/#hunt"
        const val ACTION_STOP = "com.duascontas.pokeidle.STOP"
        const val ACTION_RELOAD_ALL = "com.duascontas.pokeidle.RELOAD_ALL"
        const val ACTION_RUN_HUNT_ANALYSIS = "com.duascontas.pokeidle.RUN_HUNT_ANALYSIS"
        const val ACTION_CLOSE_ALL = "com.duascontas.pokeidle.CLOSE_ALL"
        private const val CHANNEL_ID = "janelas"
        private const val MAX_RENDERER_RECOVERY_ATTEMPTS = 4
        private val RENDERER_RECOVERY_DELAYS_MS = longArrayOf(2000L, 5000L, 15000L, 30000L)

    }

    private var wm: WindowManager? = null
    private var frame: FrameLayout? = null
    private var content: LinearLayout? = null
    private var barView: View? = null
    private var bottomView: View? = null
    private var bubble: TextView? = null
    private var keyButton: TextView? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wake: PowerManager.WakeLock? = null
    private var typing = false
    private var minimized = false
    private var savedGeo: IntArray? = null
    private var alphaIdx = 0
    private val alphas = floatArrayOf(1f, 0.6f, 0.3f)
    private var retryScheduled = false
    private var receiverRegistered = false
    private var rendererRecoveryAttempts = 0
    private var rendererRecoveryPending = false
    private var rendererRecoveryRunnable: Runnable? = null
    private var rendererHealthCheck: Runnable? = null
    private var recoveryMessage: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val flusher = object : Runnable {
        override fun run() {
            try {
                CookieManager.getInstance().flush()
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 15000)
        }
    }

    private val ctrlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RELOAD_ALL -> reloadOrRecover()
                ACTION_CLOSE_ALL -> stopSelf()
                ACTION_RUN_HUNT_ANALYSIS -> {
                    val requestedAccount = intent.getIntExtra("accountId", -1)
                    if (!isTool && requestedAccount == accountId) {
                        web?.evaluateJavascript(
                            "window.__pokeIdleRunHuntAnalysis && window.__pokeIdleRunHuntAnalysis()",
                            null
                        )
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            WebView.setDataDirectorySuffix(dataSuffix)
        } catch (e: Exception) {
            // já definido neste processo; segue normalmente
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildWindow()

        val filter = IntentFilter().apply {
            addAction(ACTION_RELOAD_ALL)
            addAction(ACTION_CLOSE_ALL)
            addAction(ACTION_RUN_HUNT_ANALYSIS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ctrlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ctrlReceiver, filter)
        }
        receiverRegistered = true

        if (keepAwake) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pokeidle:$dataSuffix").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        handler.postDelayed(flusher, 15000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        rendererRecoveryRunnable = null
        rendererHealthCheck = null
        rendererRecoveryPending = false
        if (receiverRegistered) {
            try {
                unregisterReceiver(ctrlReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
        }
        try {
            frame?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            try {
                it.destroy()
            } catch (e: Exception) {
            }
        }
        web = null
        frame = null
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Janelas flutuantes", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this,
            accountId,
            Intent(this, this::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeAllPi = PendingIntent.getBroadcast(
            this,
            50,
            Intent(ACTION_CLOSE_ALL).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Fechar esta",
            stopPi
        ).build()
        val closeAllAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Fechar tudo",
            closeAllPi
        ).build()
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PokeIdle - $titleText")
            .setContentText("Janela flutuante ativa")
            .setOngoing(true)
            .addAction(closeAction)
            .addAction(closeAllAction)
            .build()
        val id = 100 + accountId
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notif)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Áudio do jogo bloqueado (só toca se houver toque do usuário): não atrapalha série/vídeo.
        wv.settings.mediaPlaybackRequiresUserGesture = true
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) scheduleRetry(request.url.toString())
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (view !== web) return
                rendererHealthCheck?.let { handler.removeCallbacks(it) }
                val committedView = view
                val healthCheck = Runnable {
                    if (web === committedView) rendererRecoveryAttempts = 0
                }
                rendererHealthCheck = healthCheck
                handler.postDelayed(healthCheck, 60000)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererHealthCheck?.let { handler.removeCallbacks(it) }
                rendererHealthCheck = null
                if (view !== web) {
                    try { view.destroy() } catch (_: Exception) {}
                    return true
                }
                web = null
                (view.parent as? ViewGroup)?.removeView(view)
                try { view.destroy() } catch (_: Exception) {}
                scheduleRendererRecovery()
                return true
            }
        }
        wv.webChromeClient = WebChromeClient()
        // Instala a captura antes da navegação para não perder o primeiro welcome do WebSocket.
        if (!isTool && accountId in 1..3) GameDataCapture.install(this, wv, accountId)
        wv.loadUrl(startUrl)
        return wv
    }

    /** Se a conexão cair, tenta de novo a cada 10 segundos até voltar. */
    private fun scheduleRetry(url: String) {
        if (retryScheduled) return
        retryScheduled = true
        handler.postDelayed({
            retryScheduled = false
            web?.loadUrl(url)
        }, 10000)
    }

    /** Recria o WebView com limite e espera progressiva após falhas do renderizador. */
    private fun scheduleRendererRecovery(userInitiated: Boolean = false) {
        if (userInitiated) {
            rendererRecoveryRunnable?.let { handler.removeCallbacks(it) }
            rendererRecoveryRunnable = null
            rendererRecoveryPending = false
            rendererRecoveryAttempts = 0
            recoveryMessage?.let { content?.removeView(it) }
            recoveryMessage = null
        }
        if (rendererRecoveryPending || web != null || content == null) return
        if (rendererRecoveryAttempts >= MAX_RENDERER_RECOVERY_ATTEMPTS) {
            showRendererRecoveryMessage()
            return
        }
        val attempt = rendererRecoveryAttempts.coerceAtMost(RENDERER_RECOVERY_DELAYS_MS.lastIndex)
        val delay = RENDERER_RECOVERY_DELAYS_MS[attempt]
        rendererRecoveryAttempts++
        rendererRecoveryPending = true
        val task = Runnable {
            rendererRecoveryPending = false
            rendererRecoveryRunnable = null
            if (web != null || content == null) return@Runnable
            try {
                rebuildWebView()
            } catch (_: Exception) {
                scheduleRendererRecovery()
            }
        }
        rendererRecoveryRunnable = task
        handler.postDelayed(task, delay)
    }

    /** Depois de quatro falhas seguidas, para de recriar em loop e oferece tentativa manual. */
    private fun showRendererRecoveryMessage() {
        val c = content ?: return
        if (recoveryMessage != null) return
        val message = TextView(this).apply {
            text = "O navegador do jogo falhou repetidamente. Toque em ↻ para tentar novamente."
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        recoveryMessage = message
        c.addView(message, 1.coerceAtMost(c.childCount))
    }

    private fun reloadOrRecover() {
        if (web != null) web?.reload() else scheduleRendererRecovery(userInitiated = true)
    }

    private fun rebuildWebView() {
        val c = content ?: return
        val old = web
        var idx = 1
        if (old != null) {
            val i = c.indexOfChild(old)
            if (i >= 0) idx = i
            c.removeView(old)
            try { old.destroy() } catch (_: Exception) {}
        }
        recoveryMessage?.let {
            val i = c.indexOfChild(it)
            if (i >= 0) idx = i
            c.removeView(it)
        }
        recoveryMessage = null
        val wv = newWebView()
        c.addView(wv, idx.coerceAtMost(c.childCount))
        web = wv
    }

    private fun buildWindow() {
        val dm = resources.displayMetrics
        val prefs = getSharedPreferences("janela$accountId", MODE_PRIVATE)
        val portrait = dm.heightPixels >= dm.widthPixels

        // Tamanho/posição padrão. Contas: em pé uma sobre a outra; deitado lado a lado.
        val defW = if (isTool) {
            if (portrait) dm.widthPixels else (dm.widthPixels * 0.6f).toInt()
        } else {
            if (portrait) dm.widthPixels else dm.widthPixels / 3
        }
        val defH = if (isTool) {
            if (portrait) (dm.heightPixels * 0.6f).toInt() else (dm.heightPixels * 0.85f).toInt()
        } else {
            if (portrait) (dm.heightPixels * 0.28f).toInt() else (dm.heightPixels * 0.85f).toInt()
        }
        val defX = if (isTool || portrait) 0 else (accountId - 1) * defW
        val defY = if (isTool) {
            dp(40)
        } else {
            if (portrait) dp(40) + (accountId - 1) * defH else dp(8)
        }

        val w = prefs.getInt("w", defW).coerceIn(dp(240), dm.widthPixels)
        val h = prefs.getInt("h", defH).coerceIn(dp(120), dm.heightPixels)
        val x = prefs.getInt("x", defX).coerceIn(0, maxOf(0, dm.widthPixels - w))
        val y = prefs.getInt("y", defY).coerceIn(0, maxOf(0, dm.heightPixels - h))

        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = x
        params.y = y
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        lp = params

        val f = FrameLayout(this)
        f.setBackgroundColor(Color.BLACK)

        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Barra de título (arraste por aqui)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xFF263238.toInt())
        bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))

        val title = TextView(this)
        title.text = "  ≡  $titleText"
        title.setTextColor(Color.WHITE)
        title.textSize = 14f
        title.gravity = Gravity.CENTER_VERTICAL
        title.maxLines = 1
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

        val keyBtn = smallButton("⌨")
        val alphaBtn = smallButton("◐")
        val reloadBtn = smallButton("↻")
        val minBtn = smallButton("—")
        val closeBtn = smallButton("✕")
        bar.addView(title)
        bar.addView(keyBtn)
        bar.addView(alphaBtn)
        bar.addView(reloadBtn)
        bar.addView(minBtn)
        bar.addView(closeBtn)

        // Alça de redimensionar (canto inferior direito)
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.END
        bottom.setBackgroundColor(0xFF263238.toInt())
        bottom.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        val grip = TextView(this)
        grip.text = "◢ tamanho"
        grip.setTextColor(Color.WHITE)
        grip.textSize = 13f
        grip.gravity = Gravity.CENTER
        grip.setPadding(dp(12), 0, dp(12), 0)
        grip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        bottom.addView(grip)

        val wv = newWebView()
        web = wv

        c.addView(bar)
        c.addView(wv)
        c.addView(bottom)

        // Bolinha (janela minimizada): o jogo continua rodando por baixo dela
        val b = TextView(this)
        b.text = bubbleLabel
        b.setTextColor(Color.WHITE)
        b.textSize = 22f
        b.gravity = Gravity.CENTER
        b.setBackgroundColor(0xE6263238.toInt())
        b.visibility = View.GONE
        b.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        f.addView(c)
        f.addView(b)
                          frame = f
        content = c
        barView = bar
        bottomView = bottom
        bubble = b
        keyButton = keyBtn

        attachDrag(title, false, null)
        attachDrag(grip, true, null)
        attachDrag(b, false) { restore() }

        keyBtn.setOnClickListener { setTyping(!typing) }
        alphaBtn.setOnClickListener { cycleAlpha() }
        reloadBtn.setOnClickListener { reloadOrRecover() }
        minBtn.setOnClickListener { minimize() }
        closeBtn.setOnClickListener { stopSelf() }

        wm?.addView(f, params)
    }

    private fun smallButton(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 18f
        t.gravity = Gravity.CENTER
        t.layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT)
        return t
    }

    private fun setTyping(on: Boolean) {
        val p = lp ?: return
        typing = on
        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        keyButton?.setTextColor(if (on) Color.GREEN else Color.WHITE)
        frame?.let { wm?.updateViewLayout(it, p) }
        if (on) web?.requestFocus()
    }

    private fun cycleAlpha() {
        val p = lp ?: return
        alphaIdx = (alphaIdx + 1) % alphas.size
        p.alpha = alphas[alphaIdx]
        frame?.let { wm?.updateViewLayout(it, p) }
    }

    private fun minimize() {
        val p = lp ?: return
        if (minimized) return
        setTyping(false)
        savedGeo = intArrayOf(p.x, p.y, p.width, p.height)
        minimized = true
        barView?.visibility = View.GONE
        bottomView?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
        p.width = dp(56)
        p.height = dp(56)
        frame?.let { wm?.updateViewLayout(it, p) }
    }

    private fun restore() {
        val p = lp ?: return
        val g = savedGeo ?: return
        val dm = resources.displayMetrics
        p.width = g[2]
        p.height = g[3]
        p.x = p.x.coerceIn(0, maxOf(0, dm.widthPixels - p.width))
        p.y = p.y.coerceIn(0, maxOf(0, dm.heightPixels - p.height))
        minimized = false
        barView?.visibility = View.VISIBLE
        bottomView?.visibility = View.VISIBLE
        bubble?.visibility = View.GONE
        frame?.let { wm?.updateViewLayout(it, p) }
        savePrefs()
    }
                  @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View, resize: Boolean, onTap: (() -> Unit)?) {
        var startA = 0
        var startB = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            val p = lp
            val f = frame
            if (p == null || f == null) return@setOnTouchListener true
            val dm = resources.displayMetrics
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startA = if (resize) p.width else p.x
                    startB = if (resize) p.height else p.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (resize) {
                        p.width = (startA + dx).coerceIn(dp(240), dm.widthPixels)
                        p.height = (startB + dy).coerceIn(dp(120), dm.heightPixels)
                    } else {
                        p.x = (startA + dx).coerceIn(0, maxOf(0, dm.widthPixels - p.width))
                        p.y = (startB + dy).coerceIn(0, maxOf(0, dm.heightPixels - p.height))
                    }
                    wm?.updateViewLayout(f, p)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && onTap != null) onTap() else savePrefs()
                }
            }
            true
        }
    }

    private fun savePrefs() {
        if (minimized) return
        val p = lp ?: return
        getSharedPreferences("janela$accountId", MODE_PRIVATE).edit()
            .putInt("x", p.x)
            .putInt("y", p.y)
            .putInt("w", p.width)
            .putInt("h", p.height)
            .apply()
    }
}

class Conta1Service : OverlayService() {
    override val accountId: Int = 1
}

class Conta2Service : OverlayService() {
    override val accountId: Int = 2
}

class Conta3Service : OverlayService() {
    override val accountId: Int = 3
}

class HuntService : OverlayService() {
    override val accountId: Int = 9
    override val titleText: String = "Hunt (ranking)"
    override val bubbleLabel: String = "H"
    override val startUrl: String = HUNT_URL
    override val dataSuffix: String = "hunt"
    override val isTool: Boolean = true
    override val keepAwake: Boolean = false
}
