// Installs the Whisper STT frontend constants into the git-ignored asset dir:
//   app/src/main/assets/models/stt-whisper-base/hann_window_400.json  ([400])
//   app/src/main/assets/models/stt-whisper-base/mel_filters_80.json   ([201][80])
//
// Both tables are VENDORED, not computed, and that is deliberate. The encoder was
// trained on openai/whisper's own log-mel, whose 80x201 matrix ships as
// whisper/assets/mel_filters.npz. That matrix is not reproducible from the mel
// formulas: its first channel has a single non-zero bin worth 0.0249, and no triangle
// bank over linear-HTK or Slaney mel points - normalised or not - puts a value there.
// A formula-built bank is a plausible-looking substitute and it costs real accuracy:
// rebuilt for the 257 bins of the FFT the app actually runs, it took English from 0.029
// to 0.206 character error on the app's own reference clip. So the numbers were
// transcribed verbatim into scripts/vendor/ once, and are only checked here for shape
// and finiteness. WhisperStt indexes melMat[bin][mel], hence the 201x80 orientation,
// and reads its first 201 bins out of the 512-point spectrum - see WhisperStt.logMel.
//
//   node scripts/gen-whisper-mel.mjs
import { copyFileSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', 'app', 'src', 'main', 'assets', 'models', 'stt-whisper-base');
mkdirSync(root, { recursive: true });

for (const [name, rows, cols] of [['hann_window_400.json', 400, 1], ['mel_filters_80.json', 201, 80]]) {
  const a = JSON.parse(readFileSync(join(here, 'vendor', name), 'utf8'));
  const shape = cols === 1 ? [a.length, 1] : [a.length, a[0].length];
  if (shape[0] !== rows || shape[1] !== cols) {
    throw new Error(`${name}: expected ${rows}x${cols}, got ${shape.join('x')}`);
  }
  for (const r of (cols === 1 ? [a] : a)) {
    for (const v of r) if (!Number.isFinite(v)) throw new Error(`${name}: non-finite value ${v}`);
  }
  copyFileSync(join(here, 'vendor', name), join(root, name));
  console.log(`  ${name} ${shape.join('x')} ${statSync(join(root, name)).size}B -> ${root}`);
}
