package com.duascontas.pokeidle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Painel inicial do PokeIdle Assistant.
 * Os indicadores ficam neutros até existir uma fonte de dados validada;
 * não exibe números fictícios nem tenta ler dados privados do jogo.
 */
class MainActivity : Activity() {

    companion object {
        const val RELEASES_URL = "https://github.com/pachola2020/vitor-vitor/releases"
        private const val BG = 0xFF10151D.toInt()
        private const val PANEL = 0xFF1B2430.toInt()
        private const val PANEL_ALT = 0xFF222E3B.toInt()
        private const val TEXT = 0xFFF2F5F8.toInt()
        private const val MUTED = 0xFF9EACBA.toInt()
        private const val ACCENT = 0xFF69C7B8.toInt()
    }

    private lateinit var root: LinearLayout
    private lateinit var dashboardScroll: ScrollView
    private lateinit var accountStatus: TextView
    private lateinit var permissionStatus: TextView
    private var selectedAccount = 1
    private var dashboardData: org.json.JSONObject? = null
    private var dashboardLoadGeneration = 0
    private var huntPollGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dashboardIo = Executors.newSingleThreadExecutor { task -> Thread(task, "DashboardIO").apply { isDaemon = true } }
    private val accountNames = listOf("Principal", "Alt 1", "Alt 2")
    private val accountRoles = listOf("PvP · PvE · Bosses · conteúdo geral", "Farm · Bosses · PvP ocasional", "Farm · Bosses · PvP ocasional")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dashboardScroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        dashboardScroll.addView(root)
        setContentView(dashboardScroll)
        renderDashboard()
    }

    override fun onDestroy() {
        huntPollGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        dashboardIo.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.resumePendingInstall(this)
        if (::permissionStatus.isInitialized) {
            renderDashboard()
        }
    }

    private fun renderDashboard(loadSnapshot: Boolean = true) {
        root.removeAllViews()
        if (loadSnapshot) {
            dashboardData = null
            val requestedAccount = selectedAccount
            val generation = ++dashboardLoadGeneration
            dashboardIo.execute {
                val snapshot = CaptureStore.read(this, requestedAccount)
                mainHandler.post {
                    if (!isFinishing && selectedAccount == requestedAccount &&
                        generation == dashboardLoadGeneration
                    ) {
                        dashboardData = snapshot
                        renderDashboard(loadSnapshot = false)
                    }
                }
            }
        }

        val top = row()
        val brand = column()
        brand.addView(label("POKEIDLE", 12f, ACCENT, true))
        brand.addView(label("Assistant", 28f, TEXT, true))
        brand.addView(label("Painel pessoal de acompanhamento", 13f, MUTED))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val version = label("VERSÃO\n${BuildConfig.VERSION_NAME}", 11f, MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedPanel(PANEL_ALT, 12)
        }
        top.addView(version)
        root.addView(top)
        space(16)

        val hunterHero = column().apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF244653.toInt(), 0xFF1B2937.toInt(), 0xFF1B2430.toInt())
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF426878.toInt())
            }
            addView(label("✦  COMPANION", 10f, 0xFF8DE0D1.toInt(), true))
            addView(label("Inteligência durante a Hunt", 19f, TEXT, true))
            addView(label("Consulte métricas, Hunts e oportunidades. Cálculos pesados só rodam quando você solicita.", 12f, 0xFFD2DCE5.toInt()))
            addView(actionButton("Abrir jogo + Companion", true) {
                startAccount(selectedAccount)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
                ).apply { topMargin = dp(10) }
            })
        }
        root.addView(hunterHero)
        space(18)

        sectionTitle("CONTAS")
        val accountRow = row()
        accountNames.forEachIndexed { index, name ->
            val id = index + 1
            val b = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                setTextColor(if (selectedAccount == id) BG else TEXT)
                backgroundTintList = ColorStateList.valueOf(if (selectedAccount == id) ACCENT else PANEL_ALT)
                setOnClickListener {
                    selectedAccount = id
                    renderDashboard()
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            if (index > 0) lp.marginStart = dp(7)
            accountRow.addView(b, lp)
        }
        root.addView(accountRow)
        space(10)
        accountStatus = label("Conta selecionada: ${accountNames[selectedAccount - 1]} · sessão independente", 12f, MUTED)
        root.addView(accountStatus)
        val roleCard = column().apply {
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedPanel(PANEL_ALT, 13)
            addView(label("FOCO DESTA CONTA", 10f, ACCENT, true))
            addView(label(accountRoles[selectedAccount - 1], 13f, TEXT, true))
            addView(label(if (selectedAccount == 1) "Preservar versatilidade: não sacrificar PvP e Boss por um farm específico." else "Priorizar eficiência de farm; manter opções para Boss e PvP quando fizer sentido.", 11f, MUTED))
        }
        root.addView(roleCard)
        space(18)

        sectionTitle("VISÃO GERAL")
        val summary = captureSummary()
        val notice = column().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedPanel(PANEL)
            addView(label(summary.first, 11f, ACCENT, true))
            addView(label(summary.second, 13f, TEXT))
        }
        root.addView(notice)
        root.addView(actionButton("Atualizar dados", false) { renderDashboard() })
        root.addView(actionButton("Ver diagnóstico técnico", false) { showCaptureDiagnostics() })
        space(12)

        val metricRows = listOf(
            listOf("XP DO POKÉMON / H", "XP DO TREINADOR / H"),
            listOf("GOLD GANHO / H", "COINS ATUAIS"),
            listOf("ABATES / H", "HP POSITIVO / AMOSTRAS"),
            listOf("BOLAS CONSUMIDAS / SESSÃO", "SHINIES / SESSÃO")
        )
        metricRows.forEach { pair ->
            val r = row()
            pair.forEachIndexed { index, title ->
                val card = metricCard(title)
                val lp = LinearLayout.LayoutParams(0, dp(94), 1f)
                if (index == 1) lp.marginStart = dp(9)
                r.addView(card, lp)
            }
            root.addView(r)
            space(9)
        }

        sectionTitle("ANÁLISES")
        addAnalysisOption("PROGRESSÃO", "XP, Gold, abates, sobrevivência e variação do saldo", ::showProgressionDetails)
        addAnalysisOption("COMPARAR CONTAS", "Compare progressão e farm observado entre Principal, Alt 1 e Alt 2", ::showAccountComparison)
        addAnalysisOption("HUNTS", "Hunts liberadas, níveis e Hunt atual da conta", ::showHuntDetails)
        addAnalysisOption("HUNT ANALYZER", "Compare a melhor Hunt de cada Pokémon, com e sem AoE", ::openHuntAnalyzer)
        addAnalysisOption("PREY ANALYZER · EXPERIMENTAL", "Registre períodos Prey ON/OFF e compare XP, Gold, abates e capturas medidos", ::showPreyAnalyzer)
        root.addView(actionButton("Calcular Hunt Analyzer agora", true) { requestHuntAnalysis() })
        if (dashboardData?.optJSONObject("huntAnalysis")?.optString("status") == "estimativa_disponivel") addHuntAnalysisInline()
        addAnalysisOption("POKÉMON", "Equipe, Pokémon ativo, nível, IVs e atributos recebidos", ::showPokemonDetails)
        addAnalysisOption("RECURSOS", "Bolas, poções e materiais registrados na sessão do jogo", ::showInventoryDetails)
        addAnalysisOption("SHINY HUNT", "Bolas usadas, Shinies vistos e Shinies registrados na sessão", ::showShinyDetails)
        addAnalysisOption("MERCADO", "Registros capturados da aba aberta no Mercado", ::showMarketDetails)
        space(18)

        sectionTitle("JANELAS FLUTUANTES")
        val controls = column().apply {
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = roundedPanel(PANEL)
        }
        controls.addView(actionButton("Abrir conta selecionada") {
            startAccount(selectedAccount)
        })
        controls.addView(actionButton("Abrir as três contas") {
            start(Conta1Service::class.java)
            start(Conta2Service::class.java)
            start(Conta3Service::class.java)
        })
        controls.addView(actionButton("Abrir janela Hunt") { start(HuntService::class.java) })
        controls.addView(actionButton("Recarregar todas as janelas") {
            sendBroadcast(Intent(OverlayService.ACTION_RELOAD_ALL).setPackage(packageName))
        })
        controls.addView(actionButton("Fechar todas as janelas", false) {
            sendBroadcast(Intent(OverlayService.ACTION_CLOSE_ALL).setPackage(packageName))
        })
        root.addView(controls)
        space(18)

        sectionTitle("CONFIGURAÇÃO DO APARELHO")
        permissionStatus = label("", 12f, MUTED)
        root.addView(permissionStatus)
        root.addView(actionButton("Permitir exibir sobre outros apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(actionButton("Permitir notificações") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                Toast.makeText(this, "Notificações já podem ser usadas neste Android.", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(actionButton("Configurações de bateria") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        updatePermissionStatus()
        space(18)

        sectionTitle("SOBRE O ASSISTANT")
        root.addView(label("O PokeIdle Assistant acompanha as contas selecionadas, organiza métricas observadas e executa análises sob demanda. As estimativas do Hunt Analyzer são teóricas quando não há medição real da Hunt. O aplicativo não automatiza compras, vendas ou ações de combate.", 12f, MUTED))
        root.addView(actionButton("Verificar atualização", true) {
            checkForAppUpdate()
        })
        root.addView(actionButton("Ver versões do app no GitHub", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
        })
    }

    private fun checkForAppUpdate() {
        Toast.makeText(this, "Verificando atualizações...", Toast.LENGTH_SHORT).show()
        AppUpdater.check(this, { update ->
            if (update == null) {
                Toast.makeText(this, "Você já está usando a versão mais recente.", Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Atualização disponível")
                    .setMessage("Versão instalada: ${packageManager.getPackageInfo(packageName, 0).versionName}\nNova versão: ${update.versionName}\n\nO APK será baixado. Depois, o Android solicitará confirmação para instalar.")
                    .setNegativeButton("Depois", null)
                    .setPositiveButton("Atualizar") { _, _ -> AppUpdater.downloadAndInstall(this, update) }
                    .show()
            }
        }, { message ->
            Toast.makeText(this, "Não foi possível verificar: $message", Toast.LENGTH_LONG).show()
        })
    }

    private fun captureSummary(): Pair<String, String> {
        val data = dashboardData
            ?: return "AGUARDANDO DADOS DO JOGO" to
                "Abra a conta selecionada para iniciar a captura passiva. As análises detalhadas só serão executadas quando você solicitar."
        val status = data.optString("status", "unknown")
        if (status == "unsupported") {
            return "CAPTURA INDISPONÍVEL" to
                "Este WebView não oferece o recurso necessário. O jogo continua funcionando sem a captura."
        }
        if (status == "capture_error") {
            return "LEITOR NÃO INICIALIZADO" to
                "A captura encontrou uma falha (${data.optString("error", "falha desconhecida")}). Consulte o diagnóstico técnico."
        }
        val player = data.optJSONObject("player")
        val hunts = data.optJSONArray("hunts")?.length() ?: 0
        val level = player?.optInt("level", 0) ?: 0
        val pokemonList = player?.optJSONArray("pokemons")
        val activeId = player?.opt("activeId")?.toString()
        var pokemon = "não identificado"
        if (pokemonList != null && activeId != null) {
            for (i in 0 until pokemonList.length()) {
                val candidate = pokemonList.optJSONObject(i) ?: continue
                if (candidate.opt("id")?.toString() == activeId) {
                    pokemon = candidate.optString("nome", "não identificado")
                    break
                }
            }
        }
        val hunt = player?.optString("huntSlug")?.takeIf { !it.isNullOrBlank() } ?: "não identificada"
        val stamp = data.optLong("updatedAt", 0L)
        val age = if (stamp > 0) ((System.currentTimeMillis() - stamp) / 1000).coerceAtLeast(0) else -1
        val freshness = if (age >= 0) "Atualizado há ${age}s" else "horário indisponível"
        return if (player != null && hunts > 0) {
            "JOGO CONECTADO · ${accountNames[selectedAccount - 1].uppercase()}" to
                "Treinador Nv. $level · Hunt: $hunt · Pokémon ativo: $pokemon · $hunts Hunts recebidas. $freshness. Abra o Hunter para executar uma consulta quando precisar."
        } else if (status == "connected") {
            "CONECTADO · SINCRONIZANDO" to "A conexão foi detectada; aguardando os primeiros dados do jogo. $freshness."
        } else {
            "CAPTURA ATIVA · AGUARDANDO JOGO" to "O leitor passivo está instalado e aguarda a conexão do jogo. $freshness."
        }
    }

    private fun showPreyAnalyzer() {
        val account = selectedAccount
        dashboardIo.execute {
            val snapshot = CaptureStore.read(this, account)
            mainHandler.post {
                if (isFinishing || selectedAccount != account) return@post
                dashboardData = snapshot
                renderDashboard(loadSnapshot = false)
                val ageMs = System.currentTimeMillis() - (snapshot?.optLong("updatedAt", 0L) ?: 0L)
                if (snapshot == null || ageMs !in 0..60000L) {
                    showDialog("Prey Analyzer", "Os dados desta conta estão ausentes ou desatualizados. Mantenha o jogo aberto, toque em Atualizar dados e tente novamente.")
                    return@post
                }
                val prefs = getSharedPreferences("prey_experiments", MODE_PRIVATE)
                val active = prefs.getString("active_" + account, null)?.let {
                    try { org.json.JSONObject(it) } catch (_: Exception) { null }
                }
                if (active != null) {
                    val message = "Condição marcada: " + active.optString("condition") +
                        "\nHunt: " + active.optString("huntSlug", "não identificada") +
                        "\nPokémon: " + active.optString("pokemonName", "não identificado") +
                        "\n\nFinalize após um período representativo. Evite trocar Hunt, Pokémon ou bônus durante a amostra."
                    AlertDialog.Builder(this)
                        .setTitle("Prey · teste em andamento")
                        .setMessage(message)
                        .setPositiveButton("Finalizar amostra") { _, _ -> finishPreyExperiment(account, snapshot) }
                        .setNegativeButton("Manter teste", null)
                        .setNeutralButton("Cancelar teste") { _, _ ->
                            prefs.edit().remove("active_" + account).apply()
                            showDialog("Prey", "A amostra foi cancelada e não entrou nas comparações.")
                        }
                        .show()
                } else {
                    if (snapshot?.optJSONObject("gameSession") == null) {
                        showDialog("Prey Analyzer", "Ainda não há contadores de sessão nesta conta. Abra o jogo, aguarde a captura e tente novamente.")
                        return@post
                    }
                    val telemetry = snapshot.optJSONObject("preyTelemetry")
                    val detected = telemetry?.optJSONObject("eventCounts")?.length() ?: 0
                    val note = if (detected > 0) "Sinais de evento com termos relacionados detectados: " + detected +
                        ". Isso é telemetria exploratória, não confirmação da mecânica." else
                        "Nenhum evento com nome/campo explícito de Prey foi identificado até agora."
                    val message = "Não encontrei documentação pública oficial que descreva uma mecânica chamada Prey com fórmula verificável. Por isso, o módulo começa como experimento A/B: você marca ON ou OFF no jogo e o app mede os contadores reais entre as duas marcações.\n\n" +
                        note + "\n\n" + preyHistorySummary(account) +
                        "\n\nMantenha a mesma Hunt, Pokémon, nível e bônus durante cada amostra."
                    AlertDialog.Builder(this)
                        .setTitle("Prey · laboratório experimental")
                        .setMessage(message)
                        .setItems(arrayOf("Iniciar amostra · Prey ATIVADA", "Iniciar amostra · Prey DESATIVADA")) { _, which ->
                            startPreyExperiment(account, which == 0, snapshot)
                        }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
            }
        }
    }

    private fun preyCounters(snapshot: org.json.JSONObject?): org.json.JSONObject {
        val session = snapshot?.optJSONObject("gameSession") ?: return org.json.JSONObject()
        val result = org.json.JSONObject()
        for (key in arrayOf("ms", "xpPokemon", "xpTreinador", "gold", "abates", "capturas", "shiniesVistos", "shinies")) {
            if (session.has(key) && !session.isNull(key)) result.put(key, session.optLong(key))
        }
        val balls = sumJsonObject(session.optJSONObject("bolas"))
        if (balls != null) result.put("bolas", balls)
        return result
    }

    private fun preyContext(snapshot: org.json.JSONObject?): org.json.JSONObject {
        val player = snapshot?.optJSONObject("player")
        val pokemons = player?.optJSONArray("pokemons")
        val activeId = player?.opt("activeId")?.toString()
        var activeName = "não identificado"
        var activeLevel = 0
        if (pokemons != null) for (i in 0 until pokemons.length()) {
            val pokemon = pokemons.optJSONObject(i) ?: continue
            if (pokemon.opt("id")?.toString() == activeId) {
                activeName = pokemon.optString("nome", activeName)
                activeLevel = pokemon.optInt("level", 0)
                break
            }
        }
        val xp = snapshot?.optJSONObject("euXp")
        val shop = xp?.optJSONObject("loja")
        val twitch = xp?.optJSONObject("twitch")
        val kick = xp?.optJSONObject("kick")
        val bonusSignature = org.json.JSONObject()
            .put("guildBonusPct", xp?.optDouble("guildBonusPct", 0.0) ?: 0.0)
            .put("vipUntil", shop?.optLong("vipAte", 0L) ?: 0L)
            .put("twitchActive", twitch?.optBoolean("assistindo", false) ?: false)
            .put("twitchPct", twitch?.optDouble("pctAtual", 0.0) ?: 0.0)
            .put("kickPct", kick?.optDouble("pct", 0.0) ?: 0.0)
            .put("event", xp?.opt("evento")?.toString() ?: "")
        return org.json.JSONObject()
            .put("trainerLevel", player?.optInt("level", 0) ?: 0)
            .put("huntSlug", player?.optString("huntSlug", "não identificada") ?: "não identificada")
            .put("pokemonName", activeName)
            .put("pokemonLevel", activeLevel)
            .put("activeId", activeId ?: "")
            .put("bonusSignature", bonusSignature.toString())
    }

    private fun startPreyExperiment(account: Int, preyEnabled: Boolean, snapshot: org.json.JSONObject?) {
        val counters = preyCounters(snapshot)
        if (counters.length() == 0) {
            showDialog("Prey", "Sem contadores de sessão suficientes para iniciar uma amostra.")
            return
        }
        val context = preyContext(snapshot)
        if (context.optString("huntSlug").isBlank() || context.optString("huntSlug") == "não identificada" ||
            context.optString("activeId").isBlank()) {
            showDialog("Prey", "Inicie a amostra somente quando houver uma Hunt e um Pokémon ativo identificados.")
            return
        }
        val active = context
            .put("accountId", account)
            .put("condition", if (preyEnabled) "ON" else "OFF")
            .put("startedAt", System.currentTimeMillis())
            .put("baseline", counters)
        getSharedPreferences("prey_experiments", MODE_PRIVATE)
            .edit().putString("active_" + account, active.toString()).apply()
        val message = "Condição registrada: Prey " + if (preyEnabled) "ATIVADA" else "DESATIVADA"
        showDialog("Amostra iniciada", message + "\nHunt: " + active.optString("huntSlug") +
            "\nPokémon: " + active.optString("pokemonName") + " Nv. " + active.optInt("pokemonLevel") +
            "\n\nConfirme que essa condição já está ativa no jogo. Mantenha a mesma Hunt, Pokémon e bônus por pelo menos 5 minutos. Ao finalizar, o app calcula as diferenças dos contadores reais da sessão.")
    }

    private fun finishPreyExperiment(account: Int, snapshot: org.json.JSONObject?) {
        val prefs = getSharedPreferences("prey_experiments", MODE_PRIVATE)
        val active = prefs.getString("active_" + account, null)?.let {
            try { org.json.JSONObject(it) } catch (_: Exception) { null }
        }
        if (active == null) {
            showDialog("Prey", "Não há amostra ativa para finalizar.")
            return
        }
        val baseline = active.optJSONObject("baseline") ?: org.json.JSONObject()
        val ending = preyCounters(snapshot)
        if (ending.length() == 0) {
            showDialog("Prey", "Não consegui ler os contadores finais. Atualize os dados da conta e tente finalizar novamente.")
            return
        }
        val duration = (System.currentTimeMillis() - active.optLong("startedAt", System.currentTimeMillis())).coerceAtLeast(1L)
        val rates = org.json.JSONObject()
        val delta = org.json.JSONObject()
        for (key in arrayOf("xpPokemon", "xpTreinador", "gold", "abates", "capturas", "shiniesVistos", "shinies", "bolas")) {
            if (!baseline.has(key) || !ending.has(key)) continue
            val difference = ending.optLong(key) - baseline.optLong(key)
            if (difference >= 0L) {
                delta.put(key, difference)
                rates.put(key, (difference.toDouble() * 3600000.0 / duration.toDouble()).toLong())
            }
        }
        val endingContext = preyContext(snapshot)
        val contextStable = active.optString("huntSlug") == endingContext.optString("huntSlug") &&
            active.optString("activeId") == endingContext.optString("activeId") &&
            active.optInt("trainerLevel") == endingContext.optInt("trainerLevel") &&
            active.optInt("pokemonLevel") == endingContext.optInt("pokemonLevel") &&
            active.optString("bonusSignature") == endingContext.optString("bonusSignature")
        val validForComparison = contextStable && duration >= 300000L && rates.has("xpPokemon")
        val result = org.json.JSONObject()
            .put("condition", active.optString("condition"))
            .put("startedAt", active.optLong("startedAt"))
            .put("endedAt", System.currentTimeMillis())
            .put("durationMs", duration)
            .put("huntSlug", active.optString("huntSlug"))
            .put("trainerLevel", active.optInt("trainerLevel"))
            .put("pokemonName", active.optString("pokemonName"))
            .put("pokemonLevel", active.optInt("pokemonLevel"))
            .put("contextStable", contextStable)
            .put("validForComparison", validForComparison)
            .put("delta", delta)
            .put("ratesPerHour", rates)
        val history = try {
            org.json.JSONArray(prefs.getString("history_" + account, "[]") ?: "[]")
        } catch (_: Exception) { org.json.JSONArray() }
        history.put(result)
        while (history.length() > 40) history.remove(0)
        prefs.edit().remove("active_" + account)
            .putString("history_" + account, history.toString()).apply()
        fun rate(key: String): String {
            return if (rates.has(key)) "%,d/h".format(rates.optLong(key)) else "sem dado"
        }
        val validity = if (validForComparison) "Amostra válida para comparação" else
            "Amostra registrada, mas EXCLUÍDA das médias: " +
                if (!contextStable) "Hunt, Pokémon, nível ou bônus mudaram." else "duração menor que 5 minutos ou sem XP medido."
        val message = "Condição: Prey " + active.optString("condition") +
            "\nHunt: " + active.optString("huntSlug") +
            "\nPokémon: " + active.optString("pokemonName") + " Nv. " + active.optInt("pokemonLevel") +
            "\nDuração: " + (duration / 60000L) + " min" +
            "\n\nXP Pokémon: " + rate("xpPokemon") +
            "\nXP treinador: " + rate("xpTreinador") +
            "\nGold: " + rate("gold") +
            "\nAbates: " + rate("abates") +
            "\nCapturas: " + rate("capturas") +
            "\nBolas consumidas: " + (delta.optLong("bolas", -1L).takeIf { it >= 0 }?.toString() ?: "sem dado") +
            "\n\n" + validity +
            "\nResultado observado no intervalo. Uma amostra isolada não prova causalidade. Faça várias amostras ON/OFF em condições equivalentes."
        showDialog("Amostra Prey registrada", message)
    }

    private fun preyHistorySummary(account: Int): String {
        val history = try {
            org.json.JSONArray(getSharedPreferences("prey_experiments", MODE_PRIVATE)
                .getString("history_" + account, "[]") ?: "[]")
        } catch (_: Exception) { org.json.JSONArray() }
        if (history.length() == 0) return "Ainda não há amostras registradas."
        val out = StringBuilder("MÉDIAS EXPLORATÓRIAS · " + history.length() + " amostras\n")
        for (condition in arrayOf("ON", "OFF")) {
            var count = 0
            var xpPokemon = 0L
            var xpTrainer = 0L
            var gold = 0L
            var kills = 0L
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                if (item.optString("condition") != condition || !item.optBoolean("validForComparison", false)) continue
                val rates = item.optJSONObject("ratesPerHour") ?: continue
                if (!rates.has("xpPokemon")) continue
                count++
                xpPokemon += rates.optLong("xpPokemon")
                xpTrainer += rates.optLong("xpTreinador")
                gold += rates.optLong("gold")
                kills += rates.optLong("abates")
            }
            if (count > 0) {
                out.append("Prey ").append(condition).append(" · ").append(count).append(" amostras · XP Pokémon ")
                    .append("%,d".format(xpPokemon / count)).append("/h · XP treinador ")
                    .append("%,d".format(xpTrainer / count)).append("/h · Gold ")
                    .append("%,d".format(gold / count)).append("/h · abates ")
                    .append("%,d".format(kills / count)).append("/h\n")
            } else out.append("Prey ").append(condition).append(" · aguardando amostras\n")
        }
        out.append("Compare apenas amostras com Hunt, Pokémon, nível e bônus semelhantes.")
        return out.toString()
    }

    private fun showCaptureDiagnostics() {
        val data = dashboardData
        if (data == null) {
            showDialog("Diagnóstico técnico", "Ainda não há dados desta conta. Abra o jogo e tente novamente.")
            return
        }
        val details = buildString {
            appendLine("Conta: ${accountNames[selectedAccount - 1]}")
            appendLine("Status: ${data.optString("status", "desconhecido")}")
            appendLine("Última atualização: ${data.optLong("updatedAt", 0L)}")
            appendLine()
            appendLine("Dados estruturados:")
            append(data.toString(2).take(4000))
            if (data.toString().length > 4000) appendLine("\n… conteúdo técnico truncado em 4.000 caracteres. Os resumos continuam disponíveis nas telas principais.")
        }
        showDialog("Diagnóstico técnico", details)
    }

    private fun sessionRate(session: org.json.JSONObject?, key: String): Long? {
        val duration = nullableLong(session, "ms") ?: return null
        val total = nullableLong(session, key) ?: return null
        if (duration <= 0L) return null
        return (total.toDouble() * 3600000.0 / duration.toDouble()).toLong()
    }

    private fun sumJsonObject(obj: org.json.JSONObject?): Long? {
        if (obj == null) return null
        var total = 0L
        val keys = obj.keys()
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is Number) total += value.toLong().coerceAtLeast(0L)
        }
        return total
    }

    private fun showDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showAccountComparison() {
        Toast.makeText(this, "Lendo métricas das três contas…", Toast.LENGTH_SHORT).show()
        dashboardIo.execute {
            val snapshots = (1..3).map { id -> id to CaptureStore.read(this, id) }
            mainHandler.post {
                if (isFinishing) return@post
                val lines = buildString {
                    for ((id, data) in snapshots) {
                        appendLine("━━ " + accountNames[id - 1].uppercase() + " · " + accountRoles[id - 1])
                        if (data == null) {
                            appendLine("Sem dados capturados nesta conta.")
                            appendLine()
                            continue
                        }
                        val player = data.optJSONObject("player")
                        val session = data.optJSONObject("gameSession")
                        val measured = data.optJSONObject("measurements")
                        val pokemons = player?.optJSONArray("pokemons")
                        val activeId = player?.opt("activeId")?.toString()
                        var activeName = "não identificado"
                        if (pokemons != null) for (i in 0 until pokemons.length()) {
                            val p = pokemons.optJSONObject(i) ?: continue
                            if (p.opt("id")?.toString() == activeId) {
                                activeName = p.optString("nome", activeName)
                                break
                            }
                        }
                        fun rate(sessionKey: String, measuredKey: String): String {
                            val value = sessionRate(session, sessionKey) ?: nullableLong(measured, measuredKey)
                            return value?.let { "%,d/h".format(it) } ?: "—"
                        }
                        appendLine("Treinador Nv. " + (player?.optInt("level", 0) ?: 0) +
                            " · Pokémon: " + activeName)
                        appendLine("Hunt: " + (player?.optString("huntSlug")?.takeIf { it.isNotBlank() } ?: "—"))
                        appendLine("XP Pokémon: " + rate("xpPokemon", "xpPokemonH") +
                            " · XP treinador: " + rate("xpTreinador", "xpTrainerH"))
                        appendLine("Gold: " + rate("gold", "goldH") +
                            " · Abates: " + rate("abates", "killsH"))
                        appendLine("HP positivo: " + (nullableLong(measured, "survivalPct")?.let { "$it%" } ?: "—") +
                            " · Δ Coins: " + (nullableLong(measured, "coinsNetH")?.let { "%+,d/h".format(it) } ?: "—"))
                        appendLine("Sessão: " + (nullableLong(session, "ms")?.let { (it / 60000L).toString() + " min" } ?: "—"))
                        appendLine()
                    }
                    append("Comparação descritiva: níveis, Hunts, bônus, Pokémon, AoE e duração podem ser diferentes. Use os números como contexto; para comparar candidatos de farm, consulte o Hunt Analyzer de cada conta e confirme em sessão real.")
                }
                showDialog("Comparar contas · Principal e Alts", lines)
            }
        }
    }

    private fun showProgressionDetails() {
        val data = dashboardData
        val session = data?.optJSONObject("gameSession")
        val m = data?.optJSONObject("measurements")
        val player = data?.optJSONObject("player")
        val text = buildString {
            appendLine("XP do Pokémon/h: ${sessionRate(session, "xpPokemon")?.let { "%,d".format(it) } ?: "aguardando"}")
            appendLine("XP do treinador/h: ${sessionRate(session, "xpTreinador")?.let { "%,d".format(it) } ?: "aguardando"}")
            appendLine("Gold ganho/h: ${sessionRate(session, "gold")?.let { "%,d".format(it) } ?: "aguardando"}")
            appendLine("Abates/h: ${sessionRate(session, "abates")?.let { "%,d".format(it) } ?: "aguardando"}")
            appendLine("Variação líquida do saldo/h: ${nullableLong(m, "coinsNetH")?.let { "%+,d".format(it) } ?: "aguardando"}")
            appendLine("Saldo atual: ${nullableLong(data, "coinsBalance") ?: nullableLong(player, "coins") ?: "—"}")
            appendLine("HP positivo nas amostras: ${nullableLong(m, "survivalPct")?.let { "$it%" } ?: "aguardando"}")
            appendLine("Duração da sessão: ${nullableLong(session, "ms")?.let { "${it / 60000} min" } ?: "não disponível"}")
            appendLine()
            append("Os valores do contador de sessão são os registrados pelo próprio jogo. A variação líquida do saldo é uma métrica separada e pode incluir outras movimentações.")
        }
        showDialog("Progressão", text)
    }

    private fun showHuntDetails() {
        val data = dashboardData
        val hunts = data?.optJSONArray("hunts")
        val level = data?.optJSONObject("player")?.optInt("level", 0) ?: 0
        if (hunts == null || hunts.length() == 0) {
            showDialog("Hunts", "Abra a conta no jogo para receber a lista de Hunts.")
            return
        }
        val current = data?.optJSONObject("player")?.optString("huntSlug") ?: "—"
        val lines = buildString {
            appendLine("Treinador Nv. $level · Hunt atual: $current")
            var unlocked = 0
            for (i in 0 until hunts.length()) {
                val h = hunts.optJSONObject(i) ?: continue
                val required = h.optInt("nivel", Int.MAX_VALUE)
                if (required <= level) unlocked++
            }
            appendLine("Hunts recebidas: ${hunts.length()} · liberadas pelo nível: $unlocked")
            appendLine()
            for (i in 0 until minOf(hunts.length(), 45)) {
                val h = hunts.optJSONObject(i) ?: continue
                val required = h.optInt("nivel", 0)
                val name = h.optString("nome", h.optString("slug", "Hunt"))
                appendLine("${if (required <= level) "✓" else "🔒"} $name · Nv. $required")
            }
            if (hunts.length() > 45) appendLine("… e mais ${hunts.length() - 45} Hunts.")
            appendLine()
            append("A lista mostra as Hunts recebidas e liberadas pelo nível. O ranking comparativo por objetivo aparece no Hunt Analyzer.")
        }
        showDialog("Hunts", lines)
    }


    private fun openHuntAnalyzer() {
        val ready = dashboardData?.optJSONObject("huntAnalysis")?.optString("status") == "estimativa_disponivel"
        if (ready) dashboardScroll.post { root.findViewWithTag<View>("hunt-analysis-result")?.let { dashboardScroll.smoothScrollTo(0, it.top) } } else requestHuntAnalysis()
    }

    private fun requestHuntAnalysis() {
        val requestedAt = System.currentTimeMillis()
        val account = selectedAccount
        val generation = ++huntPollGeneration
        val progress = AlertDialog.Builder(this)
            .setTitle("Analisando Hunts")
            .setMessage("Confirmando a janela do jogo e preparando a análise…")
            .setNegativeButton("Fechar", null)
            .create()
        progress.show()
        progress.setOnDismissListener { if (generation == huntPollGeneration) huntPollGeneration++ }
        val intent = Intent(OverlayService.ACTION_RUN_HUNT_ANALYSIS)
            .setPackage(packageName)
            .putExtra("accountId", account)
            .putExtra("requestId", requestedAt)
        sendBroadcast(intent)
        pollDashboardHuntAnalysis(account, requestedAt, 0, generation, progress)
    }

    private fun pollDashboardHuntAnalysis(account: Int, requestedAt: Long, attempt: Int, generation: Int, progress: AlertDialog) {
        if (isFinishing || generation != huntPollGeneration || attempt >= 60 || account != selectedAccount) {
            if (progress.isShowing) progress.dismiss()
            if (attempt >= 60 && generation == huntPollGeneration) showDialog("Hunt Analyzer", "A janela confirmou o pedido, mas a análise não terminou em 90 segundos. Consulte o diagnóstico desta conta.")
            return
        }
        dashboardIo.execute {
            val snapshot = CaptureStore.read(this, account)
            mainHandler.post {
                if (isFinishing || generation != huntPollGeneration || account != selectedAccount) {
                    if (progress.isShowing) progress.dismiss()
                    return@post
                }
                val receipt = snapshot?.optJSONObject("analysisRequest")
                val receiptMatches = receipt?.optLong("requestId", -1L) == requestedAt
                if (attempt >= 4 && !receiptMatches) {
                    if (progress.isShowing) progress.dismiss()
                    showDialog("Hunt Analyzer", "A janela da conta selecionada não confirmou o comando. A análise não foi iniciada. Verifique se essa janela do jogo está aberta e conectada.")
                    return@post
                }
                if (receiptMatches && !receipt!!.optBoolean("accepted", false)) {
                    if (progress.isShowing) progress.dismiss()
                    val reason = when (receipt.optString("reason")) {
                        "dados_indisponiveis" -> "A captura ainda não recebeu Pokémon, Hunts e catálogo suficientes. Aguarde a conexão terminar e tente novamente."
                        "bridge_indisponivel" -> "O leitor da página ainda não está ativo. Reabra a janela desta conta e aguarde o jogo carregar."
                        else -> "A janela recebeu o pedido, mas não conseguiu agendar a análise (" + receipt.optString("reason", "motivo não informado") + ")."
                    }
                    showDialog("Hunt Analyzer · não iniciado", reason)
                    return@post
                }
                val analysis = snapshot?.optJSONObject("huntAnalysis")
                if (analysis != null && analysis.optString("status") == "estimativa_disponivel" &&
                    analysis.optLong("calculadoEm", 0L) >= requestedAt) {
                    dashboardData = snapshot
                    if (progress.isShowing) progress.dismiss()
                    renderDashboard(loadSnapshot = false)
                    dashboardScroll.post { root.findViewWithTag<View>("hunt-analysis-result")?.let { dashboardScroll.smoothScrollTo(0, it.top) } }
                } else if (analysis?.optString("status") == "erro") {
                    if (progress.isShowing) progress.dismiss()
                    showDialog("Hunt Analyzer · erro", analysis.optString("message", "Não foi possível concluir a análise."))
                } else if (analysis?.optString("status") == "sem_dados") {
                    if (progress.isShowing) progress.dismiss()
                    showDialog("Hunt Analyzer · sem dados", "A análise executou, mas não encontrou Pokémon com atributos suficientes. Confira no diagnóstico se Pokémon com stats, Hunts e catálogo foram recebidos.")
                } else {
                    if (receiptMatches && receipt?.optBoolean("accepted", false) == true && progress.isShowing) {
                        progress.setMessage("Pedido confirmado pela janela. Calculando com os dados desta conta…")
                    }
                    mainHandler.postDelayed({ pollDashboardHuntAnalysis(account, requestedAt, attempt + 1, generation, progress) }, 1500)
                }
            }
        }
    }

    private fun addHuntAnalysisInline() {
        val analysis = dashboardData?.optJSONObject("huntAnalysis") ?: return
        val pokemon = analysis.optJSONArray("pokemon") ?: return
        if (pokemon.length() == 0) return
        val header = column().apply {
            tag = "hunt-analysis-result"
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedPanel(PANEL_ALT, 14)
            addView(label("RESULTADO DISPONÍVEL", 10f, ACCENT, true))
            addView(label("Comparativo por Pokémon · Nv. treinador ${analysis.optInt("nivelTreinador", 0)}", 13f, TEXT, true))
            addView(label("${analysis.optInt("pokemonAnalisados", 0)} Pokémon analisados. Estimativas teóricas; confirme no farm real antes de investir.", 11f, MUTED))
        }
        root.addView(header)
        space(8)
        addHuntObjectiveSummary(pokemon)
        for (i in 0 until minOf(pokemon.length(), 8)) {
            val p = pokemon.optJSONObject(i) ?: continue
            val noAoe = p.optJSONObject("melhorXpSemAoe")
            val withAoe = p.optJSONObject("melhorXpComAoe")
            val gold = p.optJSONObject("melhorGold")
            val card = column().apply {
                setPadding(dp(13), dp(11), dp(13), dp(11))
                background = roundedPanel(PANEL, 14)
                val active = if (p.optBoolean("ativo")) " · ATIVO" else ""
                addView(label("${p.optString("nome", "Pokémon")} · Nv. ${p.optInt("level", 0)}$active", 14f, TEXT, true))
                if (noAoe != null) {
                    addView(label("SEM AoE · ${noAoe.optString("nome")}", 10f, MUTED, true))
                    addView(label("XP Pokémon ${"%,d".format(noAoe.optLong("xpPokemonH"))}/h", 12f, TEXT, true))
                    addView(label("XP treinador ${"%,d".format(noAoe.optLong("xpTreinadorH"))}/h · Gold ${"%,d".format(noAoe.optLong("goldH"))}/h", 11f, MUTED))
                    addView(label("${"%,d".format(noAoe.optLong("abatesH"))} abates/h · ${noAoe.optString("regiao", "região não informada")}", 10f, MUTED))
                }
                if (withAoe != null) {
                    val labelAoe = if (p.optBoolean("tmAoe")) "COM AoE · equipada" else "COM AoE · hipótese"
                    addView(label("$labelAoe · ${withAoe.optString("nome")}", 10f, ACCENT, true))
                    addView(label("XP Pokémon ${"%,d".format(withAoe.optLong("xpPokemonHAoeHipotetico"))}/h", 12f, TEXT, true))
                    addView(label("XP treinador ${"%,d".format(withAoe.optLong("xpTreinadorHAoeHipotetico"))}/h · Gold ${"%,d".format(withAoe.optLong("goldHAoeHipotetico"))}/h", 11f, MUTED))
                    addView(label("${"%,d".format(withAoe.optLong("abatesHAoeHipotetico"))} abates/h · ${withAoe.optString("regiao", "região não informada")}", 10f, MUTED))
                }
                if (gold != null) addView(label("Melhor Gold sem AoE: ${gold.optString("nome")} · ${"%,d".format(gold.optLong("goldH"))}/h", 10f, MUTED))
                val goldAoe = p.optJSONObject("melhorGoldComAoe")
                if (goldAoe != null) addView(label("Melhor Gold com AoE (hipótese): ${goldAoe.optString("nome")} · ${"%,d".format(goldAoe.optLong("goldHAoeHipotetico"))}/h", 10f, MUTED))
                addView(label("${p.optInt("huntsAnalisadas", 0)} Hunts avaliadas · confiança baixa (modelo teórico)", 9f, MUTED))
            }
            root.addView(card)
            space(7)
        }
        root.addView(actionButton("Ver limitações e detalhes do cálculo", false) { showHuntAnalyzerDetails() })
    }

    private fun showHuntAnalyzerDetails() {
        val data = dashboardData
        val analysis = data?.optJSONObject("huntAnalysis")
        if (analysis == null) {
            showDialog("Hunt Analyzer", "Ainda não há resultado. Abra a conta e aguarde o catálogo oficial ser carregado.")
            return
        }
        val status = analysis.optString("status", "")
        if (status != "estimativa_disponivel") {
            showDialog("Hunt Analyzer", analysis.optString("message", "O analisador ainda não conseguiu calcular."))
            return
        }
        val pokemon = analysis.optJSONArray("pokemon")
        if (pokemon == null || pokemon.length() == 0) {
            showDialog("Hunt Analyzer", "Não encontrei Pokémon com espécie e atributos suficientes para simular.")
            return
        }
        val lines = buildString {
            appendLine("Treinador Nv. ${analysis.optInt("nivelTreinador", 0)} · Pokémon analisados: ${analysis.optInt("pokemonAnalisados", 0)}")
            appendLine("MEDIÇÃO DA SESSÃO · DADOS OBSERVADOS")
            val session = data?.optJSONObject("gameSession")
            val measured = data?.optJSONObject("measurements")
            val sessionMinutes = ((nullableLong(session, "ms") ?: 0L) / 60000L).toInt()
            fun rateText(value: Long?): String = value?.let { "%,d/h".format(it) } ?: "aguardando amostra"
            appendLine("XP Pokémon: ${rateText(sessionRate(session, "xpPokemon") ?: nullableLong(measured, "xpPokemonH"))}")
            appendLine("XP treinador: ${rateText(sessionRate(session, "xpTreinador") ?: nullableLong(measured, "xpTrainerH"))}")
            appendLine("Gold: ${rateText(sessionRate(session, "gold"))} · Abates: ${rateText(sessionRate(session, "abates") ?: nullableLong(measured, "killsH"))}")
            appendLine("HP positivo: ${nullableLong(measured, "survivalPct")?.let { "$it% das amostras" } ?: "sem amostras"} · Sessão: ${sessionMinutes} min")
            appendLine("Variação do saldo Coins: ${nullableLong(measured, "coinsNetH")?.let { "%,d/h".format(it) } ?: "aguardando medição"}")
            appendLine("Estes indicadores são da sessão/conta; não representam uma previsão individual de cada Hunt.")
            appendLine()
            appendLine("ESTIMATIVA TEÓRICA · HUNTS LIBERADAS")
            appendLine()
            for (i in 0 until minOf(pokemon.length(), 8)) {
                val p = pokemon.optJSONObject(i) ?: continue
                val activeLabel = if (p.optBoolean("ativo")) " · ATIVO" else ""
                val aoeLabel = if (p.optBoolean("tmAoe")) " · TM AoE equipada" else " · sem TM AoE"
                appendLine("━━ ${p.optString("nome", "Pokémon")} · Nv. ${p.optInt("level", 0)}$activeLabel$aoeLabel")
                val noAoe = p.optJSONObject("melhorXpSemAoe")
                val withAoe = p.optJSONObject("melhorXpComAoe")
                if (noAoe == null && withAoe == null) {
                    appendLine("  Sem Hunt liberada com dados suficientes.")
                    appendLine()
                    continue
                }
                if (noAoe != null) {
                    appendLine("  SEM AoE — ${noAoe.optString("nome")} (Nv. ${noAoe.optInt("nivel")})")
                    appendLine("    XP Pokémon/h: ${"%,d".format(noAoe.optLong("xpPokemonH"))} · XP treinador/h: ${"%,d".format(noAoe.optLong("xpTreinadorH"))}")
                    appendLine("    Gold/h: ${"%,d".format(noAoe.optLong("goldH"))} · abates/h: ${"%,d".format(noAoe.optLong("abatesH"))}")
                }
                if (withAoe != null) {
                    val mode = if (p.optBoolean("tmAoe")) "COM AoE (equipada)" else "COM AoE (hipótese se equipar)"
                    appendLine("  $mode — ${withAoe.optString("nome")} (Nv. ${withAoe.optInt("nivel")})")
                    appendLine("    XP Pokémon/h: ${"%,d".format(withAoe.optLong("xpPokemonHAoeHipotetico"))} · XP treinador/h: ${"%,d".format(withAoe.optLong("xpTreinadorHAoeHipotetico"))}")
                    appendLine("    Gold/h: ${"%,d".format(withAoe.optLong("goldHAoeHipotetico"))} · abates/h: ${"%,d".format(withAoe.optLong("abatesHAoeHipotetico"))}")
                }
                val goldBest = p.optJSONObject("melhorGold")
                if (goldBest != null) appendLine("  Melhor Gold sem AoE: ${goldBest.optString("nome")} · ${"%,d".format(goldBest.optLong("goldH"))}/h")
                val goldAoeBest = p.optJSONObject("melhorGoldComAoe")
                if (goldAoeBest != null) appendLine("  Melhor Gold com AoE (hipótese): ${goldAoeBest.optString("nome")} · ${"%,d".format(goldAoeBest.optLong("goldHAoeHipotetico"))}/h")
                appendLine("  Hunts analisadas: ${p.optInt("huntsAnalisadas", 0)}")
                appendLine()
            }
            appendLine("CONFIABILIDADE E LIMITAÇÕES")
            appendLine("• Sem AoE e com AoE são rankings separados; a tela identifica se a TM está equipada.")
            appendLine("• AoE usa provisoriamente 2,0 alvos por golpe; a geometria real de cada Hunt ainda não foi medida.")
            appendLine("• Ritmo de caminhada/spawn é genérico. Dano e TTK são estimativas, não resultados observados.")
            appendLine("• Poções, mortes, revives e sobrevivência real ainda não entram neste ranking.")
            appendLine("• Use como triagem inicial; não como promessa de rendimento.")
        }
        showDialog("Hunt Analyzer · comparativo", lines)
    }

    private fun addHuntObjectiveSummary(pokemon: org.json.JSONArray) {
        data class Candidate(val name: String, val hunt: String, val value: Long)
        fun best(aoe: Boolean, metric: String): Candidate? {
            val candidates = mutableListOf<Candidate>()
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
                if (value >= 0L) candidates.add(Candidate(p.optString("nome", "Pokémon"), result.optString("nome", "Hunt"), value))
            }
            return candidates.maxByOrNull { it.value }
        }
        val card = column().apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedPanel(PANEL, 14)
            addView(label("MELHORES OPÇÕES POR OBJETIVO", 11f, ACCENT, true))
            addView(label("Cada Pokémon usa sua própria melhor Hunt liberada. AoE é um cenário separado e hipotético.", 10f, MUTED))
            for ((title, metric, unit) in listOf(Triple("XP DO POKÉMON", "pokemon", "XP Pokémon/h"), Triple("XP DO TREINADOR", "trainer", "XP treinador/h"), Triple("GOLD", "gold", "Gold/h"))) {
                addView(label(title, 10f, TEXT, true))
                val normal = best(false, metric)
                val aoe = best(true, metric)
                if (normal != null) addView(label("Sem AoE: " + normal.name + " · " + normal.hunt + " · " + "%,d".format(normal.value) + " " + unit, 11f, TEXT))
                else addView(label("Sem AoE: sem resultado", 10f, MUTED))
                if (aoe != null) addView(label("Com AoE (hipótese): " + aoe.name + " · " + aoe.hunt + " · " + "%,d".format(aoe.value) + " " + unit, 10f, MUTED))
            }
        }
        root.addView(card)
        space(8)
    }

    private fun showInventoryDetails() {
        val session = dashboardData?.optJSONObject("gameSession")
        if (session == null) {
            showDialog("Recursos", "Os contadores da sessão ainda não foram recebidos. Abra a conta no jogo e aguarde a sincronização.")
            return
        }
        val labels = linkedMapOf(
            "capturas" to "Capturas", "shiniesVistos" to "Shinies vistos", "shinies" to "Shinies registrados",
            "bossTokens" to "Boss Tokens", "fragChave" to "Fragmentos de chave", "fragShiny" to "Fragmentos Shiny",
            "fragBicicleta" to "Fragmentos de bicicleta", "fragMega" to "Fragmentos Mega",
            "fragMegaShiny" to "Fragmentos Mega Shiny", "tmPeca" to "Peças de TM",
            "tmPecaAoe" to "Peças de TM AoE", "ticketMistico" to "Tickets Místicos",
            "ticketSafari" to "Tickets Safari", "fragOvoShiny" to "Fragmentos de ovo Shiny"
        )
        val lines = buildString {
            appendLine("RECURSOS REGISTRADOS NA SESSÃO")
            for ((key, title) in labels) if (session.has(key) && !session.isNull(key)) appendLine(title + ": " + session.optLong(key))
            for (key in listOf("bolas", "pocoes")) {
                val values = session.optJSONObject(key) ?: continue
                appendLine()
                appendLine(if (key == "bolas") "BOLAS" else "POÇÕES")
                val keys = values.keys()
                var shown = 0
                while (keys.hasNext() && shown < 20) {
                    val id = keys.next()
                    appendLine(id + ": " + values.optLong(id))
                    shown++
                }
                if (values.length() > shown) appendLine("… e mais " + (values.length() - shown) + " tipos.")
            }
            appendLine()
            append("Contadores da sessão mantidos pelo jogo; não representam necessariamente todo o inventário e não estimam valor de mercado. Nenhum item é usado, vendido ou transferido.")
        }
        showDialog("Recursos da conta", lines)
    }

    private fun showPokemonDetails() {
        val data = dashboardData
        val player = data?.optJSONObject("player")
        val pokemons = player?.optJSONArray("pokemons")
        if (pokemons == null || pokemons.length() == 0) {
            showDialog("Pokémon", "Abra a conta no jogo para receber a equipe e o depósito.")
            return
        }
        val activeId = player?.optString("activeId")
        val lines = buildString {
            appendLine("Pokémon recebidos: ${pokemons.length()}")
            appendLine()
            for (i in 0 until minOf(pokemons.length(), 35)) {
                val p = pokemons.optJSONObject(i) ?: continue
                val name = p.optString("nome", "Espécie ${p.opt("speciesId")}")
                val active = p.optString("id") == activeId
                val shiny = if (p.optBoolean("shiny")) " ✨" else ""
                appendLine("${if (active) "▶ " else ""}$name$shiny · Nv. ${p.optInt("level", 0)} · ID ${p.optString("id")}")
                val stats = p.optJSONObject("stats")
                if (stats != null) appendLine("  HP ${stats.optInt("hp")} · ATK ${stats.optInt("atk")} · DEF ${stats.optInt("def")} · SpA ${stats.optInt("spAtk")} · SpD ${stats.optInt("spDef")} · SPD ${stats.optInt("speed")}")
            }
            if (pokemons.length() > 35) appendLine("… e mais ${pokemons.length() - 35} Pokémon.")
        }
        showDialog("Pokémon da conta", lines)
    }

    private fun showShinyDetails() {
        val data = dashboardData
        val session = data?.optJSONObject("gameSession")
        val balls = sumJsonObject(session?.optJSONObject("bolas"))
        val seen = nullableLong(session, "shiniesVistos")
        val captured = nullableLong(session, "shinies")
        val diag = data?.optJSONObject("diagnostics")
        val eventKinds = diag?.optJSONObject("battleEventKinds")
        var eventTypeCount = 0
        var observedEventCount = 0
        eventKinds?.keys()?.forEach { key ->
            eventTypeCount++
            observedEventCount += eventKinds.optInt(key, 0).coerceAtLeast(0)
        }
        val eventSamples = diag?.optJSONObject("battleEventSamples")
        var sampleCount = 0
        eventSamples?.keys()?.forEach { key ->
            sampleCount += eventSamples.optJSONArray(key)?.length() ?: 0
        }
        val sessionAvailable = diag?.optBoolean("gameSessionAvailable", false) == true
        val lines = buildString {
            appendLine("Bolas consumidas na sessão: ${balls ?: "aguardando"}")
            appendLine("Shinies vistos (contador do jogo): ${seen ?: "aguardando"}")
            appendLine("Shinies registrados (contador do jogo): ${captured ?: "aguardando"}")
            appendLine("Contador local da sessão encontrado: ${if (sessionAvailable) "sim" else "não"}")
            appendLine()
            appendLine("Eventos de batalha observados: $observedEventCount em $eventTypeCount tipos")
            appendLine("Amostras técnicas guardadas: $sampleCount (disponíveis no diagnóstico)")
            appendLine()
            appendLine("Regra do PokeIdle.io: chance-base de 1 em 24.000 por lançamento elegível; a Outland não gera Shiny.")
            appendLine()
            append("A probabilidade acumulada continua indisponível até identificar o evento real de lançamento e confirmar espécie com forma Shiny, área elegível e bônus ativos. O contador total de bolas não é suficiente.")
        }
        showDialog("Shiny Hunt", lines)
    }

    private fun showMarketDetails() {
        val data = dashboardData
        val market = data?.optJSONObject("market")
        val linesArray = market?.optJSONArray("linhas")
        if (market == null || linesArray == null || linesArray.length() == 0) {
            showDialog("Mercado", "Ainda não há registros capturados. Abra a aba ou o histórico desejado no Mercado do jogo; a leitura é passiva e não compra nem anuncia itens.")
            return
        }
        fun amount(value: Any?): Long? {
            if (value == null || value == org.json.JSONObject.NULL) return null
            if (value is Number) return value.toLong()
            return value.toString().filter { it.isDigit() }.toLongOrNull()
        }
        fun currencyLabel(currency: String): String = when {
            currency.contains("orb") || currency.contains("gem") -> "Gemas"
            currency.contains("gold") || currency.contains("coin") -> "Coins"
            else -> currency.ifBlank { "moeda não identificada" }
        }
        val tab = market.optString("aba", "")
        val totalRecords = market.optInt("total", 0)
        val isSalesHistory = tab == "historicoGlobal"
        val isActiveListings = tab == "vitrine" || tab == "meus"
        data class SaleSample(val price: Long, val currency: String, val isPokemon: Boolean, val name: String, val speciesKey: String)
        val samples = mutableListOf<SaleSample>()
        for (i in 0 until linesArray.length()) {
            val item = linesArray.optJSONObject(i) ?: continue
            val ficha = item.optJSONObject("ficha")
            val currency = item.optString("moeda", "").lowercase()
            val price = amount(item.opt("bruto") ?: item.opt("preco")) ?: continue
            if (price <= 0L) continue
            val isPokemon = ficha != null && (ficha.has("speciesId") || ficha.has("level"))
            val name = ficha?.optString("nome")?.takeIf { it.isNotBlank() }
                ?: item.optString("nome", if (isPokemon) "Pokémon" else "Registro")
            val speciesId = ficha?.opt("speciesId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            val speciesKey = if (speciesId != null) "id:" + speciesId else "nome:" + name.trim().lowercase(java.util.Locale.ROOT)
            samples.add(SaleSample(price, currency, isPokemon, name, speciesKey))
        }
        val lines = buildString {
            appendLine("ORIGEM DOS DADOS")
            appendLine("Aba: ${tab.ifBlank { "não identificada" }} · Capturado em: ${market.optLong("atualizadoEm", 0L).takeIf { it > 0L }?.let { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR"))?.format(java.util.Date(it)) } ?: "horário indisponível"}")
            appendLine("Registros guardados: ${linesArray.length()} · Total informado pelo jogo: $totalRecords")
            val pageCount = market.optJSONArray("paginasObservadas")?.length() ?: 1
            appendLine("Páginas observadas: $pageCount")
            if (totalRecords > 0) {
                val coverage = (100.0 * linesArray.length() / totalRecords).coerceIn(0.0, 100.0)
                appendLine("Cobertura da amostra: ${"%.1f".format(coverage)}% do total informado")
            }
            appendLine()
            when {
                isSalesHistory -> {
                    appendLine("VENDAS CONCLUÍDAS · HISTÓRICO DO JOGO")
                    appendLine("Somente registros desta aba entram nas estatísticas de vendas. A captura pode conter apenas parte do histórico.")
                    appendLine()
                    for (currency in listOf("orb", "gold")) {
                        val group = samples.filter { it.currency.contains(currency) }
                        if (group.isEmpty()) continue
                        val sorted = group.map { it.price }.sorted()
                        val middle = sorted.size / 2
                        val median = if (sorted.size % 2 == 0) ((sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0).toLong() else sorted[middle]
                        val volume = group.sumOf { it.price }
                        val estimatedNet = group.sumOf { sample ->
                            val rate = when {
                                sample.currency.contains("orb") && sample.isPokemon && sample.price <= 500L -> 0.13
                                sample.currency.contains("orb") && sample.isPokemon && sample.price <= 5000L -> 0.10
                                sample.currency.contains("orb") && sample.isPokemon -> 0.075
                                else -> 0.15
                            }
                            (sample.price * (1.0 - rate)).toLong()
                        }
                        appendLine("${currencyLabel(currency)} · ${group.size} vendas observadas")
                        appendLine("Volume bruto: ${"%,d".format(volume)} · Mediana: ${"%,d".format(median)}")
                        appendLine("Líquido estimado após comissão do jogo: ${"%,d".format(estimatedNet)}")
                        if (currency == "orb") appendLine("Gemas líquidas ainda não são BRL: saque/conversão têm etapa e spread próprios.")
                        appendLine()
                    }
                    val pokemonSales = samples.count { it.isPokemon }
                    val itemSales = samples.size - pokemonSales
                    appendLine("Composição: $pokemonSales vendas de Pokémon · $itemSales vendas de itens")
                    appendLine()
                    appendLine("REFERÊNCIA POR ESPÉCIE · VENDAS OBSERVADAS")
                    for (currency in listOf("orb", "gold")) {
                        val speciesGroups = samples.filter { it.isPokemon && it.currency.contains(currency) }
                            .groupBy { it.speciesKey }.values.sortedByDescending { it.size }.take(6)
                        if (speciesGroups.isEmpty()) continue
                        appendLine(currencyLabel(currency))
                        for (group in speciesGroups) {
                            val prices = group.map { it.price }.sorted()
                            val mid = prices.size / 2
                            val med = if (prices.size % 2 == 0) ((prices[mid - 1].toDouble() + prices[mid].toDouble()) / 2.0).toLong() else prices[mid]
                            val range = "%,d".format(prices.first()) + "–" + "%,d".format(prices.last())
                            val confidence = if (group.size >= 5) "amostra moderada" else if (group.size >= 3) "amostra pequena" else "amostra insuficiente"
                            appendLine("• " + group.first().name + ": n=" + group.size + " · mediana " + "%,d".format(med) + " · faixa " + range + " · " + confidence)
                        }
                    }
                    appendLine()
                    appendLine("A mediana usa somente registros capturados. A referência por espécie não prevê vendas futuras nem representa todo o mercado; sem ID de espécie, nomes iguais podem ser agrupados.")
                }
                isActiveListings -> {
                    appendLine("ANÚNCIOS ATIVOS · NÃO SÃO VENDAS")
                    appendLine("Estes valores representam preços pedidos pelos vendedores. Não entram no volume vendido nem em receita realizada.")
                    val groups = samples.groupBy { it.currency }
                    for ((currency, group) in groups) {
                        val prices = group.map { it.price }.sorted()
                        appendLine("${currencyLabel(currency)} · ${group.size} anúncios com preço legível")
                        appendLine("Faixa anunciada: ${"%,d".format(prices.first())}–${"%,d".format(prices.last())}")
                    }
                }
                else -> {
                    appendLine("TIPO DE REGISTRO NÃO CONFIRMADO")
                    appendLine("Não vou classificar estes valores como vendas concluídas nem como receita. Abra a Tabela de preços do jogo para consultar o histórico de vendas.")
                }
            }
            appendLine()
            appendLine("AMOSTRA CAPTURADA")
            for (i in 0 until minOf(linesArray.length(), 18)) {
                val item = linesArray.optJSONObject(i) ?: continue
                val ficha = item.optJSONObject("ficha")
                val name = ficha?.optString("nome")?.takeIf { it.isNotBlank() } ?: item.optString("nome", "Registro")
                val price = amount(item.opt("bruto") ?: item.opt("preco"))
                val currency = item.optString("moeda", "")
                appendLine("$name · ${price?.let { "%,d".format(it) } ?: "preço —"} ${currencyLabel(currency)}")
                if (isSalesHistory) {
                    val isPokemon = ficha != null && (ficha.has("speciesId") || ficha.has("level"))
                    if (price != null && price > 0L) {
                        val rate = when {
                            currency.contains("orb", true) && isPokemon && price <= 500L -> 0.13
                            currency.contains("orb", true) && isPokemon && price <= 5000L -> 0.10
                            currency.contains("orb", true) && isPokemon -> 0.075
                            currency.contains("orb", true) || currency.contains("gold", true) -> 0.15
                            else -> 0.0
                        }
                        if (rate > 0.0) appendLine("  Comissão aproximada: ${(rate * 100).toInt()}% · Líquido estimado: ${"%,d".format((price * (1.0 - rate)).toLong())}")
                    }
                } else {
                    appendLine("  Status observado: ${item.optString("status", "não informado")}")
                }
            }
            if (linesArray.length() > 18) appendLine("… e mais ${linesArray.length() - 18} registros.")
            appendLine()
            append("Leitura passiva: nenhuma compra, venda ou publicação é executada. Nenhum nick de jogador é exibido.")
        }
        showDialog(if (isSalesHistory) "Mercado · vendas observadas" else "Mercado · leitura de registros", lines)
    }

    private fun addAnalysisOption(title: String, description: String, action: () -> Unit) {
        val option = column().apply {
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedPanel(PANEL_ALT, 14)
            addView(label(title, 12f, ACCENT, true))
            addView(label(description, 12f, TEXT))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        root.addView(option)
        space(8)
    }

    private fun nullableLong(obj: org.json.JSONObject?, key: String): Long? {
        val raw = obj?.opt(key) ?: return null
        if (raw == org.json.JSONObject.NULL) return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun metricCard(title: String): View = column().apply {
        val display = metricDisplay(title)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = roundedPanel(PANEL_ALT, 14)
        addView(label(title, 9f, ACCENT, true))
        addView(label(display.first, 23f, TEXT, true))
        addView(label(display.second, 9f, MUTED))
    }

    private fun metricDisplay(title: String): Pair<String, String> {
        val data = dashboardData
        val m = data?.optJSONObject("measurements")
        val session = data?.optJSONObject("gameSession")
        val player = data?.optJSONObject("player")
        val minutes = m?.optInt("sampleMinutes", 0) ?: 0
        val sessionMinutes = ((nullableLong(session, "ms") ?: 0L) / 60000L).toInt()
        val value = when (title) {
            "XP DO POKÉMON / H" -> sessionRate(session, "xpPokemon") ?: nullableLong(m, "xpPokemonH") ?: -1L
            "XP DO TREINADOR / H" -> sessionRate(session, "xpTreinador") ?: nullableLong(m, "xpTrainerH") ?: -1L
            "GOLD GANHO / H" -> sessionRate(session, "gold") ?: -1L
            "COINS ATUAIS" -> nullableLong(data, "coinsBalance")
                ?: nullableLong(player, "coins") ?: nullableLong(player, "gold") ?: -1L
            "ABATES / H" -> sessionRate(session, "abates") ?: nullableLong(m, "killsH") ?: -1L
            "HP POSITIVO / AMOSTRAS" -> nullableLong(m, "survivalPct") ?: -1L
            "BOLAS CONSUMIDAS / SESSÃO" -> sumJsonObject(session?.optJSONObject("bolas")) ?: -1L
            "SHINIES / SESSÃO" -> nullableLong(session, "shinies") ?: -1L
            else -> -1L
        }
        if (value < 0 && title != "COINS LÍQUIDOS / H") {
            val detail = if (minutes > 0) "coletando · ${minutes} min" else "aguardando medição"
            return "—" to detail
        }
        val formatted = when (title) {
            "HP POSITIVO / AMOSTRAS" -> "${value}%"
            else -> "%,d".format(value)
        }
        val detail = when (title) {
            "HP POSITIVO / AMOSTRAS" -> "percentual de amostras com HP > 0; não é chance de sobreviver"
            "GOLD GANHO / H" -> "contador nativo do jogo · sessão de ${sessionMinutes} min"
            "BOLAS CONSUMIDAS / SESSÃO" -> "contador nativo de bolas usadas na sessão"
            "SHINIES / SESSÃO" -> "contador nativo de Shinies registrados na sessão"
            "COINS ATUAIS" -> "saldo atual da conta"
            else -> "contador de sessão ou amostra do leitor"
        }
        return formatted to detail
    }

    private fun addInfoCard(title: String, description: String) {
        val card = column().apply {
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedPanel(PANEL)
            addView(label(title, 13f, ACCENT, true))
            addView(label(description, 12f, TEXT))
        }
        root.addView(card)
        space(8)
    }

    private fun sectionTitle(value: String) {
        root.addView(label(value, 12f, ACCENT, true))
        space(8)
    }

    private fun updatePermissionStatus() {
        permissionStatus.text = if (Settings.canDrawOverlays(this)) {
            "Sobreposição: liberada ✓"
        } else {
            "Sobreposição: ainda não liberada. Autorize para abrir as janelas."
        }
    }

    private fun startAccount(id: Int) {
        when (id) {
            1 -> start(Conta1Service::class.java)
            2 -> start(Conta2Service::class.java)
            3 -> start(Conta3Service::class.java)
        }
    }

    private fun start(cls: Class<*>) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Libere primeiro a permissão de sobreposição.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startForegroundService(Intent(this, cls))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a janela: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun actionButton(title: String, primary: Boolean = true, action: () -> Unit) =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 13f
            setTextColor(if (primary) BG else TEXT)
            backgroundTintList = ColorStateList.valueOf(if (primary) ACCENT else PANEL_ALT)
            setOnClickListener { action() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun space(height: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun roundedPanel(color: Int, radiusDp: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0xFF34485A.toInt())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
