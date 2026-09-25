// Reproduce DhVaaniEngine.refMel() + Fft.stftPower() in JS to locate the mel scaling defect.
const fs = require('fs');
const path = require('path');
const TTS = path.join(__dirname, 'models', 'tts');

// ---- npz (stored, uncompressed) ----
function npzEntries(file) {
  const b = fs.readFileSync(file);
  let eocd = -1;
  for (let i = b.length - 22; i >= 0; i--) {
    if (b[i] === 0x50 && b[i + 1] === 0x4b && b[i + 2] === 0x05 && b[i + 3] === 0x06) { eocd = i; break; }
  }
  const count = b.readUInt16LE(eocd + 10);
  let off = b.readUInt32LE(eocd + 16);
  const out = {};
  for (let k = 0; k < count; k++) {
    const fnLen = b.readUInt16LE(off + 28), exLen = b.readUInt16LE(off + 30), fcLen = b.readUInt16LE(off + 32);
    const comp = b.readUInt16LE(off + 10);
    const csize = b.readUInt32LE(off + 20);
    const nm = b.slice(off + 46, off + 46 + fnLen).toString('utf8');
    const lho = b.readUInt32LE(off + 42);
    const lfLen = b.readUInt16LE(lho + 26), leLen = b.readUInt16LE(lho + 28);
    const ds = lho + 30 + lfLen + leLen;
    out[nm] = { data: b.slice(ds, ds + csize), comp };
    off += 46 + fnLen + exLen + fcLen;
  }
  return out;
}
function npy(buf) {
  const hlen = buf.readUInt16LE(8);
  const header = buf.slice(10, 10 + hlen).toString('ascii');
  const shape = /'shape':\s*\(([\d,\s]*)\)/.exec(header)[1].split(',').map(s => s.trim()).filter(s => s !== '').map(Number);
  const descr = /'([a-z])\d?'/i.exec(header);
  const data = buf.slice(10 + hlen);
  return { header, shape, dtype: /'[<>|=]?(\w\w)'/.exec(header)[1], data };
}
function f32(e) { const n = e.data.length / 4; const a = new Float32Array(n); for (let i = 0; i < n; i++) a[i] = e.data.readFloatLE(i * 4); return a; }
function i64(e) { const n = e.data.length / 8; const a = new BigInt64Array(n); for (let i = 0; i < n; i++) a[i] = e.data.readBigInt64LE(i * 8); return a; }
const melEntries = npzEntries(path.join(TTS, 'mel_fb.npz'));
const vocEntries = npzEntries(path.join(TTS, 'vocos_head.npz'));

console.log('=== mel_fb.npz ===');
for (const [k, v] of Object.entries(melEntries)) {
  const e = npy(v.data);
  console.log(` ${k.padEnd(12)} dtype=${e.dtype} shape=[${e.shape}] bytes=${e.data.length}`);
}
console.log('=== vocos_head.npz ===');
for (const [k, v] of Object.entries(vocEntries)) {
  const e = npy(v.data);
  console.log(` ${k.padEnd(14)} dtype=${e.dtype} shape=[${e.shape}] bytes=${e.data.length}`);
}
const nfftE = npy(melEntries['n_fft.npy'].data), hopE = npy(melEntries['hop.npy'].data), nmelsE = npy(melEntries['n_mels.npy'].data);
console.log('scalars: n_fft=', Array.from(i64(nfftE)), 'hop=', Array.from(i64(hopE)), 'n_mels=', Array.from(i64(nmelsE)));
const wn = npy(vocEntries['n_fft.npy'].data), wh = npy(vocEntries['hop_length.npy'].data), ww = npy(vocEntries['win_length.npy'].data);
console.log('vocos   : n_fft=', Array.from(i64(wn)), 'hop_length=', Array.from(i64(wh)), 'win_length=', Array.from(i64(ww)));

const fbE = npy(melEntries['fb.npy'].data);
const fbShape = fbE.shape; // expect [513,100]
const fb = f32(npy(melEntries['fb.npy'].data));
const win = f32(npy(melEntries['window.npy'].data));
function statsArr(a, name) {
  let mn = Infinity, mx = -Infinity, s = 0, ss = 0;
  for (const v of a) { if (v < mn) mn = v; if (v > mx) mx = v; s += v; ss += v * v; }
  const m = s / a.length;
  console.log(`${name}: n=${a.length} min=${mn.toFixed(6)} max=${mx.toFixed(6)} mean=${m.toFixed(6)} sd=${Math.sqrt(ss / a.length - m * m).toFixed(6)}`);
}
statsArr(win, 'window');
statsArr(fb, 'fb(all)');
// per-column sums of fb (energy normalization check)
const C = fbShape[1];
const colSum = new Array(C).fill(0);
for (let c = 0; c < C; c++) { let s = 0; for (let r = 0; r < fbShape[0]; r++) s += fb[r * C + c]; colSum[c] = s; }
console.log('fb col sums (first 8):', colSum.slice(0, 8).map(x => x.toFixed(6)).join(' '), '| last 4:', colSum.slice(-4).map(x => x.toFixed(6)).join(' '));
console.log('fb col sum min/max:', Math.min(...colSum).toFixed(6), Math.max(...colSum).toFixed(6));

// ---- WAV ----
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
  const n = Math.floor(Math.min(dataLen, b.length - dataOff) / (bits / 8) / ch);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let v = 0;
    if (fmt === 3 && bits === 32) v = b.readFloatLE(dataOff + i * 4 * ch);
    else v = b.readInt16LE(dataOff + i * 2 * ch) / 32768;
    out[i] = v;
  }
  return { sr, ch, fmt, bits, samples: out };
}
function resample(x, from, to) {
  if (from === to) return x;
  const n = Math.max(1, Math.floor(x.length * to / from));
  const o = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const p = i * from / to, i0 = Math.min(Math.max(0, Math.floor(p)), x.length - 1), i1 = Math.min(i0 + 1, x.length - 1);
    o[i] = x[i0] * (1 - (p - i0)) + x[i1] * (p - i0);
  }
  return o;
}

// ---- FFT (port of Kotlin radix-2) ----
function fft(re, im, invert) {
  const n = re.length; let j = 0;
  for (let i = 1; i < n; i++) {
    let bit = n >> 1;
    while (j & bit) { j ^= bit; bit >>= 1; }
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = 2 * Math.PI / len * (invert ? 1 : -1);
    const wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cwr = 1, cwi = 0;
      for (let k = 0; k < len / 2; k++) {
        const ur = re[i + k], ui = im[i + k];
        const vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi;
        const vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr;
        re[i + k] = ur + vr; im[i + k] = ui + vi;
        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
        const nwr = cwr * wr - cwi * wi; cwi = cwr * wi + cwi * wr; cwr = nwr;
      }
    }
  }
  if (invert) for (let i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
}
function stftPower(pcm, winArr, hop, nf) {
  const pad = nf / 2;
  const ext = new Float32Array(pcm.length + 2 * pad);
  for (let i = 0; i < pcm.length; i++) ext[i + pad] = pcm[i];
  for (let i = 0; i < pad; i++) {
    ext[pad - 1 - i] = pcm[1 + i] ?? 0;
    ext[pad + pcm.length + i] = pcm[pcm.length - 2 - i] ?? 0;
  }
  const frames = 1 + Math.floor(pcm.length / hop);
  const bins = nf / 2 + 1;
  const wlen = winArr.length;
  const out = new Array(frames);
  for (let f = 0; f < frames; f++) {
    const re = new Float64Array(nf), im = new Float64Array(nf);
    for (let i = 0; i < wlen; i++) re[(nf - wlen) / 2 + i] = (ext[f * hop + i] ?? 0) * winArr[i];
    fft(re, im, false);
    const row = new Float64Array(bins);
    for (let k = 0; k < bins; k++) row[k] = re[k] * re[k] + im[k] * im[k];
    out[f] = row;
  }
  return out;
}
function refMel(pcm) {
  const pow = stftPower(pcm, win, 256, 1024);
  const res = new Array(pow.length);
  for (let f = 0; f < pow.length; f++) {
    const row = new Float64Array(100);
    for (let m = 0; m < 100; m++) {
      let s = 0;
      for (let b = 0; b < 513; b++) s += pow[f][b] * fb[b * 100 + m];
      row[m] = Math.log(Math.max(s, 1e-5));
    }
    res[f] = row;
  }
  return res;
}

const f = path.join(__dirname, 'refs', 'ref_hi_m.wav');
const wav = readWav(f);
console.log(`\nref_hi_m.wav sr=${wav.sr} ch=${wav.ch} fmt=${wav.fmt} bits=${wav.bits} n=${wav.samples.length} (${(wav.samples.length / wav.sr).toFixed(2)}s)`);
let pk = 0, absmax = 0; for (const v of wav.samples) { if (Math.abs(v) > absmax) absmax = Math.abs(v); pk += v * v; }
console.log(`ref rms=${Math.sqrt(pk / wav.samples.length).toFixed(6)} absmax=${absmax.toFixed(6)}`);
const pcm = resample(wav.samples, wav.sr, 24000);
const mel = refMel(pcm);
statsArr(Float64Array.from(mel.flatMap(r => Array.from(r))), 'refMel(Kotlin-port)');

// where does the max live?
let best = { v: -Infinity }, worstFrames = {};
for (let i = 0; i < mel.length; i++) for (let m = 0; m < 100; m++) {
  const v = mel[i][m];
  if (v > best.v) best = { v, f: i, m };
  if (v > 20) worstFrames[i] = (worstFrames[i] || 0) + 1;
}
console.log(`max log-mel ${best.v.toFixed(2)} at frame=${best.f} band=${best.m}`);
console.log(`frames with >1 band above 20: ${Object.keys(worstFrames).length} of ${mel.length}`);
const powRef = stftPower(pcm, win, 256, 1024);
console.log(`pow[frame ${best.f}][bin ${best.m}] =`, powRef[best.f][best.m].toExponential(4));
let powMax = 0, powMaxAt = []; for (let i = 0; i < powRef.length; i++) for (let k = 0; k < 513; k++) if (powRef[i][k] > powMax) { powMax = powRef[i][k]; powMaxAt = [i, k]; }
console.log(`max raw power = ${powMax.toExponential(4)} at frame/bin`, powMaxAt);

// what SHOULD it be? typical torch/espnet mel: /(sum win^2) and 1/nfft scaling -> compare
const winEnergy = win.reduce((s, v) => s + v * v, 0);
console.log(`sum(win)=${win.reduce((s, v) => s + v, 0).toFixed(4)} sum(win^2)=${winEnergy.toFixed(4)}`);

// normalized variant
function refMelNorm(pcm) {
  const pow = stftPower(pcm, win, 256, 1024);
  const norm = 1 / (winEnergy * 1024); // scale by window+nfft so power ~ amplitude^2
  const res = new Array(pow.length);
  for (let f = 0; f < pow.length; f++) {
    const row = new Float64Array(100);
    for (let m = 0; m < 100; m++) {
      let s = 0;
      for (let b = 0; b < 513; b++) s += pow[f][b] * fb[b * 100 + m];
      row[m] = Math.log(Math.max(s * norm, 1e-5));
    }
    res[f] = row;
  }
  return res;
}
statsArr(Float64Array.from(refMelNorm(pcm).flatMap(r => Array.from(r))), 'refMel(/nfft*winE)');
