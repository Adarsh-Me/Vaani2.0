// Fetches the multilingual STT pack (Whisper-base INT8, onnx-community) into the
// git-ignored assets dir. Run once per checkout; nothing here runs at build time.
//   node scripts/fetch-whisper-base.mjs
// Result: app/src/main/assets/models/stt-whisper-base/{
//   encoder_model_fp16.onnx, decoder_model_int8.onnx,
//   decoder_with_past_model_int8.onnx, tokenizer.json}
// (~142 MB total: 39 fp16 + 51 + 48 + ~3). The encoder cannot be the int8 export: Without these files the app still builds
// and runs Hindi-only; with them, 10 more languages get a microphone.
import { mkdirSync, existsSync, statSync, createWriteStream } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { get } from 'node:https';

const REPO = 'onnx-community/whisper-base';
const WANT = ['encoder_model_fp16.onnx', 'decoder_model_int8.onnx', 'decoder_with_past_model_int8.onnx', 'tokenizer.json'];
const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'app', 'src', 'main', 'assets', 'models', 'stt-whisper-base');
mkdirSync(root, { recursive: true });

function jget(url) {
  return new Promise((resolve, reject) => {
    get(url, { headers: { 'user-agent': 'vani-fetch/1' } }, (r) => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
        return resolve(jget(new URL(r.headers.location, url).toString()));
      }
      if (r.statusCode !== 200) return reject(new Error(`${r.statusCode} ${url}`));
      let s = '';
      r.on('data', (c) => (s += c));
      r.on('end', () => resolve(s));
    }).on('error', reject);
  });
}

function dl(url, dest) {
  return new Promise((resolve, reject) => {
    const go = (u) =>
      get(u, { headers: { 'user-agent': 'vani-fetch/1' } }, (r) => {
        if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
          return go(new URL(r.headers.location, u).toString());
        }
        if (r.statusCode !== 200) return reject(new Error(`${r.statusCode} ${u}`));
        const total = Number(r.headers['content-length'] || 0);
        let got = 0, last = 0;
        const w = createWriteStream(dest + '.part');
        r.on('data', (c) => {
          got += c.length;
          if (total && got - last > 20 << 20) {
            last = got;
            process.stdout.write(`\r  ${basename(dest)} ${(got / 1048576).toFixed(0)}/${(total / 1048576).toFixed(0)}MB`);
          }
        });
        r.pipe(w);
        w.on('finish', () => {
          process.stdout.write(`\r  ${basename(dest)} ${(got / 1048576).toFixed(1)}MB done\n`);
          import('node:fs').then(({ renameSync }) => {
            renameSync(dest + '.part', dest);
            resolve();
          });
        });
        w.on('error', reject);
      }).on('error', reject);
    go(url);
  });
}

const tree = JSON.parse(await jget(`https://huggingface.co/api/models/${REPO}/tree/main?recursive=true`));
for (const name of WANT) {
  const hit = tree.find((f) => f.path.endsWith(name));
  if (!hit) throw new Error(`not found in ${REPO}: ${name}`);
  const dest = join(root, name);
  if (existsSync(dest) && statSync(dest).size === hit.size) {
    console.log(`  ${name} ${(hit.size / 1048576).toFixed(1)}MB present, skip`);
    continue;
  }
  console.log(`  ${name} ${(hit.size / 1048576).toFixed(1)}MB ...`);
  await dl(`https://huggingface.co/${REPO}/resolve/main/${hit.path}`, dest);
  if (statSync(dest).size !== hit.size) throw new Error(`size mismatch ${name}`);
}
console.log('OK. Verify on-device with: adb shell am start -n com.itantra.walkie/.MainActivity --es debug sttbench');
