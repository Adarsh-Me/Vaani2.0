#!/usr/bin/env node
/*
 * Fold an ONNX model's external-data weights into the graph file.
 *
 * ai4bharat's STT export splits weights into model.int8.opt.onnx (739 KB graph) +
 * .onnx.data (130 MB). ONNX Runtime resolves that sidecar relative to a model PATH,
 * which is why the app had to copy both into filesDir - and copying meant the device
 * stored 812 MB of models twice, on top of the APK. Merging makes the model one
 * self-contained file that can be streamed from the APK into OrtEnvironment.
 *
 * Only the wire format is needed: walk TensorProto entries, and for each one marked
 * data_location=EXTERNAL, replace its external_data entries with raw_data bytes.
 *
 * usage: node scripts/merge-onnx-data.js <model.onnx> <weights.data> <out.onnx>
 */
const fs = require('fs');

const WIRE = { VARINT: 0, I64: 1, LEN: 2, I32: 5 };

function readVarint(buf, pos) {
  let v = 0n, shift = 0n, i = pos;
  while (true) {
    const b = buf[i++];
    v |= BigInt(b & 0x7f) << shift;
    if (!(b & 0x80)) break;
    shift += 7n;
  }
  return [v, i];
}

function writeVarint(n) {
  const out = [];
  let v = typeof n === 'bigint' ? n : BigInt(n);
  do { let b = Number(v & 0x7fn); v >>= 7n; if (v > 0n) b |= 0x80; out.push(b); } while (v > 0n);
  return Buffer.from(out);
}

/** Split a length-delimited message into [fieldNumber, wireType, payloadBuffer][] */
function fields(buf) {
  const list = [];
  let i = 0;
  while (i < buf.length) {
    const [key, j] = readVarint(buf, i); i = j;
    const fn = Number(key >> 3n), wt = Number(key & 7n);
    if (wt === WIRE.VARINT) { const [v, k] = readVarint(buf, i); list.push([fn, wt, v, i, k]); i = k; }
    else if (wt === WIRE.I64) { list.push([fn, wt, buf.subarray(i, i + 8), i, i + 8]); i += 8; }
    else if (wt === WIRE.I32) { list.push([fn, wt, buf.subarray(i, i + 4), i, i + 4]); i += 4; }
    else if (wt === WIRE.LEN) {
      const [len, k] = readVarint(buf, i); const start = k, end = start + Number(len);
      list.push([fn, wt, buf.subarray(start, end), start, end]); i = end;
    } else throw new Error('unsupported wire type ' + wt);
  }
  return list;
}

function tag(fn, wt) { return writeVarint((BigInt(fn) << 3n) | BigInt(wt)); }
function lenField(fn, payload) { return Buffer.concat([tag(fn, WIRE.LEN), writeVarint(payload.length), payload]); }

// TensorProto field numbers (onnx.proto)
const T = { DIMS: 1, DATA_TYPE: 2, NAME: 8, RAW_DATA: 9, EXTERNAL: 13, LOCATION: 14 };
// StringStringEntryProto
const KV = { KEY: 1, VALUE: 2 };

function mergeTensor(tbuf, data) {
  const fs_ = fields(tbuf);
  const isExternal = fs_.some(f => f[0] === T.LOCATION && f[2] === 1n);
  if (!isExternal) return { buf: tbuf, merged: 0 };

  // external_data is repeated StringStringEntryProto, so each field-13 occurrence in
  // the tensor IS one entry: parse it once to get its key and value.
  const kv = {};
  for (const f of fs_) {
    if (f[0] !== T.EXTERNAL || f[1] !== WIRE.LEN) continue;
    let k = '', v = '';
    for (const p of fields(f[2])) {
      if (p[0] === KV.KEY) k = p[2].toString('utf8');
      else if (p[0] === KV.VALUE) v = p[2].toString('utf8');
    }
    if (k) kv[k] = v;
  }
  const offset = BigInt(kv.offset || '0');
  const length = BigInt(kv.length || '0');
  if (!kv.location) throw new Error('external tensor without location');
  if (offset + length > BigInt(data.length)) throw new Error('external range past end of data file');
  const raw = data.subarray(Number(offset), Number(offset + length));

  const out = [];
  for (const f of fs_) {
    if (f[0] === T.EXTERNAL) continue;              // drop: no longer external
    if (f[0] === T.LOCATION) continue;              // drop: defaults to DEFAULT(0)
    if (f[0] === T.RAW_DATA) continue;              // replaced below
    if (f[1] === WIRE.VARINT) out.push(Buffer.concat([tag(f[0], f[1]), writeVarint(f[2])]));
    else out.push(Buffer.concat([tag(f[0], f[1]), writeVarint(f[2].length), f[2]]));
  }
  out.push(lenField(T.RAW_DATA, raw));
  return { buf: Buffer.concat(out), merged: raw.length };
}

function main() {
  const [modelPath, dataPath, outPath] = process.argv.slice(2);
  if (!modelPath || !dataPath || !outPath) { console.error('usage: merge-onnx-data.js <in.onnx> <in.data> <out.onnx>'); process.exit(2); }
  const model = fs.readFileSync(modelPath);
  const data = fs.readFileSync(dataPath);
  const top = fields(model);
  let graph = null, graphIdx = -1;
  top.forEach((f, i) => { if (f[0] === 7 && f[1] === WIRE.LEN) { graph = f[2]; graphIdx = i; } });
  if (!graph) throw new Error('no GraphProto (field 7) in model');

  const gf = fields(graph);
  const outParts = [];
  let mergedBytes = 0, mergedTensors = 0;
  for (const f of gf) {
    if (f[0] === 5 && f[1] === WIRE.LEN) { // initializer: TensorProto
      const m = mergeTensor(f[2], data);
      mergedBytes += m.merged; if (m.merged) mergedTensors++;
      outParts.push(Buffer.concat([tag(5, WIRE.LEN), writeVarint(m.buf.length), m.buf]));
    } else if (f[1] === WIRE.VARINT) {
      outParts.push(Buffer.concat([tag(f[0], f[1]), writeVarint(f[2])]));
    } else {
      outParts.push(Buffer.concat([tag(f[0], f[1]), writeVarint(f[2].length), f[2]]));
    }
  }
  const newGraph = Buffer.concat(outParts);
  const rebuilt = [];
  top.forEach((f, i) => {
    if (i === graphIdx) { rebuilt.push(Buffer.concat([tag(7, WIRE.LEN), writeVarint(newGraph.length), newGraph])); return; }
    if (f[1] === WIRE.VARINT) rebuilt.push(Buffer.concat([tag(f[0], f[1]), writeVarint(f[2])]));
    else rebuilt.push(Buffer.concat([tag(f[0], f[1]), writeVarint(f[2].length), f[2]]));
  });
  fs.writeFileSync(outPath, Buffer.concat(rebuilt));
  console.log(`tensors embedded: ${mergedTensors}, weight bytes: ${(mergedBytes / 1048576).toFixed(1)} MB`);
  console.log(`data file used: ${(data.length / 1048576).toFixed(1)} MB, output: ${(fs.statSync(outPath).size / 1048576).toFixed(1)} MB`);
  if (mergedBytes !== data.length) console.log(`WARNING: embedded ${mergedBytes} of ${data.length} data bytes`);
}

main();
