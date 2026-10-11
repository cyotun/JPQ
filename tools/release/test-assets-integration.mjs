import { mkdirSync,writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'), dir=resolve(root,'.wrangler/asset-integration');
const assets=resolve(dir,'assets'); mkdirSync(resolve(assets,'parts'),{recursive:true});
const bytes=Buffer.from('JPQ-INTEGRATION-FIXTURE'); const sha=createHash('sha256').update(bytes).digest('hex');
const key=`releases/999/jpq-${sha}.apk`;
const parts=[{path:`parts/${sha}-0.bin`,sizeBytes:10},{path:`parts/${sha}-1.bin`,sizeBytes:bytes.length-10}];
writeFileSync(resolve(assets,parts[0].path),bytes.subarray(0,10)); writeFileSync(resolve(assets,parts[1].path),bytes.subarray(10));
writeFileSync(resolve(assets,'release-index.json'),JSON.stringify({key,sizeBytes:bytes.length,sha256:sha,parts}));
writeFileSync(resolve(assets,'latest.json'),JSON.stringify({schemaVersion:1,packageName:'com.cyotun.jpq',versionCode:999,versionName:'local-test',minSdk:28,publishedAt:'2026-10-11T00:00:00Z',notes:'Local synthetic transport fixture; never deploy.',apk:{key,sizeBytes:bytes.length,sha256:sha},signature:Buffer.alloc(64).toString('base64')}));
const config=resolve(dir,'wrangler.jsonc');
writeFileSync(config,JSON.stringify({name:'jpq-local-integration',main:resolve(root,'services/updates/src/worker.mjs'),compatibility_date:'2026-10-11',workers_dev:false,assets:{directory:assets,binding:'ASSETS',run_worker_first:true}}));
const child=spawn(process.execPath,[resolve(root,'node_modules/wrangler/bin/wrangler.js'),'dev','--config',config,'--port','8787','--local'],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
let logs=''; child.stdout.on('data',b=>{logs+=b.toString();}); child.stderr.on('data',b=>{logs+=b.toString();});
try {
  let ready=false;
  for(let i=0;i<60;i++) { await new Promise(r=>setTimeout(r,500)); try { const r=await fetch('http://127.0.0.1:8787/health'); if(r.status===200) {ready=true;break;} } catch {} }
  if(!ready) throw new Error('Local Worker did not start: '+logs.slice(-1600));
  const url='http://127.0.0.1:8787/v1/releases/999/download';
  const whole=await fetch(url); assert.equal(whole.status,200); assert.equal(whole.headers.get('etag'),`"${sha}"`);
  assert.deepEqual(Buffer.from(await whole.arrayBuffer()),bytes);
  const range=await fetch(url,{headers:{Range:'bytes=8-14'}}); assert.equal(range.status,206);
  assert.deepEqual(Buffer.from(await range.arrayBuffer()),bytes.subarray(8,15));
  const suffix=await fetch(url,{headers:{Range:'bytes=-4'}}); assert.equal(suffix.status,206); assert.deepEqual(Buffer.from(await suffix.arrayBuffer()),bytes.subarray(-4));
  const head=await fetch(url,{method:'HEAD'}); assert.equal(head.headers.get('content-length'),String(bytes.length));
  assert.equal((await fetch('http://127.0.0.1:8787/latest.json')).status,404);
  console.log('Actual local Workers Static Assets integration: full / cross-part range / suffix / HEAD / private-path rejection passed.');
} catch(error) { console.error(logs.slice(-4000)); throw error; }
finally { child.kill(); }
