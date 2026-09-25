// Band-energy profile of a WAV: answers "does it sound human" better than zcr,
// because a muffled/buzzy voice fails here while a clean one tracks the reference.
const fs = require('fs');

function readWav(p) {
  const b = fs.readFileSync(p);
  let off = 12, fmt = 1, bits = 16, ch = 1, sr = 8000, dataOff = -1, dataLen = 0;
  while (off + 8 <= b.length) {
    const id = b.toString('ascii', off, off + 4), len = b.readUInt32LE(off + 4);
    // fmt chunk body starts at off+8: audioFormat u16, channels u16, sampleRate u32, ... bits u16 at +22
    if (id === 'fmt ') { fmt = b.readUInt16LE(off + 8); ch = b.readUInt16LE(off + 10); sr = b.readUInt32LE(off + 12); bits = b.readUInt16LE(off + 22); }
    if (id === 'data') { dataOff = off + 8; dataLen = len; break; }
    off += 8 + len + (len & 1);
  }
  const stride = (bits / 8) * ch;
  const n = Math.floor(Math.min(dataLen, b.length - dataOff) / stride);
  const out = new Float64Array(n);
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength);
  for (let i = 0; i < n; i++) {
    const p2 = dataOff + i * stride;
    out[i] = fmt === 3 ? view.getFloat32(p2, true) : view.getInt16(p2, true) / 32768;
  }
  return { sr, samples: out };
}

// radix-2 in-place FFT on Float64 re/im
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

const BANDS = [[0, 1000], [1000, 3000], [3000, 6000], [6000, 12000]];

function profile(label, path) {
  const { sr, samples } = readWav(path);
  const N = 1024, hop = 256;
  const win = new Float64Array(N);
  for (let i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
  const energy = BANDS.map(() => 0);
  let total = 0, voiced = 0, frames = 0;
  const re = new Float64Array(N), im = new Float64Array(N);
  for (let s = 0; s + N <= samples.length; s += hop) {
    let rms = 0;
    for (let i = 0; i < N; i++) { const v = samples[s + i] * win[i]; re[i] = v; im[i] = 0; rms += samples[s + i] * samples[s + i]; }
    rms = Math.sqrt(rms / N);
    frames++;
    if (rms < 0.01) continue; // silence/padding carries no spectral information
    voiced++;
    fft(re, im);
    for (let k = 1; k <= N / 2; k++) {
      const mag = re[k] * re[k] + im[k] * im[k];
      const hz = (k * sr) / N;
      total += mag;
      for (let bi = 0; bi < BANDS.length; bi++) if (hz >= BANDS[bi][0] && hz < BANDS[bi][1]) energy[bi] += mag;
    }
  }
  const pct = energy.map((e) => ((100 * e) / (total || 1)).toFixed(1).padStart(5));
  const centroid = energy.reduce((a, e, i) => a + e * ((BANDS[i][0] + BANDS[i][1]) / 2), 0) / (energy.reduce((a, e) => a + e, 0) || 1);
  console.log(
    `${label.padEnd(20)} voiced=${voiced}/${frames}  0-1k=${pct[0]}%  1-3k=${pct[1]}%  3-6k=${pct[2]}%  6-12k=${pct[3]}%  centroid=${Math.round(centroid)}Hz`
  );
}

process.argv.slice(2).forEach((a) => { const i = a.indexOf('='); profile(decodeURI(a.slice(0, i)), decodeURI(a.slice(i + 1))); });
