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

    private fun renderDashboard() {
        root.removeAllViews()

        val top = row()
        val brand = column()
        brand.addView(label("POKEIDLE", 12f, ACCENT, true))
        brand.addView(label("Assistant", 28f, TEXT, true))
        brand.addView(label("Painel pessoal de acompanhamento", 13f, MUTED))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val version = label("TESTE\n0.5.2", 11f, MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(PANEL_ALT)
        }
        top.addView(version)
        root.addView(top)
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
        val notice = column().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundColor(PANEL)
            addView(label(captureSummary().first, 11f, ACCENT, true))
            addView(label(captureSummary().second, 13f, TEXT))
        }
        root.addView(notice)
        space(12)

        val metricRows = listOf(
            listOf("XP DO POKÉMON / H", "XP DO TREINADOR / H"),
            listOf("COINS ATUAIS", "COINS LÍQUIDOS / H"),
            listOf("ABATES / H", "SOBREVIVÊNCIA"),
            listOf("ENCONTROS SHINY / H", "SHINY CAPTURADOS")
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
        addInfoCard("PROGRESSÃO", "XP/h, Gold/h, abates/h e sobrevivência. Comparação por Hunt e por Pokémon.")
        addInfoCard("ECONOMIA", "Recursos negociáveis, vendas concluídas, taxas e lucro líquido estimado.")
        addInfoCard("SHINY HUNT", "Encontros elegíveis, capturas, bônus aplicáveis e probabilidade acumulada.")
        addInfoCard("LEITOR DE HUNT", "Captura passiva de Hunts, Pokémon ativo e dados de equipe. O ranking calculado com e sem AoE será conectado na próxima etapa.")
        space(18)

        sectionTitle("JANELAS FLUTUANTES")
        val controls = column().apply {
            setPadding(dp(13), dp(8), dp(13), dp(8))
            setBackgroundColor(PANEL)
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
        root.addView(label("A versão original continua separada e não é alterada por este teste. As janelas do jogo continuam usando o mecanismo flutuante existente. O dashboard não automatiza ações do jogo.", 12f, MUTED))
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
        val data = CaptureStore.read(this, selectedAccount)
            ?: return "AGUARDANDO DADOS DO JOGO" to "Abra a conta selecionada para iniciar a leitura. A captura é instalada antes do WebSocket e não envia comandos ao jogo."
        val status = data.optString("status", "unknown")
        if (status == "unsupported") {
            return "CAPTURA INDISPONÍVEL NESTE WEBVIEW" to "O componente de leitura do Android não está disponível nesta versão. Nenhum dado foi capturado."
        }
        val hunts = data.optJSONArray("hunts")?.length() ?: 0
        val player = data.optJSONObject("player")
        val active = player?.optJSONObject("activePokemon")
        val pokemon = active?.optString("nome")?.takeIf { !it.isNullOrBlank() } ?: "não identificado"
        val teamCount = data.optJSONArray("team")?.length() ?: 0
        val catalog = data.optJSONObject("catalog")
        val typeTableSize = catalog?.optJSONObject("combate")?.optJSONObject("tabelaTipos")?.length() ?: 0
        val combatReady = typeTableSize > 0
        val hunt = player?.optString("huntSlug")?.takeIf { !it.isNullOrBlank() } ?: "não identificada"
        val level = player?.optInt("level", 0) ?: 0
        val stamp = data.optLong("updatedAt", 0L)
        val age = if (stamp > 0) ((System.currentTimeMillis() - stamp) / 1000).coerceAtLeast(0) else -1
        val freshness = if (age >= 0) "último pacote há ${age}s" else "sem horário"
        val market = data.optJSONObject("market")
        val marketCount = market?.optJSONArray("linhas")?.length() ?: 0
        val marketText = if (marketCount > 0) " · Mercado: $marketCount registros lidos (${market?.optString("aba")})" else ""
        val diag = data.optJSONObject("diagnostics")
        val frames = diag?.optInt("jsonFrames", 0) ?: 0
        val welcomeCount = diag?.optInt("welcome", 0) ?: 0
        val estadoCount = diag?.optInt("estado", 0) ?: 0
        val lastType = diag?.optString("lastType")?.takeIf { !it.isNullOrBlank() } ?: "nenhum"
        val huntsWithSpecies = diag?.optInt("huntsWithSpecies", 0) ?: 0
        val speciesEntries = diag?.optInt("huntSpeciesEntries", 0) ?: 0
        val statsCount = diag?.optInt("pokemonWithStats", 0) ?: 0
        val speciesIdCount = diag?.optInt("pokemonWithSpeciesId", 0) ?: 0
        val typeEntries = diag?.optInt("typeChartEntries", 0) ?: 0
        val activeFound = diag?.optBoolean("activePokemonFound", false) == true
        val messageTypes = diag?.optJSONObject("typeCounts")?.toString() ?: "{}"
        val battleKinds = diag?.optJSONObject("battleEventKinds")?.toString() ?: "{}"
        val diagnosticText = " · Protocolo: $frames JSON, welcome $welcomeCount, estado $estadoCount, última: $lastType · Tipos de mensagem: $messageTypes · Eventos de batalha: $battleKinds · Estrutura: $huntsWithSpecies Hunts/$speciesEntries grupos de espécies, $statsCount Pokémon com stats, $speciesIdCount com speciesId, $typeEntries tipos, ativo ${if (activeFound) "ok" else "não encontrado"}"
        return if (player != null && hunts > 0) {
            "DADOS RECEBIDOS · ${accountNames[selectedAccount - 1].uppercase()}" to
                "$hunts Hunts recebidas · Equipe: $teamCount Pokémon · Catálogo de combate: ${if (combatReady) "recebido" else "incompleto"} · Treinador Nv. $level · Hunt: $hunt · Pokémon ativo: $pokemon$marketText$diagnosticText · $freshness. Captura observacional; ranking comparativo ainda não está integrado."
        } else if (status == "connected") {
            "WEBSOCKET CONECTADO · AGUARDANDO QUADRO INICIAL" to
                "A conexão abriu, mas ainda não chegou o quadro inicial. $diagnosticText · $freshness."
        } else {
            "LEITOR INSTALADO · AGUARDANDO WEBSOCKET" to
                "O leitor foi instalado, mas ainda não recebeu o quadro inicial. $diagnosticText · $freshness."
        }
    }

    private fun metricCard(title: String): View = column().apply {
        val display = metricDisplay(title)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(PANEL)
        addView(label(title, 10f, MUTED, true))
        addView(label(display.first, 25f, TEXT, true))
        addView(label(display.second, 10f, MUTED))
    }

    private fun metricDisplay(title: String): Pair<String, String> {
        val data = CaptureStore.read(this, selectedAccount)
        val m = data?.optJSONObject("measurements")
        val minutes = m?.optInt("sampleMinutes", 0) ?: 0
        val player = data?.optJSONObject("player")
        val value = when (title) {
            "XP DO POKÉMON / H" -> m?.optLong("xpPokemonH", -1L) ?: -1L
            "XP DO TREINADOR / H" -> m?.optLong("xpTrainerH", -1L) ?: -1L
            "COINS ATUAIS" -> data?.optLong("coinsBalance", player?.optLong("coins", player?.optLong("gold", -1L) ?: -1L) ?: -1L) ?: -1L
            "COINS LÍQUIDOS / H" -> m?.let { if (it.has("coinsNetH")) it.optLong("coinsNetH") else Long.MIN_VALUE } ?: Long.MIN_VALUE
            "ABATES / H" -> m?.optLong("killsH", -1L) ?: -1L
            "SOBREVIVÊNCIA" -> (m?.optInt("survivalPct", -1) ?: -1).toLong()
            else -> -1L
        }
        if (title == "") return "—" to ""
        if (value == Long.MIN_VALUE) return "—" to "variação ainda não medida"
        if (value < 0 && title != "COINS LÍQUIDOS / H") {
            val detail = if (minutes > 0) "coletando · ${minutes} min" else "aguardando medição"
            return "—" to detail
        }
        val formatted = when (title) {
            "SOBREVIVÊNCIA" -> "${value}%"
            "COINS LÍQUIDOS / H" -> (if (value > 0) "+" else "") + "%,d".format(value)
            else -> "%,d".format(value)
        }
        val detail = when (title) {
            "SOBREVIVÊNCIA" -> "amostras com HP > 0"
            "COINS ATUAIS" -> "saldo da conta"
            "COINS LÍQUIDOS / H" -> "variação do saldo · ${minutes} min"
            else -> "janela: ${minutes} min"
        }
        return formatted to detail
    }

    private fun addInfoCard(title: String, description: String) {
        val card = column().apply {
            setPadding(dp(13), dp(11), dp(13), dp(11))
            setBackgroundColor(PANEL)
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
