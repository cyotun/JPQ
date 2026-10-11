/** Workers Static Assets storage adapter. Parts stay below the free per-file limit. */
export class AssetBucket {
  constructor(assets) { this.assets=assets; }
  async read(path,method='GET',range) {
    const headers={ 'Accept-Encoding':'identity' };
    if(range) headers.Range=`bytes=${range.offset}-${range.offset+range.length-1}`;
    return this.assets.fetch(new Request(`https://jpq-assets.invalid/${path}`,{method,headers}));
  }
  async index(key) {
    const response=await this.read('release-index.json');
    if(response.status===404) return null;
    if(response.status!==200) throw new Error('Asset index unavailable');
    const raw=await response.text(); if(raw.length>16384) throw new Error('Asset index too large');
    const i=JSON.parse(raw);
    if(i.key!==key) return null;
    if(!Number.isSafeInteger(i.sizeBytes) || i.sizeBytes<=0 || !/^[a-f0-9]{64}$/.test(i.sha256) || !Array.isArray(i.parts) || i.parts.length<1 || i.parts.length>64) throw new Error('Invalid asset index');
    let size=0;
    for(const p of i.parts) {
      if(!/^parts\/[a-f0-9]{64}-\d+\.bin$/.test(p.path) || !Number.isSafeInteger(p.sizeBytes) || p.sizeBytes<1 || p.sizeBytes>20*1024*1024) throw new Error('Invalid part');
      size+=p.sizeBytes;
    }
    if(size!==i.sizeBytes) throw new Error('Part length mismatch'); return i;
  }
  async head(key) { const i=await this.index(key); return i ? {size:i.sizeBytes,customMetadata:{sha256:i.sha256}} : null; }
  async get(key,options) {
    if(key==='releases/stable/latest.json') {
      const r=await this.read('latest.json'); if(r.status===404) return null;
      if(r.status!==200) throw new Error('Manifest unavailable');
      const raw=await r.text(); const size=new TextEncoder().encode(raw).length;
      if(size>32768) throw new Error('Manifest too large'); return {size,text:async()=>raw};
    }
    const i=await this.index(key); if(!i) return null;
    const start=options?.range?.offset ?? 0; const length=options?.range?.length ?? i.sizeBytes;
    const requests=[]; let base=0;
    for(const p of i.parts) {
      const from=Math.max(start,base), to=Math.min(start+length,base+p.sizeBytes);
      if(to>from) requests.push({path:p.path,offset:from-base,length:to-from,size:p.sizeBytes});
      base+=p.sizeBytes;
    }
    let position=0,reader=null,canceled=false,remainingBytes=0,sourceRemaining=0,skipBytes=0;
    const body=new ReadableStream({
      pull:async controller=>{
        try {
          while(!canceled) {
            if(!reader) {
              if(position>=requests.length) { controller.close(); return; }
              const p=requests[position++]; const partial=p.offset!==0 || p.length!==p.size;
              const r=await this.read(p.path,'GET',partial?p:undefined);
              if(r.status!==200 && !(partial && r.status===206)) throw new Error('Part unavailable');
              if(r.status===206 && r.headers.get('Content-Range')!==`bytes ${p.offset}-${p.offset+p.length-1}/${p.size}`) throw new Error('Invalid part range');
              const headerLength=r.headers.get('Content-Length');
              sourceRemaining=r.status===206?p.length:p.size;
              skipBytes=r.status===206?0:p.offset;
              if(headerLength!==null && Number(headerLength)!==sourceRemaining) throw new Error('Invalid part length');
              remainingBytes=p.length;
              reader=r.body.getReader();
            }
            const result=await reader.read();
            if(result.done) {
              if(remainingBytes!==0 || sourceRemaining!==0) throw new Error('Truncated part');
              reader.releaseLock(); reader=null; continue;
            }
            sourceRemaining-=result.value.byteLength;
            if(sourceRemaining<0) throw new Error('Oversized part');
            const skip=Math.min(skipBytes,result.value.byteLength); skipBytes-=skip;
            const take=Math.min(remainingBytes,result.value.byteLength-skip); remainingBytes-=take;
            if(take>0) { controller.enqueue(result.value.subarray(skip,skip+take)); return; }
          }
        } catch(error) { controller.error(error); if(reader) await reader.cancel().catch(()=>{}); }
      },
      cancel:async()=>{ canceled=true; if(reader) await reader.cancel(); },
    });
    return {size:i.sizeBytes,customMetadata:{sha256:i.sha256},body};
  }
}
