// All-language TTS baseline scorer. Pulls tts_all_*.wav + human refs and prints
// one gate row per file: PASS/WARN/FAIL vs the human reference profile.
// Usage: node scripts/tts_baseline.js [dir]
const fs = require('fs');
const path = require('path');
const dir = process.argv[2] || 'tmp/ttsbase';

function readWav(file) {
  const b = fs.readFileSync(file);
  const fmt = b.readUInt16LE(20), ch = b.readUInt16LE(22), sr = b.readUInt32LE(24), bits = b.readUInt16LE(34);
  let o = 12, dataOff = -1, dataLen = 0;
  while (o < b.length - 8) {
    const id = b.slice(o, o + 4).toString('ascii');
    const sz = b.readInt32LE(o + 4);
    if (id === 'data') { dataOff = o + 8; dataLen = sz; break; }
    o += 8 + sz + (sz & 1);
  }
  const w = (bits / 8) * ch;
  const n = Math.floor(Math.min(dataLen, b.length - dataOff) / w);
  const s = new Float64Array(n);
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength);
  for (let i = 0; i < n; i++) {
    let v = 0;
    for (let c = 0; c < ch; c++) {
      const q = dataOff + i * w + c * (bits / 8);
      v += (fmt === 3 && bits === 32) ? view.getFloat32(q, true) : view.getInt16(q, true) / 32768;
    }
    s[i] = v / ch;
  }
  return { sr, samples: s };
}

function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang), half = len >> 1;
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < half; k++) {
        const ur = re[i + k], ui = im[i + k];
        const vr = re[i + k + half] * cr - im[i + k + half] * ci;
        const vi = re[i + k + half] * ci + im[i + k + half] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi;
        re[i + k + half] = ur - vr; im[i + k + half] = ui - vi;
        const nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
      }
    }
  }
}

function f0(sr, x, s, win) {
  let e0 = 0;
  for (let i = 0; i < win; i++) e0 += x[s + i] * x[s + i];
  if (e0 / win < 4e-4) return 0;
  const minLag = Math.floor(sr / 400), maxLag = Math.ceil(sr / 60);
  let best = 0, bestLag = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    let ac = 0, ea = 0, eb = 0;
    for (let i = 0; i < win - lag; i++) { const a = x[s + i], c = x[s + i + lag]; ac += a * c; ea += a * a; eb += c * c; }
    const v = ac / (Math.sqrt(ea * eb) + 1e-20);
    if (v > best) { best = v; bestLag = lag; }
  }
  return best < 0.45 ? 0 : sr / bestLag;
}

function score(label, file) {
  const { sr, samples: x } = readWav(file);
  const dur = x.length / sr;
  // silence fraction (25ms win / 10ms hop, <-55dBFS = silent)
  const win = Math.floor(sr * 0.025), hop = Math.floor(sr * 0.01);
  let frames = 0, silent = 0;
  for (let f = 0; f + win <= x.length; f += hop) {
    let s = 0; for (let i = f; i < f + win; i++) s += x[i] * x[i];
    frames++;
    if (10 * Math.log10(s / (win / 2) + 1e-12) < -55) silent++;
  }
  const silPct = 100 * silent / Math.max(1, frames);
  // pitch
  const winF = Math.round(sr * 0.04), hopF = Math.round(sr * 0.01), f = [];
  for (let s = 0; s + winF < x.length; s += hopF) { const v = f0(sr, x, s, winF); if (v) f.push(v); }
  let semiSD = 0, f0mean = 0;
  if (f.length >= 5) {
    const m = f.reduce((a, v) => a + v, 0) / f.length; f0mean = m;
    const semi = f.map((v) => 12 * Math.log2(v / 220));
    const sm = semi.reduce((a, v) => a + v, 0) / semi.length;
    semiSD = Math.sqrt(semi.reduce((a, v) => a + (v - sm) ** 2, 0) / semi.length);
  }
  // spectral bands over voiced frames
  const N = 1024, h256 = 256, hann = new Float64Array(N);
  for (let i = 0; i < N; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
  const bands = [[0, 1000], [1000, 3000], [3000, 6000], [6000, 12000]];
  const energy = [0, 0, 0, 0]; let total = 0, voiced = 0;
  const re = new Float64Array(N), im = new Float64Array(N);
  for (let s = 0; s + N <= x.length; s += h256) {
    let rms = 0;
    for (let i = 0; i < N; i++) { re[i] = x[s + i] * hann[i]; im[i] = 0; rms += x[s + i] * x[s + i]; }
    if (Math.sqrt(rms / N) < 0.01) continue;
    voiced++; fft(re, im);
    for (let k = 1; k <= N / 2; k++) {
      const mag = re[k] * re[k] + im[k] * im[k], hz = (k * sr) / N;
      total += mag;
      for (let bi = 0; bi < 4; bi++) if (hz >= bands[bi][0] && hz < bands[bi][1]) energy[bi] += mag;
    }
  }
  const mid = 100 * energy[1] / (total || 1);
  const centroid = energy.reduce((a, e, i) => a + e * ((bands[i][0] + bands[i][1]) / 2), 0) / (energy.reduce((a, e) => a + e, 0) || 1);
  // frame-boundary click (hop=256)
  let at = 0, atN = 0, off = 0, offN = 0;
  for (let i = 1; i < x.length; i++) {
    const d = Math.abs(x[i] - x[i - 1]);
    if (i % 256 === 0) { at += d; atN++; } else { off += d; offN++; }
  }
  const click = (at / atN) / (off / offN + 1e-20);
  const gate = (v, lo, hi) => (v >= lo && v <= hi ? 'PASS' : (v >= lo - (hi - lo) * 0.6 && v <= hi + (hi - lo) * 0.6 ? 'WARN' : 'FAIL'));
  return {
    label, dur: dur.toFixed(2), sil: silPct.toFixed(0), semi: semiSD.toFixed(2),
    mid: mid.toFixed(1), cent: Math.round(centroid), click: click.toFixed(2), f0: Math.round(f0mean),
    gSil: gate(silPct, 0, 15), gSemi: semiSD < 1.5 ? 'FAIL' : gate(semiSD, 2.2, 4.5),
    gMid: gate(mid, 4.0, 12.0), gClick: gate(click, 0.8, 1.2),
  };
}

const files = fs.readdirSync(dir).filter((f) => f.endsWith('.wav')).sort();
if (!files.length) { console.log('no wavs in ' + dir); process.exit(1); }
const rows = files.map((f) => score(path.basename(f, '.wav'), path.join(dir, f)));
console.log('label              dur  sil% semiSD 1-3k%  cent click  F0 | sil  pitch band click');
for (const r of rows) {
  console.log(
    `${r.label.padEnd(18)} ${r.dur.padStart(5)} ${r.sil.padStart(4)} ${r.semi.padStart(6)} ${r.mid.padStart(5)} ${String(r.cent).padStart(5)} ${r.click.padStart(5)} ${String(r.f0).padStart(4)} | ${r.gSil.padEnd(4)} ${r.gSemi.padEnd(5)} ${r.gMid.padEnd(4)} ${r.gClick}`
  );
}
