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
import java.util.concurrent.Executors

/**
 * Captura passiva de mensagens recebidas pelo WebSocket oficial do jogo.
 * Não lê cookies/tokens e não envia mensagens ao servidor.
 */
object GameDataCapture {
    private val persistenceExecutor = Executors.newSingleThreadExecutor()
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

        try {
            WebViewCompat.addWebMessageListener(
                webView,
                "PokeIdleCapture",
                setOf(ORIGIN)
            ) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || sourceOrigin.scheme != "https" ||
                    sourceOrigin.host != "pokeidle.io" || sourceOrigin.port != -1
                ) return@addWebMessageListener
                try {
                    val raw = message.data ?: return@addWebMessageListener
                    if (raw.length > 2_000_000) return@addWebMessageListener
                    val payload = JSONObject(raw)
                    payload.put("accountId", accountId)
                    payload.put("updatedAt", System.currentTimeMillis())
                    val appContext = context.applicationContext
                    persistenceExecutor.execute {
                        CaptureStore.save(appContext, accountId, payload)
                    }
                } catch (_: Exception) {
                    // Dados inválidos nunca podem interromper o jogo.
                }
            }

            WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(ORIGIN))
        } catch (e: Exception) {
            // Falha do mecanismo de captura não pode impedir o jogo de abrir.
            CaptureStore.save(context, accountId, JSONObject()
                .put("accountId", accountId)
                .put("status", "capture_error")
                .put("error", e.javaClass.simpleName)
                .put("updatedAt", System.currentTimeMillis()))
        }
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
  let fieldData = null;
  let catalogData = null;
  const diagnostics = {
    socketSeen: 0, textFrames: 0, jsonFrames: 0, binaryFrames: 0,
    welcome: 0, estado: 0, batalha: 0, market: 0, field: 0,
    lastType: null, lastMessageAt: 0, lastPublishAt: 0, marketMessageKeys: [], marketLineKeys: [], fieldMessageKeys: [],
    typeCounts: {}, battleEventKinds: {}, lastMessageKeys: []
  };
  const safeMarketLine = (line) => {
    const f = line && (line.ficha || line.pokemon) ? (line.ficha || line.pokemon) : {};
    const ficha = {};
    for (const k of ['id','speciesId','nome','level','nota','ivTotal','quality','potencia','shiny','tmAoe','tmElemental','refinoTotal','stats','ivs','poder','refino','natureza','habilidade','tipo1','tipo2']) {
      if (f[k] !== undefined) ficha[k] = f[k];
    }
    const out = {};
    for (const k of ['id','tipo','moeda','bruto','preco','em','criadoEm','compravelEm','expiraEm','pokemonId','comissao','taxa','liquido','valorLiquido','precoLiquido','dias','duracao','status','vendidoEm','compradoEm','precoVenda','categoria','itemId','nome','quantidade']) {
      if (line && line[k] !== undefined) out[k] = line[k];
    }
    out.ficha = ficha;
    return out;
  };
  const sanitizeField = (value, depth = 0) => {
    if (depth > 4 || value == null) return null;
    if (typeof value === 'string') return value.slice(0, 120);
    if (typeof value === 'number' || typeof value === 'boolean') return value;
    if (Array.isArray(value)) return value.slice(0, 80).map(v => sanitizeField(v, depth + 1));
    if (typeof value === 'object') {
      const out = {};
      for (const key of Object.keys(value).slice(0, 100)) {
        if (/token|cookie|auth|password|senha|email|session|credential/i.test(key)) continue;
        out[key] = sanitizeField(value[key], depth + 1);
      }
      return out;
    }
    return null;
  };
  const safePokemon = (p) => {
    if (!p || typeof p !== 'object' || Array.isArray(p)) return null;
    return {
    id: p.id, pokeId: p.pokeId, speciesId: p.speciesId, nome: p.nome,
    level: p.level, xp: p.xp, ivs: p.ivs, stats: p.stats,
    tipos: p.tipos ?? null, tmAoe: p.tmAoe ?? p.tm_aoe ?? null,
    tmElemental: p.tmElemental ?? null, maxHp: p.maxHp, hp: p.hp,
    shiny: !!p.shiny, potencia: p.potencia, nota: p.nota,
    quality: p.quality ?? null, iv: p.iv ?? null, slot: p.slot ?? null,
    poder: p.poder ?? null, refino: p.refino ?? null
    };
  };
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
        let trainerXp = 0;
        for (let i = 1; i < samples.length; i++) {
          trainerXp += Math.max(0, samples[i].tx - samples[i - 1].tx);
        }
        xpTrainerH = Math.round(trainerXp * 3600000 / elapsed);

        // XP do Pokémon só é comparado dentro da sequência contínua do mesmo ativo.
        // Trocar o Pokémon não pode produzir XP/h artificialmente alto ou negativo.
        const activeSamples = [];
        const activeId = String(last.pid ?? '');
        for (let i = samples.length - 1; i >= 0; i--) {
          if (String(samples[i].pid ?? '') !== activeId) break;
          activeSamples.unshift(samples[i]);
        }
        if (activeSamples.length >= 2) {
          const pokemonElapsed = activeSamples[activeSamples.length - 1].t - activeSamples[0].t;
          if (pokemonElapsed >= 120000) {
            let pokemonXp = 0;
            for (let i = 1; i < activeSamples.length; i++) {
              pokemonXp += Math.max(0, activeSamples[i].px - activeSamples[i - 1].px);
            }
            xpPokemonH = Math.round(pokemonXp * 3600000 / pokemonElapsed);
          }
        }
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
    if (!force && (kind === 'estado' || kind === 'batalha' || kind === 'field') &&
        now - diagnostics.lastPublishAt < 8000) return;
    diagnostics.lastPublishAt = now;
    const pokemons = Array.isArray(state.pokemons)
      ? state.pokemons.map(safePokemon).filter(Boolean) : [];
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
      activeId: state.activeId, huntSlug: state.huntSlug, outlandTier: state.outlandTier ?? null,
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
        level: state.level, xp: Number(state.xp) || 0, outlandTier: state.outlandTier ?? null,
        coins: coinData.value, gold: coinData.value,
        activeId: state.activeId, huntSlug: state.huntSlug,
        activePokemon: active, pokemonCount: pokemons.length, pokemons
      },
      catalog: catalogData,
      measurements: metrics, market: marketData, fieldData,
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
      state = { ...rest, pokemons: Array.isArray(pokemons)
        ? pokemons.filter(p => p && typeof p === 'object' && !Array.isArray(p)) : [] };
      return;
    }
    Object.assign(state, rest);
    const map = new Map((Array.isArray(state.pokemons) ? state.pokemons : [])
      .filter(p => p && typeof p === 'object' && p.id != null)
      .map(p => [p.id, p]));
    if (Array.isArray(pokemons)) {
      map.clear();
      for (const p of pokemons) {
        if (p && typeof p === 'object' && p.id != null) map.set(p.id, p);
      }
    }
    for (const p of (Array.isArray(pkMud) ? pkMud : [])) {
      if (p && typeof p === 'object' && p.id != null) {
        map.set(p.id, { ...(map.get(p.id) || {}), ...p });
      }
    }
    for (const id of (Array.isArray(pkFora) ? pkFora : [])) map.delete(id);
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
                outlandTiers: Array.isArray(msg.outlandTiers) ? msg.outlandTiers.map(t => ({
                  id: t.id ?? null, nivel: t.nivel ?? null,
                  mult: t.mult ?? t.multiplicador ?? null
                })) : [],
                bolas: (Array.isArray(msg.catalogoBolas) ? msg.catalogoBolas : []).map(b => ({
                  id: b.id, nome: b.nome, priceGold: Number(b.priceGold) || 0,
                  compravel: !!b.compravel, catchRate: b.catchRate ?? null
                })),
                compraveis: (Array.isArray(msg.itensCompraveis) ? msg.itensCompraveis : []).map(i => ({
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
              merge(msg.estado && typeof msg.estado === 'object' && !Array.isArray(msg.estado) ? msg.estado : {});
              publish('welcome', true);
            } else if (msg.t === 'estado' && state) {
              diagnostics.estado++;
              merge(msg.estado && typeof msg.estado === 'object' && !Array.isArray(msg.estado) ? msg.estado : null);
              const list = Array.isArray(state.pokemons) ? state.pokemons : [];
              const active = list.find(p => String(p.id) === String(state.activeId));
              if (active) {
                samples.push({
                  t: Date.now(), pid: active.id, px: Number(active.xp) || 0,
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
            } else if (msg.t === 'market') {
              diagnostics.market++;
              diagnostics.marketMessageKeys = Object.keys(msg).slice(0, 30);
              const rawLines = [msg.linhas, msg.vendas, msg.historico, msg.registros, msg.itens].find(Array.isArray) || [];
              const incoming = rawLines.map(safeMarketLine);
              diagnostics.marketLineKeys = incoming[0] ? Object.keys(incoming[0]) : [];
              if (incoming.length > 0) {
                const page = Number(msg.pagina) || 0;
                const sameTab = marketData && marketData.aba === (msg.aba || null);
                const previous = page > 0 && sameTab ? marketData.linhas : [];
                const byId = new Map(previous.map((line, index) => [
                  String(line.id ?? line.pokemonId ?? (line.ficha?.id ?? index)), line
                ]));
                incoming.forEach((line, index) => byId.set(
                  String(line.id ?? line.pokemonId ?? (line.ficha?.id ?? (page + ':' + index))), line
                ));
                marketData = {
                  aba: msg.aba || null, pagina: page,
                  total: Number(msg.total) || 0, porPagina: Number(msg.porPagina) || 0,
                  temMais: !!msg.temMais, linhas: [...byId.values()].slice(0, 500),
                  paginasObservadas: page > 0 && sameTab
                    ? [...new Set([...(marketData.paginasObservadas || []), page])]
                    : [page],
                  atualizadoEm: Date.now()
                };
              }
              send({ kind: 'market', status: 'connected', market: marketData,
                diagnostics: { ...diagnostics }, updatedAt: Date.now() });
            } else if (msg.t === 'campo' || msg.t === 'campo.init') {
              diagnostics.field++;
              diagnostics.fieldMessageKeys = Object.keys(msg).slice(0, 30);
              fieldData = { type: msg.t, capturedAt: Date.now(), payload: sanitizeField(msg) };
              diagnostics.fieldDataKeys = Object.keys(fieldData.payload || {}).slice(0, 40);
              publish('field', true);
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
