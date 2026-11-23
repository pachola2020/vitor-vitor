package com.duascontas.pokeidle

import android.content.Context
import android.util.AtomicFile
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Captura passiva de mensagens recebidas pelo WebSocket oficial do jogo.
 * Não lê cookies/tokens e não envia mensagens ao servidor.
 */
object GameDataCapture {
    private const val ORIGIN = "https://pokeidle.io"

    fun install(context: Context, webView: WebView, accountId: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            CaptureStore.save(context, accountId, JSONObject()
                .put("accountId", accountId)
                .put("status", "unsupported")
                .put("updatedAt", System.currentTimeMillis()))
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "PokeIdleCapture",
            setOf(ORIGIN)
        ) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || sourceOrigin.toString() != ORIGIN) return@addWebMessageListener
            try {
                val payload = JSONObject(message.data ?: return@addWebMessageListener)
                payload.put("accountId", accountId)
                payload.put("updatedAt", System.currentTimeMillis())
                CaptureStore.save(context, accountId, payload)
            } catch (_: Exception) {
                // Dados inválidos nunca podem interromper o jogo.
            }
        }

        WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(ORIGIN))
    }

    private val SCRIPT = """
(() => {
  if (window.__pokeIdleAssistantCapture) return;
  window.__pokeIdleAssistantCapture = true;
  const send = (data) => {
    try {
      if (window.PokeIdleCapture && typeof window.PokeIdleCapture.postMessage === 'function') {
        window.PokeIdleCapture.postMessage(JSON.stringify(data));
      }
    } catch (_) {}
  };
  let state = null;
  let hunts = [];
  let socket = null;
  const safePokemon = (p) => ({
    id: p.id, speciesId: p.speciesId, nome: p.nome, level: p.level,
    ivs: p.ivs, stats: p.stats, tipos: p.tipos, tmAoe: !!p.tmAoe,
    tmElemental: !!p.tmElemental, maxHp: p.maxHp, hp: p.hp,
    shiny: !!p.shiny, potencia: p.potencia, nota: p.nota
  });
  const publish = (kind) => {
    if (!state) return;
    const pokemons = Array.isArray(state.pokemons) ? state.pokemons.map(safePokemon) : [];
    const active = pokemons.find(p => String(p.id) === String(state.activeId)) || null;
    send({
      kind, status: 'connected',
      hunts: hunts.map(h => ({
        slug: h.slug, nome: h.nome, nivel: h.nivel,
        regiao: h.regiao, area: h.area,
        especies: Array.isArray(h.especies) ? h.especies.map(e => ({
          pokeId: e.pokeId, pontos: e.pontos
        })) : []
      })),
      player: {
        level: state.level, activeId: state.activeId,
        huntSlug: state.huntSlug, activePokemon: active,
        pokemonCount: pokemons.length, pokemons
      }
    });
  };
  const merge = (partial) => {
    if (!partial) return;
    const { cheio, pokemons, pkMud, pkFora, dexMud, dexFora, ...rest } = partial;
    if (cheio || !state) {
      state = { ...rest, pokemons: Array.isArray(pokemons) ? pokemons.slice() : [] };
      return;
    }
    Object.assign(state, rest);
    const map = new Map((state.pokemons || []).map(p => [p.id, p]));
    if (Array.isArray(pokemons)) {
      map.clear();
      for (const p of pokemons) map.set(p.id, p);
    }
    for (const p of (pkMud || [])) map.set(p.id, { ...(map.get(p.id) || {}), ...p });
    for (const id of (pkFora || [])) map.delete(id);
    state.pokemons = [...map.values()];
    if (dexMud || dexFora) {
      state.pokedex = { ...(state.pokedex || {}), ...(dexMud || {}) };
      for (const id of (dexFora || [])) delete state.pokedex[id];
    }
  };
  const NativeWebSocket = window.WebSocket;
  window.WebSocket = new Proxy(NativeWebSocket, {
    construct(target, args, newTarget) {
      const ws = Reflect.construct(target, args, newTarget);
      try {
        const u = new URL(String(args[0]), location.href);
        if (u.host === location.host) {
          socket = ws;
          ws.addEventListener('open', () => send({ kind: 'connection', status: 'connected' }));
          ws.addEventListener('close', () => send({ kind: 'connection', status: 'disconnected' }));
          ws.addEventListener('message', (event) => {
            if (socket !== ws || typeof event.data !== 'string') return;
            let msg;
            try { msg = JSON.parse(event.data); } catch (_) { return; }
            if (msg.t === 'welcome') {
              state = null;
              hunts = Array.isArray(msg.hunts) ? msg.hunts : [];
              merge(msg.estado);
              publish('welcome');
            } else if (msg.t === 'estado' && state) {
              merge(msg.estado);
              publish('estado');
            }
          });
        }
      } catch (_) {}
      return ws;
    }
  });
  send({ kind: 'capture-ready', status: 'listening' });
})();
    """.trimIndent()
}

object CaptureStore {
    private fun file(context: Context, accountId: Int) =
        File(context.filesDir, "pokeidle_capture_account_$accountId.json")

    fun save(context: Context, accountId: Int, data: JSONObject) {
        val target = AtomicFile(file(context, accountId))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(data.toString().toByteArray(StandardCharsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) target.failWrite(stream)
        }
    }

    fun read(context: Context, accountId: Int): JSONObject? {
        return try {
            val f = file(context, accountId)
            if (f.exists()) JSONObject(f.readText(StandardCharsets.UTF_8)) else null
        } catch (_: Exception) {
            null
        }
    }
}
