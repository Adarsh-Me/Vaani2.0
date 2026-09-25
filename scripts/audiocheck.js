// Objective speech-ness check: compares generated TTS audio against a human reference.
// Noise and speech differ sharply in zero-crossing rate, silent-frame fraction and dynamics.
const fs = require('fs');

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
  const s = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    if (fmt === 3 && bits === 32) s[i] = b.readFloatLE(dataOff + i * w);
    else s[i] = b.readInt16LE(dataOff + i * w) / 32768;
  }
  return { sr, ch, samples: s };
}

function analyze(name, file) {
  const { sr, samples: x } = readWav(file);
  let zc = 0, peak = 0, e = 0;
  for (let i = 0; i < x.length; i++) {
    const a = Math.abs(x[i]); if (a > peak) peak = a;
    e += x[i] * x[i];
    if (i > 0 && (x[i] >= 0) !== (x[i - 1] >= 0)) zc++;
  }
  const win = Math.floor(sr * 0.025), hop = Math.floor(sr * 0.01);
  let frames = 0, silent = 0, loudest = 0;
  const db = [];
  for (let f = 0; f + win <= x.length; f += hop) {
    let s = 0; for (let i = f; i < f + win; i++) s += x[i] * x[i];
    const d = 10 * Math.log10(s / (win / 2) + 1e-12);
    db.push(d); frames++; if (d < -55) silent++; if (d > loudest) loudest = d;
  }
  const quiet = db.length ? Math.min(...db) : 0;
  let clip = 0;
  for (const v of x) if (Math.abs(v) >= 0.999) clip++;
  console.log(
    `${name.padEnd(18)} sr=${sr} dur=${(x.length / sr).toFixed(2)}s ` +
    `zcr=${Math.round(zc / (x.length / sr))}/s ` +
    `peak=${peak.toFixed(3)} rms=${Math.sqrt(e / x.length).toFixed(4)} ` +
    `silentFrames=${((silent / frames) * 100).toFixed(0)}% ` +
    `dynRange ${(loudest - quiet).toFixed(0)}dB ` +
    `clipped=${clip}`
  );
}

for (const a of process.argv.slice(2)) {
  const i = a.indexOf('=');
  analyze(a.slice(0, i), a.slice(i + 1));
}
