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
  let samples = [];
  let kills = [];
  let marketData = null;
  let catalogData = null;
  const diagnostics = {
    socketSeen: 0, textFrames: 0, jsonFrames: 0, binaryFrames: 0,
    welcome: 0, estado: 0, batalha: 0, market: 0,
    lastType: null, lastMessageAt: 0, lastPublishAt: 0,
    typeCounts: {}, battleEventKinds: {}, lastMessageKeys: []
  };
  const safeMarketLine = (line) => {
    const f = line && line.ficha ? line.ficha : {};
    const ficha = {};
    for (const k of ['id','speciesId','nome','level','nota','ivTotal','quality','potencia','shiny','tmAoe','tmElemental','refinoTotal','stats','ivs']) {
      if (f[k] !== undefined) ficha[k] = f[k];
    }
    const out = {};
    for (const k of ['id','tipo','moeda','bruto','preco','em','criadoEm','compravelEm','expiraEm','pokemonId']) {
      if (line && line[k] !== undefined) out[k] = line[k];
    }
    out.ficha = ficha;
    return out;
  };
  const safePokemon = (p) => ({
    id: p.id, pokeId: p.pokeId, speciesId: p.speciesId, nome: p.nome,
    level: p.level, xp: p.xp, ivs: p.ivs, stats: p.stats,
    tipos: p.tipos ?? null, tmAoe: p.tmAoe ?? p.tm_aoe ?? null,
    tmElemental: p.tmElemental ?? null, maxHp: p.maxHp, hp: p.hp,
    shiny: !!p.shiny, potencia: p.potencia, nota: p.nota,
    quality: p.quality ?? null, iv: p.iv ?? null, slot: p.slot ?? null,
    poder: p.poder ?? null, refino: p.refino ?? null
  });
  const readCoins = () => {
    const candidates = [state?.coins, state?.gold, state?.player?.coins, state?.player?.gold];
    for (const value of candidates) {
      if (value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))) {
        return { value: Number(value), source: value === state?.coins ? 'state.coins' : value === state?.gold ? 'state.gold' : 'nested-player' };
      }
    }
    return { value: null, source: null };
  };
  const measure = () => {
    const now = Date.now();
    samples = samples.filter(s => now - s.t <= 15 * 60 * 1000);
    kills = kills.filter(t => now - t <= 15 * 60 * 1000);
    let xpPokemonH = null, xpTrainerH = null, minutes = 0;
    if (samples.length >= 2) {
      const first = samples[0], last = samples[samples.length - 1];
      const elapsed = last.t - first.t;
      minutes = Math.round(elapsed / 60000);
      if (elapsed >= 120000) {
        let p = 0, tr = 0;
        for (let i = 1; i < samples.length; i++) {
          p += Math.max(0, samples[i].px - samples[i - 1].px);
          tr += Math.max(0, samples[i].tx - samples[i - 1].tx);
        }
        xpPokemonH = Math.round(p * 3600000 / elapsed);
        xpTrainerH = Math.round(tr * 3600000 / elapsed);
      }
    }
    const killSpan = kills.length >= 2 ? kills[kills.length - 1] - kills[0] : 0;
    const killsH = kills.length >= 5 && killSpan >= 120000
      ? Math.round((kills.length - 1) * 3600000 / killSpan) : null;
    const coinSamples = samples.filter(s => s.coins != null);
    let coinsNetH = null;
    if (coinSamples.length >= 2) {
      const firstCoin = coinSamples[0], lastCoin = coinSamples[coinSamples.length - 1];
      const coinElapsed = lastCoin.t - firstCoin.t;
      if (coinElapsed >= 120000) {
        coinsNetH = Math.round((lastCoin.coins - firstCoin.coins) * 3600000 / coinElapsed);
      }
    }
    const hpSamples = samples.filter(s => s.hp != null && s.maxHp > 0);
    const survivalPct = hpSamples.length
      ? Math.round(100 * hpSamples.filter(s => s.hp > 0).length / hpSamples.length) : null;
    return { xpPokemonH, xpTrainerH, killsH, survivalPct, coinsNetH, sampleMinutes: minutes, killSamples: kills.length };
  };
  const publish = (kind, force = false) => {
    if (!state) return;
    const now = Date.now();
    // Atualizações incrementais podem chegar muitas vezes por segundo.
    // Amostra tudo em memória, mas persiste no Android no máximo a cada 8 s.
    if (!force && (kind === 'estado' || kind === 'batalha') &&
        now - diagnostics.lastPublishAt < 8000) return;
    diagnostics.lastPublishAt = now;
    const pokemons = Array.isArray(state.pokemons) ? state.pokemons.map(safePokemon) : [];
    const team = pokemons.filter(p => p.slot != null)
      .sort((a, b) => (Number(a.slot) || 0) - (Number(b.slot) || 0));
    const active = pokemons.find(p => String(p.id) === String(state.activeId)) || null;
    const metrics = measure();
    const coinData = readCoins();
    const automation = state.automation || {};
    const loja = state.loja || {};
    const guild = state.guild || {};
    const twitch = state.twitch || null;
    const kick = state.kick || null;
    send({
      kind, status: 'connected', connected: true, ready: true,
      nick: state.nick ?? null, level: state.level,
      coinsBalance: coinData.value, currencySource: coinData.source,
      gold: coinData.value, coins: coinData.value, xpTreinador: Number(state.xp) || 0,
      activeId: state.activeId, huntSlug: state.huntSlug,
      team, pokemonCount: pokemons.length,
      gameAuto: {
        autoPotion: !!automation.autoPotion, autoRevive: !!automation.autoRevive,
        autoBall: !!automation.autoBall, ballIds: automation.ballIds ?? [],
        potionIds: automation.potionIds ?? [], autoVoltarHunt: !!automation.autoVoltarHunt
      },
      euXp: {
        level: state.level,
        loja: { vipAte: Number(loja.vipAte) || 0, boosts: loja.boosts ?? {}, atrasados: loja.atrasados ?? null },
        guildBonusPct: Number(state.guildBonusPct) || 0,
        guild: { boostAte: Number(guild.boostAte) || 0 },
        twitch: twitch ? { assistindo: !!twitch.assistindo, pctAtual: Number(twitch.pctAtual) || 0 } : null,
        kick: kick ? { pct: Number(kick.pct) || 0, bonus: kick.bonus ?? {} } : null,
        evento: state.evento ?? null
      },
      xpMedido: {
        kph: metrics.killsH, kphReal: metrics.killsH != null,
        nivelPokemon: active?.level ?? null, minutos: metrics.sampleMinutes,
        xpPokemonH: metrics.xpPokemonH
      },
      hunts: hunts.map(h => ({
        slug: h.slug, nome: h.nome, nivel: h.nivel,
        regiao: h.regiao, area: h.area,
        especies: Array.isArray(h.especies) ? h.especies.map(e => ({
          pokeId: e.pokeId, pontos: e.pontos
        })) : []
      })),
      player: {
        level: state.level, xp: Number(state.xp) || 0,
        coins: coinData.value, gold: coinData.value,
        activeId: state.activeId, huntSlug: state.huntSlug,
        activePokemon: active, pokemonCount: pokemons.length, pokemons
      },
      catalog: catalogData,
      measurements: metrics, market: marketData,
      diagnostics: {
        ...diagnostics,
        huntsWithSpecies: hunts.filter(h => Array.isArray(h.especies) && h.especies.length > 0).length,
        huntSpeciesEntries: hunts.reduce((n, h) => n + (Array.isArray(h.especies) ? h.especies.length : 0), 0),
        typeChartEntries: Object.keys(catalogData?.combate?.tabelaTipos || {}).length,
        pokemonWithStats: pokemons.filter(p => p.stats && typeof p.stats === 'object').length,
        pokemonWithSpeciesId: pokemons.filter(p => p.speciesId != null).length,
        activePokemonFound: !!active,
        teamCount: team.length
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
          diagnostics.socketSeen++;
          ws.addEventListener('open', () => send({ kind: 'connection', status: 'connected' }));
          ws.addEventListener('close', () => send({ kind: 'connection', status: 'disconnected' }));
          ws.addEventListener('message', (event) => {
            if (socket !== ws) return;
            if (typeof event.data !== 'string') {
              diagnostics.binaryFrames++;
              return;
            }
            diagnostics.textFrames++;
            let msg;
            try { msg = JSON.parse(event.data); } catch (_) { return; }
            diagnostics.jsonFrames++;
            diagnostics.lastType = typeof msg.t === 'string' ? msg.t : 'sem-t';
            diagnostics.lastMessageAt = Date.now();
            diagnostics.typeCounts[diagnostics.lastType] = (diagnostics.typeCounts[diagnostics.lastType] || 0) + 1;
            diagnostics.lastMessageKeys = Object.keys(msg).slice(0, 24);
            if (msg.t === 'welcome') {
              diagnostics.welcome++;
              state = null;
              hunts = Array.isArray(msg.hunts) ? msg.hunts : [];
              // Catálogo/constantes enviados no welcome pelo próprio servidor do jogo.
              // Só guardamos dados públicos de jogo necessários ao analisador.
              catalogData = {
                at: Date.now(),
                hunts,
                bolas: (msg.catalogoBolas ?? []).map(b => ({
                  id: b.id, nome: b.nome, priceGold: Number(b.priceGold) || 0,
                  compravel: !!b.compravel, catchRate: b.catchRate ?? null
                })),
                compraveis: (msg.itensCompraveis ?? []).map(i => ({
                  itemId: i.itemId, preco: Number(i.preco) || 0
                })),
                captura: msg.captura ?? null,
                chanceShiny: Number(msg.chanceShiny) || 0,
                combate: {
                  tabelaTipos: msg.tabelaTipos ?? {},
                  ampliacaoHunt: msg.ampliacaoHunt ?? 1.5,
                  multHpSelvagem: msg.multHpSelvagem ?? 5,
                  multDanoSelvagem: msg.multDanoSelvagem ?? 1.8
                },
                tiposBoost: Array.isArray(msg.tiposBoost) ? msg.tiposBoost : [],
                captura: msg.captura ?? null,
                chanceShiny: Number(msg.chanceShiny) || 0,
                mercado: {
                  catalogo: Array.isArray(msg.mercado?.catalogo)
                    ? msg.mercado.catalogo.map(i => ({ id: i.id, nome: i.nome, categoria: i.categoria ?? null }))
                    : []
                },
                itensNossos: Array.isArray(msg.itensNossos)
                  ? msg.itensNossos.map(i => ({ id: i.id, name: i.name, npcPrice: i.npcPrice ?? null }))
                  : [],
                itensCompraveis: Array.isArray(msg.itensCompraveis)
                  ? msg.itensCompraveis.map(i => ({ itemId: i.itemId, preco: i.preco }))
                  : [],
                catalogoBolas: Array.isArray(msg.catalogoBolas)
                  ? msg.catalogoBolas.map(i => ({ id: i.id, nome: i.nome, priceGold: i.priceGold, compravel: !!i.compravel, catchRate: i.catchRate ?? null }))
                  : []
              };
              merge(msg.estado);
              publish('welcome', true);
            } else if (msg.t === 'estado' && state) {
              diagnostics.estado++;
              merge(msg.estado);
              const list = Array.isArray(state.pokemons) ? state.pokemons : [];
              const active = list.find(p => String(p.id) === String(state.activeId));
              if (active) {
                samples.push({
                  t: Date.now(), px: Number(active.xp) || 0,
                  tx: Number(state.xp) || 0,
                  hp: Number.isFinite(Number(active.hp)) ? Number(active.hp) : null,
                  maxHp: Number(active.maxHp) || 0,
                  coins: readCoins().value
                });
              }
              publish('estado');
            } else if (msg.t === 'batalha' && Array.isArray(msg.ev)) {
              diagnostics.batalha++;
              for (const ev of msg.ev) {
                if (ev && typeof ev.k === 'string') {
                  diagnostics.battleEventKinds[ev.k] = (diagnostics.battleEventKinds[ev.k] || 0) + 1;
                }
                if (ev && ev.k === 'morte' && ev.quem === 'selvagem') kills.push(Date.now());
              }
              publish('batalha');
            } else if (msg.t === 'market' && Array.isArray(msg.linhas)) {
              diagnostics.market++;
              marketData = {
                aba: msg.aba || null, pagina: Number(msg.pagina) || 0,
                total: Number(msg.total) || 0, porPagina: Number(msg.porPagina) || 0,
                temMais: !!msg.temMais,
                linhas: msg.linhas.map(safeMarketLine)
              };
              send({ kind: 'market', status: 'connected', market: marketData,
                diagnostics: { ...diagnostics }, updatedAt: Date.now() });
            }
          });
        }
      } catch (_) {}
      return ws;
    }
  });
  send({ kind: 'capture-ready', status: 'listening',
    diagnostics: { ...diagnostics }, updatedAt: Date.now() });
})();
    """.trimIndent()
}

object CaptureStore {
    private fun file(context: Context, accountId: Int) =
        File(context.filesDir, "pokeidle_capture_account_$accountId.json")

    @Synchronized
    fun save(context: Context, accountId: Int, data: JSONObject) {
        val target = AtomicFile(file(context, accountId))
        var stream: java.io.FileOutputStream? = null
        try {
            // Eventos de conexão não apagam o último snapshot completo recebido.
            val kind = data.optString("kind")
            val previous = read(context, accountId)
            val merged = if (previous != null && (kind == "connection" || kind == "capture-ready" || kind == "market")) {
                JSONObject(previous.toString()).apply {
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, data.get(key))
                    }
                }
            } else data
            stream = target.startWrite()
            stream.write(merged.toString().toByteArray(StandardCharsets.UTF_8))
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
