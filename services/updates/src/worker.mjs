/**
 * JPQ's read-only update service. No release is advertised until an explicit
 * JPQ_RELEASES binding and validated stable release manifest are present.
 */
import { AssetBucket } from './asset-bucket.mjs';
const PACKAGE_NAME = "com.cyotun.jpq";
const MANIFEST_KEY = "releases/stable/latest.json";
const MAX_MANIFEST_BYTES = 32 * 1024;
const SAFE_HEADERS = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
};

class ReleaseUnavailable extends Error {}

function json(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...SAFE_HEADERS,
      "Content-Type": "application/json; charset=utf-8",
      ...extraHeaders,
    },
  });
}

function validManifest(release) {
  if (!release || typeof release !== "object" || Array.isArray(release)) return false;
  const apk = release.apk;
  return (
    release.schemaVersion === 1 &&
    release.packageName === PACKAGE_NAME &&
    Number.isSafeInteger(release.versionCode) && release.versionCode > 0 &&
    typeof release.versionName === "string" && release.versionName.length > 0 && release.versionName.length <= 80 &&
    Number.isSafeInteger(release.minSdk) && release.minSdk >= 21 && release.minSdk <= 100 &&
    typeof release.publishedAt === "string" && Number.isFinite(Date.parse(release.publishedAt)) &&
    typeof release.notes === "string" && release.notes.length <= 4000 &&
    apk && typeof apk === "object" &&
    typeof apk.sha256 === "string" && /^[a-f0-9]{64}$/.test(apk.sha256) &&
    Number.isSafeInteger(apk.sizeBytes) && apk.sizeBytes > 0 && apk.sizeBytes <= 1024 ** 3 &&
    apk.key === `releases/${release.versionCode}/jpq-${apk.sha256}.apk` &&
    typeof release.signature === "string" && /^[A-Za-z0-9+/]{86}==$/.test(release.signature)
  );
}

async function readRelease(bucket) {
  if (!bucket) return null;
  const object = await bucket.get(MANIFEST_KEY);
  if (!object) return null;
  if (!Number.isSafeInteger(object.size) || object.size > MAX_MANIFEST_BYTES) {
    throw new ReleaseUnavailable("Manifest too large");
  }
  let release;
  let text;
  try {
    text = await object.text();
    if (new TextEncoder().encode(text).byteLength > MAX_MANIFEST_BYTES) throw new Error("Manifest too large");
    release = JSON.parse(text);
  } catch {
    throw new ReleaseUnavailable("Manifest is invalid");
  }
  if (!validManifest(release)) throw new ReleaseUnavailable("Release contract is invalid");
  return { release, text };
}

// Support a single bounded byte range. APK bytes are streamed from R2.
function parseRange(header, size) {
  if (header === null) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header);
  if (!match || (!match[1] && !match[2])) return false;
  let start;
  let end;
  if (!match[1]) {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return false;
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= size || end < start) return false;
    end = Math.min(end, size - 1);
  }
  return { offset: start, length: end - start + 1 };
}

async function download(request, bucket, release) {
  const range = parseRange(request.headers.get("Range"), release.apk.sizeBytes);
  if (range === false) {
    return new Response(null, { status: 416, headers: { ...SAFE_HEADERS, "Content-Range": `bytes */${release.apk.sizeBytes}` } });
  }
  const key = release.apk.key;
  const object = request.method === "HEAD"
    ? await bucket.head(key)
    : await bucket.get(key, range ? { range } : undefined);
  if (!object) throw new ReleaseUnavailable("APK not uploaded");
  if (object.size !== release.apk.sizeBytes || object.customMetadata?.sha256 !== release.apk.sha256) {
    throw new ReleaseUnavailable("APK metadata does not match the manifest");
  }
  const headers = {
    ...SAFE_HEADERS,
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Disposition": `attachment; filename="jpq-${release.versionCode}.apk"`,
    "Content-Length": String(range ? range.length : object.size),
    "Accept-Ranges": "bytes",
    "ETag": `"${release.apk.sha256}"`,
  };
  if (range) headers["Content-Range"] = `bytes ${range.offset}-${range.offset + range.length - 1}/${object.size}`;
  return new Response(request.method === "HEAD" ? null : object.body, { status: range ? 206 : 200, headers });
}

async function handle(request, env = {}) {
  if (!['GET', 'HEAD'].includes(request.method)) {
    return json({ error: "method_not_allowed" }, 405, { Allow: "GET, HEAD" });
  }
  const url = new URL(request.url);
  if (url.pathname === "/") {
    return new Response("JPQ 更新服务运行中。\n尚未发布安装包时，版本接口返回 204。\n", {
      headers: { ...SAFE_HEADERS, "Content-Type": "text/plain; charset=utf-8" },
    });
  }
  if (url.pathname === "/health") return json({ service: "jpq-updates", status: "ok", schemaVersion: 1 });
  const downloadMatch = /^\/v1\/releases\/([1-9]\d*)\/download$/.exec(url.pathname);
  if (url.pathname !== "/v1/releases/latest" && !downloadMatch) return json({ error: "not_found" }, 404);
  const assets=env.RELEASE_STORE ?? env.ASSETS;
  const bucket=env.JPQ_RELEASES ?? (assets ? new AssetBucket(assets) : undefined);
  const stored = await readRelease(bucket);
  const release = stored?.release;
  if (url.pathname === "/v1/releases/latest") {
    if (!release) return new Response(null, { status: 204, headers: SAFE_HEADERS });
    // Do not rewrite a signed publisher document, including its whitespace.
    return new Response(stored.text, {
      headers: { ...SAFE_HEADERS, "Content-Type": "application/json; charset=utf-8" },
    });
  }
  if (!release || downloadMatch[1] !== String(release.versionCode)) return json({ error: "not_found" }, 404);
  return download(request, bucket, release);
}

export default {
  async fetch(request, env) {
    let response;
    try {
      response = await handle(request, env);
    } catch {
      // Fail closed; do not leak storage failures or configuration values.
      response = json({ error: "release_unavailable" }, 503, { "Retry-After": "300" });
    }
    if (request.method === "HEAD") return new Response(null, { status: response.status, headers: response.headers });
    return response;
  },
};
