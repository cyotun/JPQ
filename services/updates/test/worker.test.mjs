import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.mjs';

const origin = 'https://jpq.example';
const sha256 = 'a'.repeat(64);
const release = {
  schemaVersion: 1, packageName: 'com.cyotun.jpq', versionCode: 1,
  versionName: '0.1.0', minSdk: 28, publishedAt: '2026-10-11T09:00:00Z', notes: '测试发布',
  apk: { key: `releases/1/jpq-${sha256}.apk`, sizeBytes: 10, sha256 },
  signature: 'A'.repeat(86) + '==',
};
function object(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return { size: new TextEncoder().encode(text).length, text: async () => text };
}
function bucket(manifest = release, metadata = {}, calls = []) {
  return {
    async get(key, options) {
      calls.push({ key, options });
      if (key === 'releases/stable/latest.json') return manifest === null ? null : object(manifest);
      if (key !== release.apk.key) return null;
      const range = options?.range;
      const bytes = range ? '0123456789'.slice(range.offset, range.offset + range.length) : '0123456789';
      return { size: 10, customMetadata: { sha256, ...metadata }, body: new Response(bytes).body };
    },
    async head(key) { return this.get(key); },
  };
}
const fetch = (path, env = {}, options) => worker.fetch(new Request(origin + path, options), env);

test('healthy service explicitly has no release without storage', async () => {
  assert.equal((await fetch('/health')).status, 200);
  const response = await fetch('/v1/releases/latest');
  assert.equal(response.status, 204);
  assert.equal(await response.text(), '');
});
test('missing stable pointer has no release, not an invented version', async () => {
  assert.equal((await fetch('/v1/releases/latest', { JPQ_RELEASES: bucket(null) })).status, 204);
});
test('stable manifest survives unchanged for client signature verification', async () => {
  const original = JSON.stringify(release, null, 2) + '\n';
  const response = await fetch('/v1/releases/latest', { JPQ_RELEASES: bucket(original) });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), original);
});
test('wrong app, traversing object key, unsigned or malformed manifest fail closed', async () => {
  for (const manifest of [
    { ...release, packageName: 'another.app' },
    { ...release, apk: { ...release.apk, key: '../other-project.apk' } },
    { ...release, signature: '' }, 'bad JSON',
  ]) {
    assert.equal((await fetch('/v1/releases/latest', { JPQ_RELEASES: bucket(manifest) })).status, 503);
  }
});
test('download is limited to the published version and hash metadata', async () => {
  assert.equal((await fetch('/v1/releases/2/download', { JPQ_RELEASES: bucket() })).status, 404);
  assert.equal((await fetch('/v1/releases/1/download', { JPQ_RELEASES: bucket(release, { sha256: 'b'.repeat(64) }) })).status, 503);
  const response = await fetch('/v1/releases/1/download', { JPQ_RELEASES: bucket() });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), '0123456789');
});
test('byte and suffix ranges stream only the requested APK segment', async () => {
  for (const [header, expected, contentRange] of [
    ['bytes=2-5', '2345', 'bytes 2-5/10'], ['bytes=-3', '789', 'bytes 7-9/10'],
  ]) {
    const response = await fetch('/v1/releases/1/download', { JPQ_RELEASES: bucket() }, { headers: { Range: header } });
    assert.equal(response.status, 206);
    assert.equal(response.headers.get('Content-Range'), contentRange);
    assert.equal(await response.text(), expected);
  }
  assert.equal((await fetch('/v1/releases/1/download', { JPQ_RELEASES: bucket() }, { headers: { Range: 'bytes=20-' } })).status, 416);
});
test('HEAD is empty and public routes provide no writes or arbitrary object reads', async () => {
  const response = await fetch('/v1/releases/1/download', { JPQ_RELEASES: bucket() }, { method: 'HEAD' });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), '');
  assert.equal((await fetch('/v1/releases/latest', {}, { method: 'POST' })).status, 405);
  assert.equal((await fetch('/admin/upload')).status, 404);
});
