// Renders promo.html frame by frame and pipes JPEGs into ffmpeg.
//
// Talks CDP directly to chrome-headless-shell. At 4K the compositor sometimes hands back a frame
// before the phone layer is rasterized (blank screen), so every frame is captured until two
// consecutive captures agree; a missing layer compresses to a visibly smaller JPEG.
//
// usage: CHROME=/path/to/chrome-headless-shell node render.mjs [out.mp4] [fps]
//   SCALE=2      render at 2x device pixels (3840x2160, or 2160x3840 with PORTRAIT)
//   PORTRAIT=1   1080x1920 Reels layout
//   AUDIO=f.wav  mux an audio track
//   WORKERS=4    parallel browser tabs
//   FROM/TO      render only an excerpt, in seconds
//   NVENC=0      encode with libx264 instead of NVIDIA NVENC
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = process.argv[2] || path.join(here, 'lightforge-promo.mp4');
const fps = +(process.argv[3] || 30);
const scale = +(process.env.SCALE || 1);
const portrait = !!process.env.PORTRAIT;
const workers = +(process.env.WORKERS || 4);
const nvenc = process.env.NVENC !== '0';
const [vw, vh] = portrait ? [1080, 1920] : [1920, 1080];
const url = pathToFileURL(path.join(here, 'promo.html')).href + (portrait ? '?portrait&t=0' : '?t=0');

// ---- minimal CDP client over the browser WebSocket (flattened sessions)
const chrome = spawn(process.env.CHROME || 'chrome-headless-shell', [
  '--remote-debugging-port=0', '--run-all-compositor-stages-before-draw', '--disable-checker-imaging',
  '--hide-scrollbars', '--allow-file-access-from-files', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
const wsUrl = await new Promise((resolve, reject) => {
  let buf = '';
  chrome.stderr.on('data', d => { buf += d; const m = buf.match(/DevTools listening on (ws:\S+)/); if (m) resolve(m[1]); });
  chrome.on('exit', c => reject(new Error(`chrome exited (${c}): ${buf}`)));
});
const ws = new WebSocket(wsUrl);
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let seq = 0;
const calls = new Map();
const events = [];
ws.addEventListener('message', ({ data }) => {
  const msg = JSON.parse(data);
  if (msg.id && calls.has(msg.id)) {
    const { resolve, reject } = calls.get(msg.id); calls.delete(msg.id);
    msg.error ? reject(new Error(`${msg.error.message} ${msg.error.data || ''}`)) : resolve(msg.result);
  } else if (msg.method) events.forEach(fn => fn(msg));
});
const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const id = ++seq; calls.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const waitEvent = (method, sessionId) => new Promise(r => { const fn = m => { if (m.method === method && m.sessionId === sessionId) { events.splice(events.indexOf(fn), 1); r(m.params); } }; events.push(fn); });

async function openTab() {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank', width: vw, height: vh });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  const tab = async (method, params) => {
    const r = await send(method, params, sessionId);
    if (r && r.exceptionDetails) throw new Error(`${method}: ${r.exceptionDetails.exception?.description || r.exceptionDetails.text}`);
    return r;
  };
  await tab('Emulation.setDeviceMetricsOverride', { width: vw, height: vh, deviceScaleFactor: scale, mobile: false });
  await tab('Page.enable');
  await tab('Runtime.enable');
  const loaded = waitEvent('Page.loadEventFired', sessionId);
  await tab('Page.navigate', { url });
  await loaded;
  await tab('Runtime.evaluate', { expression: 'document.fonts.ready', awaitPromise: true });
  return { tab };
}

const tabs = [];
for (let i = 0; i < workers; i++) tabs.push(await openTab());
const { result: { value: duration } } = await tabs[0].tab('Runtime.evaluate', { expression: 'window.DURATION', returnByValue: true });
const from = Math.round(+(process.env.FROM || 0) * fps);
const frames = Math.round(+(process.env.TO || duration) * fps) - from;

const stats = { frames: 0, captures: 0 };
async function shot(t, f) {
  await t.tab('Runtime.evaluate', { expression: `window.render(${(from + f) / fps})` });
  const cap = async () => { stats.captures++; return Buffer.from((await t.tab('Page.captureScreenshot', { format: 'jpeg', quality: 93 })).data, 'base64'); };
  let prev = await cap();
  for (let attempt = 0; attempt < 8; attempt++) {
    const cur = await cap();
    if (Math.abs(cur.length - prev.length) <= prev.length * 0.002) { stats.frames++; return cur.length >= prev.length ? cur : prev; }
    prev = cur;
  }
  throw new Error(`frame ${from + f}: captures never stabilized`);
}

const jpegSize = buf => { for (let i = 2; i < buf.length;) { const m = buf[i + 1]; if (m === 0xC0 || m === 0xC2) return [buf.readUInt16BE(i + 7), buf.readUInt16BE(i + 5)]; i += 2 + buf.readUInt16BE(i + 2); } };
const want = [vw * scale, vh * scale];

const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-i', '-',
  ...(process.env.AUDIO ? ['-i', process.env.AUDIO, '-c:a', 'aac', '-b:a', '256k', '-shortest'] : []),
  ...(nvenc ? ['-c:v', 'h264_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '18', '-b:v', '0', '-profile:v', 'high'] : ['-c:v', 'libx264', '-preset', 'medium', '-crf', '17']),
  '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], { stdio: ['pipe', 'inherit', 'inherit'] });

// each tab renders every workers-th frame; frames are written to ffmpeg in order
const pending = new Map();
for (let f = 0; f < Math.min(workers, frames); f++) pending.set(f, shot(tabs[f], f));
for (let next = 0; next < frames; next++) {
  const buf = await pending.get(next); pending.delete(next);
  if (next === 0) {
    const got = jpegSize(buf);
    if (got[0] !== want[0] || got[1] !== want[1]) throw new Error(`captured ${got.join('x')}, expected ${want.join('x')}`);
  }
  const nf = next + workers;
  if (nf < frames) pending.set(nf, shot(tabs[next % workers], nf));
  if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
  if (next % fps === 0) process.stdout.write(`\r${next}/${frames}`);
}
ff.stdin.end();
await new Promise(r => ff.on('close', r));
ws.close();
chrome.kill();
console.log(`\nwrote ${out} (${(stats.captures / stats.frames).toFixed(2)} captures/frame)`);
