/* ===== 阿洛娜背景 · 可插拔驱动引擎（当前为纯 CSS 半透明毛玻璃） =====
 * 默认驱动 static：不加载图片、不绘制 canvas、不启动 requestAnimationFrame，
 * 背景完全由 app.css 的 #aronaWall（半透明无色毛玻璃）呈现。
 * use('off') 给面板加 .wall-off 即可隐藏渐变层。
 * 其余驱动仅作为预留：spine / live2d（canvas 名兼容映射到 static）。
 * 对外接口 window.AronaBG：
 *   use(name) / start() / stop() / pointer(nx,ny) / tap(nx,ny)
 *   mood(m) / configure(obj) / status()
 * 全部资源走本地相对路径，无 CDN 依赖。
 */
(function(){
'use strict';
function noop(){}
function stub(){ return {use:noop,start:noop,stop:noop,pointer:noop,tap:noop,mood:noop,
  configure:noop,status:function(){return {name:'off',want:'off',note:'背景层未初始化',running:false};}}; }

var canvas=document.getElementById('aronaBg');
if(!canvas){ window.AronaBG=stub(); return; }

var host=(function(){
  var d=document.getElementById('aronaBgHost');
  if(!d){ d=document.createElement('div'); d.id='aronaBgHost';
    canvas.parentNode.insertBefore(d,canvas.nextSibling); }
  return d;
})();

/* ---- 素材路径约定（可用 configure() 覆盖，覆盖值存 localStorage）----
 * Spine : bg/spine/arona.json(或 .skel) + arona.atlas + 图集 png
 * Live2D: bg/live2d/arona.model3.json + .moc3 + textures/ + motions/ 等
 * 运行时统一放 bg/lib/，按所用 Spine / Cubism 版本选对应构建。
 */
var RES={
  spine:{ lib:'bg/lib/spine-player.min.js', skel:'bg/spine/arona.json',
          atlas:'bg/spine/arona.atlas', anim:'idle' },
  live2d:{ libs:['bg/lib/live2dcubismcore.min.js','bg/lib/live2d.min.js'],
           model:'bg/live2d/arona.model3.json' }
};
try{
  var ov=JSON.parse(localStorage.getItem('arona_bg_res')||'null');
  if(ov){
    for(var a in (ov.spine||{})){ if(Object.prototype.hasOwnProperty.call(ov.spine,a)) RES.spine[a]=ov.spine[a]; }
    for(var b in (ov.live2d||{})){ if(Object.prototype.hasOwnProperty.call(ov.live2d,b)) RES.live2d[b]=ov.live2d[b]; }
  }
}catch(e){}

var st={name:'off',want:'off',driver:null,running:false,raf:0,last:0,t:0,
        px:.5,py:.5,tx:.5,ty:.5,mood:'idle',note:''};

function clamp(v,a,b){ return v<a?a:(v>b?b:v); }
function loadScript(src){
  return new Promise(function(res,rej){
    var s=document.createElement('script'); s.src=src; s.async=false;
    s.onload=function(){res(true);}; s.onerror=function(){rej(new Error('缺少 '+src));};
    document.head.appendChild(s);
  });
}
/* 用 XHR 探测资源是否存在：http://127.0.0.1 与 file:// 两条加载路径都可用 */
function probe(url){
  return new Promise(function(res,rej){
    var x=new XMLHttpRequest();
    x.open('GET',url,true);
    x.onreadystatechange=function(){
      if(x.readyState!==4) return;
      var ok=(x.status>=200&&x.status<300)||(x.status===0&&(x.responseText||x.response));
      if(ok) res(true); else rej(new Error('缺少 '+url));
    };
    x.onerror=function(){rej(new Error('缺少 '+url));};
    try{ x.send(); }catch(e){ rej(new Error('无法读取 '+url+'（'+e.message+'）')); }
  });
}
function measure(){
  var dpr=Math.min(window.devicePixelRatio||1,1.75);
  var r=canvas.getBoundingClientRect();
  var w=Math.max(1,Math.round(r.width)), h=Math.max(1,Math.round(r.height));
  canvas.width=Math.round(w*dpr); canvas.height=Math.round(h*dpr);
  return {w:w,h:h,dpr:dpr};
}

/* ---------------- 驱动 1：static（背景由纯 CSS 半透明毛玻璃层承担） ----------------
 * 不加载任何图片、不绘制任何像素、不启动逐帧循环。
 * 仅保留空的 resize / destroy 供引擎生命周期调用；
 * 渐变层的显示与否由 #aronaWall + .wall-off 控制（见 app.css）。
 */
function staticDriver(){
  function resize(){}
  return { resize:resize, destroy:noop };
}

/* ---------------- 驱动 2：Spine（spine-player 接入骨架） ---------------- */
function spineDriver(){
  var player=null, wrap=null;
  function init(){
    return loadScript(RES.spine.lib)
      .then(function(){ return probe(RES.spine.skel); })
      .then(function(){ return probe(RES.spine.atlas); })
      .then(function(){
        if(!window.spine||!window.spine.SpinePlayer)
          throw new Error('运行时版本不匹配（需 spine-player，且须与导出版本一致）');
        canvas.style.display='none';
        wrap=document.createElement('div'); wrap.className='abg-spine';
        host.appendChild(wrap);
        player=new window.spine.SpinePlayer(wrap,{
          jsonUrl:RES.spine.skel, atlasUrl:RES.spine.atlas,
          animation:RES.spine.anim||'idle', showControls:false,
          alpha:true, backgroundColor:'#00000000'
        });
        return true;
      });
  }
  function play(name){
    if(!player) return;
    try{
      if(player.setAnimation) player.setAnimation(name,false);
      else if(player.state&&player.state.setAnimation) player.state.setAnimation(0,name,false);
      else if(player.play) player.play(name);
    }catch(e){}
  }
  return { init:init, resize:noop, frame:noop,
    pointer:function(x,y){
      if(!wrap) return;
      wrap.style.transform='translate3d('+((x-.5)*22).toFixed(1)+'px,'+((y-.5)*12).toFixed(1)+'px,0)';
    },
    tap:function(){ play('tap'); },
    mood:function(m){ play(m==='talk'?'talk':m==='think'?'think':'idle'); },
    destroy:function(){
      try{ if(player&&player.dispose) player.dispose(); }catch(e){}
      player=null; wrap=null;
    } };
}

/* ---------------- 驱动 3：Live2D（Cubism / pixi-live2d-display 接入骨架） ---------------- */
function live2dDriver(){
  var app=null, model=null;
  function init(){
    var seq=Promise.resolve();
    for(var i=0;i<RES.live2d.libs.length;i++){
      (function(u){ seq=seq.then(function(){ return loadScript(u); }); })(RES.live2d.libs[i]);
    }
    return seq.then(function(){ return probe(RES.live2d.model); })
      .then(function(){
        var PIXI=window.PIXI;
        if(!PIXI||!PIXI.live2d||!PIXI.live2d.Live2DFactory)
          throw new Error('运行时版本不匹配（需 live2dcubismcore + pixi-live2d-display，Cubism 版本须与模型一致）');
        var r=canvas.getBoundingClientRect();
        app=new PIXI.Application({ view:canvas, width:Math.max(1,r.width), height:Math.max(1,r.height),
          backgroundAlpha:0, autoStart:true, resizeTo:host, antialias:true });
        return PIXI.live2d.Live2DFactory.from(RES.live2d.model);
      })
      .then(function(m){
        model=m; app.stage.addChild(m);
        try{ m.scale.set(Math.min(app.screen.width/900,app.screen.height/1200)); }catch(e){}
        return true;
      });
  }
  return { init:init,
    resize:function(){ if(app&&app.renderer){ var r=canvas.getBoundingClientRect();
      try{ app.renderer.resize(Math.max(1,r.width),Math.max(1,r.height)); }catch(e){} } },
    frame:noop,
    pointer:function(x,y){ if(!model) return;
      try{
        if(model.focus) model.focus(x*window.innerWidth,y*window.innerHeight);
        else if(model.internalModel&&model.internalModel.focusController)
          model.internalModel.focusController.focus(x*2-1,-(y*2-1));
      }catch(e){} },
    tap:function(x,y){ if(!model) return; try{ if(model.tap) model.tap(x*window.innerWidth,y*window.innerHeight); }catch(e){} },
    mood:function(m){ if(!model) return;
      try{ if(model.motion) model.motion(m==='talk'?'Talk':m==='happy'?'TapBody':'Idle'); }catch(e){} },
    destroy:function(){
      try{ if(model&&model.destroy) model.destroy(); }catch(e){}
      try{ if(app&&app.destroy) app.destroy(true,{children:true}); }catch(e){}
      model=null; app=null;
    } };
}

/* ---------------- 引擎主体 ---------------- */
function loop(ts){
  if(!st.running) return;
  if(!st.last) st.last=ts;
  var dt=Math.min(64,ts-st.last); st.last=ts; st.t+=dt;
  var k=Math.min(1,dt*.008);
  st.px+=(st.tx-st.px)*k; st.py+=(st.ty-st.py)*k;
  try{ if(st.driver&&st.driver.frame) st.driver.frame(dt,st); }catch(e){}
  st.raf=requestAnimationFrame(loop);
}
function start(){
  if(st.name==='off'||!st.driver) return;
  /* 面板隐藏时不绘制，也不测量 0×0 画布；打开面板时会再次调用 start() */
  var p=document.getElementById('arona');
  if(p&&(p.classList.contains('hidden')||getComputedStyle(p).display==='none')) return;
  try{ if(st.driver.resize) st.driver.resize(); }catch(e){}
  /* 静态背景：resize 已绘制一帧，绝不启动逐帧循环 */
  if(st.name==='static'){ st.running=false; return; }
  if(st.running) return;
  st.running=true; st.last=0;
  st.raf=requestAnimationFrame(loop);
}
function stop(){ st.running=false; if(st.raf) cancelAnimationFrame(st.raf); st.raf=0; }
function teardown(){
  stop();
  if(st.driver&&st.driver.destroy) try{ st.driver.destroy(); }catch(e){}
  st.driver=null;
  while(host.firstChild) host.removeChild(host.firstChild);
  canvas.style.display='';
  try{ var c=canvas.getContext('2d'); c.clearRect(0,0,canvas.width,canvas.height); }catch(e){}
}
function status(){ return { name:st.name, want:st.want, note:st.note,
  running:st.running, mood:st.mood, spine:RES.spine, live2d:RES.live2d }; }

function activate(name){
  teardown(); st.note='';
  if(name==='off'){ st.name='off'; st.note='半透明毛玻璃背景已关闭'; return Promise.resolve(status()); }
  if(name==='static'){
    st.driver=staticDriver(); st.name='static';
    st.note='半透明无色毛玻璃（纯 CSS，无图片 / 无动画 / 无交互）';
    start(); return Promise.resolve(status());
  }
  var d=(name==='spine')?spineDriver():live2dDriver();
  return d.init().then(function(){
    st.driver=d; st.name=name;
    st.note=name+' 驱动已加载';
    start(); return status();
  }).catch(function(err){
    st.note=name+' 驱动不可用：'+err.message+' → 已自动回退 CSS 半透明毛玻璃';
    console.warn('[AronaBG] '+st.note);
    teardown();
    st.driver=staticDriver(); st.name='static';
    start(); return status();
  });
}
function use(name){
  name=String(name||'static').toLowerCase();
  if(name==='canvas') name='static';           /* 兼容旧版终端命令 */
  if(['off','static','spine','live2d'].indexOf(name)<0) name='static';
  st.want=name;
  /* off 时同步隐藏 CSS 半透明毛玻璃层，static 时恢复 */
  var panel=document.getElementById('arona');
  if(panel) panel.classList.toggle('wall-off', name==='off');
  try{ localStorage.setItem('arona_bg',name); }catch(e){}
  return activate(name);
}
/* 静态背景不响应任何交互；保留空接口以兼容旧调用 */
function pointer(nx,ny){}
function tap(nx,ny){}
function mood(m){ st.mood=m; }
function configure(obj){
  if(obj&&obj.spine) for(var a in obj.spine) RES.spine[a]=obj.spine[a];
  if(obj&&obj.live2d) for(var b in obj.live2d) RES.live2d[b]=obj.live2d[b];
  try{ localStorage.setItem('arona_bg_res',JSON.stringify({spine:RES.spine,live2d:RES.live2d})); }catch(e){}
  if(obj&&obj.use) return use(obj.use);
  return status();
}

window.AronaBG={ use:use, start:start, stop:stop, pointer:pointer, tap:tap,
                 mood:mood, configure:configure, status:status };
window.addEventListener('resize',function(){
  if(st.driver&&st.driver.resize) try{ st.driver.resize(); }catch(e){}
});
document.addEventListener('visibilitychange',function(){
  if(document.hidden) stop(); else if(st.name!=='off'&&st.want!=='off') start();
});
/* 启动：固定为 static（纯 CSS 半透明毛玻璃），忽略历史遗留的互动驱动设定 */
try{ use('static'); }catch(e){}
})();
