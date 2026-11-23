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
import android.graphics.drawable.GradientDrawable
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
import java.util.concurrent.Executors
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
    private var hunterPanel: LinearLayout? = null
    private var hunterResult: TextView? = null
    private var hunterPreviousHeight: Int? = null
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
    private val hunterQueryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HunterQuery").apply { isDaemon = true }
    }
    // CookieManager.flush() pode fazer I/O síncrono; nunca executá-lo na thread da interface.
    private val cookieFlushExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CookiePersistence").apply { isDaemon = true }
    }

    private val flusher = object : Runnable {
        override fun run() {
            try {
                cookieFlushExecutor.execute {
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            // O WebView já sincroniza cookies automaticamente; a gravação explícita é apenas uma salvaguarda.
            handler.postDelayed(this, 120000)
        }
    }

    private val ctrlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RELOAD_ALL -> reloadOrRecover()
                ACTION_CLOSE_ALL -> stopSelf()
                ACTION_RUN_HUNT_ANALYSIS -> {
                    val requestedAccount = intent?.getIntExtra("accountId", -1) ?: -1
                    val requestId = intent?.getLongExtra("requestId", 0L) ?: 0L
                    if (!isTool && requestedAccount == accountId) {
                        val script = "window.__pokeIdleRunHuntAnalysis ? window.__pokeIdleRunHuntAnalysis() : {accepted:false,reason:'bridge_indisponivel'}"
                        val target = web
                        if (target == null) {
                            CaptureStore.save(this, accountId, org.json.JSONObject()
                                .put("kind", "analysis-control")
                                .put("analysisRequest", org.json.JSONObject()
                                    .put("requestId", requestId)
                                    .put("accepted", false)
                                    .put("reason", "webview_indisponivel")
                                    .put("receivedAt", System.currentTimeMillis()))
                                .put("updatedAt", System.currentTimeMillis()))
                        } else {
                            target.evaluateJavascript(script) { raw ->
                                val result = try {
                                    if (raw.isNullOrBlank() || raw == "null") org.json.JSONObject()
                                        .put("accepted", false).put("reason", "bridge_indisponivel")
                                    else org.json.JSONObject(raw)
                                } catch (_: Exception) {
                                    org.json.JSONObject().put("accepted", false).put("reason", "resposta_invalida")
                                }
                                CaptureStore.save(this, accountId, org.json.JSONObject()
                                    .put("kind", "analysis-control")
                                    .put("analysisRequest", org.json.JSONObject()
                                        .put("requestId", requestId)
                                        .put("accepted", result.optBoolean("accepted", false))
                                        .put("reason", result.optString("reason", "não informado"))
                                        .put("receivedAt", System.currentTimeMillis())
                                        .put("details", result.optJSONObject("details") ?: org.json.JSONObject()))
                                    .put("updatedAt", System.currentTimeMillis()))
                            }
                        }
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
        hunterQueryExecutor.shutdownNow()
        try {
            cookieFlushExecutor.execute {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        cookieFlushExecutor.shutdown()
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
        hunterPanel = null
        hunterResult = null
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
        val hunterBtn = smallButton("H")
        if (!isTool) bar.addView(hunterBtn)
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
        if (!isTool) {
            hunterBtn.setOnClickListener { toggleHunterPanel() }
            hunterPanel = createHunterPanel().also { panel ->
                panel.visibility = View.GONE
                val panelParams = FrameLayout.LayoutParams(
                    dp(276), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
                panelParams.topMargin = dp(42)
                panelParams.marginEnd = dp(8)
                f.addView(panel, panelParams)
            }
        }
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

    private fun toggleHunterPanel() {
        val panel = hunterPanel ?: return
        if (panel.visibility == View.VISIBLE) {
            hideHunterPanel()
            return
        }
        val params = lp
        if (params != null) {
            hunterPreviousHeight = params.height
            params.height = maxOf(params.height, dp(410)).coerceAtMost(resources.displayMetrics.heightPixels)
            frame?.let { wm?.updateViewLayout(it, params) }
        }
        panel.visibility = View.VISIBLE
        showHunterSection("overview")
    }

    private fun hideHunterPanel() {
        hunterPanel?.visibility = View.GONE
        val previous = hunterPreviousHeight ?: return
        val params = lp ?: return
        params.height = previous
        hunterPreviousHeight = null
        frame?.let { wm?.updateViewLayout(it, params) }
        savePrefs()
    }

    private fun createHunterPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF51B2430.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF69C7B8.toInt())
            }
            elevation = dp(12).toFloat()
            isClickable = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "HUNTER  ·  CONSULTA RÁPIDA"
            setTextColor(0xFF69C7B8.toInt())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { hideHunterPanel() }
        }
        header.addView(title)
        header.addView(close)
        panel.addView(header)
        val subtitle = TextView(this).apply {
            text = "CONTA $accountId  ·  Consulte sem sair da Hunt"
            setTextColor(0xFF9EACBA.toInt())
            textSize = 10f
            setPadding(dp(2), 0, 0, dp(8))
        }
        panel.addView(subtitle)
        val actions = listOf(
            "Oportunidades" to "opportunities",
            "Bônus XP" to "xp",
            "Shiny" to "shiny",
            "Ver resultado" to "hunt_result",
            "Calcular Hunt" to "hunt"
        )
        actions.chunked(2).forEach { group ->
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
                ).apply { bottomMargin = dp(5) }
            }
            group.forEachIndexed { index, (label, key) ->
                val button = TextView(this).apply {
                    text = label
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (key == "hunt") 0xFF102027.toInt() else Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(if (key == "hunt") 0xFF69C7B8.toInt() else if (key == "shiny") 0xFF594A70.toInt() else 0xFF263544.toInt())
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), if (key == "hunt") 0xFF8DE0D1.toInt() else 0xFF35495B.toInt())
                    }
                    elevation = dp(2).toFloat()
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index > 0) marginStart = dp(5)
                    }
                    setOnClickListener { showHunterSection(key) }
                }
                actionRow.addView(button)
            }
            if (group.size == 1) {
                val spacer = View(this)
                actionRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            }
            panel.addView(actionRow)
        }
        val result = TextView(this).apply {
            text = "Escolha uma consulta. A análise só será executada quando você solicitar."
            textSize = 11f
            setTextColor(0xFFD5DEE7.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF17212C.toInt())
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), 0xFF2E4050.toInt())
            }
            minHeight = dp(58)
            maxLines = 20
        }
        hunterResult = result
        panel.addView(result)
        return panel
    }

    private fun showHunterSection(section: String) {
        val output = hunterResult ?: return
        if (section == "hunt") {
            val requestedAt = System.currentTimeMillis()
            output.text = "⏳  ANALISANDO HUNTS\n\nO resultado aparecerá aqui automaticamente. Você não precisa trocar de aba."
            web?.evaluateJavascript(
                "window.__pokeIdleRunHuntAnalysis ? window.__pokeIdleRunHuntAnalysis() : {accepted:false,reason:'bridge_indisponivel'}"
            ) { raw ->
                val result = try { org.json.JSONObject(raw ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
                if (result.optBoolean("accepted", raw == "true")) {
                    pollHuntResult(0, requestedAt)
                } else {
                    output.text = when (result.optString("reason")) {
                        "dados_indisponiveis" -> "⚠️  Dados ainda incompletos\n\nAguarde a captura de Pokémon, Hunts e catálogo do jogo."
                        "bridge_indisponivel" -> "⚠️  Leitor ainda não ativo\n\nReabra a janela e aguarde a conexão."
                        else -> "⚠️  Não foi possível iniciar\n\n" + result.optString("reason", "A janela não aceitou o pedido.")
                    }
                }
            } ?: run { output.text = "Janela do jogo indisponível." }
            return
        }
        output.text = "Consultando os dados capturados…"
        hunterQueryExecutor.execute {
            val data = CaptureStore.read(this, accountId)
            handler.post {
                if (hunterResult !== output || hunterPanel?.visibility != View.VISIBLE) return@post
                if (data == null) {
                    output.text = "Ainda não há dados capturados desta conta. Abra o jogo e aguarde a conexão."
                    return@post
                }
                val player = data.optJSONObject("player")
                val xp = data.optJSONObject("euXp")
                val session = data.optJSONObject("gameSession")
                output.text = when (section) {
                    "overview" -> {
                        val analysis = data.optJSONObject("huntAnalysis")
                        val analysisStatus = analysis?.optString("status") ?: "ainda não executada"
                        "CONTA $accountId · TREINADOR NV. ${player?.optInt("level", 0) ?: 0}\nHunt atual: ${player?.optString("huntSlug", "não identificada")}\nHunter trabalha somente quando você toca em uma consulta.\nHunt Analyzer: $analysisStatus"
                    }
                    "opportunities" -> {
                        val event = xp?.opt("evento")
                        val hunts = data.optJSONArray("hunts")?.length() ?: 0
                        val measured = data.optJSONObject("measurements")
                        fun measuredRate(key: String): String =
                            measured?.takeIf { it.has(key) && !it.isNull(key) }
                                ?.optLong(key)?.let { "%,d/h".format(it) } ?: "aguardando"
                        "OPORTUNIDADES\nTreinador Nv. ${player?.optInt("level", 0) ?: 0}\nHunts recebidas: $hunts\nXP Pokémon: ${measuredRate("xpPokemonH")} · XP treinador: ${measuredRate("xpTrainerH")}\nAbates: ${measuredRate("killsH")} · Δ saldo Coins: ${measuredRate("coinsNetH")}\nEvento informado pelo jogo: ${event?.toString() ?: "nenhum dado disponível"}\nEncontros raros ao vivo ainda dependem de uma fonte de evento validada."
                    }
                    "xp" -> {
                        val twitch = xp?.optJSONObject("twitch")
                        val kick = xp?.optJSONObject("kick")
                        val vipUntil = xp?.optJSONObject("loja")?.optLong("vipAte", 0L) ?: 0L
                        buildString {
                            append("BÔNUS DE XP\n")
                            append("Guilda: ${xp?.optDouble("guildBonusPct", 0.0) ?: 0.0}%\n")
                            append("VIP: ${if (vipUntil > 0) "data cadastrada (validade a confirmar)" else "sem informação"}\n")
                            append("Twitch: ${if (twitch?.optBoolean("assistindo", false) == true) "+${twitch.optDouble("pctAtual", 0.0)}%" else "não ativo/sem dado"}\n")
                            append("Kick: ${kick?.optDouble("pct", 0.0)?.takeIf { it > 0 }?.let { "+$it%" } ?: "não ativo/sem dado"}\n")
                            append("Evento: ${xp?.opt("evento")?.toString() ?: "nenhum informado"}")
                        }
                    }
                    "shiny" -> {
                        val shinyCount = session?.optInt("shinies", -1) ?: -1
                        "SHINY\nShinies registrados na sessão: ${if (shinyCount >= 0) shinyCount else "sem contador disponível"}\nO leitor registra eventos de captura shiny observados; encontros raros não confirmados não serão inventados."
                    }
                    "hunt_result" -> {
                        val analysis = data.optJSONObject("huntAnalysis")
                        if (analysis == null) {
                            "Ainda não existe resultado. Toque em “Calcular Hunt” para iniciar a análise."
                        } else {
                            val pokemon = analysis.optJSONArray("pokemon")
                            buildString {
                                append("HUNT ANALYZER · ${analysis.optString("status", "sem status")}\n")
                                append("Pokémon analisados: ${analysis.optInt("pokemonAnalisados", 0)}\n")
                                if (pokemon != null) {
                                    for (i in 0 until minOf(2, pokemon.length())) {
                                        val p = pokemon.optJSONObject(i) ?: continue
                                        append("${p.optString("nome", "Pokémon")} Nv.${p.optInt("level", 0)}\n")
                                        val noAoe = p.optJSONObject("melhorXpSemAoe")
                                        val aoe = p.optJSONObject("melhorXpComAoe")
                                        if (noAoe != null) append("Sem AoE: ${noAoe.optString("nome")} · XP/h ${noAoe.optLong("xpPokemonH")}\n")
                                        if (aoe != null) append("Com AoE: ${aoe.optString("nome")} · XP/h ${aoe.optLong("xpPokemonHAoeHipotetico")}\n")
                                    }
                                }
                                append("A estimativa completa está na tela Hunt Analyzer do app.")
                            }
                        }
                    }
                    else -> "Consulta indisponível."
                }
            }
        }
    }

    private fun pollHuntResult(attempt: Int, requestedAt: Long) {
        if (hunterPanel?.visibility != View.VISIBLE) return
        if (attempt >= 60) {
            hunterResult?.text = "⚠️  A análise não terminou em 90 segundos.\n\nConfira o diagnóstico da conta e tente novamente."
            return
        }
        hunterQueryExecutor.execute {
            val data = CaptureStore.read(this, accountId)
            handler.post {
                val output = hunterResult ?: return@post
                if (hunterPanel?.visibility != View.VISIBLE) return@post
                val analysis = data?.optJSONObject("huntAnalysis")
                val status = analysis?.optString("status")
                if (analysis != null && status == "estimativa_disponivel" && analysis.optLong("calculadoEm", 0L) >= requestedAt) {
                    val pokemon = analysis.optJSONArray("pokemon")
                    val text = StringBuilder()
                    text.append("🏆  RESULTADO DA HUNT ANALYZER\n")
                    text.append(analysis.optInt("pokemonAnalisados", 0)).append(" Pokémon analisados\n")
                    text.append("Estimativa teórica · confiança baixa · não é medição real\n\n")
                    if (pokemon != null) {
                        fun appendBest(title: String, metric: String, aoe: Boolean) {
                            var bestValue = -1L
                            var bestPokemon = ""
                            var bestHunt = ""
                            for (i in 0 until pokemon.length()) {
                                val p = pokemon.optJSONObject(i) ?: continue
                                val result = when {
                                    metric == "gold" && aoe -> p.optJSONObject("melhorGoldComAoe")
                                    metric == "gold" -> p.optJSONObject("melhorGold")
                                    aoe -> p.optJSONObject("melhorXpComAoe")
                                    else -> p.optJSONObject("melhorXpSemAoe")
                                } ?: continue
                                val key = when {
                                    metric == "gold" && aoe -> "goldHAoeHipotetico"
                                    metric == "gold" -> "goldH"
                                    metric == "trainer" && aoe -> "xpTreinadorHAoeHipotetico"
                                    metric == "trainer" -> "xpTreinadorH"
                                    aoe -> "xpPokemonHAoeHipotetico"
                                    else -> "xpPokemonH"
                                }
                                val value = result.optLong(key, -1L)
                                if (value > bestValue) {
                                    bestValue = value
                                    bestPokemon = p.optString("nome", "Pokémon")
                                    bestHunt = result.optString("nome", "Hunt")
                                }
                            }
                            text.append(title).append(if (aoe) " · COM AoE (hipótese): " else " · SEM AoE: ")
                            if (bestValue >= 0L) {
                                text.append(bestPokemon).append(" / ").append(bestHunt)
                                    .append(" · ").append("%,d".format(bestValue)).append("/h\n")
                            } else text.append("sem resultado\n")
                        }
                        text.append("MELHOR OPÇÃO POR OBJETIVO\n")
                        text.append("Cada Pokémon usa sua própria Hunt liberada.\n")
                        appendBest("XP Pokémon", "pokemon", false)
                        appendBest("XP Pokémon", "pokemon", true)
                        appendBest("XP treinador", "trainer", false)
                        appendBest("XP treinador", "trainer", true)
                        appendBest("Gold", "gold", false)
                        appendBest("Gold", "gold", true)
                    }
                    text.append("\nCenários teóricos; confirme no farm real antes de investir.")
                    output.text = text.toString()
                } else if (status == "erro") {
                    output.text = "⚠️  A análise falhou\n\n" + analysis.optString("message", "Erro sem detalhes")
                } else if (status == "sem_dados") {
                    output.text = "⚠️  Análise concluída sem dados suficientes.\n\nConfira Pokémon com stats, Hunts e catálogo no diagnóstico."
                } else {
                    output.text = "⏳  ANALISANDO HUNTS\n\nComparando Pokémon e Hunts liberadas…\n\nVocê não precisa trocar de aba."
                    handler.postDelayed({ pollHuntResult(attempt + 1, requestedAt) }, 1500)
                }
            }
        }
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
        hideHunterPanel()
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
