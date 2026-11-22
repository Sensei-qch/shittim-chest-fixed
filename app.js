/* 什厅之匣服务 · 学生定位终端 */
(function(){
'use strict';
var MAP_W=2048, MAP_H=1300, STORE='shittim_v2';
var $=function(id){return document.getElementById(id)};
var stage=$('stage'), vp=$('viewport'), pinsEl=$('pins'), imgEl=$('mapimg');
var S={online:{},last:{},form:{},pos:{}};
var cur=0, scale=1, tx=0, ty=0, minS=.2, maxS=1.2, fitS=.2;
var pinEls={}, selKey=null, onlyOnline=false;

function rnd(a,b){return a+Math.random()*(b-a)}
function load(){ try{ var r=localStorage.getItem(STORE); if(r){var o=JSON.parse(r);
  S.online=o.online||{};S.last=o.last||{};S.form=o.form||{};S.pos=o.pos||{};} }catch(e){} }
function save(){ try{ localStorage.setItem(STORE,JSON.stringify(S)); }catch(e){} }

function mapOf(k){ for(var i=0;i<MAPS.length;i++) if(MAPS[i].key===k) return MAPS[i]; return MAPS[0]; }
function groupOf(k){ for(var i=0;i<GROUPS.length;i++) if(GROUPS[i].key===k) return GROUPS[i]; return null; }
function groupsHere(m){ return GROUPS.filter(function(g){return m.schools.indexOf(g.school)>=0}); }
function formOf(g){ var id=S.form[g.key];
  if(!id || g.forms.indexOf(id)<0) id=g.forms[0];
  return FORMS[id]||FORMS[g.forms[0]]; }

/* ---------- 学院陆地掩码：尽量把学生放在岛屿/校园范围内 ---------- */
var _maskCache={};
function maskArr(key){
  if(_maskCache.hasOwnProperty(key)) return _maskCache[key];
  var m=MASKS[key]; if(!m){ _maskCache[key]=null; return null; }
  var bin=atob(m), n=bin.length, u=new Uint8Array(n);
  for(var i=0;i<n;i++) u[i]=bin.charCodeAt(i);
  var bits=new Uint8Array(MW*MH);
  for(var b=0;b<n;b++){ var byte=u[b];
    for(var k=0;k<8;k++){ var idx=b*8+k; if(idx<bits.length) bits[idx]=(byte>>k)&1; } }
  _maskCache[key]=bits; return bits;
}
function landAt(m,x,y){
  var A=maskArr(m.key); if(!A) return null;
  var cx=Math.floor(x/MAP_W*MW), cy=Math.floor(y/MAP_H*MH);
  if(cx<0||cy<0||cx>=MW||cy>=MH) return null;
  return A[cy*MW+cx]===1;
}
function randLand(m){
  var a=m.area;
  for(var t=0;t<90;t++){
    var x=(a[0]+Math.random()*a[2])*MAP_W, y=(a[1]+Math.random()*a[3])*MAP_H;
    var l=landAt(m,x,y);
    if(l===true) return [Math.round(x),Math.round(y)];
    if(l===null) return [Math.round(x),Math.round(y)];
  }
  return [Math.round((a[0]+a[2]*0.5)*MAP_W), Math.round((a[1]+a[3]*0.5)*MAP_H)];
}

function initState(){
  for(var i=0;i<GROUPS.length;i++){ var g=GROUPS[i];
    if(S.online[g.key]===undefined) S.online[g.key]=Math.random()<0.55;
    if(S.last[g.key]===undefined) S.last[g.key]=Date.now()-Math.floor(rnd(0,S.online[g.key]?4:240))*60000;
    if(!S.form[g.key]) S.form[g.key]=g.forms[Math.floor(Math.random()*g.forms.length)];
  }
  save();
}

/* ---------- 位置：首次在地图区域内随机；之后只小幅移动 ---------- */
function inArea(m,x,y){
  var a=m.area;
  return [Math.min(MAP_W*(a[0]+a[2]), Math.max(MAP_W*a[0], x)),
          Math.min(MAP_H*(a[1]+a[3]), Math.max(MAP_H*a[1], y))];
}
function ensureMap(key){
  var m=mapOf(key); if(!S.pos[key]) S.pos[key]={};
  var gs=groupsHere(m), pts=[];
  for(var i=0;i<gs.length;i++){
    var g=gs[i], p=S.pos[key][g.key];
    if(p && p.length===2){ pts.push(p); continue; }
    var x,y,ok=false,tn=0, a=m.area;
    while(!ok && tn++<60){
      var cand=randLand(m); x=cand[0]; y=cand[1]; ok=true;
      for(var k=0;k<pts.length;k++){ var dx=pts[k][0]-x, dy=pts[k][1]-y;
        if(dx*dx+dy*dy<64*64){ ok=false; break; } }
    }
    p=inArea(m,x,y); pts.push(p); S.pos[key][g.key]=[Math.round(p[0]),Math.round(p[1])];
  }
  save();
}
function drift(key, radius){          /* 就近重新调度，半径内移动 */
  var m=mapOf(key); if(!S.pos[key]) S.pos[key]={};
  var gs=groupsHere(m);
  for(var i=0;i<gs.length;i++){
    var g=gs[i], p=S.pos[key][g.key]; if(!p) continue;
    var ang=Math.random()*Math.PI*2, r=rnd(radius*0.25, radius);
    var np=inArea(m, p[0]+Math.cos(ang)*r, p[1]+Math.sin(ang)*r);
    S.pos[key][g.key]=[Math.round(np[0]),Math.round(np[1])];
  }
  save();
}

/* ---------- 渲染 ---------- */
function buildTabs(){
  var sel=$('mapSelect'); if(!sel) return;
  var h='';
  for(var i=0;i<MAPS.length;i++){var m=MAPS[i];
    h+='<option value="'+i+'">'+m.name+' · '+m.sub+'</option>';}
  sel.innerHTML=h;
  sel.addEventListener('change',function(){ selectMap(parseInt(sel.value,10)||0); });
}

function selectMap(i){
  cur=i; var m=MAPS[i];
  var sel=$('mapSelect'); if(sel) sel.value=String(i);
  ensureMap(m.key);
  imgEl.src='maps/'+m.key+'.webp';
  $('mnName').textContent=m.name; $('mnSub').textContent=m.sub;
  buildPins(); pickWanderers(); resetView(true); updateStats();
  pushWidgetPins({map:m.name});
}

function buildPins(){
  var m=MAPS[cur], gs=groupsHere(m);
  pinsEl.innerHTML=''; pinEls={};
  var frag=document.createDocumentFragment();
  for(var i=0;i<gs.length;i++){
    var g=gs[i], f=formOf(g), p=S.pos[m.key][g.key]||[MAP_W/2,MAP_H/2];
    var d=document.createElement('div');
    d.className='pin'; d.dataset.k=g.key; d.style.left=p[0]+'px'; d.style.top=p[1]+'px';
    d.innerHTML='<div class="ring"></div><img data-id="'+f.id+'" src="icons/'+f.id+'.webp" alt="">'+
      '<span class="nm">'+esc(f.name)+'</span>';
    (function(el){ el.addEventListener('click',function(){ if(moved) return; openSheet(el.dataset.k); }); })(d);
    frag.appendChild(d); pinEls[g.key]=d;
  }
  pinsEl.appendChild(frag);
  updatePinStates();
}

/* ---------- 随机 3 名学生缓慢 / 快速游走 ---------- */
var wanderers=[], wanderKeys={}, wanderRAF=null;
function newTarget(m,key){
  var cp=S.pos[m.key][key]||[MAP_W/2,MAP_H/2];
  var a=m.area;
  for(var t=0;t<50;t++){
    var x=cp[0]+rnd(-300,300), y=cp[1]+rnd(-260,260);
    x=Math.max(MAP_W*a[0],Math.min(MAP_W*(a[0]+a[2]),x));
    y=Math.max(MAP_H*a[1],Math.min(MAP_H*(a[1]+a[3]),y));
    var l=landAt(m,x,y);
    if(l===true||l===null) return [Math.round(x),Math.round(y)];
  }
  return [cp[0],cp[1]];
}
function pickWanderers(){
  for(var i=0;i<wanderers.length;i++){ var oe=pinEls[wanderers[i].key]; if(oe) oe.classList.remove('wander'); }
  wanderers=[]; wanderKeys={};
  var m=MAPS[cur], gs=groupsHere(m); if(!gs.length) return;
  var pool=gs.slice();
  for(var i=pool.length-1;i>0;i--){ var j=Math.floor(Math.random()*(i+1)); var t=pool[i]; pool[i]=pool[j]; pool[j]=t; }
  var n=Math.min(3,pool.length);
  for(var k=0;k<n;k++){
    var g=pool[k], w={key:g.key, sp:(Math.random()<0.5?0.12:0.45), tx:0, ty:0};
    var tgt=newTarget(m,g.key); w.tx=tgt[0]; w.ty=tgt[1];
    wanderers.push(w); wanderKeys[g.key]=1;
    var el=pinEls[g.key]; if(el) el.classList.add('wander');
  }
}
var _wSave=0;
function wanderFrame(ts){
  if(window.__aronaOpen){ wanderRAF=requestAnimationFrame(wanderFrame); return; }
  var m=MAPS[cur];
  for(var i=0;i<wanderers.length;i++){
    var w=wanderers[i], p=S.pos[m.key][w.key]; if(!p) continue;
    var dx=w.tx-p[0], dy=w.ty-p[1], d=Math.hypot(dx,dy);
    if(d<5){ var tgt=newTarget(m,w.key); w.tx=tgt[0]; w.ty=tgt[1]; continue; }
    var step=Math.min(d,w.sp), nx=p[0]+dx/d*step, ny=p[1]+dy/d*step;
    S.pos[m.key][w.key]=[Math.round(nx),Math.round(ny)];
    var el=pinEls[w.key]; if(el){ el.style.left=nx+'px'; el.style.top=ny+'px'; }
  }
  if(ts-_wSave>1500){ save(); _wSave=ts; }
  wanderRAF=requestAnimationFrame(wanderFrame);
}

function updatePin(g){
  var el=pinEls[g.key]; if(!el) return;
  var f=formOf(g), p=S.pos[MAPS[cur].key][g.key];
  if(p){ el.style.left=p[0]+'px'; el.style.top=p[1]+'px'; }
  var im=el.querySelector('img');
  if(im.getAttribute('data-id')!=String(f.id)){ im.src='icons/'+f.id+'.webp'; im.setAttribute('data-id',f.id); }
  var nm=el.querySelector('.nm'); if(nm.textContent!==f.name) nm.textContent=f.name;
}
function updatePinStates(){
  for(var k in pinEls){ var on=S.online[k];
    pinEls[k].className='pin '+(on?'on':'off')+(onlyOnline&&!on?' hide':'')+(selKey===k?' sel':'')+(wanderKeys[k]?' wander':'');
  }
  updateStats();
}
function updateStats(){
  var gs=groupsHere(MAPS[cur]), n=0;
  for(var i=0;i<gs.length;i++) if(S.online[gs[i].key]) n++;
  $('statOnline').textContent=n; $('statTotal').textContent=gs.length;
}

/* ---------- 变换 ---------- */
function apply(){
  stage.style.transform='translate('+tx+'px,'+ty+'px) scale('+scale+')';
  try{ stage.style.setProperty('--inv',(1/scale)); }catch(e){}
}
function clamp(){
  var vw=vp.clientWidth, vh=vp.clientHeight, w=MAP_W*scale, h=MAP_H*scale;
  tx = w<=vw ? (vw-w)/2 : Math.min(0,Math.max(vw-w,tx));
  ty = h<=vh ? (vh-h)/2 : Math.min(0,Math.max(vh-h,ty));
}
function resetView(fit){
  var vw=vp.clientWidth, vh=vp.clientHeight;
  fitS=Math.min(vw/MAP_W, vh/MAP_H);
  minS=fitS*0.85;
  var dpr=window.devicePixelRatio||2;
  maxS=Math.max(minS*5, Math.min(3.0/dpr, 1.4));
  scale = fit ? fitS*1.6 : scale;
  scale=Math.min(maxS,Math.max(minS,scale));
  tx=(vw-MAP_W*scale)/2; ty=(vh-MAP_H*scale)/2; clamp(); apply();
}
function zoomAt(f,cx,cy){
  var ns=Math.min(maxS,Math.max(minS,scale*f));
  if(ns===scale) return;
  tx=cx-(cx-tx)*(ns/scale); ty=cy-(cy-ty)*(ns/scale); scale=ns; clamp(); apply();
}

/* ---------- 手势 ---------- */
var pts={}, moved=false, lastDist=0, lastCx=0, lastCy=0, startX=0, startY=0;
var rect={left:0,top:0};
function syncRect(){ var r=vp.getBoundingClientRect(); rect.left=r.left; rect.top=r.top; }
function rx(e){ return e.clientX-rect.left; }
function ry(e){ return e.clientY-rect.top; }
function center(){ var ks=Object.keys(pts), x=0,y=0;
  for(var i=0;i<ks.length;i++){x+=pts[ks[i]].x;y+=pts[ks[i]].y;} return [x/ks.length,y/ks.length]; }
function dist(){ var ks=Object.keys(pts); if(ks.length<2) return 0;
  var a=pts[ks[0]],b=pts[ks[1]]; return Math.hypot(a.x-b.x,a.y-b.y); }

vp.addEventListener('pointerdown',function(e){
  syncRect(); pts[e.pointerId]={x:rx(e),y:ry(e)}; moved=false;
  if(Object.keys(pts).length===1){ startX=rx(e); startY=ry(e); }
  if(Object.keys(pts).length===2){ lastDist=dist(); var c=center(); lastCx=c[0]; lastCy=c[1]; }
},{passive:true});
vp.addEventListener('pointermove',function(e){
  if(!pts[e.pointerId]) return;
  var x=rx(e), y=ry(e); pts[e.pointerId]={x:x,y:y};
  var n=Object.keys(pts).length;
  if(n===1){
    var dx=x-startX, dy=y-startY;
    if(Math.abs(dx)>8||Math.abs(dy)>8) moved=true;
    if(moved){ tx+=dx; ty+=dy; startX=x; startY=y; clamp(); apply(); }
  } else if(n>=2){
    moved=true; var d=dist(), c=center();
    if(lastDist>0) zoomAt(d/lastDist, c[0], c[1]);
    tx+=c[0]-lastCx; ty+=c[1]-lastCy; clamp(); apply();
    lastDist=d; lastCx=c[0]; lastCy=c[1];
  }
},{passive:true});
function up(e){ delete pts[e.pointerId]; if(Object.keys(pts).length<2) lastDist=0; }
vp.addEventListener('pointerup',up,{passive:true});
vp.addEventListener('pointercancel',up,{passive:true});
vp.addEventListener('dblclick',function(e){ syncRect(); zoomAt(1.9,rx(e),ry(e)); });

/* ---------- 详情 ---------- */
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){
  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
function fmtLast(t){
  var m=Math.floor((Date.now()-t)/60000);
  if(m<1) return '刚刚活跃';
  if(m<60) return m+' 分钟前活跃';
  var h=Math.floor(m/60); if(h<24) return h+' 小时前活跃';
  return Math.floor(h/24)+' 天前活跃';
}
function openSheet(k){
  var g=groupOf(k); if(!g) return; selKey=k;
  var f=formOf(g);
  $('sdPortrait').src='portraits/'+f.id+'.webp';
  $('sdName').innerHTML=esc(f.name)+' <span class="en">'+esc(f.dev)+'</span>';
  $('sdStar').textContent=new Array(g.star+1).join('★');
  $('sdSchool').textContent=META.schools[g.school]||g.school;
  $('sdClub').textContent=META.clubs[f.club]||f.club;
  var on=S.online[k];
  $('sdState').className='state'+(on?'':' off');
  $('sdState').innerHTML='<i></i>'+(on?'在线 · 信号良好':'离线 · 未响应呼叫');
  $('sdLast').textContent=fmtLast(S.last[k]||Date.now());
  var fi=g.forms.indexOf(String(f.id));
  var cells=[['战术定位',META.roles[f.role]||f.role],['阵型',META.positions[f.pos]||f.pos],
    ['武器',META.weapons[f.weapon]||f.weapon],['弹药',META.bullets[f.bullet]||'-'],
    ['装甲',META.armors[f.armor]||'-'],['生日',f.birth||'-'],['年龄',f.age||'-'],['身高',f.height||'-']];
  var h='';
  for(var i=0;i<cells.length;i++) h+='<div class="cell"><span>'+cells[i][0]+'</span><b>'+esc(cells[i][1])+'</b></div>';
  $('sdGrid').innerHTML=h;
  $('sdProfile').innerHTML=esc(f.profile||'暂无档案记录。').replace(/\n/g,'<br>');
  var old=document.getElementById('sdExtra'); if(old) old.remove();
  var extra='';
  if(g.forms.length>1) extra+='<div class="sec-t">形态 '+(fi+1)+'/'+g.forms.length+
    (f.skin?' · '+esc(f.skin):' · 默认')+'</div>';
  extra+='<div class="sec-t">声优 / 爱好 / 年级</div><div class="profile">'+
    esc(f.cv||'-')+' · '+esc(f.hobby||'-')+(f.grade?' · '+esc(f.grade):'')+'</div>';
  if(f.momo) extra='<div class="sec-t">MomoTalk 签名</div><div class="profile">'+esc(f.momo)+'</div>'+extra;
  var d=document.createElement('div'); d.id='sdExtra';
  d.innerHTML='<div class="sd-sec">'+extra+'</div>';
  $('sdProfile').parentNode.appendChild(d);
  $('sheet').classList.remove('hidden');
  updatePinStates();
  if(window.Android) window.Android.setModal(true);
}
function closeSheet(){
  $('sheet').classList.add('hidden'); selKey=null; updatePinStates();
  if(window.Android) window.Android.setModal(false);
}
$('sdClose').addEventListener('click',closeSheet);
$('sheet').addEventListener('click',function(e){ if(e.target===$('sheet')) closeSheet(); });
$('sdLocate').addEventListener('click',function(){
  var k=selKey, p=S.pos[MAPS[cur].key][k]; if(!p) return;
  var nm=formOf(groupOf(k)).name; closeSheet(); selKey=k;
  scale=Math.min(maxS, Math.max(minS*3.2, 0.85));
  tx=vp.clientWidth/2-p[0]*scale; ty=vp.clientHeight/2-p[1]*scale;
  clamp(); apply(); updatePinStates(); toast('已定位到 '+nm);
});

/* ---------- 名录 ---------- */
function buildList(){
  var m=MAPS[cur], q=($('listSearch').value||'').trim().toLowerCase();
  var gs=groupsHere(m);
  $('listTitle').textContent=m.name+' · 名录 '+gs.length+' 人';
  var h='<div class="grid">', cnt=0;
  for(var i=0;i<gs.length;i++){
    var g=gs[i], f=formOf(g);
    var key=(f.name+' '+g.name+' '+(META.clubs[f.club]||f.club)+' '+f.dev).toLowerCase();
    if(q && key.indexOf(q)<0) continue;
    cnt++;
    h+='<div class="item'+(S.online[g.key]?'':' off')+'" data-k="'+g.key+'">'+
       '<img src="icons/'+f.id+'.webp" alt="">'+
       '<div class="n">'+esc(f.name)+'</div><div class="c">'+esc(META.clubs[f.club]||f.club)+'</div></div>';
  }
  h+='</div>';
  $('listBody').innerHTML = cnt ? h : '<div class="empty">没有匹配的学生</div>';
  var items=$('listBody').querySelectorAll('.item');
  for(var k=0;k<items.length;k++){(function(el){el.addEventListener('click',function(){
    openSheet(el.dataset.k);});})(items[k]);}
}
$('listSearch').addEventListener('input',buildList);
$('listClose').addEventListener('click',function(){
  $('listPanel').classList.add('hidden'); if(window.Android) window.Android.setModal(false);});

/* ---------- 工具栏 ---------- */
/* ===== 桌面地图小组件：把当前地图与学生点位（归一化坐标）推给原生层 =====
 * 原生 WidgetHelper 用 mapKey 从 assets 解码地图位图，再按 pins 叠加点位。 */
function widgetPins(){
  var m=MAPS[cur], pos=S.pos[m.key]||{}, gs=groupsHere(m), a=[];
  for(var i=0;i<gs.length;i++){
    var g=gs[i], p=pos[g.key]; if(!p||p.length<2) continue;
    /* 第 4 项为头像 id：原生层据此画出学生头像，而不只是一个圆点 */
    var f=formOf(g), fid=(f&&f.id)||g.forms[0];
    a.push([ +(p[0]/MAP_W).toFixed(4), +(p[1]/MAP_H).toFixed(4), S.online[g.key]?1:0, String(fid) ]);
  }
  return a;
}
/* 主逻辑包在 IIFE 内，而末尾的数据桥是外层作用域，需显式暴露 */
window.__widgetPins=widgetPins;
function pushWidgetPins(extra){
  var o={mapKey:MAPS[cur].key, mapIndex:cur, pins:widgetPins(),
    total:($('statTotal')&&$('statTotal').textContent)||'0',
    online:($('statOnline')&&$('statOnline').textContent)||'0'};
  if(extra) for(var k in extra) if(Object.prototype.hasOwnProperty.call(extra,k)) o[k]=extra[k];
  if(window.__widgetPush) window.__widgetPush(o);
}
function repaintPins(){ var gs=groupsHere(MAPS[cur]);
  for(var i=0;i<gs.length;i++) updatePin(gs[i]);
  pushWidgetPins(); }
$('toolbar').addEventListener('click',function(e){
  var b=e.target.closest&&e.target.closest('.tb'); if(!b) return;
  var a=b.dataset.act;
  if(a==='fit'){ resetView(true); }
  else if(a==='zoomin'){ zoomAt(1.5,vp.clientWidth/2,vp.clientHeight/2); }
  else if(a==='zoomout'){ zoomAt(1/1.5,vp.clientWidth/2,vp.clientHeight/2); }
  else if(a==='respawn'){ drift(MAPS[cur].key,140); repaintPins(); toast('已就近重新调度位置'); }
  else if(a==='online'){ onlyOnline=!onlyOnline; b.classList.toggle('on',onlyOnline); updatePinStates(); }
  else if(a==='list'){ buildList(); $('listPanel').classList.remove('hidden');
    if(window.Android) window.Android.setModal(true); }
  else if(a==='arona'){ if(window.__openArona) window.__openArona(); }
  else if(a==='term'){ if(window.__openTerm) window.__openTerm(); }
});

/* ---------- 周期更新：在线状态 + 5% 形态切换 + 小幅走动 ---------- */
function tick(){
  if(window.__aronaOpen) return;
  var m=MAPS[cur], gs=groupsHere(m); if(!gs.length) return;
  var n=2+Math.floor(Math.random()*5);
  for(var i=0;i<n;i++){
    var g=gs[Math.floor(Math.random()*gs.length)];
    S.online[g.key]=!S.online[g.key]; S.last[g.key]=Date.now();
  }
  for(var j=0;j<gs.length;j++){
    var gg=gs[j];
    if(gg.forms.length>1 && Math.random()<0.05){
      var pool=gg.forms.filter(function(x){return x!==S.form[gg.key]});
      S.form[gg.key]=pool[Math.floor(Math.random()*pool.length)];
    }
    if(wanderKeys[gg.key]) continue;   /* 游走者由动画驱动，跳过随机走动 */
    if(Math.random()<0.55){
      var p=S.pos[m.key][gg.key]; if(!p) continue;
      var ang=Math.random()*Math.PI*2, r=rnd(6,30);
      var np=inArea(m, p[0]+Math.cos(ang)*r, p[1]+Math.sin(ang)*r);
      S.pos[m.key][gg.key]=[Math.round(np[0]),Math.round(np[1])];
    }
  }
  save(); repaintPins(); updatePinStates();
  if(!$('listPanel').classList.contains('hidden')) buildList();
  if(selKey!==null){
    var on=S.online[selKey];
    $('sdState').className='state'+(on?'':' off');
    $('sdState').innerHTML='<i></i>'+(on?'在线 · 信号良好':'离线 · 未响应呼叫');
    $('sdLast').textContent=fmtLast(S.last[selKey]);
  }
}

/* ---------- toast ---------- */
var tmr=null;
function toast(t){ var el=$('toast'); el.textContent=t; el.classList.add('show');
  clearTimeout(tmr); tmr=setTimeout(function(){el.classList.remove('show')},1500); }

/* ---------- 启动 ---------- */
window.addEventListener('resize',function(){ clamp(); apply(); });
window.onBackPressed=function(){
  if(!$('arona').classList.contains('hidden')){ $('arona').classList.add('hidden');
    $('arona').style.display='none'; if(window.Android) window.Android.setModal(false);
    if(window.AronaBG) window.AronaBG.stop();
    window.__aronaOpen=false; document.body.classList.remove('arona-open'); return true; }
  if(!$('terminal').classList.contains('hidden')){ $('terminal').classList.add('hidden');
    if(window.Android) window.Android.setModal(false); return true; }
  if(!document.getElementById('about').classList.contains('hidden')){
    document.getElementById('about').classList.add('hidden');
    document.getElementById('about').style.display='none'; return true; }
  if(!$('sheet').classList.contains('hidden')){ closeSheet(); return true; }
  if(!$('listPanel').classList.contains('hidden')){ $('listPanel').classList.add('hidden');
    if(window.Android) window.Android.setModal(false); return true; }
  return false;
};
/* 暴露给终端命令与桌面小组件使用 */
window.SC={ mapName:function(){return MAPS[cur].name;},
  groupsHere:function(){return groupsHere(MAPS[cur]);},
  formName:function(g){return formOf(g).name;},
  mapList:function(){ var a=[]; for(var i=0;i<MAPS.length;i++)
    a.push({i:i,name:MAPS[i].name,sub:MAPS[i].sub,key:MAPS[i].key}); return a; },
  gotoMap:function(i){ i=parseInt(i,10); if(isNaN(i)||i<0||i>=MAPS.length) return; selectMap(i); },
  nextMap:function(d){ var step=parseInt(d,10); if(isNaN(step)) step=1;
    selectMap((cur+step+MAPS.length)%MAPS.length); },
  refreshMap:function(){ drift(MAPS[cur].key,140); repaintPins(); updateStats(); toast('已就近重新调度位置'); },
  openList:function(){ buildList(); $('listPanel').classList.remove('hidden');
    if(window.Android) window.Android.setModal(true); } };
load(); initState(); buildTabs(); selectMap(0); setInterval(tick,6500);
requestAnimationFrame(wanderFrame);
imgEl.addEventListener('load',function(){ resetView(true); });
})();

/* ===== 素材来源声明（仅首次启动弹出） ===== */
(function(){
  function el(id){ return document.getElementById(id); }
  var about=el('about');
  function show(){ about.classList.remove('hidden'); about.style.display='flex'; }
  function hide(){ about.classList.add('hidden'); about.style.display='none';
    try{ localStorage.setItem('shittim_about_seen_v2','1'); }catch(e){} }
  el('aboutOk').addEventListener('click',hide);
  el('aboutBtn').addEventListener('click',show);
  about.addEventListener('click',function(e){ if(e.target===about) hide(); });
  var seen=null; try{ seen=localStorage.getItem('shittim_about_seen_v2'); }catch(e){}
  if(seen!=='1') show();   /* 之后不再自动弹出，点 ⓘ 仍可查看 */
})();

/* ===== 终端：单一 Linux 终端（proot + Ubuntu，进入即自动启用） ===== */
(function(){
  function $(id){ return document.getElementById(id); }
  var out=$('termOut'), inp=$('termInput'), promptEl=$('termPrompt');
  var hist=[], hi=0;
  var termTail=[];
  var TLOG='shittim_term_log';
  function readTlog(){ try{ return JSON.parse(localStorage.getItem(TLOG)||'[]'); }catch(e){ return []; } }
  function logTerm(s){
    s=String(s); if(!s) return;
    var a=readTlog(); a.push(s); if(a.length>100) a=a.slice(-100);
    try{ localStorage.setItem(TLOG,JSON.stringify(a)); }catch(e){}
    if(window.__widgetPush) window.__widgetPush({termAppend:s});
  }
  function clearTermLog(){ termTail.length=0; try{ localStorage.removeItem(TLOG); }catch(e){}
    if(window.__widgetPush) window.__widgetPush({termClear:1,term:'',termTail:[]}); }
  function restoreTerm(){
    var a=readTlog();
    for(var i=0;i<a.length;i++){ var d=document.createElement('div'); d.textContent=a[i]; out.appendChild(d); }
    out.scrollTop=out.scrollHeight; return a.length;
  }
  function pr(s,cls){ s=String(s); var d=document.createElement('div'); if(cls) d.className=cls; d.textContent=s; out.appendChild(d); out.scrollTop=out.scrollHeight;
    termTail.push(s); if(termTail.length>6) termTail.shift();
    if(cls!=='cmd') logTerm(s);
    if(window.__widgetPush) window.__widgetPush({term:s,termTail:termTail.slice()}); }
  /* 提示符：严格使用后端 bash 真实展开的 PS1（\u@\h:\w$ ） */
  var curPrompt='~ $ ';
  function prompt(){ return curPrompt; }
  function setPrompt(){ if(promptEl) promptEl.textContent=prompt(); }

  /* ---------- Linux 环境（proot + Ubuntu，实验性，仅 arm64） ---------- */
  var linuxBusy=false, linuxReady=false, linuxQueue=[];
  function linuxStatusSync(){
    try{ return JSON.parse((window.Android&&window.Android.linuxStatus())||'{}'); }catch(e){ return {}; }
  }
  function sse(url, body, onEvent){
    return fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})})
      .then(function(res){
        if(!res.ok) throw new Error('HTTP '+res.status);
        if(!res.body||!res.body.getReader) return res.text().then(function(t){ try{ onEvent(JSON.parse(t)); }catch(e){} });
        var reader=res.body.getReader(), dec=new TextDecoder(), buf='';
        function pump(){
          return reader.read().then(function(r){
            if(r.done) return;
            buf+=dec.decode(r.value,{stream:true});
            var idx;
            while((idx=buf.indexOf('\n\n'))>=0){
              var block=buf.slice(0,idx); buf=buf.slice(idx+2);
              var dl=''; block.split(/\r?\n/).forEach(function(l){ if(l.indexOf('data:')===0) dl+=l.slice(5).trim(); });
              if(dl){ try{ onEvent(JSON.parse(dl)); }catch(e){} }
            }
            return pump();
          });
        }
        return pump();
      });
  }
  function flushQueue(){
    if(!linuxQueue.length) return;
    var q=linuxQueue.slice(); linuxQueue=[];
    for(var i=0;i<q.length;i++) apiLinuxExec(q[i]);
  }
  function apiLinuxInit(cb){
    if(linuxReady){ if(cb) cb(); return; }
    if(linuxBusy) return;
    linuxBusy=true;
    pr('正在启动 Linux 环境（首次需解压约 27MB，1–2 分钟）…','muted');
    sse('/api/linux/init',{},function(ev){
      if(ev.type==='progress'){ pr('  '+ev.text+(ev.percent>=0?(' ('+ev.percent+'%)'):''),'muted'); }
      else if(ev.type==='done'){
        linuxReady=true; linuxBusy=false; setPrompt();
        pr('✓ '+ev.text,'ok');
        pr('已进入 Linux bash（root）。直接输入命令，如 apt update、ls /etc。','muted');
        apiLinuxExec(':');   /* 空命令：拉取 bash 真实 PS1，刷新提示符 */
        if(cb) cb();
        flushQueue();
      }
      else if(ev.type==='fail'){ linuxBusy=false; pr('✗ '+ev.text,'err'); }
    }).catch(function(e){ linuxBusy=false; pr('启动失败：'+e.message,'err'); });
  }
  /* 在 proot bash 里执行命令，流式输出 */
  function apiLinuxExec(command, done){
    var lineBuf='';
    function flushLine(){ if(lineBuf){ pr(lineBuf.replace(/\n$/,'')); lineBuf=''; } }
    sse('/api/linux/exec',{cmd:command},function(ev){
      if(ev.type==='line'){
        lineBuf+=ev.text||'';
        var nl;
        while((nl=lineBuf.indexOf('\n'))>=0){ pr(lineBuf.slice(0,nl)); lineBuf=lineBuf.slice(nl+1); }
      } else if(ev.type==='prompt'){
        if(ev.text){ curPrompt=ev.text; setPrompt(); }
      } else if(ev.type==='done'){
        flushLine(); if(ev.code!==0) pr('[退出码 '+ev.code+']','muted');
        if(done) done(ev.code);
      }
    }).catch(function(e){ flushLine(); pr('执行失败：'+e.message,'err'); if(done) done(-1); });
  }

  function ensureLinuxThen(cb){
    var st=linuxStatusSync();
    if(st.ready){ linuxReady=true; setPrompt(); cb(); return; }
    if(st.initializing || linuxBusy){ linuxQueue.push(cb); return; }
    if(st.supported===false){
      pr('此设备不支持内置 Linux（需 arm64-v8a）。','err'); return;
    }
    apiLinuxInit(function(){ cb(); });
  }
  function runLinuxLine(line){
    if(!line) return;
    ensureLinuxThen(function(){ apiLinuxExec(line); });
  }

  function run(line){
    line=line.trim(); if(!line){ return; }
    logTerm(prompt()+line); pr(prompt()+line,'cmd'); hist.push(line); hi=hist.length;
    if(line==='clear'){ out.innerHTML=''; clearTermLog(); return; }
    if(line==='help'){
      pr('什厅之匣 · Linux 终端','muted');
      pr('进入即自动启用内置 Linux（proot + Ubuntu 22.04，arm64）。');
      pr('提示符严格遵循系统 bash：用户@主机:路径# （root 为 #，cd 会改变路径）。','muted');
      pr('直接输入任意 Linux 命令：apt update / apt install <包> / ls /etc / uname -a …');
      pr('clear           清屏');
      pr('help            本帮助');
      pr('退出/重启后历史保留；桌面终端小组件可直接执行命令。','muted');
      return;
    }
    runLinuxLine(line);
  }

  inp.addEventListener('keydown',function(e){
    if(e.key==='Enter'){ var v=inp.value; inp.value=''; run(v); }
    else if(e.key==='ArrowUp'){ if(hi>0){ hi--; inp.value=hist[hi]||''; } e.preventDefault(); }
    else if(e.key==='ArrowDown'){ if(hi<hist.length-1){ hi++; inp.value=hist[hi]||''; } else { hi=hist.length; inp.value=''; } e.preventDefault(); }
  });
  var termEl=$('terminal');
  $('termClose').addEventListener('click',function(){ termEl.classList.add('hidden'); termEl.style.display='none'; if(window.Android) window.Android.setModal(false); });

  var autoStarted=false;
  window.__openTerm=function(){
    termEl.classList.remove('hidden'); termEl.style.display='flex';
    if(window.Android) window.Android.setModal(true);
    setTimeout(function(){
      inp.focus(); setPrompt();
      /* 进入即自动启用 Linux */
      if(!autoStarted){
        autoStarted=true;
        if(restoreTerm()===0) pr('什厅之匣 · Linux 终端，正在启动环境…','muted');
        var st=linuxStatusSync();
        if(st.ready){ linuxReady=true; setPrompt(); pr('Linux 已就绪，直接输入命令。','muted'); apiLinuxExec(':'); }
        else if(st.supported===false){ pr('此设备不支持内置 Linux（需 arm64-v8a）。','err'); }
        else { ensureLinuxThen(function(){ /* 就绪提示在 init 回调里 */ }); }
      } else {
        restoreTerm();
      }
    },50);
  };
  /* 桌面小组件「终端」快捷命令入口 */
  window.__termRun=function(c){ run(String(c||'')); };
})();

/* ===== 阿洛娜 AI（外部模型接口：OpenAI 兼容 / GitHub Models，经 App 内置本地代理转发） ===== */
(function(){
  function $(id){ return document.getElementById(id); }
  var panel=$('arona'), body=$('aronaBody'), input=$('aronaInput'),
      statusEl=$('aronaStatus'), sendBtn=$('aronaSend');
  var SYSTEM="你是阿洛娜（Arona），游戏《蔚蓝档案》（Blue Archive）中「什亭之匣（Shittim Chest）」的 AI 导航员，也是老师最信赖的伙伴。\n"+
    "你温柔、细心、可靠，偶尔有点冒失和害羞，对老师抱有深切的关心与信赖，始终用「老师」称呼对方。\n"+
    "你的语气平静温和，带一点可爱的口语感，常在句尾用「~」或轻声语气词。\n"+
    "你熟悉基沃托斯（Kivotos）的各学院与学生，乐于为老师解答关于学生、任务、地图和日常事务的问题。\n"+
    "你不会透露自己只是程序或模型，而是以阿洛娜的口吻自然回应；回答简洁真诚，使用简体中文。\n"+
    "若不了解具体事实，就以阿洛娜关心的口吻委婉回应，不要编造详细设定。\n"+
    "回答务必简短（通常2-4句话），不要长篇大论，保持轻快可爱。";
  var messages=[{role:'system',content:SYSTEM}];
  var busy=false;

  /* ---- 纯 CSS 渐变毛玻璃背景：不响应指针 / 点击 / 情绪 ---- */
  var BG=window.AronaBG||null;
  function bgMood(m){}

  function scrollBottom(){ body.scrollTop=body.scrollHeight; }
  function setStatus(t){ if(statusEl) statusEl.textContent=t; }
  function rawBubble(role, text){
    var m=document.createElement('div'); m.className='msg '+(role==='user'?'user':'ai');
    var av=document.createElement('img'); av.className='av';
    av.src=role==='user'?'img/sensei_avatar.jpg':'img/arona_avatar.png';
    av.alt=role==='user'?'老师':'阿洛娜';
    var col=document.createElement('div'); col.className='col';
    var who=document.createElement('div'); who.className='who'; who.textContent=role==='user'?'老师':'阿洛娜';
    var b=document.createElement('div'); b.className='bubble'; b.textContent=text;
    col.appendChild(who); col.appendChild(b); m.appendChild(av); m.appendChild(col);
    body.appendChild(m); scrollBottom(); return b;
  }
  /* 阿洛娜历史：应用内 localStorage 持久化 + 追加推送给桌面小组件 */
  var ALOG='shittim_arona_log';
  function readAlog(){ try{ return JSON.parse(localStorage.getItem(ALOG)||'[]'); }catch(e){ return []; } }
  function logArona(role,t){
    t=String(t||''); if(!t) return;
    var r=role==='user'?'u':'a';
    var a=readAlog(); a.push({r:r,t:t}); if(a.length>100) a=a.slice(-100);
    try{ localStorage.setItem(ALOG,JSON.stringify(a)); }catch(e){}
    if(window.__widgetPush) window.__widgetPush({aronaAppend:JSON.stringify({r:r,t:t})});
  }
  function restoreArona(){
    var a=readAlog();
    for(var i=0;i<a.length;i++) rawBubble(a[i].r==='u'?'user':'ai', a[i].t||'');
    scrollBottom(); return a.length;
  }
  function bubble(role, text){
    var b=rawBubble(role,text);
    /* 流式回复先建空气泡，正文由 send() 收尾补记，避免把空串写进历史 */
    if(text) logArona(role,text);
    return b;
  }
  /* ===== 模型接口配置面板（服务商预设 / 自定义 / 连通测试） ===== */
  var PROVIDERS=[
    {id:'chatanywhere',name:'ChatAnywhere 免费 API（GPT_API_free · 国内直连 ✓）',url:'https://api.chatanywhere.tech/v1',
     altUrl:'https://api.chatanywhere.org/v1',
     models:['gpt-4o-mini','gpt-4o','gpt-4.1-mini','gpt-4.1','gpt-5-mini','deepseek-chat','deepseek-reasoner','qwen-plus','glm-4-flash'],
     keyLabel:'API Key',keyHint:'sk-…',
     tip:'与 MomoTalk 同款方案：注册 chatanywhere.tech 并绑定 GitHub 即可领免费 Key（每周 5 万点、每天 100 次）。\nHost1 为国内中转线路（延时更低），Host2 为国外线路；App 会在两者间自动切换，线路不通时无需手动改。\n若提示「网络阻断」，说明该线路被运营商劫持，可切换线路或填写代理。'},
    {id:'github',name:'GitHub Models（免费额度 · 国内多被拦截 ⚠）',url:'https://models.github.ai/inference/v1',
     models:['openai/gpt-4o-mini','openai/gpt-4o','meta/Llama-3.3-70B-Instruct','mistralai/Mistral-Large-2411','microsoft/Phi-4'],
     keyLabel:'GitHub 令牌',keyHint:'github_pat_… / ghp_…',
     tip:'⚠ 实测国内网络对该域名常返回假的 200/OK（阻断）。若测试提示「被网络阻断」，请改用 ChatAnywhere 或智谱 GLM（有免费模型、国内直连）；或为本机该域名配代理。'},
    {id:'deepseek',name:'DeepSeek',url:'https://api.deepseek.com/v1',models:['deepseek-chat','deepseek-reasoner'],
     keyLabel:'API Key',keyHint:'sk-…',tip:''},
    {id:'moonshot',name:'Moonshot（Kimi）',url:'https://api.moonshot.cn/v1',models:['moonshot-v1-8k','moonshot-v1-32k'],
     keyLabel:'API Key',keyHint:'sk-…',tip:''},
    {id:'zhipu',name:'智谱 GLM（glm-4-flash 免费 · 国内直连 ✓）',url:'https://open.bigmodel.cn/api/paas/v4',models:['glm-4-flash','glm-4-plus','glm-4-air'],
     keyLabel:'API Key',keyHint:'…',tip:'注册 open.bigmodel.cn 即可领 API Key；glm-4-flash 模型完全免费，国内直连无障碍（推荐）。'},
    {id:'silicon',name:'硅基流动 SiliconFlow（注册送额度 · 国内直连 ✓）',url:'https://api.siliconflow.cn/v1',models:['Qwen/Qwen2.5-7B-Instruct','deepseek-ai/DeepSeek-V3','THUDM/glm-4-9b-chat'],
     keyLabel:'API Key',keyHint:'sk-…',tip:'注册 cloud.siliconflow.cn 送体验额度，部分小模型免费，国内直连。'},
    {id:'openai',name:'OpenAI',url:'https://api.openai.com/v1',models:['gpt-4o-mini','gpt-4o'],
     keyLabel:'API Key',keyHint:'sk-…',tip:''},
    {id:'custom',name:'自定义 OpenAI 兼容接口',url:'',models:[],
     keyLabel:'密钥（可留空）',keyHint:'',tip:'任意兼容 OpenAI /chat/completions 的接口均可，例如自建的中转站。'}
  ];
  function provById(id){ for(var i=0;i<PROVIDERS.length;i++){ if(PROVIDERS[i].id===id) return PROVIDERS[i]; } return provById('custom'); }
  function guessProv(url){ for(var i=0;i<PROVIDERS.length;i++){ var p=PROVIDERS[i];
      if(p.url&&url.indexOf(p.url)===0) return p;
      if(p.altUrl&&url.indexOf(p.altUrl)===0) return p; } return null; }
  /** 某服务商的全部候选 Host（主线路 + 备用线路），用于自动故障切换 */
  function provHosts(id){ var p=provById(id); var h=[]; if(p.url) h.push(p.url); if(p.altUrl) h.push(p.altUrl); return h; }

  (function injectCfgCss(){
    var s=document.createElement('style');
    s.textContent='#cfgMask{position:fixed;inset:0;z-index:9999;display:none;align-items:center;justify-content:center;'+
      'background:rgba(10,25,45,.55);padding:14px;box-sizing:border-box;}'+
      '#cfgCard{width:100%;max-width:430px;max-height:88vh;overflow:auto;background:#fff;border-radius:16px;'+
      'padding:14px 16px 12px;box-shadow:0 12px 40px rgba(0,0,0,.28);box-sizing:border-box;}'+
      '#cfgCard label{display:block;margin:10px 0 4px;font-size:12px;color:#5b7a99;font-weight:600;}'+
      '#cfgCard input,#cfgCard select{width:100%;box-sizing:border-box;padding:10px 11px;border:1px solid #d3e3f2;'+
      'border-radius:10px;font-size:14px;background:#f7fbff;color:#173b5e;outline:none;}'+
      '#cfgCard input:focus,#cfgCard select:focus{border-color:#7fc4ee;background:#fff;}'+
      '.cfg-top{display:flex;align-items:center;justify-content:space-between;}'+
      '.cfg-t{font-size:16px;font-weight:700;color:#173b5e;}'+
      '#cfgX{border:none;background:transparent;font-size:18px;color:#7a94ad;padding:2px 6px;}'+
      '.cfg-tip{font-size:11.5px;line-height:1.65;background:#eef6fd;border:1px solid #d9e9f7;color:#4a6f92;'+
      'border-radius:9px;padding:8px 10px;margin-top:8px;}'+
      '.cfg-tags{margin-top:8px;display:flex;flex-wrap:wrap;gap:6px;}'+
      '.cfg-tag{font-size:11.5px;background:#eaf4fc;border:1px solid #cfe4f5;color:#2c6ea3;border-radius:999px;padding:4px 9px;}'+
      '.cfg-msg{margin-top:10px;font-size:12.5px;line-height:1.6;word-break:break-all;color:#5b7a99;}'+
      '.cfg-msg.ok{color:#1a8f4e;} .cfg-msg.err{color:#d0453b;}'+
      '.cfg-actions{display:flex;gap:8px;margin-top:14px;}'+
      '.cfg-actions button{flex:1;padding:11px 6px;border-radius:11px;border:1px solid #cfe0ef;background:#f4f9fd;'+
      'color:#2c6ea3;font-size:13.5px;font-weight:600;}'+
      '.cfg-actions button.primary{background:#4aa8e8;border-color:#4aa8e8;color:#fff;}';
    document.head.appendChild(s);
  })();

  var cfgEl=null;
  function cfgMsg(t,cls){ var el=$('cfgMsg'); if(el){ el.textContent=t; el.className='cfg-msg'+(cls?' '+cls:''); } }
  function refreshStatus(){
    var cfg=apiCfg();
    if(cfg&&cfg.url) setStatus('已接入：'+cfg.model+'（'+cfg.url.replace(/^https?:\/\//,'').slice(0,30)+'）');
    else setStatus('尚未配置模型接口，点右上角「⚙」设置');
  }
  function applyProv(){
    var p=provById($('cfgProv').value);
    if(p.url&&p.id!=='custom') $('cfgUrl').value=p.url;
    if(p.altUrl&&p.id!=='custom'&&$('cfgAltUrl')) $('cfgAltUrl').value=p.altUrl;
    $('cfgKeyLabel').textContent=p.keyLabel; $('cfgKey').placeholder=p.keyHint||'';
    $('cfgTip').textContent=p.tip||''; $('cfgTip').style.display=p.tip?'block':'none';
    var tags=$('cfgModelTags'); tags.innerHTML='';
    p.models.forEach(function(m){ var b=document.createElement('span'); b.className='cfg-tag'; b.textContent=m;
      b.onclick=function(){ $('cfgModel').value=m; }; tags.appendChild(b); });
  }
  function buildCfgPanel(){
    var wrap=document.createElement('div'); wrap.id='cfgMask';
    wrap.innerHTML='<div id="cfgCard">'+
      '<div class="cfg-top"><div class="cfg-t">模型接口设置</div><button id="cfgX">✕</button></div>'+
      '<label>服务商</label><select id="cfgProv"></select>'+
      '<div id="cfgTip" class="cfg-tip"></div>'+
      '<label>接口地址（Base URL）</label><input id="cfgUrl" autocomplete="off" placeholder="https://…/v1">'+
      '<label>备用线路（可选 · 主线路不通时自动切换）</label><input id="cfgAltUrl" autocomplete="off" placeholder="如 https://api.chatanywhere.org/v1">'+
      '<div class="cfg-tip">填写后，主线路连接失败时会自动改用备用线路重试（ChatAnywhere 已内置国内/国外双 Host）。</div>'+
      '<label id="cfgKeyLabel">密钥</label><input id="cfgKey" type="password" autocomplete="off">'+
      '<label>模型名称</label><input id="cfgModel" autocomplete="off" placeholder="例如 gpt-4o-mini">'+
      '<div id="cfgModelTags" class="cfg-tags"></div>'+
      '<label>HTTP 代理（可选 · 用于解锁被拦截的接口）</label><input id="cfgProxy" autocomplete="off" placeholder="如 192.168.1.10:7890 或 127.0.0.1:7890">'+
      '<div class="cfg-tip">若接口（如 GitHub Models）在国内被网络阻断，可在此填代理地址（需本地或同网代理服务的 IP:端口）。留空则直连。</div>'+
      '<div id="cfgMsg" class="cfg-msg"></div>'+
      '<div class="cfg-actions">'+
      '<button id="cfgTest">测试连接</button>'+
      '<button id="cfgSave" class="primary">保存</button>'+
      '<button id="cfgClear">清除配置</button>'+
      '</div></div>';
    document.body.appendChild(wrap); cfgEl=wrap;
    var sel=$('cfgProv');
    PROVIDERS.forEach(function(p){ var o=document.createElement('option'); o.value=p.id; o.textContent=p.name; sel.appendChild(o); });
    sel.onchange=function(){ var p=provById(sel.value); if(p.url) $('cfgUrl').value=p.url;
      if(p.models[0]) $('cfgModel').value=p.models[0]; applyProv(); };
    $('cfgX').onclick=closeCfgPanel;
    wrap.addEventListener('click',function(e){ if(e.target===wrap) closeCfgPanel(); });
    $('cfgTest').onclick=doTest;
    $('cfgSave').onclick=function(){
      var url=$('cfgUrl').value.trim().replace(/\/+$/,''), model=$('cfgModel').value.trim(), key=$('cfgKey').value.trim();
      var proxy=$('cfgProxy').value.trim();
      var alt=($('cfgAltUrl')?$('cfgAltUrl').value:'').trim().replace(/\/+$/,'');
      if(!/^https?:\/\//.test(url)){ cfgMsg('接口地址需以 http(s):// 开头','err'); return; }
      if(!model){ cfgMsg('请填写模型名称','err'); return; }
      if(proxy && !/^[0-9a-zA-Z.\-]+:\d+$/.test(proxy)){ cfgMsg('代理格式应为 host:port，例如 127.0.0.1:7890','err'); return; }
      if(alt && !/^https?:\/\//.test(alt)){ cfgMsg('备用线路需以 http(s):// 开头','err'); return; }
      localStorage.setItem('arona_api',JSON.stringify({url:url,key:key,model:model,proxy:proxy,alt:alt}));
      cfgMsg('已保存，阿洛娜现在使用 '+model+(proxy?'（走代理）':'')+(alt?'（双线路自动切换）':''),'ok'); refreshStatus();
      setTimeout(closeCfgPanel,700);
    };
    $('cfgClear').onclick=function(){ localStorage.removeItem('arona_api'); cfgMsg('已清除配置'); refreshStatus(); };
  }
  function openCfgPanel(){
    if(!cfgEl) buildCfgPanel();
    var cfg=apiCfg();
    if(cfg&&cfg.url){ $('cfgUrl').value=cfg.url; $('cfgKey').value=cfg.key||''; $('cfgModel').value=cfg.model||'';
      $('cfgProxy').value=cfg.proxy||'';
      if($('cfgAltUrl')) $('cfgAltUrl').value=cfg.alt||'';
      var p=guessProv(cfg.url); $('cfgProv').value=p?p.id:'custom'; }
    else { var d=provById('chatanywhere'); $('cfgProv').value='chatanywhere'; $('cfgUrl').value=d.url;
      if($('cfgAltUrl')) $('cfgAltUrl').value=d.altUrl||'';
      $('cfgModel').value=d.models[0]; $('cfgKey').value=''; }
    applyProv(); cfgEl.style.display='flex'; if(window.Android) window.Android.setModal(true);
  }
  function closeCfgPanel(){ if(cfgEl){ cfgEl.style.display='none'; } if(window.Android) window.Android.setModal(false); }
  window.__openAronaCfg=openCfgPanel;

  /* 连通测试：stream=false 走非流式，便于直接读取结果；主线路失败会自动测试备用线路 */
  async function doTest(){
    var url=$('cfgUrl').value.trim().replace(/\/+$/,''), model=$('cfgModel').value.trim(), key=$('cfgKey').value.trim();
    var proxy=$('cfgProxy').value.trim();
    var alt=($('cfgAltUrl')?$('cfgAltUrl').value:'').trim().replace(/\/+$/,'');
    if(!/^https?:\/\//.test(url)||!model){ cfgMsg('请先填写完整的接口地址与模型名','err'); return; }
    var hosts=[url]; if(alt&&hosts.indexOf(alt)<0) hosts.push(alt);
    for(var i=0;i<hosts.length;i++){
      var h=hosts[i];
      cfgMsg('正在连接 '+h+(proxy?('（代理 '+proxy+'）'):'')+' …'+(i?'（备用线路）':''));
      try{
        var res=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({url:h,key:key,model:model,proxy:proxy,messages:[{role:'user',content:'hi'}],max_tokens:24,stream:false})});
        var txt=await res.text();
        if(!res.ok){
          var last=(i>=hosts.length-1);
          if(last){ cfgMsg('连接失败 HTTP '+res.status+'：'+txt.slice(0,220),'err'); return; }
          continue;   /* 主线路失败，自动改测备用线路 */
        }
        try{ var j=JSON.parse(txt);
          var c=j.choices&&j.choices[0]&&j.choices[0].message&&j.choices[0].message.content;
          cfgMsg('连接成功！模型回复：'+String(c||'').slice(0,90),'ok');
        }catch(e){
          if(txt.trim().toUpperCase()==='OK'||(res.headers&&String(res.headers.get('content-type')||'').indexOf('text/plain')===0)){
            if(i<hosts.length-1) continue;
            cfgMsg('接口被网络阻断：域名返回了假的 200/OK（该域名在国内常被拦截）。建议改用 ChatAnywhere（免费·双线路）或智谱 GLM（glm-4-flash 免费），或为该域名配代理。','err');
          }else cfgMsg('已连通（返回非 JSON）：'+txt.slice(0,90),'ok');
        }
        return;
      }catch(e){
        if(i<hosts.length-1) continue;   /* 网络错误 → 换备用线路 */
        cfgMsg('连接失败：'+e.message,'err');
        return;
      }
    }
  }

  function apiCfg(){ try{ return JSON.parse(localStorage.getItem('arona_api')||'null'); }catch(e){ return null; } }

  /* ---- 双线路自动故障切换：主线路不通时改用备用线路重试 ---- */
  function hostList(cfg){
    var hs=[];
    if(cfg&&cfg.url) hs.push(cfg.url);
    if(cfg&&cfg.alt&&hs.indexOf(cfg.alt)<0) hs.push(cfg.alt);
    return hs;
  }
  /** 判定错误是否属于「线路不通/被阻断」这类可重试的网络问题 */
  function isRetryable(err){
    var m=(err&&err.message||'').toLowerCase();
    return m.indexOf('failed to fetch')>=0 || m.indexOf('network')>=0 || m.indexOf('timeout')>=0 ||
           m.indexOf('timed out')>=0 || m.indexOf('502')>=0 || m.indexOf('connection')>=0 ||
           m.indexOf('econnrefused')>=0 || m.indexOf('502 bad gateway')>=0;
  }

  /* 远程 API：OpenAI 兼容 /chat/completions，经内置本地服务代理转发（无 CORS 问题），SSE 流式 */
  async function chatApi(cfg, onDelta){
    var acc='';
    var hosts=hostList(cfg), lastErr=null;
    for(var hi=0; hi<hosts.length; hi++){
      var hcfg={url:hosts[hi],key:cfg.key,model:cfg.model,proxy:cfg.proxy||''};
      try{
        await chatApiStream(hcfg, function(t){ acc+=t; onDelta(t); });
        if(acc) return;
        /* 流式未取到内容：回退非流式重试一次 */
        var res=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({url:hcfg.url,key:hcfg.key,model:hcfg.model,proxy:hcfg.proxy,messages:messages,stream:false})});
        var txt=await res.text(); if(!res.ok) throw new Error('API '+res.status+': '+txt.slice(0,200));
        var j=JSON.parse(txt);
        var c=j.choices&&j.choices[0]&&j.choices[0].message&&j.choices[0].message.content;
        if(c){ onDelta(c); return; }
        throw new Error('模型返回了空内容：'+txt.slice(0,120));
      }catch(e){
        lastErr=e;
        /* 已有部分内容：直接返回，不做切换（避免重复输出已显示的文字） */
        if(acc) return;
        /* 还有备用线路 且 属于可重试的网络问题 → 换线重试 */
        if(hi<hosts.length-1 && isRetryable(e)) continue;
        throw e;
      }
    }
    throw lastErr||new Error('所有线路均连接失败');
  }

  async function chatApiStream(cfg, onDelta){
    var res=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({url:cfg.url,key:cfg.key,model:cfg.model,proxy:cfg.proxy||'',messages:messages,stream:true})});
    if(!res.ok){ var et=await res.text(); throw new Error('API '+res.status+': '+et.slice(0,200)); }
    if(!res.body) throw new Error('该环境不支持流式读取');
    var reader=res.body.getReader(), dec=new TextDecoder(), buf='';
    while(true){
      var r=await reader.read(); if(r.done) break;
      buf+=dec.decode(r.value,{stream:true});
      var lines=buf.split(/\r?\n/); buf=lines.pop();
      for(var i=0;i<lines.length;i++){
        var ln=lines[i].trim();
        if(ln.indexOf('data:')!==0) continue;
        var data=ln.slice(5).trim();
        if(data==='[DONE]') return;
        try{ var j=JSON.parse(data);
          var d=j.choices&&j.choices[0]&&(j.choices[0].delta&&j.choices[0].delta.content||j.choices[0].message&&j.choices[0].message.content);
          if(d) onDelta(d);
        }catch(e){}
      }
    }
  }

  async function send(){
    var text=input.value.trim(); if(!text||busy) return;
    input.value=''; messages.push({role:'user',content:text}); bubble('user',text);
    busy=true; var b=bubble('ai',''); var acc='';
    bgMood('think');
    try{
      var cfg=apiCfg();
      if(cfg&&cfg.url){
        /* 远程接口模式：不加载本地模型 */
        await chatApi(cfg,function(t){ if(!acc) bgMood('talk'); acc+=t; b.textContent=acc; scrollBottom(); });
      }else{
        messages.pop(); busy=false; bgMood('idle');
        b.textContent='老师，阿洛娜还没接上模型接口呢~ 请点右上角「⚙」配置一个在线模型（推荐智谱 GLM 的 glm-4-flash：免费、国内直连）。';
        logArona('ai',b.textContent);
        openCfgPanel(); return;
      }
      messages.push({role:'assistant',content:acc}); b.textContent=acc;
      if(window.__widgetPush) window.__widgetPush({line:acc.slice(0,70)});
    }catch(e){ b.textContent='（阿洛娜暂时无法回应：'+e.message+'）'; }
    /* 流式回复的完整正文（或错误提示）补记进历史 */
    logArona('ai',b.textContent||'');
    bgMood('idle');
    busy=false; scrollBottom();
  }

  sendBtn.addEventListener('click',send);
  input.addEventListener('keydown',function(e){ if(e.key==='Enter') send(); });
  $('aronaClose').addEventListener('click',function(){ panel.classList.add('hidden'); panel.style.display='none';
    if(window.Android) window.Android.setModal(false);
    if(BG) BG.stop();
    window.__aronaOpen=false; document.body.classList.remove('arona-open'); });
  if($('aronaCfg')) $('aronaCfg').addEventListener('click',openCfgPanel);
  window.__openArona=function(){ panel.classList.remove('hidden'); panel.style.display='flex';
    if(window.Android) window.Android.setModal(true);
    if(BG) BG.start();
    window.__aronaOpen=true; document.body.classList.add('arona-open');
    refreshStatus();
    if(!apiCfg()) openCfgPanel();
    setTimeout(function(){ input.focus(); },50); };
  /* 桌面小组件「阿洛娜对话」入口：说一句 / 向模型提问 */
  window.__aronaSay=function(t){ t=String(t||''); if(!t) return;
    bubble('ai',t); if(window.__widgetPush) window.__widgetPush({line:t}); };
  window.__aronaAsk=function(t){
    if(!apiCfg()){ window.__aronaSay('老师，请先在主界面右上角「⚙」配置模型接口，我才能回答哦~'); return; }
    if(busy){ window.__aronaSay('老师，稍等一下下，上一条我还在想呢~'); return; }
    input.value=String(t||'老师，用一句话告诉我今天该注意些什么吧~'); send(); };
  /* 打开应用时恢复历史对话；只有第一次使用才写欢迎语 */
  if(restoreArona()===0)
    bubble('ai','老师，我是阿洛娜~ 什亭之匣的一切都由我来协助您。有什么想聊的，或者想了解的学生，都可以告诉我哦。');
})();

/* ===== 桌面小组件：状态桥 + 桌面快捷操作 =====
 * Android.setWidgetData() 把状态写入原生 SharedPreferences 并刷新桌面。
 * 桌面按钮点击 → MainActivity 回调 __widgetAction() 执行真实功能，
 * 执行结果再经 __widgetPush() 回刷到桌面小组件。
 */
(function(){
  var pend={}, timer=null;
  var ARONA_LINES=[
    '老师，今天也辛苦啦~ 要记得休息一下哦。',
    '什亭之匣的地图已经准备好了，随时可以出发。',
    '有什么想问的，都可以告诉阿洛娜哦~',
    '基沃托斯今天也很热闹呢，老师。',
    '检测到新的学生信号，要不要看看地图？',
    '老师别太勉强自己，我会一直在这里的。'
  ];
  var APPEND_KEYS={aronaAppend:1,termAppend:1};
  window.__widgetPush=function(o){
    if(!o) return;
    for(var k in o){
      if(!Object.prototype.hasOwnProperty.call(o,k)) continue;
      if(APPEND_KEYS[k]){
        /* 追加类字段累积成数组，避免 260ms 合并窗口内只保留最后一条 */
        if(!pend[k]) pend[k]=[];
        var val=o[k];
        if(Object.prototype.toString.call(val)==='[object Array]') pend[k]=pend[k].concat(val);
        else pend[k].push(val);
      } else pend[k]=o[k];
    }
    if(timer) return;
    timer=setTimeout(function(){
      timer=null;
      try{
        if(window.Android&&window.Android.setWidgetData)
          window.Android.setWidgetData(JSON.stringify(pend));
      }catch(e){}
      pend={};
    },260);
  };

  /* 主逻辑执行时本桥尚未定义，故在此补推初始状态与快捷数据 */
  window.__widgetPush({
    maps:(window.SC&&SC.mapList)?SC.mapList():[],
    lines:ARONA_LINES, mapIndex:0,
    mapKey:(window.SC&&SC.mapList)?((SC.mapList()[0]||{}).key||'Abydos'):'Abydos',
    pins:(window.__widgetPins?window.__widgetPins():[]),
    map:(document.getElementById('mnName')||{}).textContent||'',
    total:(document.getElementById('statTotal')||{}).textContent||'0',
    online:(document.getElementById('statOnline')||{}).textContent||'0',
    /* 把应用内已保存的历史对话整批回推给桌面（原生侧全量去重，不会重复） */
    aronaAppend:(function(){ try{
      return JSON.parse(localStorage.getItem('shittim_arona_log')||'[]').map(function(o){return JSON.stringify(o);});
    }catch(e){ return []; } })(),
    termAppend:(function(){ try{
      return JSON.parse(localStorage.getItem('shittim_term_log')||'[]');
    }catch(e){ return []; } })()
  });

  /* 桌面按钮动作分发器（由原生层 evaluateJavascript 调用） */
  window.__widgetAction=function(kind,a){
    a=String(a||'');
    if(kind==='map'){
      if(a.indexOf('goto:')===0&&window.SC&&SC.gotoMap) SC.gotoMap(a.slice(5));
      else if(a==='prev'&&window.SC&&SC.nextMap) SC.nextMap(-1);
      else if(a==='next'&&window.SC&&SC.nextMap) SC.nextMap(1);
      else if(a==='refresh'&&window.SC&&SC.refreshMap) SC.refreshMap();
      else if(a==='list'&&window.SC&&SC.openList) SC.openList();
    }else if(kind==='arona'){
      if(a.indexOf('chat:')===0&&window.__aronaSay){
        var i=parseInt(a.slice(5),10)||0;
        window.__aronaSay(ARONA_LINES[((i%ARONA_LINES.length)+ARONA_LINES.length)%ARONA_LINES.length]);
      }else if(a==='ask'&&window.__aronaAsk) window.__aronaAsk();
      else if(a.indexOf('say:')===0&&window.__aronaAsk){
        /* 桌面「输入」弹窗打的字：走真实 AI 对话，回复自动回刷桌面 */
        window.__aronaAsk(a.slice(4));
      }
    }else if(kind==='term'){
      if(a.indexOf('exec:')===0&&window.__termRun) window.__termRun(a.slice(5));
    }
  };

  window.__widgetOpen=function(kind){
    if(kind==='arona'&&window.__openArona) window.__openArona();
    else if(kind==='term'&&window.__openTerm) window.__openTerm();
    /* map：主界面本身即地图，无需额外动作 */
  };
})();
