import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AssetBucket } from '../src/asset-bucket.mjs';
test('static asset adapter streams across part boundary and range without public keys',async()=>{
  const sha='a'.repeat(64), key=`releases/2/jpq-${sha}.apk`;
  const index={key,sizeBytes:8,sha256:sha,parts:[{path:`parts/${sha}-0.bin`,sizeBytes:4},{path:`parts/${sha}-1.bin`,sizeBytes:4}]};
  const assets={fetch:async request=>{
    const path=new URL(request.url).pathname;
    if(path==='/release-index.json') return Response.json(index);
    let bytes=path.endsWith('-0.bin')?new Uint8Array([0,1,2,3]):new Uint8Array([4,5,6,7]);
    const range=request.headers.get('Range');
    if(range) { const [,a,b]=/^bytes=(\d+)-(\d+)$/.exec(range); const from=Number(a),to=Number(b); bytes=bytes.slice(from,to+1); return new Response(bytes,{status:206,headers:{'Content-Length':String(bytes.length),'Content-Range':`bytes ${from}-${to}/4`}}); }
    return new Response(bytes,{headers:{'Content-Length':'4'}});
  }};
  const bucket=new AssetBucket(assets); const object=await bucket.get(key,{range:{offset:2,length:4}});
  assert.equal(object.size,8); assert.deepEqual([...new Uint8Array(await new Response(object.body).arrayBuffer())],[2,3,4,5]);
  const full=await bucket.get(key); assert.deepEqual([...new Uint8Array(await new Response(full.body).arrayBuffer())],[0,1,2,3,4,5,6,7]);
});
