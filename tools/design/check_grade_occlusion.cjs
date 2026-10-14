const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const file=path.resolve('docs/design/grade-occlusion/index.html');
const html=fs.readFileSync(file,'utf8');
const markup=html.replace(/<style>[\s\S]*?<\/style>/g,'').replace(/<script>[\s\S]*?<\/script>/g,'');
const ids=[...markup.matchAll(/\sid="([^"]+)"/g)].map(m=>m[1]);
assert.equal(new Set(ids).size,ids.length,'Duplicate HTML ids');
const stable=[...markup.matchAll(/data-htmlprd-id="([^"]+)"/g)].map(m=>m[1]);
assert.equal(new Set(stable).size,stable.length,'Duplicate stable semantic ids');
const elements=Object.fromEntries(ids.map(id=>[id,{style:{},value:'',checked:true,hidden:false,disabled:false,textContent:'',innerHTML:'',listeners:{},
 addEventListener(type,handler){this.listeners[type]=handler;},getBoundingClientRect(){return {width:1000,height:460};},setPointerCapture(){}}]));
const grades={style:{}};
elements.scenario.value='blocked';
const document={getElementById:id=>elements[id],querySelector:selector=>{assert.equal(selector,'.grades');return grades;}};
const context={document,window:{addEventListener(){}}};
vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],context);
const originalGrid=elements.grid.innerHTML,originalHistory=elements.history.innerHTML;
function click(id){elements[id].listeners.click();sameCounts();}
function scenario(value){elements.scenario.value=value;elements.scenario.listeners.change();sameCounts();}
function sameCounts(){assert.equal(elements.grid.innerHTML,originalGrid);assert.equal(elements.history.innerHTML,originalHistory);}
function toggle(on){elements.tips.checked=on;elements.tips.listeners.change();sameCounts();}
assert.equal(elements.tip.hidden,false);assert.equal(elements.move.disabled,false);assert.equal(elements.counter.style.left,'64px');
click('move');assert.equal(elements.counter.style.left,'148px');assert.equal(elements.tip.hidden,true);
scenario('blocked');assert.equal(elements.tip.hidden,false);
click('ignore');assert.equal(elements.counter.style.left,'64px');assert.equal(elements.tip.hidden,true);
click('next-round');assert.equal(elements.tip.hidden,true);
click('region-change');assert.equal(grades.style.left,'20px');assert.equal(elements.tip.hidden,true);
scenario('blocked');assert.equal(elements.tip.hidden,true,'Changing region/state cannot clear session ignore');
toggle(false);toggle(true);assert.equal(elements.tip.hidden,true,'Global toggle cannot clear session ignore');
click('session');assert.equal(elements.counter.hidden,true);assert.equal(elements.session.textContent,'开始记牌');
click('session');assert.equal(elements.counter.hidden,false);assert.equal(elements.tip.hidden,false);assert.equal(elements.counter.style.left,'64px');
toggle(false);click('session');click('session');assert.equal(elements.tips.checked,false);assert.equal(elements.tip.hidden,true);assert.ok(elements.reading.textContent.includes('读取继续'));
click('reset');assert.equal(elements.tip.hidden,false);assert.equal(elements.tips.checked,true);assert.equal(grades.style.left,'8px');
scenario('tight');assert.equal(elements.move.disabled,true);assert.equal(elements.move.textContent,'请手动拖动');click('move');assert.equal(elements.counter.style.left,'64px');
for(let i=0;i<11;i++)elements.counter.listeners.keydown({key:'ArrowRight',preventDefault(){}});
sameCounts();assert.equal(elements.counter.style.left,'152px');assert.equal(elements.tip.hidden,true);
click('reset');elements.counter.listeners.pointerdown({target:{closest(){return null;}},clientX:64,clientY:6,pointerId:1});
elements.counter.listeners.pointermove({clientX:148,clientY:6});elements.counter.listeners.pointerup();
sameCounts();assert.equal(elements.counter.style.left,'148px');assert.equal(elements.tip.hidden,true);
for(const value of ['blocked','moved','ignored','tight']){click('reset');scenario(value);sameCounts();}
const font=html.match(/src:url\('([^']+)'\)/)[1];assert.ok(fs.existsSync(path.resolve(path.dirname(file),font)));
assert.ok(!html.includes('暂按天然同花顺判断'),'Unapproved secondary hint leaked into design');
console.log('Grade-occlusion structure and script interactions passed: move, ignore across rounds/region changes, stop/start reset, independent global toggle, insufficient-space guard, keyboard/pointer drag; numbers unchanged. Not browser or phone validation.');
