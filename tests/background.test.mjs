import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
function setup() {
  const events={}, calls=[], listeners=[];
  const event=name=>({addListener(fn){events[name]=fn;}});
  let url='https://site.test/channel';
  const links=[1,2,3].map(n=>({url:`https://site.test/${n}.mp4`,title:`Film ${n}`}));
  const image={url:'https://site.test/large.jpg',thumb:'https://site.test/thumb.jpg',name:'large.jpg'};
  const video={url:'https://site.test/watch',source:'site',videoId:'main',title:'Main video',qualities:[{quality:'height:720',label:'720p'}]};
  const chrome={storage:{local:{get:async()=>({cookies:false})},session:{remove:async()=>{}}},
    runtime:{id:'test',getURL:p=>`chrome-extension://test/${p}`,onMessage:event('message'),connectNative(){return{onMessage:{addListener(fn){listeners.push(fn)}},onDisconnect:event('disconnect'),disconnect(){},postMessage(m){
      calls.push(m);const result=m.command==='inspect'?{videos:[video]}:m.command==='match_links'?{urls:links.map(l=>l.url)}:[];
      listeners.at(-1)({id:m.id,ok:true,result});
    }}}}, tabs:{get:async()=>({url}),onRemoved:event('removed'),onUpdated:event('updated')},
    scripting:{executeScript:async()=>[{result:{top:true,title:'Channel',links,images:[image],streams:[],embeds:[]}}]}};
  vm.runInNewContext(readFileSync(new URL('../background.js',import.meta.url),'utf8'),{chrome,navigator:{brave:{}},URL,Map,Set,crypto,Date,setTimeout:(fn,n)=>setTimeout(fn,n).unref(),clearTimeout});
  const sender={id:'test',url:'chrome-extension://test/popup.html'};
  const send=(message,source=sender)=>new Promise(resolve=>{if(!events.message(message,source,resolve))resolve(undefined)});
  return{send,calls,events,links,setUrl:value=>url=value};
}
test('Only the same extension popup can request downloads',async()=>{
 const s=setup();
 for(const source of [{id:'test',url:'https://site.test'},{id:'other',url:'chrome-extension://test/popup.html'},{id:'test',url:'chrome-extension://test/popup.html.evil'}])assert.equal(await s.send({type:'SCAN',tabId:7},source),undefined);
 assert.equal(s.calls.length,0);
});
test('Channel download queues exactly the ticked videos, never arbitrary URLs',async()=>{
 const s=setup();await s.send({type:'SCAN',tabId:7});await s.send({type:'LINKS',tabId:7});
 const r=await s.send({type:'DOWNLOAD_LINKS',tabId:7,urls:[s.links[0].url,s.links[2].url,'https://unselected.test/evil.mp4'],quality:'720'});
 assert.equal(r.ok,true);const call=s.calls.at(-1);assert.equal(call.command,'start_many');
 assert.deepEqual(Array.from(call.items,i=>i.url),[s.links[0].url,s.links[2].url]);assert.equal(call.quality,'720');assert.equal(call.referer,'https://site.test/channel');
 assert.equal((await s.send({type:'DOWNLOAD_LINKS',tabId:7,urls:['https://unselected.test/a.mp4']})).ok,false);
});
test('Images are restricted to the actual scanned page selection',async()=>{
 const s=setup();await s.send({type:'SCAN',tabId:7});
 assert.equal((await s.send({type:'DOWNLOAD_IMAGES',tabId:7,urls:['https://evil.test/a']})).ok,false);
 assert.equal((await s.send({type:'DOWNLOAD_IMAGES',tabId:7,urls:['https://site.test/large.jpg']})).ok,true);
 assert.equal(s.calls.at(-1).items[0].url,'https://site.test/large.jpg');
});
test('Only an offered quality can download the resolved main video',async()=>{
 const s=setup();await s.send({type:'SCAN',tabId:7});const r=await s.send({type:'RESOLVE',tabId:7});
 assert.equal((await s.send({type:'DOWNLOAD_VIDEO',tabId:7,key:r.result[0].key,quality:'height:360'})).ok,false);
 assert.equal((await s.send({type:'DOWNLOAD_VIDEO',tabId:7,key:r.result[0].key,quality:'height:720'})).ok,true);
 assert.equal(s.calls.at(-1).videoId,'main');
});
test('Navigation invalidates cached video resolution and channel selections',async()=>{
 const s=setup();await s.send({type:'SCAN',tabId:7});await s.send({type:'LINKS',tabId:7});await s.send({type:'RESOLVE',tabId:7});s.setUrl('https://site.test/other');
 for(const type of ['RESOLVE','LINKS','DOWNLOAD_LINKS','DOWNLOAD_IMAGES'])assert.equal((await s.send({type,tabId:7,urls:[s.links[0].url]})).ok,false);
 assert.equal(s.calls.filter(c=>c.command.startsWith('start')).length,0);
});
test('Reopening the popup reuses resolved videos and still restores native jobs',async()=>{
 const s=setup();await s.send({type:'SCAN',tabId:7});await s.send({type:'RESOLVE',tabId:7});await s.send({type:'SCAN',tabId:7});await s.send({type:'RESOLVE',tabId:7});await s.send({type:'JOBS'});
 assert.equal(s.calls.filter(c=>c.command==='inspect').length,1);assert.equal(s.calls.at(-1).command,'jobs');
});
