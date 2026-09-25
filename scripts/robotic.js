// Objective "does it sound robotic" proxies for a frame-based vocoder output.
// A mel->vocoder chain that regenerates every hop samples independently leaks
// frame-rate periodicity: micro-clicks at boundaries and modulation energy at
// frameRate and its harmonics. Real speech has neither.
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
    let v = 0;
    for (let c = 0; c < ch; c++) v += fmt === 3 ? view.getFloat32(q + c * 4, true) : view.getInt16(q + c * 2, true) / 32768;
    out[i] = v / ch;
  }
  return { sr, samples: out };
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

const HOP = 256; // Vocos/iSTFT hop used by DhVaaniEngine

function analyse(label, path) {
  const { sr, samples: x } = readWav(path);
  const frameRate = sr / HOP;

  // 1. Boundary discontinuity: average |dx| exactly at frame boundaries vs everywhere else.
  let at = 0, atN = 0, off2 = 0, offN = 0;
  for (let i = 1; i < x.length; i++) {
    const d = Math.abs(x[i] - x[i - 1]);
    if (i % HOP === 0) { at += d; atN++; } else { off2 += d; offN++; }
  }
  const clickRatio = (at / atN) / (off2 / offN);

  // 2. Modulation spectrum of the smoothed envelope, looking for frameRate + harmonics.
  // mhop=32 gives a 750 Hz envelope rate, so the 94/188/281 Hz harmonics all stay
  // under its 375 Hz Nyquist limit.
  const smooth = 480, mhop = 32;
  const env = new Float64Array(Math.floor((x.length - smooth) / mhop));
  let acc = 0;
  for (let i = 0; i < smooth; i++) acc += Math.abs(x[i]);
  for (let f = 0; f < env.length; f++) {
    env[f] = acc / smooth;
    const base = f * mhop;
    acc += Math.abs(x[base + smooth]) - Math.abs(x[base]);
  }
  // A running sum drifts a hair below zero on quiet frames; log() of that is NaN.
  for (let i = 0; i < env.length; i++) env[i] = Math.log(Math.max(env[i], 0) + 1e-7);
  // demean + Hann
  const M = 4096, mean = env.reduce((a, v) => a + v, 0) / (env.length || 1);
  const re = new Float64Array(M), im = new Float64Array(M);
  const segs = Math.max(1, Math.floor(env.length / M));
  const spec = new Float64Array(M / 2);
  for (let s = 0; s < segs; s++) {
    for (let i = 0; i < M; i++) {
      const w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / M);
      const v = s * M + i < env.length ? env[s * M + i] : mean; // zero-pad tails, never NaN
      re[i] = (v - mean) * w; im[i] = 0;
    }
    fft(re, im);
    for (let k = 1; k < M / 2; k++) spec[k] += re[k] * re[k] + im[k] * im[k];
  }
  const modHz = (sr / mhop) / M; // 0.0916 Hz resolution
  const peakAt = (hz) => {
    const k = Math.round(hz / modHz);
    if (k + 120 >= M / 2) return NaN;
    let nb = 0, c = 0;
    for (let d = 25; d <= 120; d++) { nb += spec[k - d] + spec[k + d]; c += 2; }
    return 10 * Math.log10((spec[k] / (nb / c + 1e-20)));
  };

  // 3. Cycle-to-cycle similarity of the residual: how much does the signal repeat every HOP.
  const lag = HOP;
  let num = 0, d1 = 0, d2 = 0;
  for (let i = lag; i < x.length; i++) { num += x[i] * x[i - lag]; d1 += x[i] * x[i]; d2 += x[i - lag] * x[i - lag]; }
  const hopCorr = num / (Math.sqrt(d1 * d2) + 1e-20);

  console.log(
    `${label.padEnd(18)} frameRate=${frameRate.toFixed(1)}Hz  boundaryClick=x${clickRatio.toFixed(2)}` +
    `  mod@94Hz=${peakAt(frameRate).toFixed(1)}dB  mod@188Hz=${peakAt(frameRate * 2).toFixed(1)}dB` +
    `  mod@281Hz=${peakAt(frameRate * 3).toFixed(1)}dB  corr(x,x@hop)=${hopCorr.toFixed(3)}`
  );
}

process.argv.slice(2).forEach((a) => { const i = a.indexOf('='); analyse(decodeURI(a.slice(0, i)), decodeURI(a.slice(i + 1))); });
