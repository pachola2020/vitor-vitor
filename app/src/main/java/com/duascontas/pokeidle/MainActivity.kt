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
    private lateinit var accountStatus: TextView
    private lateinit var permissionStatus: TextView
    private var selectedAccount = 1
    private var dashboardData: org.json.JSONObject? = null
    private var dashboardLoadGeneration = 0
    private val accountNames = listOf("Principal", "Alt 1", "Alt 2")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)
        renderDashboard()
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
            Thread {
                val snapshot = CaptureStore.read(this, requestedAccount)
                runOnUiThread {
                    if (!isFinishing && selectedAccount == requestedAccount &&
                        generation == dashboardLoadGeneration
                    ) {
                        dashboardData = snapshot
                        renderDashboard(loadSnapshot = false)
                    }
                }
            }.start()
        }

        val top = row()
        val brand = column()
        brand.addView(label("POKEIDLE", 12f, ACCENT, true))
        brand.addView(label("Assistant", 28f, TEXT, true))
        brand.addView(label("Painel pessoal de acompanhamento", 13f, MUTED))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val version = label("TESTE\n${BuildConfig.VERSION_NAME}", 11f, MUTED, true).apply {
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
            addView(label("✦  HUNTER", 12f, 0xFF8DE0D1.toInt(), true))
            addView(label("Seu companion durante o jogo", 19f, TEXT, true))
            addView(label("Consultas rápidas de Shiny, bônus de XP e Hunts. O processamento pesado só começa quando você pedir.", 12f, 0xFFD2DCE5.toInt()))
            addView(actionButton("Abrir jogo com Hunter", true) {
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
        addAnalysisOption("HUNTS", "Hunts liberadas, níveis e Hunt atual da conta", ::showHuntDetails)
        addAnalysisOption("HUNT ANALYZER", "Ranking preliminar por Pokémon: XP/h, Gold/h, abates/h e cenário AoE", ::showHuntAnalyzerDetails)
        root.addView(actionButton("Calcular Hunt Analyzer agora", true) { requestHuntAnalysis() })
        addAnalysisOption("POKÉMON", "Equipe, Pokémon ativo, nível, IVs e atributos recebidos", ::showPokemonDetails)
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

        sectionTitle("SOBRE ESTE TESTE")
        root.addView(label("A versão original e a instalação anterior do Assistant continuam instaladas e não serão removidas por esta migração. Esta versão usa um pacote separado para preservar os dados antigos; será necessário entrar novamente no jogo nesta instalação. As janelas continuam usando o mecanismo flutuante existente. O dashboard não automatiza ações do jogo.", 12f, MUTED))
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
            append("Esta tela lista os dados recebidos; o ranking comparativo e a simulação com/sem AoE ainda não estão conectados.")
        }
        showDialog("Hunts", lines)
    }

    private fun requestHuntAnalysis() {
        val intent = Intent(OverlayService.ACTION_RUN_HUNT_ANALYSIS)
            .setPackage(packageName)
            .putExtra("accountId", selectedAccount)
        sendBroadcast(intent)
        Toast.makeText(
            this,
            "Solicitação enviada. Mantenha a janela da conta selecionada aberta e consulte o resultado em Hunt Analyzer.",
            Toast.LENGTH_LONG
        ).show()
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
            appendLine("• AoE usa provisoriamente 2,2 alvos por golpe; a geometria real de cada Hunt ainda não foi medida.")
            appendLine("• Ritmo de caminhada/spawn é genérico. Dano e TTK são estimativas, não resultados observados.")
            appendLine("• Poções, mortes, revives e sobrevivência real ainda não entram neste ranking.")
            appendLine("• Use como triagem inicial; não como promessa de rendimento.")
        }
        showDialog("Hunt Analyzer · comparativo", lines)
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
            val digits = value.toString().filter { it.isDigit() }
            return digits.toLongOrNull()
        }
        val lines = buildString {
            appendLine("Aba: ${market.optString("aba", "não identificada")} · Página: ${market.optInt("pagina", 0)}")
            appendLine("Registros observados: ${linesArray.length()} · Total informado: ${market.optInt("total", 0)}")
            appendLine("Páginas observadas: ${market.optJSONArray("paginasObservadas")?.length() ?: 1}")
            appendLine()
            for (i in 0 until minOf(linesArray.length(), 25)) {
                val item = linesArray.optJSONObject(i) ?: continue
                val ficha = item.optJSONObject("ficha")
                val name = ficha?.optString("nome")?.takeIf { it.isNotBlank() } ?: item.optString("nome", "Registro")
                val priceRaw = item.opt("preco") ?: item.opt("bruto")
                val gross = amount(priceRaw)
                val currency = item.optString("moeda", "").lowercase()
                val isPokemon = ficha != null && (ficha.has("speciesId") || ficha.has("level"))
                val currencyLabel = when {
                    currency.contains("gem") -> "Gems"
                    currency.contains("coin") || currency.contains("gold") -> "Coins"
                    else -> currency.ifBlank { "moeda não identificada" }
                }
                appendLine("$name · ${gross?.let { "%,d".format(it) } ?: "preço —"} $currencyLabel")
                appendLine("  Status observado: ${item.optString("status", "não informado")}")
                if (gross != null && (currency.contains("gem") || currency.contains("coin") || currency.contains("gold"))) {
                    val feeRate = when {
                        currency.contains("gem") && isPokemon && gross <= 500L -> 0.13
                        currency.contains("gem") && isPokemon && gross <= 5000L -> 0.10
                        currency.contains("gem") && isPokemon -> 0.075
                        else -> 0.15
                    }
                    val fee = if (currency.contains("gem")) kotlin.math.ceil(gross * feeRate).toLong() else (gross * feeRate).toLong()
                    appendLine("  Comissão estimada: ${"%,d".format(fee)} (${(feeRate * 100).toInt()}%)")
                    appendLine("  Líquido estimado se vender: ${"%,d".format((gross - fee).coerceAtLeast(0L))} $currencyLabel")
                }
                if (isPokemon) {
                    val days = item.optInt("dias", item.optInt("duracao", 0)).coerceIn(0, 30)
                    if (days > 0) appendLine("  Custo do anúncio: ${"%,d".format(days * 100000L)} Coins (100.000/dia)")
                }
                val serverNet = amount(item.opt("liquido") ?: item.opt("valorLiquido"))
                if (serverNet != null) appendLine("  Líquido informado pelo jogo: ${"%,d".format(serverNet)}")
            }
            if (linesArray.length() > 25) appendLine("… e mais ${linesArray.length() - 25} registros.")
            appendLine()
            append("As comissões são estimativas baseadas nos Termos atuais. Anúncio ativo não é venda concluída; só o status/histórico confirmado permite contabilizar uma venda. Nenhuma ação é executada.")
        }
        showDialog("Mercado · custos e líquido", lines)
    }

    private fun addAnalysisOption(title: String, description: String, action: () -> Unit) {
        val option = column().apply {
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = roundedPanel(PANEL)
            addView(label(title, 13f, ACCENT, true))
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
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedPanel(PANEL)
        addView(label(title, 10f, MUTED, true))
        addView(label(display.first, 25f, TEXT, true))
        addView(label(display.second, 10f, MUTED))
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
        setStroke(dp(1), 0xFF2A3948.toInt())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
