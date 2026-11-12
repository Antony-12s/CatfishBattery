// Run after exporting production HTML with BatteryPageExport. No npm dependencies or live-car access.
// Fixtures below are the application's default settings and unavailable telemetry, not claimed live values.
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const dir=resolve(process.argv[2]), profile=resolve(process.env.TMPDIR,'catfish-layout-chrome-'+process.pid);
// 1.3 sensor anchors (e9d1de9, viewBox 0 0 1180 470) in pack-local SVG units: the wider canvas must not move them.
const PACK_ANCHORS=[[1,428.8,217.5],[2,532.2,275.8],[3,701.6,180.3],[4,598.2,122],[5,370.5,374.5],[6,394.7,388.2],[7,405.7,283.8],[8,393.6,296.9],[9,304.5,337.3],[10,326.5,349.7],[11,284.7,257.7],[12,560.8,218.9]];
const settings={currency:'THB',chargers:[{id:'home',name:'Home',price:0,tou:false,peak:0,offPeak:0}],ac:'home',dc:'home',lossAc:0,lossDc:0,budget:0,alertSoc:0};
const server=createServer(async(req,res)=>{
  const path=new URL(req.url,'http://localhost').pathname;
  if(path.startsWith('/batt-')){
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify(path==='/batt-settings-data'?{saved:false,settings}:path==='/batt-data'?{streamConnected:false,cells:[],cellTemps:[]}:{points:[],sessions:[],live:null,stats:{}}));
  }else{
    try{res.setHeader('Content-Type','text/html');res.end(await readFile(resolve(dir,path.slice(1))));}
    catch{res.writeHead(404).end();}
  }
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const base='http://127.0.0.1:'+server.address().port;
await mkdir(profile,{recursive:true});
const chrome=spawn('google-chrome',['--headless=new','--disable-gpu','--no-first-run','--hide-scrollbars','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:['ignore','ignore','pipe']});
let ws;
try{
 const endpoint=await new Promise((ok,bad)=>{
  let text='';const timer=setTimeout(()=>bad(Error('Chrome startup timed out')),20000);
  chrome.stderr.on('data',b=>{text+=b;const m=text.match(/DevTools listening on (ws:\/\/\S+)/);if(m){clearTimeout(timer);ok(m[1]);}});
  chrome.on('exit',code=>bad(Error('Chrome exited '+code)));
 });
 ws=new WebSocket(endpoint);await new Promise(r=>ws.addEventListener('open',r,{once:true}));
 let id=0;const pending=new Map(),errors=[];
 ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}}
  if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text+': '+(m.params.exceptionDetails.exception?.description||''));});
 const call=(method,params={},sessionId)=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params,...(sessionId?{sessionId}:{})}));});
 const {targetInfos}=await call('Target.getTargets');
 const {sessionId}=await call('Target.attachToTarget',{targetId:targetInfos.find(t=>t.type==='page').targetId,flatten:true});
 const c=(m,p)=>call(m,p,sessionId);
 await c('Runtime.enable');await c('Page.enable');
 const evaluate=async expression=>{const r=await c('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.text);return r.result.value;};
 const sleep=ms=>new Promise(r=>setTimeout(r,ms));
 async function click(selector){
  const pos=await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)}),r=e.getBoundingClientRect();const x=r.x+r.width/2,y=r.y+r.height/2,h=document.elementFromPoint(x,y);return {x,y,ok:e===h||e.contains(h)}})()`);
  assert(pos.ok,'click covered: '+selector);
  await c('Input.dispatchMouseEvent',{type:'mousePressed',x:pos.x,y:pos.y,button:'left',clickCount:1});
  await c('Input.dispatchMouseEvent',{type:'mouseReleased',x:pos.x,y:pos.y,button:'left',clickCount:1});await sleep(150);
 }
 const ledger=[];
 for(const [width,height] of [[2496,1176],[1900,950]]){
  await c('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});
  for(const theme of ['day','night'])for(const page of ['status','charging','history','settings']){
   await c('Page.navigate',{url:base+'/'+page+'-'+theme+'.html'});await sleep(400);
   const states=page==='status'?['schematic','cells']:['default'];
   for(const state of states){
    if(page==='status')await click(state==='cells'?'#packTabCells':'#packTabSchematic');
    const fit=await evaluate(`(()=>{const card=document.querySelector('.page>.card,.packCard'),r=card.getBoundingClientRect();
      const outside=[];for(const e of document.querySelectorAll('button,input:not([type=checkbox]),select,.facts,.legend,.packBox,.histMain,.savebar,.mc,.blk')){
        const b=e.getBoundingClientRect();if(!b.width||!b.height||!e.getClientRects().length)continue;
        if(b.bottom>innerHeight+1||b.right>innerWidth+1||b.top< -1||b.left< -1)outside.push(e.id||e.className);}
      const svg=document.querySelector('#packSvg'),box=svg?.getBoundingClientRect();
      const packRatio=box?Math.abs(box.width/box.height-1390/470):0;
      let pack=null;if(svg&&svg.checkVisibility()){
        // pack-local geometry must stay exactly as in 1.3 (the wider canvas grows outward, the pack is not moved)
        const P=[...svg.querySelectorAll('polygon')],pts=P.map(p=>p.getAttribute('points')).join('|');let hash=0;for(const ch of pts)hash=(hash*31+ch.charCodeAt(0))>>>0;
        const anchors=[...svg.querySelectorAll('circle[data-sensor]')].map(c=>[+c.dataset.sensor,+c.getAttribute('cx'),+c.getAttribute('cy')]).sort((a,b)=>a[0]-b[0]);
        const hull=P.map(p=>p.getBBox()).reduce((u,b)=>[Math.min(u[0],b.x),Math.min(u[1],b.y),Math.max(u[2],b.x+b.width),Math.max(u[3],b.y+b.height)],[1e9,1e9,-1e9,-1e9]);
        const vb=svg.viewBox.baseVal,clash=[];document.getElementById('chgNode').style.display='';
        // panels and their text: inside the canvas, never over the pack hull
        for(const e of svg.querySelectorAll('.sysNode,#sysLayer text,#packSvg>text')){const b=e.getBBox();
          if(b.x<vb.x||b.y<vb.y||b.x+b.width>vb.x+vb.width||b.y+b.height>vb.y+vb.height)clash.push('outside:'+(e.id||e.textContent||e.className.baseVal));
          const onPack=b.x<hull[2]&&b.x+b.width>hull[0]&&b.y<hull[3]&&b.y+b.height>hull[1],packTag=/^(P[1-4]|air|FRONT)/.test(e.textContent);
          if(onPack&&!packTag)clash.push('pack:'+(e.id||e.textContent||'node'));}
        document.getElementById('chgNode').style.display='none';
        pack={hash,polygons:P.length,anchors,viewBox:[vb.x,vb.y,vb.width,vb.height].join(' '),clash};}
      return {height:innerHeight,cardBottom:r.bottom,outside,packRatio,pack,docHeight:document.documentElement.scrollHeight,tiles:document.querySelectorAll('.mc:not(.st)').length};})()`);
    const label=`${width}x${height} ${theme} ${page}/${state}`;
    assert(fit.outside.length===0,label+' outside: '+fit.outside);
    assert(Math.abs(fit.cardBottom-height)<2,label+' card does not fill window: '+fit.cardBottom);
    assert(fit.docHeight<=height+1,label+' outer page scrolls: '+fit.docHeight);
    if(page==='status'&&state==='schematic')assert(fit.packRatio<.002,label+' SVG distorted');
    if(page==='status'&&state==='schematic'){
     assert.equal(fit.pack.viewBox,'-100 0 1390 470',label+' viewBox');
     assert.equal(fit.pack.polygons,360,label+' pack polygons');
     assert.equal(fit.pack.hash,779587572,label+' pack polygon points changed');
     assert.deepEqual(fit.pack.anchors,PACK_ANCHORS,label+' sensor anchors moved');
     assert.deepEqual(fit.pack.clash,[],label+' side panels clash');
    }
    if(page==='status'&&state==='cells')assert.equal(fit.tiles,118,label+' missing cells');
    ledger.push({label,...fit});
    if(width===2496){const shot=await c('Page.captureScreenshot',{format:'png'});await writeFile(resolve(dir,`${page}-${state}-${theme}.png`),Buffer.from(shot.data,'base64'));}
   }
   if(page==='settings'){
    await click('.cTou');
    const overflow=await evaluate(`(()=>{const r=document.querySelector('.chargerRow').getBoundingClientRect();return [...document.querySelectorAll('.chargerRow input,.chargerRow button')].filter(e=>{const b=e.getBoundingClientRect();return b.width&&b.right>r.right+1}).map(e=>e.className)})()`);
    assert.equal(overflow.length,0,'TOU fields overflow '+overflow);
    await click('.addc');
    assert.equal(await evaluate("document.querySelectorAll('.chargerRow').length"),2,'Add charger failed');
    await click('.chargerRow:last-child .defBtn.ac');
    assert.equal(await evaluate("document.querySelector('#setAc').value===document.querySelector('.chargerRow:last-child').dataset.id"),true,'AC default lost');
    await click('.chargerRow:last-child .defBtn.dc');
    assert.equal(await evaluate("document.querySelector('#setDc').value===document.querySelector('.chargerRow:last-child').dataset.id"),true,'DC default lost');
   }
  }
 }
 assert.deepEqual(errors,[],'browser script exceptions');
 await writeFile(resolve(dir,'layout-results.json'),JSON.stringify({ledger,errors},null,2));
 console.log('PASS',ledger.length,'layout states; schematic aspect ratio, unchanged pack geometry/anchors, side panels clear of the pack, 118 cells, day/night, actual mouse navigation, TOU, add charger, AC/DC defaults.');
}finally{if(ws)ws.close();chrome.kill();server.close();}
