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
                    // O callback do WebMessageListener roda na thread principal.
                    // Faça somente a validação do tamanho aqui; parse e persistência ficam fora da UI.
                    val appContext = context.applicationContext
                    persistenceExecutor.execute {
                        try {
                            var payload = JSONObject(raw)
                            val replaceSnapshot = payload.optBoolean("_replaceSnapshot", false)
                            payload.remove("_replaceSnapshot")
                            if (!replaceSnapshot) {
                                val previous = CaptureStore.read(appContext, accountId)
                                if (previous != null) {
                                    val keys = payload.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        previous.put(key, payload.opt(key))
                                    }
                                    payload = previous
                                }
                            }
                            payload.put("accountId", accountId)
                            payload.put("updatedAt", System.currentTimeMillis())
                            CaptureStore.save(appContext, accountId, payload)
                        } catch (_: Exception) {
                            // Payload inválido não pode interromper a interface nem o jogo.
                        }
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
  let staticSnapshotSent = false;
  let sessionDiagnostics = { available: false, keys: [] };
  // Estatísticas oficiais da sessão que o próprio jogo mantém no armazenamento local.
  // Só são lidos contadores de jogo; nunca cookies, tokens ou credenciais.
  const readGameSession = () => {
    const nick = String(state?.nick ?? '').trim();
    if (!nick) return null;
    try {
      const raw = localStorage.getItem('sessao-hunt:' + nick.toLowerCase());
      if (!raw) return null;
      const source = JSON.parse(raw);
      if (!source || typeof source !== 'object' || Array.isArray(source)) return null;
      sessionDiagnostics = { available: true, keys: Object.keys(source).filter(k => !/token|cookie|auth|password|senha|email|credential/i.test(k)).slice(0, 60) };
      const out = {};
      for (const key of ['ms','xpTreinador','xpPokemon','gold','abates','capturas',
        'shiniesVistos','shinies','bossTokens','fragChave','fragShiny',
        'fragBicicleta','fragMega','fragMegaShiny','tmPeca','tmPecaAoe',
        'ticketMistico','ticketSafari','fragOvoShiny']) {
        const n = Number(source[key]);
        if (Number.isFinite(n) && n >= 0) out[key] = n;
      }
      for (const key of ['bolas','pocoes']) {
        const map = source[key];
        const safe = {};
        if (map && typeof map === 'object' && !Array.isArray(map)) {
          for (const [id, value] of Object.entries(map).slice(0, 80)) {
            const n = Number(value);
            if (Number.isFinite(n) && n >= 0) safe[id] = n;
          }
        }
        out[key] = safe;
      }
      return out;
    } catch (_) {
      sessionDiagnostics = { available: false, keys: [] };
      return null;
    }
  };
  // Histórico de capturas mantido pelo próprio jogo (quando disponível). Apenas campos públicos de cada captura são copiados.
  const readCaptureHistory = () => {
    const nick = String(state?.nick ?? '').trim();
    if (!nick) return { available: false, records: [], count: 0 };
    try {
      const raw = localStorage.getItem('capturas-hist:' + nick.toLowerCase());
      if (!raw) return { available: false, records: [], count: 0 };
      const history = JSON.parse(raw);
      if (!Array.isArray(history)) return { available: false, records: [], count: 0 };
      const records = history.slice(0, 100).map(c => {
        const ivs = c?.ivs && typeof c.ivs === 'object'
          ? Object.values(c.ivs).reduce((total, value) => total + (Number(value) || 0), 0)
          : null;
        return {
          em: Number(c?.em) || 0,
          nome: String(c?.nome ?? '—').slice(0, 40),
          level: Number(c?.level) || 0,
          shiny: !!c?.shiny,
          speciesId: c?.speciesId ?? null,
          quality: c?.quality ?? null,
          ivTotal: Number.isFinite(ivs) ? ivs : null
        };
      });
      return {
        available: true,
        records,
        count: records.length,
        latestAt: records.reduce((latest, item) => Math.max(latest, item.em), 0),
        oldestAt: records.reduce((oldest, item) => item.em > 0 ? Math.min(oldest, item.em) : oldest, Number.MAX_SAFE_INTEGER)
      };
    } catch (_) {
      return { available: false, records: [], count: 0 };
    }
  };
  // Consumo de bolas observado pela redução do inventário durante uma hunt normal.
  // É um proxy de lançamentos; não é apresentado como evento individual confirmado.
  let previousBallCounts = null;
  let ballUseTimes = [];
  let shinyCaptureTimes = [];
  const readBallCounts = () => {
    const out = {};
    const balls = state?.balls;
    if (balls && typeof balls === 'object' && !Array.isArray(balls)) {
      for (const [id, qty] of Object.entries(balls)) {
        const n = Number(qty);
        if (Number.isFinite(n) && n >= 0) out[id] = n;
      }
    }
    return out;
  };
  const trackBallUse = () => {
    const current = readBallCounts();
    const now = Date.now();
    if (previousBallCounts && state?.huntSlug && !state?.noCentro &&
        !state?.outlandTier && !/outland/i.test(String(state.huntSlug))) {
      for (const [id, before] of Object.entries(previousBallCounts)) {
        const spent = Math.max(0, before - (current[id] ?? 0));
        for (let i = 0; i < Math.min(spent, 100); i++) ballUseTimes.push(now);
      }
    }
    previousBallCounts = current;
    ballUseTimes = ballUseTimes.filter(t => now - t <= 15 * 60 * 1000);
    shinyCaptureTimes = shinyCaptureTimes.filter(t => now - t <= 24 * 60 * 60 * 1000);
  };
  const diagnostics = {
    socketSeen: 0, textFrames: 0, jsonFrames: 0, binaryFrames: 0,
    welcome: 0, estado: 0, batalha: 0, market: 0, field: 0,
    lastType: null, lastMessageAt: 0, lastPublishAt: 0, marketMessageKeys: [], marketLineKeys: [], fieldMessageKeys: [],
    typeCounts: {}, battleEventKinds: {}, battleEventKeys: {}, battleEventSamples: {}, lastMessageKeys: []
  };
  // Telemetria exploratória: registra apenas eventos cujos nomes/campos mencionem Prey/presa.
  // A ausência de sinal não prova que a mecânica não exista; o formato do jogo ainda precisa ser observado.
  const preyTelemetry = { eventCounts: {}, samples: [], stateKeys: [], lastAt: 0 };
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
  // Diagnóstico estrutural conservador: procura pares de coordenadas e objetos espaciais.
  const inspectFieldGeometry = (root) => {
    const hints = [];
    const visited = new Set();
    const spatialKey = /^(x|y|z|gx|gy|tx|ty|row|col|pos|position|coords|coordinate|coordinates|tile|cell|grid|map|area|zone|hunt|enemy|monster|mob|creature|spawn|target|id|type|kind|k)$/i;
    const walk = (value, path, depth) => {
      if (!value || typeof value !== 'object' || depth > 6 || hints.length >= 40) return;
      if (visited.has(value)) return;
      visited.add(value);
      if (Array.isArray(value)) {
        value.slice(0, 80).forEach((item, index) => walk(item, path + '[' + index + ']', depth + 1));
        return;
      }
      const keys = Object.keys(value).filter(key => !/token|cookie|auth|password|senha|email|session|credential/i.test(key));
      const lower = keys.map(key => key.toLowerCase());
      const hasX = lower.some(key => /^(x|gx|tx|col)$/.test(key));
      const hasY = lower.some(key => /^(y|gy|ty|row)$/.test(key));
      const context = lower.some(key => /enemy|monster|mob|creature|spawn|position|coords|coordinate|tile|cell|grid|map|target/.test(key));
      if ((hasX && hasY) || (context && keys.some(key => spatialKey.test(key)))) {
        const sample = {};
        for (const key of keys) {
          if (!spatialKey.test(key)) continue;
          const item = value[key];
          if (item == null || ['string','number','boolean'].includes(typeof item)) {
            if (item !== undefined) sample[key] = typeof item === 'string' ? item.slice(0, 60) : item;
          } else if (Array.isArray(item)) {
            sample[key] = item.slice(0, 8).filter(v => v == null || ['string','number','boolean'].includes(typeof v));
          }
        }
        hints.push({ path: path || 'root', keys: keys.slice(0, 24), sample });
      }
      for (const key of keys.slice(0, 100)) walk(value[key], path ? path + '.' + key : key, depth + 1);
    };
    walk(root, 'root', 0);
    return hints;
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
        // XP do treinador pode reiniciar ao subir de nível. Medimos apenas
        // a sequência contínua do nível atual, sem atravessar o reset.
        const trainerSamples = [];
        const trainerLevel = String(last.tl ?? '');
        for (let i = samples.length - 1; i >= 0; i--) {
          if (String(samples[i].tl ?? '') !== trainerLevel) break;
          trainerSamples.unshift(samples[i]);
        }
        if (trainerSamples.length >= 2) {
          const trainerElapsed = trainerSamples[trainerSamples.length - 1].t - trainerSamples[0].t;
          if (trainerElapsed >= 120000) {
            let trainerXp = 0;
            for (let i = 1; i < trainerSamples.length; i++) {
              trainerXp += Math.max(0, trainerSamples[i].tx - trainerSamples[i - 1].tx);
            }
            xpTrainerH = Math.round(trainerXp * 3600000 / trainerElapsed);
          }
        }

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
    const ballSpan = ballUseTimes.length >= 2 ? ballUseTimes[ballUseTimes.length - 1] - ballUseTimes[0] : 0;
    const ballThrowsH = ballUseTimes.length >= 5 && ballSpan >= 120000
      ? Math.round((ballUseTimes.length - 1) * 3600000 / ballSpan) : null;
    return { xpPokemonH, xpTrainerH, killsH, survivalPct, coinsNetH,
      ballThrowsObserved: ballUseTimes.length, ballThrowsH,
      shinyCapturesObserved: shinyCaptureTimes.length,
      ballUseWindowMinutes: ballUseTimes.length ? Math.round((Date.now() - ballUseTimes[0]) / 60000) : 0,
      sampleMinutes: minutes, killSamples: kills.length };
  };
  let huntAnalysis = { status: 'aguardando', message: 'Aguardando dados e catálogo do jogo.' };
  let analysisBusy = false;
  let analysisScheduled = false;
  let analysisTimer = null;
  let scheduledAnalysisKey = '';
  let lastAnalysisKey = '';
  let lastAnalysisAt = 0;
  const ANALYSIS_DEBOUNCE_MS = 12000;
  const ANALYSIS_MIN_INTERVAL_MS = 60000;
  let analysisCatalog = null;
  let analysisModules = null;

  const loadAnalysisDependencies = async () => {
    if (analysisCatalog && analysisModules) return;
    const base = location.origin + '/';
    const getJson = async (path) => {
      const response = await fetch(base + path, { credentials: 'same-origin' });
      if (!response.ok) throw new Error(path + ' HTTP ' + response.status);
      return response.json();
    };
    const optionalModule = (path) => import(base + path).catch(() => null);
    const [cr, newer, outland, overrides, analyser, reward, cooldown,
      planilhas, outlandMod, baseOutland, evolucoes, especiais, golpesOrre, megas, alias, tm] = await Promise.all([
      getJson('assets/creatures.json'),
      getJson('assets/creatures-novos.json').catch(() => null),
      getJson('assets/creatures-outland-novos.json').catch(() => null),
      getJson('assets/creatures-audit-overrides.json').catch(() => null),
      import(base + 'hunt-analyser.mjs'),
      import(base + 'shared/recompensa-hunt.mjs'),
      import(base + 'shared/cooldown-golpes.mjs'),
      optionalModule('shared/golpes-planilhas.mjs'),
      optionalModule('shared/outland.mjs'),
      optionalModule('shared/herdar-base-outland.mjs'),
      optionalModule('shared/evolucoes-cruzadas.mjs'),
      optionalModule('shared/golpes-especiais.mjs'),
      optionalModule('shared/herdar-golpes-orre.mjs'),
      optionalModule('shared/megas.mjs'),
      optionalModule('shared/alias-item.mjs'),
      optionalModule('shared/tm-elemental.mjs')
    ]);
    const list = [
      ...(Array.isArray(cr?.creatures) ? cr.creatures : []),
      ...(Array.isArray(newer?.creatures) ? newer.creatures : []),
      ...(Array.isArray(outland?.creatures) ? outland.creatures : [])
    ];
    if (outlandMod?.aplicarRemapOutlandLista) {
      try { outlandMod.aplicarRemapOutlandLista(list); } catch (_) {}
    }
    const byId = new Map(list.filter(x => x && x.pokeId != null).map(x => [String(x.pokeId), x]));
    for (const item of (overrides?.overrides || [])) {
      const target = byId.get(String(item?.pokeId));
      if (!target) continue;
      for (const key of ['baseHp','baseAtk','baseDef','baseSpAtk','baseSpDef','baseSpeed']) {
        if (Number.isFinite(Number(item[key]))) target[key] = Number(item[key]);
      }
      if (Array.isArray(item.attacks)) target.attacks = item.attacks.map(a => ({ ...a }));
    }
    if (planilhas?.aplicarGolpesDasPlanilhas) {
      try { planilhas.aplicarGolpesDasPlanilhas(id => list.filter(x => x.pokeId === id)); } catch (_) {}
    }
    if (baseOutland?.herdarBaseOutland) {
      try { baseOutland.herdarBaseOutland(list); } catch (_) {}
    }
    if (evolucoes?.aplicarCadeiasTruncadas) {
      try { evolucoes.aplicarCadeiasTruncadas(list); } catch (_) {}
      try { evolucoes.normalizarEvolveLevel600?.(list); } catch (_) {}
    }
    for (const creature of list) {
      if (!creature || (outlandMod?.isEspelhoFantasmaPokeId?.(creature.pokeId))) continue;
      if (alias?.resolverNomeItem && Array.isArray(creature.loot)) {
        for (const loot of creature.loot) {
          try { loot.name = alias.resolverNomeItem(loot.name); } catch (_) {}
        }
      }
      try { especiais?.injetarGolpesEspeciais?.(creature); } catch (_) {}
    }
    try { golpesOrre?.herdarGolpesOrre?.(list); } catch (_) {}
    try {
      const megaSpecies = megas?.criarEspeciesMega?.(list) || [];
      for (const mega of megaSpecies) if (mega?.pokeId != null) list.push(mega);
    } catch (_) {}
    const finalMap = new Map(list.filter(x => x && x.pokeId != null).map(x => [String(x.pokeId), x]));
    if (!finalMap.size) throw new Error('Catálogo oficial sem espécies identificáveis.');
    analysisCatalog = finalMap;
    analysisModules = { analyser, reward, cooldown, tm };
  };

  const typeEffect = (moveType, defender, table, analyser) => {
    const d1 = defender?.[0] || null, d2 = defender?.[1] || null;
    try {
      if (typeof analyser.efetividade === 'function') {
        const value = Number(analyser.efetividade(moveType, d1, d2, table || {}));
        if (Number.isFinite(value)) return value;
      }
    } catch (_) {}
    const read = (atk, def) => {
      if (!def || !table) return 1;
      const row = table[atk] || table[String(atk).toLowerCase()] || {};
      const raw = row[def] ?? row[String(def).toLowerCase()];
      return Number.isFinite(Number(raw)) ? Number(raw) : 1;
    };
    return read(moveType, d1) * (d2 && d2 !== d1 ? read(moveType, d2) : 1);
  };

  const estimatePokemonHunts = async (pk, context, shouldCancel = () => false) => {
    const { analyser, reward, cooldown } = analysisModules;
    const species = analysisCatalog.get(String(pk.speciesId ?? pk.pokeId));
    if (!species || !pk.stats) return null;
    const level = Math.max(1, Number(pk.level) || 1);
    const ivSpeed = Math.min(32, Math.max(1, Number(pk.ivs?.speed) || 1));
    const speedCd = (ms) => {
      try {
        return cooldown.cooldownGolpeComSpeed(ms, species.baseSpeed || 0, ivSpeed);
      } catch (_) {
        return Math.max(400, Number(ms) - ivSpeed * 10);
      }
    };
    const pkTypes = Array.isArray(pk.tipos) && pk.tipos.length
      ? pk.tipos : [species.type1, species.type2].filter(Boolean);
    const attacks = (Array.isArray(species.attacks) ? species.attacks : [])
      .filter(a => Number(a.learnLevel ?? 1) <= level && Number(a.power) > 0)
      .map(a => ({ ...a, cd: speedCd(Number(a.cooldownMs) || 2000) }));
    if (pk.tmElemental && analysisModules.tm?.golpeTmElemental) {
      try {
        const tmMove = analysisModules.tm.golpeTmElemental(pk.tmElemental);
        if (tmMove) attacks.push({ ...tmMove, cd: speedCd(Number(tmMove.cooldownMs) || 60000) });
      } catch (_) {}
    }
    if (!attacks.length) attacks.push({ name: 'Investida (estimada)', type: pkTypes[0], power: 30, category: 'PHYSICAL', cd: speedCd(2000) });
    const trainerLevel = Number(state?.level) || 1;
    const unlocked = hunts.filter(h => Number(h.nivel) <= trainerLevel && Array.isArray(h.especies) && h.especies.length);
    const typeTable = catalogData?.combate?.tabelaTipos || {};
    const typeMult = (moveType, defender) => {
      let value = typeEffect(moveType, defender, typeTable, analyser);
      const amplify = Number(catalogData?.combate?.ampliacaoHunt) || 1.5;
      if (value > 1) value = 1 + (value - 1) * amplify;
      else if (value > 0 && value < 1) value /= amplify;
      return value;
    };
    const now = Date.now();
    let xpPokemonMult = 1, xpTrainerMult = 1, lootBonus = 0;
    try { xpPokemonMult = Number(analyser.multXpPokemon?.(state, now, catalogData?.tiposBoost || [])) || 1; } catch (_) {}
    try { xpTrainerMult = Number(analyser.multXpTreinador?.(state, now, catalogData?.tiposBoost || [])) || 1; } catch (_) {}
    try { lootBonus = Number(analyser.bonusLootPct?.(state, now, catalogData?.tiposBoost || [])) || 0; } catch (_) {}
    const rows = [];
    let processedHunts = 0;
    for (const hunt of unlocked) {
      if (++processedHunts % 8 === 0) {
        if (shouldCancel()) return null;
        // Devolve o controle ao WebView durante análises extensas.
        await new Promise(resolve => setTimeout(resolve, 0));
      }
      const targets = hunt.especies.map(entry => ({
        entry,
        enemy: analysisCatalog.get(String(entry.pokeId))
      })).filter(x => x.enemy && Number(x.entry.pontos) > 0);
      // O jogo não publica frequência relativa das espécies da Hunt.
      // Portanto, cada espécie recebe o mesmo peso; "pontos" não é tratado como taxa de spawn.
      if (!targets.length) continue;
      let ttk = 0, xpPerKill = 0, goldPerKill = 0;
      for (const target of targets) {
        const enemy = target.enemy;
        const weight = 1 / targets.length;
        const enemyTypes = [enemy.type1, enemy.type2].filter(Boolean);
        const enemyLevel = Math.max(1, Number(hunt.nivel) || 1);
        const enemyDef = ((Number(enemy.baseDef) || 0) + 32) * enemyLevel / 100;
        const enemySpDef = ((Number(enemy.baseSpDef) || 0) + 32) * enemyLevel / 100;
        const enemyHp = Math.max(24, Math.round(((Number(enemy.baseHp) || 0) + 32) * enemyLevel / 100 * 12)) *
          (Number(catalogData?.combate?.multHpSelvagem) || 5);
        let bestDps = 0;
        for (const move of attacks) {
          const special = String(move.category).toUpperCase() === 'SPECIAL';
          const attackStat = Number(special ? pk.stats.spAtk : pk.stats.atk) || 1;
          const defenseStat = special ? enemySpDef : enemyDef;
          const effectiveness = typeMult(move.type, enemyTypes);
          const stab = pkTypes.includes(move.type) ? 1.5 : 1;
          const base = (((2 * level / 5 + 2) * Math.max(15, Number(move.power) || 15) *
            Math.max(1, attackStat) / Math.max(1, defenseStat)) / 50) + 2;
          const damage = base * stab * effectiveness * 0.925;
          const cooldownSeconds = Math.max(0.4, Number(move.cd) / 1000, 0.9 - ivSpeed * 0.01);
          bestDps = Math.max(bestDps, damage / cooldownSeconds);
        }
        if (bestDps <= 0) continue;
        ttk += weight * enemyHp / bestDps;
        try {
          const xpValue = Number(reward.xpPorDerrota(hunt, enemyLevel, enemy));
          if (Number.isFinite(xpValue) && xpValue >= 0) xpPerKill += weight * xpValue;
        } catch (_) {}
        try {
          const goldValue = Number(reward.ouroPorDerrotaHunt(hunt, enemyLevel, enemy, lootBonus));
          if (Number.isFinite(goldValue) && goldValue >= 0) goldPerKill += weight * goldValue;
        } catch (_) {}
      }
      if (!Number.isFinite(ttk) || ttk <= 0) continue;
      const targetCount = Math.max(1, Math.min(16, Number(analyser.MAX_MOBS) || 16));
      const travelSeconds = 20.6 * 0.26 * 0.31;
      const killsSingle = Math.min(3600 / (ttk + 1.8), targetCount * 3600 / (travelSeconds + 2.8));
      const aoePossible = pk.tmAoe === true || pk.tmAoe === 1 || pk.tmAoe === 'true';
      // AoE hipotético usa densidade provisória; não é medição do campo desta Hunt.
      const aoeTargets = 2.0;
      const killsAoe = Math.min(killsSingle * aoeTargets, targetCount * 3600 / (Math.max(0.8, travelSeconds * 0.55) + 2.8));
      const xpP = killsSingle * xpPerKill * xpPokemonMult;
      const xpT = killsSingle * xpPerKill * xpTrainerMult;
      const gold = killsSingle * goldPerKill;
      rows.push({
        slug: hunt.slug, nome: hunt.nome, nivel: Number(hunt.nivel) || 0,
        regiao: hunt.regiao || hunt.area || '',
        ttkEstimado: Math.round(ttk * 10) / 10,
        xpPokemonH: Math.round(xpP), xpTreinadorH: Math.round(xpT), goldH: Math.round(gold),
        abatesH: Math.round(killsSingle),
        xpPokemonHAoeHipotetico: Math.round(killsAoe * xpPerKill * xpPokemonMult),
        xpTreinadorHAoeHipotetico: Math.round(killsAoe * xpPerKill * xpTrainerMult),
        goldHAoeHipotetico: Math.round(killsAoe * goldPerKill),
        abatesHAoeHipotetico: Math.round(killsAoe),
        possuiAoe: aoePossible,
        confianca: 'baixa: sem geometria de campo e ritmo medido',
        observacaoAoe: 'cenário hipotético com 2 alvos por golpe; não é resultado medido'
      });
    }
    const noAoeRows = rows.slice().sort((a,b) => b.xpPokemonH - a.xpPokemonH);
    const aoeRows = rows.slice().sort((a,b) => b.xpPokemonHAoeHipotetico - a.xpPokemonHAoeHipotetico);
    const goldRows = rows.slice().sort((a,b) => b.goldH - a.goldH);
    const goldAoeRows = rows.slice().sort((a,b) => b.goldHAoeHipotetico - a.goldHAoeHipotetico);
    const hasAoe = pk.tmAoe === true || pk.tmAoe === 1 || pk.tmAoe === 'true';
    return {
      pokemonId: pk.id, nome: pk.nome || species.name || ('Pokémon ' + pk.speciesId),
      level, tmAoe: hasAoe, ativo: String(pk.id) === String(state?.activeId),
      melhorXp: (hasAoe ? aoeRows[0] : noAoeRows[0]) || null,
      melhorXpSemAoe: noAoeRows[0] || null,
      melhorXpComAoe: aoeRows[0] || null,
      melhorGold: goldRows[0] || null,
      melhorGoldComAoe: goldAoeRows[0] || null,
      topHunts: (hasAoe ? aoeRows : noAoeRows).slice(0, 5),
      topHuntsSemAoe: noAoeRows.slice(0, 5),
      topHuntsComAoe: aoeRows.slice(0, 5),
      topGoldSemAoe: goldRows.slice(0, 5),
      topGoldComAoe: goldAoeRows.slice(0, 5),
      huntsAnalisadas: rows.length
    };
  };

  const getHuntAnalysisInputKey = () => {
    if (!state || !Array.isArray(state.pokemons) || !hunts.length) return '';
    const pokemon = state.pokemons
      .filter(p => p && p.id != null && p.speciesId != null && p.stats)
      .map(p => ({
        id: p.id, speciesId: p.speciesId, level: p.level, ivs: p.ivs,
        stats: p.stats, tipos: p.tipos, tmAoe: p.tmAoe,
        tmElemental: p.tmElemental, slot: p.slot
      }));
    const huntKey = hunts.map(h => ({
      slug: h.slug, nivel: h.nivel,
      especies: Array.isArray(h.especies)
        ? h.especies.map(e => [e.pokeId, e.pontos]) : []
    }));
    try {
      return JSON.stringify({
        trainerLevel: state.level, activeId: state.activeId,
        pokemon, hunts: huntKey,
        typeTable: catalogData?.combate?.tabelaTipos || null
      });
    } catch (_) {
      return '';
    }
  };

  const refreshHuntAnalysis = async (expectedKey) => {
    if (analysisBusy) return;
    const inputKey = expectedKey || getHuntAnalysisInputKey();
    if (!inputKey || inputKey !== getHuntAnalysisInputKey()) {
      scheduleHuntAnalysis();
      return;
    }
    analysisBusy = true;
    lastAnalysisAt = Date.now();
    try {
      if (!state || !Array.isArray(state.pokemons) || !hunts.length || !catalogData) {
        huntAnalysis = { status: 'aguardando', message: 'Aguardando Pokémon, Hunts e catálogo.' };
        return;
      }
      await loadAnalysisDependencies();
      const candidates = state.pokemons
        .filter(p => p && p.id != null && p.speciesId != null && p.stats)
        .sort((a,b) => {
          const activeA = String(a.id) === String(state.activeId) ? 1 : 0;
          const activeB = String(b.id) === String(state.activeId) ? 1 : 0;
          if (activeA !== activeB) return activeB - activeA;
          const teamA = a.slot != null ? 1 : 0, teamB = b.slot != null ? 1 : 0;
          if (teamA !== teamB) return teamB - teamA;
          return (Number(b.level) || 0) - (Number(a.level) || 0);
        }).slice(0, 20);
      const results = [];
      // Cede tempo ao WebView entre Pokémon para não monopolizar a thread do jogo.
      for (const candidate of candidates) {
        if (inputKey !== getHuntAnalysisInputKey()) break;
        const result = await estimatePokemonHunts(candidate, catalogData, () => inputKey !== getHuntAnalysisInputKey());
        if (result) results.push(result);
        await new Promise(resolve => setTimeout(resolve, 16));
      }
      if (inputKey !== getHuntAnalysisInputKey()) {
        huntAnalysis = {
          status: 'atualizando',
          message: 'Os dados mudaram durante a análise; uma nova análise será agendada.'
        };
        return;
      }
      huntAnalysis = {
        status: results.length ? 'estimativa_disponivel' : 'sem_dados',
        calculadoEm: Date.now(),
        nivelTreinador: Number(state.level) || 0,
        pokemonAnalisados: results.length,
        fonteCatalogo: 'assets públicos oficiais carregados do pokeidle.io',
        modelo: 'estimativa teórica preliminar; sem geometria real da Hunt',
        pokemon: results
      };
      lastAnalysisKey = inputKey;
    } catch (error) {
      huntAnalysis = { status: 'erro', message: String(error?.message || error).slice(0, 180) };
    } finally {
      analysisBusy = false;
      publish('analysis', true);
    }
  };

  const scheduleHuntAnalysis = () => {
    const inputKey = getHuntAnalysisInputKey();
    if (!inputKey || inputKey === lastAnalysisKey || analysisBusy) return;
    if (analysisScheduled && scheduledAnalysisKey === inputKey) return;
    if (analysisTimer) clearTimeout(analysisTimer);
    scheduledAnalysisKey = inputKey;
    analysisScheduled = true;
    const throttleWait = Math.max(0, ANALYSIS_MIN_INTERVAL_MS - (Date.now() - lastAnalysisAt));
    const delay = Math.max(ANALYSIS_DEBOUNCE_MS, throttleWait);
    analysisTimer = setTimeout(() => {
      analysisScheduled = false;
      analysisTimer = null;
      const key = scheduledAnalysisKey;
      scheduledAnalysisKey = '';
      refreshHuntAnalysis(key);
    }, delay);
  };

  // A análise pesada só é iniciada quando o usuário solicita pelo painel nativo.
  window.__pokeIdleRunHuntAnalysis = () => {
    if (!state || !Array.isArray(state.pokemons) || !state.pokemons.length || !hunts.length || !catalogData) {
      return {
        accepted: false,
        reason: 'dados_indisponiveis',
        details: {
          hasState: !!state,
          pokemonCount: Array.isArray(state?.pokemons) ? state.pokemons.length : 0,
          huntsCount: hunts.length,
          catalogReady: !!catalogData,
          pokemonWithStats: Array.isArray(state?.pokemons) ? state.pokemons.filter(p => p && p.stats).length : 0
        }
      };
    }
    if (analysisBusy) return { accepted: true, reason: 'em_execucao' };
    if (analysisScheduled) return { accepted: true, reason: 'ja_agendada' };
    lastAnalysisKey = '';
    scheduleHuntAnalysis();
    return analysisScheduled
      ? { accepted: true, reason: 'agendada' }
      : { accepted: false, reason: 'nao_agendada', details: { inputKeyReady: !!getHuntAnalysisInputKey() } };
  };

  const publish = (kind, force = false) => {
    if (!state) return;
    const now = Date.now();
    // Eventos podem chegar muitas vezes por segundo. O estado é atualizado em memória,
    // mas a ponte nativa recebe no máximo um snapshot comum a cada 8 s.
    // Snapshot inicial e resultado de uma análise solicitada são exceções.
    if (kind !== 'welcome' && kind !== 'analysis' &&
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
    const payload = {
      kind, status: 'connected', connected: true, ready: true,
      nick: state.nick ?? null, level: state.level,
      coinsBalance: coinData.value, currencySource: coinData.source,
      gold: coinData.value, coins: coinData.value, xpTreinador: Number(state.xp) || 0,
      activeId: state.activeId, huntSlug: state.huntSlug, outlandTier: state.outlandTier ?? null,
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
      player: {
        level: state.level, xp: Number(state.xp) || 0, outlandTier: state.outlandTier ?? null,
        coins: coinData.value, gold: coinData.value,
        activeId: state.activeId, huntSlug: state.huntSlug,
        pokemons
      },
      gameSession: readGameSession(),
      captureHistory: readCaptureHistory(),
      huntAnalysis,
      preyTelemetry: {
        eventCounts: { ...preyTelemetry.eventCounts },
        samples: preyTelemetry.samples.slice(-40),
        stateKeys: Object.keys(state || {}).filter(k => /prey|presa|preda/i.test(k)).slice(0, 20),
        lastAt: preyTelemetry.lastAt
      },
      measurements: metrics, market: marketData, fieldData,
      diagnostics: {
        ...diagnostics,
        huntsWithSpecies: hunts.filter(h => Array.isArray(h.especies) && h.especies.length > 0).length,
        huntSpeciesEntries: hunts.reduce((n, h) => n + (Array.isArray(h.especies) ? h.especies.length : 0), 0),
        typeChartEntries: Object.keys(catalogData?.combate?.tabelaTipos || {}).length,
        pokemonWithStats: pokemons.filter(p => p.stats && typeof p.stats === 'object').length,
        pokemonWithSpeciesId: pokemons.filter(p => p.speciesId != null).length,
        activePokemonFound: !!active,
        teamCount: team.length,
        gameSessionAvailable: sessionDiagnostics.available,
        gameSessionKeys: sessionDiagnostics.keys
      }
    };
    if (!staticSnapshotSent || kind === 'welcome') {
      // O app nativo só precisa do índice de Hunts para exibição.
      // Catálogo completo e espécies ficam no JavaScript do WebView, onde o Analyzer os usa.
      // Isso evita persistir e transportar centenas de KB de dados estáticos a cada conta.
      payload.hunts = hunts.map(h => ({
        slug: h.slug, nome: h.nome, nivel: h.nivel,
        regiao: h.regiao, area: h.area
      }));
      payload._replaceSnapshot = true;
      staticSnapshotSent = true;
    }
    send(payload);
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
              previousBallCounts = readBallCounts();
              publish('welcome', true);
            } else if (msg.t === 'estado' && state) {
              diagnostics.estado++;
              merge(msg.estado && typeof msg.estado === 'object' && !Array.isArray(msg.estado) ? msg.estado : null);
              trackBallUse();
              const list = Array.isArray(state.pokemons) ? state.pokemons : [];
              const active = list.find(p => String(p.id) === String(state.activeId));
              if (active) {
                samples.push({
                  t: Date.now(), pid: active.id, px: Number(active.xp) || 0,
                  tx: Number(state.xp) || 0, tl: state.level ?? null,
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
                  if (!diagnostics.battleEventKeys[ev.k]) {
                    diagnostics.battleEventKeys[ev.k] = Object.keys(ev).slice(0, 24);
                  }
                  if (!diagnostics.battleEventSamples[ev.k]) diagnostics.battleEventSamples[ev.k] = [];
                  if (diagnostics.battleEventSamples[ev.k].length < 3) {
                    const sample = {};
                    // Guarda somente primitivas de eventos raros/amostrados, sem credenciais.
                    // A captura ampla dos nomes ajuda a descobrir o protocolo real sem adivinhar campos.
                    for (const key of Object.keys(ev).slice(0, 32)) {
                      if (/token|cookie|auth|password|senha|email|session|credential/i.test(key)) continue;
                      const value = ev[key];
                      if (value == null || ['number','boolean'].includes(typeof value)) {
                        if (value !== undefined) sample[key] = value;
                      } else if (typeof value === 'string') {
                        sample[key] = value.slice(0, 100);
                      }
                    }
                    diagnostics.battleEventSamples[ev.k].push(sample);
                  }
                }
                if (ev) {
                  const preyText = [ev.k, ...Object.keys(ev), ...Object.values(ev).filter(v => typeof v === 'string')].join(' ').toLowerCase();
                  if (/\bprey\b|presa|preda/.test(preyText)) {
                    const eventName = String(ev.k || 'evento');
                    preyTelemetry.eventCounts[eventName] = (preyTelemetry.eventCounts[eventName] || 0) + 1;
                    preyTelemetry.lastAt = Date.now();
                    if (preyTelemetry.samples.length < 40) {
                      const sample = { k: eventName, at: preyTelemetry.lastAt, keys: Object.keys(ev).slice(0, 24) };
                      for (const key of Object.keys(ev).slice(0, 24)) {
                        if (/token|cookie|auth|password|senha|email|session|credential/i.test(key)) continue;
                        const value = ev[key];
                        if (value == null || ['number','boolean'].includes(typeof value)) sample[key] = value;
                        else if (typeof value === 'string') sample[key] = value.slice(0, 100);
                      }
                      preyTelemetry.samples.push(sample);
                    }
                  }
                }
                if (ev && ev.k === 'morte' && ev.quem === 'selvagem') kills.push(Date.now());
                if (ev && ev.k === 'capturado' && ev.pokemon && ev.pokemon.shiny) shinyCaptureTimes.push(Date.now());
              }
              publish('batalha');
              // O jogo grava os contadores da sessão com pequeno atraso após o evento.
              setTimeout(() => publish('session', true), 2500);
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
              const safePayload = sanitizeField(msg);
              const geometryHints = inspectFieldGeometry(safePayload);
              fieldData = { type: msg.t, capturedAt: Date.now(), payload: safePayload, geometryHints };
              diagnostics.fieldDataKeys = Object.keys(fieldData.payload || {}).slice(0, 40);
              diagnostics.fieldGeometryHints = geometryHints.length;
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
            val merged = if (previous != null && (kind == "connection" || kind == "capture-ready" || kind == "market" || kind == "analysis-control")) {
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
            AtomicFile(file(context, accountId)).openRead().use { input ->
                JSONObject(input.readBytes().toString(StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
    }
}
