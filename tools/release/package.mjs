import { createHash,sign,createPrivateKey } from 'node:crypto';
import { readFileSync,writeFileSync,mkdirSync,existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const root=resolve(import.meta.dirname,'../..');
const [apkPath,metadataPath]=process.argv.slice(2);
if(!apkPath || !metadataPath) throw new Error('Usage: node tools/release/package.mjs <verified APK> <release metadata JSON>');
const meta=JSON.parse(readFileSync(metadataPath,'utf8'));
if(meta.packageName!=='com.cyotun.jpq' || !Number.isSafeInteger(meta.versionCode) || meta.versionCode<=0 || typeof meta.versionName!=='string' || !Number.isSafeInteger(meta.minSdk) || typeof meta.notes!=='string' || meta.notes.length>4000) throw new Error('Invalid release metadata');
const java=resolve(root,'.android-tools/jdk-17.0.20.1+1/bin/java.exe');
const sdk=resolve(root,'.android-tools/sdk/build-tools/35.0.0');
const verified=spawnSync(java,['-jar',resolve(sdk,'lib/apksigner.jar'),'verify','--print-certs',resolve(apkPath)],{encoding:'utf8'});
const own=JSON.parse(readFileSync(resolve(root,'.signing/public-identity.json'),'utf8'));
if(verified.status!==0 || !verified.stdout.includes(`certificate SHA-256 digest: ${own.certificateSha256}`)) throw new Error('APK signing identity mismatch');
const badging=spawnSync(resolve(sdk,'aapt.exe'),['dump','badging',resolve(apkPath)],{encoding:'utf8'});
if(badging.status!==0) throw new Error('Cannot inspect APK');
const p=/package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/.exec(badging.stdout);
const minimum=/sdkVersion:'(\d+)'/.exec(badging.stdout);
if(!p || p[1]!==meta.packageName || Number(p[2])!==meta.versionCode || p[3]!==meta.versionName || !minimum || Number(minimum[1])!==meta.minSdk) throw new Error('APK metadata mismatch');
const apk=readFileSync(apkPath); if(apk.length<1 || apk.length>1024**3) throw new Error('Invalid APK size');
const sha256=createHash('sha256').update(apk).digest('hex');
const key=`releases/${meta.versionCode}/jpq-${sha256}.apk`;
const release={schemaVersion:1,packageName:meta.packageName,versionCode:meta.versionCode,versionName:meta.versionName,minSdk:meta.minSdk,publishedAt:meta.publishedAt ?? new Date().toISOString(),notes:meta.notes,apk:{key,sizeBytes:apk.length,sha256}};
const values=['1',release.packageName,String(release.versionCode),release.versionName,String(release.minSdk),release.publishedAt,release.notes,key,String(apk.length),sha256];
const payload=Buffer.concat([Buffer.from('JPQ-RELEASE-V1\n'),...values.flatMap(s=>{const b=Buffer.from(s);return[Buffer.from(`${b.length}:`),b];})]);
const privateKey=createPrivateKey(readFileSync(resolve(root,'.signing/manifest-private.pem')));
release.signature=sign(null,payload,privateKey).toString('base64');
const dir=resolve(root,'services/updates/release-assets'); mkdirSync(resolve(dir,'parts'),{recursive:true});
const parts=[]; const chunk=20*1024*1024;
for(let offset=0,index=0;offset<apk.length;offset+=chunk,index++) {
  const bytes=apk.subarray(offset,Math.min(apk.length,offset+chunk)); const path=`parts/${sha256}-${index}.bin`;
  const file=resolve(dir,path);
  if(existsSync(file) && !readFileSync(file).equals(bytes)) throw new Error('Immutable release part collision');
  writeFileSync(file,bytes); parts.push({path,sizeBytes:bytes.length});
}
writeFileSync(resolve(dir,'release-index.json'),JSON.stringify({key,sizeBytes:apk.length,sha256,parts}));
// Deploy is a separate action; no network publication occurs in this packaging command.
writeFileSync(resolve(dir,'latest.json'),JSON.stringify(release,null,2));
console.log(JSON.stringify({versionCode:release.versionCode,bytes:apk.length,sha256,parts:parts.length,signed:true,deployed:false}));
