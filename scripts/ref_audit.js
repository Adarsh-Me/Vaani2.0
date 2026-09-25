// Pull ref transcripts + audit token coverage with the real tokens.txt
const fs = require('fs');
const cp = require('child_process');
const ADB = (process.env.LOCALAPPDATA + '/Android/Sdk/platform-tools/adb.exe').replace(/\\/g, '/');

function sh(cmd) {
  return cp.execSync(`"${ADB}" -s emulator-5554 shell "${cmd}"`, { encoding: 'latin1', maxBuffer: 1e7 });
}

// tokens.txt via base64 (binary-safe)
const b64 = sh(`run-as com.itantra.walkie sh -c 'cat files/models/tts/tokens.txt | base64'`).replace(/\s+/g, '');
const tokBuf = Buffer.from(b64, 'base64');
const tokMap = new Map();
for (const l of tokBuf.toString('utf8').split('\n')) {
  if (!l) continue;
  const i = l.lastIndexOf('\t');
  if (i < 0) continue;
  tokMap.set(l.slice(0, i), parseInt(l.slice(i + 1), 10));
}
console.log('tokens:', tokMap.size, 'blank _ =', tokMap.get('_'));

// ref transcripts: one base64 blob of a tar-like concat with names
const files = sh(`run-as com.itantra.walkie sh -c 'ls files/refs'`).split('\n').map(s => s.trim()).filter(s => s.endsWith('.txt'));
console.log('ref txt files:', files.length);
const rows = [];
for (const f of files) {
  const t = sh(`run-as com.itantra.walkie sh -c 'cat files/refs/${f}'`);
  // sh() gives latin1-decoded bytes; the device bytes are UTF-8 -> recover first
  const text = Buffer.from(t, 'latin1').toString('utf8').replace(/\r/g, '').replace(/\n+$/, '');
  // tokenize like the app: per code point, fallback blank
  let ids = 0, blank = 0;
  for (const ch of text) {
    const id = tokMap.has(ch) ? tokMap.get(ch) : tokMap.get('_');
    if (id === undefined) continue;
    ids++;
    if (id === tokMap.get('_')) blank++;
  }
  const pct = ids ? (100 * blank / ids).toFixed(1) : '-';
  rows.push({ f, n: text.length, ids, blankPct: pct, text: text.slice(0, 60) });
}
rows.sort((a, b) => parseFloat(b.blankPct) - parseFloat(a.blankPct));
for (const r of rows) console.log(`${r.f} chars=${r.n} ids=${r.ids} blankFallback=${r.blankPct}% :: ${r.text}`);
