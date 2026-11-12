import { createHash, createPublicKey, verify } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const args = Object.fromEntries(process.argv.slice(2).map((value, index, values) =>
  value.startsWith('--') ? [value.slice(2), values[index + 1]] : null).filter(Boolean));
if (!args.apk || !args.manifest || !args.report) throw new Error('Required: --apk FILE --manifest FILE --report FILE [--base URL]');
const root = resolve(import.meta.dirname, '../..');
const expected = JSON.parse(readFileSync(args.manifest, 'utf8'));
const apk = readFileSync(args.apk);
const rawKey = Buffer.from(readFileSync(resolve(root, 'app/src/main/assets/update-key.txt'), 'utf8').trim(), 'base64');
const publicKey = createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), rawKey]), type: 'spki', format: 'der' });
function validate(release) {
  assert.equal(release.packageName, 'com.cyotun.jpq');
  assert.equal(release.schemaVersion, 1);
  const values = ['1', release.packageName, String(release.versionCode), release.versionName, String(release.minSdk),
    release.publishedAt, release.notes, release.apk.key, String(release.apk.sizeBytes), release.apk.sha256];
  const payload = Buffer.concat([Buffer.from('JPQ-RELEASE-V1\n'), ...values.flatMap(value => {
    const bytes = Buffer.from(value); return [Buffer.from(`${bytes.length}:`), bytes];
  })]);
  assert(verify(null, payload, publicKey, Buffer.from(release.signature, 'base64')), 'Manifest signature mismatch');
  assert.equal(apk.length, release.apk.sizeBytes);
  assert.equal(createHash('sha256').update(apk).digest('hex'), release.apk.sha256);
}
validate(expected);
const report = { versionCode: expected.versionCode, versionName: expected.versionName, sha256: expected.apk.sha256,
  bytes: apk.length, manifestSignatureVerified: true, apkHashVerified: true, checkedAt: new Date().toISOString() };
if (args.base) {
  const base = args.base.replace(/\/$/, '');
  const latest = await fetch(base + '/v1/releases/latest', { signal: AbortSignal.timeout(60000) });
  assert.equal(latest.status, 200);
  const actual = await latest.json(); validate(actual); assert.deepEqual(actual, expected);
  const url = base + `/v1/releases/${expected.versionCode}/download`;
  const head = await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(60000) });
  assert.equal(head.status, 200); assert.equal(head.headers.get('content-length'), String(apk.length));
  const start = 20 * 1024 * 1024 - 10, end = start + 31;
  const range = await fetch(url, { headers: { Range: `bytes=${start}-${end}` }, signal: AbortSignal.timeout(60000) });
  assert.equal(range.status, 206);
  assert.deepEqual(Buffer.from(await range.arrayBuffer()), apk.subarray(start, end + 1));
  const suffix = await fetch(url, { headers: { Range: 'bytes=-32' }, signal: AbortSignal.timeout(60000) });
  assert.equal(suffix.status, 206); assert.deepEqual(Buffer.from(await suffix.arrayBuffer()), apk.subarray(-32));
  const whole = await fetch(url, { signal: AbortSignal.timeout(300000) }); assert.equal(whole.status, 200);
  const hash = createHash('sha256'); let size = 0;
  for await (const bytes of whole.body) { hash.update(bytes); size += bytes.length; }
  assert.equal(size, apk.length); assert.equal(hash.digest('hex'), expected.apk.sha256);
  assert.equal((await fetch(base + '/latest.json', { signal: AbortSignal.timeout(60000) })).status, 404);
  Object.assign(report, { production: base, headVerified: true, crossPartRangeVerified: true,
    suffixVerified: true, completeDownloadHashVerified: true, privatePathRejected: true });
} else {
  const dir = resolve(root, 'services/updates/release-assets');
  const index = JSON.parse(readFileSync(resolve(dir, 'release-index.json'), 'utf8'));
  assert.equal(index.key, expected.apk.key);
  const joined = Buffer.concat(index.parts.map(part => {
    const bytes = readFileSync(resolve(dir, part.path)); assert.equal(bytes.length, part.sizeBytes); return bytes;
  }));
  assert.deepEqual(joined, apk); report.localPartsVerified = index.parts.length;
}
writeFileSync(args.report, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
