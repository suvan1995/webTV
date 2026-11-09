// Connects to the app's WebView over Chrome DevTools and samples YouTube's ad state every 3 s.
const http = require('http');
const [,, out, seconds] = process.argv;
const get = u => new Promise((res, rej) => http.get(u, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => { try { res(JSON.parse(d)); } catch (e) { rej(e); } }); }).on('error', rej));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const EXPR = `(() => {
  const q = s => document.querySelector(s);
  const player = q('#movie_player') || q('.html5-video-player');
  let pr = -1; try { const r = player && player.getPlayerResponse && player.getPlayerResponse(); pr = r ? (r.adPlacements ? r.adPlacements.length : 0) : -1; } catch (e) { pr = 'err'; }
  const v = q('video');
  return JSON.stringify({ url: location.href,
    adShowing: !!q('.ad-showing'),
    adUi: !!q('.ytp-ad-player-overlay, .ytp-ad-text, .ytp-ad-preview-container, .ytp-ad-module .ytp-ad-overlay-container, .ad-interrupting, ytm-promoted-sparkles-web-renderer, ytd-ad-slot-renderer, .ytp-skip-ad-button, .ytp-ad-skip-button-modern'),
    initialAds: window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.adPlacements ? window.ytInitialPlayerResponse.adPlacements.length : 0,
    playerAds: pr,
    video: v ? { t: Math.round(v.currentTime), d: Math.round(v.duration || 0), paused: v.paused, muted: v.muted } : null,
    title: document.title });
})()`;
(async () => {
  const samples = [];
  let ws = null, id = 0;
  const deadline = Date.now() + Number(seconds) * 1000;
  while (Date.now() < deadline) {
    try {
      if (!ws || ws.readyState !== 1) {
        const pages = (await get('http://127.0.0.1:9222/json')).filter(p => p.type === 'page' && /youtube/.test(p.url));
        if (!pages.length) { samples.push({ note: 'no youtube page yet' }); await sleep(3000); continue; }
        ws = new WebSocket(pages[0].webSocketDebuggerUrl);
        await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
      }
      const myId = ++id;
      const r = await new Promise((res) => {
        const t = setTimeout(() => res(null), 4000);
        ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === myId) { clearTimeout(t); res(d); } };
        ws.send(JSON.stringify({ id: myId, method: 'Runtime.evaluate', params: { expression: EXPR, returnByValue: true } }));
      });
      const val = r && r.result && r.result.result && r.result.result.value;
      samples.push(val ? JSON.parse(val) : { note: 'no result' });
    } catch (e) { samples.push({ note: 'error ' + e.message }); ws = null; }
    await sleep(3000);
  }
  const s = samples.filter(x => x.url);
  const summary = {
    samples: s.length,
    adShowingSamples: s.filter(x => x.adShowing).length,
    adUiSamples: s.filter(x => x.adUi).length,
    maxInitialAds: Math.max(0, ...s.map(x => x.initialAds || 0)),
    maxPlayerAds: Math.max(-1, ...s.map(x => typeof x.playerAds === 'number' ? x.playerAds : -1)),
    lastVideo: s.length ? s[s.length - 1].video : null,
    lastUrl: s.length ? s[s.length - 1].url : null
  };
  require('fs').writeFileSync(out, JSON.stringify({ summary, samples }, null, 1));
  console.log(out, JSON.stringify(summary));
})();
