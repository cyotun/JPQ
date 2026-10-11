import { generateKeyPairSync,randomBytes,createHash } from 'node:crypto';
import { existsSync,mkdirSync,writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
const root=resolve(import.meta.dirname,'../..');
const dir=resolve(root,'.signing'); mkdirSync(dir,{recursive:true});
const files=['release.p12','release.properties','manifest-private.pem'];
if(files.some(f=>existsSync(resolve(dir,f)))) {
  if(!files.every(f=>existsSync(resolve(dir,f)))) throw new Error('Partial identity exists; preserve and inspect it before proceeding.');
  console.log('Existing identity preserved.');
} else {
  const password=randomBytes(32).toString('hex');
  const keytool=resolve(root,'.android-tools/jdk-17.0.20.1+1/bin/keytool.exe');
  const cert=spawnSync(keytool,['-genkeypair','-keystore',resolve(dir,'release.p12'),'-storetype','PKCS12','-storepass:env','JPQ_STORE_PASSWORD','-keypass:env','JPQ_STORE_PASSWORD','-alias','jpq','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=JPQ Personal, OU=Personal, O=JPQ, C=CN'],{env:{...process.env,JPQ_STORE_PASSWORD:password}});
  if(cert.status!==0) throw new Error('Installation key generation failed. Preserve partial files.');
  const pair=generateKeyPairSync('ed25519');
  writeFileSync(resolve(dir,'manifest-private.pem'),pair.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
  const publicRaw=pair.publicKey.export({type:'spki',format:'der'}).subarray(-32);
  writeFileSync(resolve(root,'app/src/main/assets/update-key.txt'),publicRaw.toString('base64')+'\n');
  writeFileSync(resolve(dir,'release.properties'),`storeFile=.signing/release.p12\nstorePassword=${password}\nkeyAlias=jpq\nkeyPassword=${password}\n`,{mode:0o600});
  const exported=spawnSync(keytool,['-exportcert','-keystore',resolve(dir,'release.p12'),'-storepass:env','JPQ_STORE_PASSWORD','-alias','jpq'],{env:{...process.env,JPQ_STORE_PASSWORD:password}});
  if(exported.status!==0) throw new Error('Certificate export failed');
  const certificateSha256=createHash('sha256').update(exported.stdout).digest('hex');
  writeFileSync(resolve(dir,'public-identity.json'),JSON.stringify({packageName:'com.cyotun.jpq',certificateSha256,updateKey:publicRaw.toString('base64')},null,2));
  console.log(JSON.stringify({packageName:'com.cyotun.jpq',certificateSha256,privateKeys:'Local ignored .signing directory; keep a separate backup.'}));
}
