package com.duascontas.pokeidle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        // Página onde ficam as versões novas do app (confira se o nome do repositório está certo)
        const val RELEASES_URL = "https://github.com/pachola2020/pokeidle-contas/releases"
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

                          val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        col.addView(text("PokeIdle - Contas", 22f))
        col.addView(text("Três contas em janelas flutuantes, com login separado, mais a janela Hunt (ranking de hunts).", 14f))

        status = text("", 15f)
        col.addView(status)

        col.addView(text("PASSO 1 - Permissões (só na primeira vez)", 16f))
        col.addView(button("1. Permitir exibir sobre outros apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("2. Permitir notificações") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                Toast.makeText(this, "Já está liberado neste Android", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(button("3. Bateria: deixar sem restrições") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        col.addView(
            text(
                "No POCO: Configurações > Apps > PokeIdle Contas > Economia de bateria > " +
                    "\"Sem restrições\", e ative também \"Início automático\". " +
                    "Isso evita que o sistema feche o jogo em segundo plano.",
                13f
            )
        )

        col.addView(text("PASSO 2 - Janelas", 16f))
        col.addView(button("Abrir as 3 contas") {
            start(Conta1Service::class.java)
            start(Conta2Service::class.java)
            start(Conta3Service::class.java)
        })
        col.addView(button("Abrir só a conta 1") { start(Conta1Service::class.java) })
        col.addView(button("Abrir só a conta 2") { start(Conta2Service::class.java) })
        col.addView(button("Abrir só a conta 3") { start(Conta3Service::class.java) })
        col.addView(button("Abrir janela Hunt (ranking)") { start(HuntService::class.java) })
        col.addView(button("Atualizar tudo (recarregar as janelas)") {
            sendBroadcast(Intent(OverlayService.ACTION_RELOAD_ALL).setPackage(packageName))
        })
        col.addView(button("Fechar todas as janelas") {
            sendBroadcast(Intent(OverlayService.ACTION_CLOSE_ALL).setPackage(packageName))
        })

        col.addView(text("Atualizar o app", 16f))
        col.addView(button("Baixar versão nova (abre o GitHub)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
        })

        col.addView(
            text(
                "Dicas:\n" +
                    "• Arraste pela barra de título para mover; \"◢ tamanho\" redimensiona.\n" +
                    "• ⌨: para digitar ou colar (login, status do Pokémon), toque em ⌨ (fica verde), " +
                    "toque no campo e digite. Toque em ⌨ de novo para soltar o teclado.\n" +
                    "• ◐: muda a transparência (100%, 60%, 30%).\n" +
                    "• ↻: recarrega. —: minimiza para uma bolinha (o jogo continua rodando); " +
                    "toque na bolinha para voltar. ✕: fecha.\n" +
                    "• Na notificação há \"Fechar tudo\".\n" +
                                                  "• O áudio do jogo fica bloqueado para não atrapalhar outros apps.\n" +
                    "• Se a internet cair, a janela tenta reconectar sozinha a cada 10 segundos.\n" +
                    "• Na janela Hunt, cole os status do seu Pokémon uma vez (use o ⌨); depois ficam salvos.",
                13f
            )
        )

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this)) {
            "✔ Exibir sobre outros apps: liberado"
        } else {
            "✖ Falta liberar \"Exibir sobre outros apps\" (botão 1)"
        }
    }

    private fun start(cls: Class<*>) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Antes, libere \"Exibir sobre outros apps\" (botão 1)", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, cls))
    }

    private fun text(t: String, size: Float): TextView = TextView(this).apply {
        text = t
        textSize = size
        setPadding(0, 12, 0, 12)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
