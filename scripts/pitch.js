// F0 contour via autocorrelation, to separate "robotic" (flat, jittered pitch) from
// "natural" (moving intonation). Reports semitone spread, which is what the ear tracks.
const fs = require('fs');

function readWav(p) {
  const b = fs.readFileSync(p);
  let off = 12, fmt = 1, bits = 16, ch = 1, sr = 8000, dataOff = -1, dataLen = 0;
  while (off + 8 <= b.length) {
    const id = b.toString('ascii', off, off + 4), len = b.readUInt32LE(off + 4);
    if (id === 'fmt ') { fmt = b.readUInt16LE(off + 8); ch = b.readUInt16LE(off + 10); sr = b.readUInt32LE(off + 12); bits = b.readUInt16LE(off + 22); }
    if (id === 'data') { dataOff = off + 8; dataLen = len; break; }
    off += 8 + len + (len & 1);
  }
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const stride = (bits / 8) * ch;
  const n = Math.floor(Math.min(dataLen, b.length - dataOff) / stride);
  const out = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const q = dataOff + i * stride;
    out[i] = fmt === 3 ? view.getFloat32(q, true) : view.getInt16(q, true) / 32768;
  }
  return { sr, samples: out };
}

const LO = 60, HI = 400; // Hz search range for Indic speech

function f0(sr, x, s, win) {
  let e0 = 0;
  for (let i = 0; i < win; i++) e0 += x[s + i] * x[s + i];
  if (e0 / win < 4e-4) return 0; // unvoiced
  const minLag = Math.floor(sr / HI), maxLag = Math.ceil(sr / LO);
  let best = 0, bestLag = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    let ac = 0, ea = 0, eb = 0;
    for (let i = 0; i < win - lag; i++) {
      const a = x[s + i], b2 = x[s + i + lag];
      ac += a * b2; ea += a * a; eb += b2 * b2;
    }
    const norm = ac / (Math.sqrt(ea * eb) + 1e-20);
    if (norm > best) { best = norm; bestLag = lag; }
  }
  return best < 0.45 ? 0 : sr / bestLag;
}

function analyse(label, path) {
  const { sr, samples: x } = readWav(path);
  const win = Math.round(sr * 0.04), hop = Math.round(sr * 0.01);
  const f = [];
  for (let s = 0; s + win < x.length; s += hop) { const v = f0(sr, x, s, win); if (v) f.push(v); }
  if (f.length < 5) { console.log(`${label.padEnd(22)} voiced=${f.length} - too few frames`); return; }
  const st = (a) => { const m = a.reduce((p, c) => p + c, 0) / a.length; return { m, sd: Math.sqrt(a.reduce((p, c) => p + (c - m) ** 2, 0) / a.length) }; }
  const semi = f.map((v) => 12 * Math.log2(v / 220));
  const h = st(f), sm = st(semi);
  const sorted = [...f].sort((a, b) => a - b);
  const p10 = sorted[Math.floor(sorted.length * 0.1)], p90 = sorted[Math.floor(sorted.length * 0.9)];
  console.log(
    `${label.padEnd(22)} voiced=${f.length}  F0 mean=${h.m.toFixed(0)}Hz sd=${h.sd.toFixed(0)}Hz` +
    `  spread(p10-p90)=${(p10 / p90 * 100).toFixed(0)}%  semitoneSD=${sm.sd.toFixed(2)}  frame-to-frame jumps>1sem=${
      f.slice(1).filter((v, i) => Math.abs(12 * Math.log2(v / f[i])) > 1).length}`
  );
}

process.argv.slice(2).forEach((a) => { const i = a.indexOf('='); analyse(decodeURI(a.slice(0, i)), decodeURI(a.slice(i + 1))); });
