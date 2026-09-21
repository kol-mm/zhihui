<template>
  <section ref="viewer" class="pdf-viewer" :class="{ 'is-fullscreen': isFullscreen }" aria-label="PDF 在线阅读器" tabindex="0" @keydown="handleKeydown">
    <header class="pdf-toolbar">
      <div class="pdf-page-controls">
        <el-button size="small" :disabled="loading || pageNumber <= 1" @click="goToPage(pageNumber - 1)">上一页</el-button>
        <label>第 <input v-model.number="pageInput" type="number" inputmode="numeric" :min="1" :max="pageCount || 1" :disabled="loading" @change="goToPage(pageInput)" /> / {{ pageCount || '—' }} 页</label>
        <el-button size="small" :disabled="loading || pageNumber >= pageCount" @click="goToPage(pageNumber + 1)">下一页</el-button>
      </div>
      <div class="pdf-zoom-controls">
        <el-button size="small" :disabled="loading || zoom <= 0.75" aria-label="缩小 PDF" @click="setZoom(zoom - 0.25)">－</el-button>
        <button type="button" class="pdf-fit-button" :disabled="loading" title="恢复适合宽度" @click="setZoom(1)">{{ Math.round(zoom * 100) }}%</button>
        <el-button size="small" :disabled="loading || zoom >= 2.5" aria-label="放大 PDF" @click="setZoom(zoom + 0.25)">＋</el-button>
        <el-button size="small" :disabled="loading" @click="toggleFullscreen">{{ isFullscreen ? '退出' : '全屏' }}</el-button>
      </div>
    </header>
    <div class="pdf-reading-progress" aria-hidden="true"><i :style="{ width: `${readingProgress}%` }"></i></div>
    <div ref="stage" class="pdf-stage" @click="focusViewer" @scroll.passive="handleScroll">
      <div v-if="loading" class="pdf-state" role="status"><span class="pdf-spinner"></span><strong>正在载入 PDF{{ loadProgress ? ` ${loadProgress}%` : '…' }}</strong></div>
      <div v-else-if="error" class="pdf-state pdf-error" role="alert"><strong>PDF 暂时无法显示</strong><p>{{ error }}</p><el-button type="primary" plain @click="loadDocument">重新加载</el-button></div>
      <div v-else class="pdf-pages">
        <article
          v-for="page in pages"
          :key="page.number"
          :ref="element => registerPage(page.number, element as HTMLElement | null)"
          class="pdf-page"
          :class="{ 'is-current': page.number === pageNumber }"
          :style="{ width: `${pageWidth}px`, height: `${Math.round(pageWidth * page.ratio)}px` }"
          :data-page="page.number"
        >
          <canvas :ref="element => registerCanvas(page.number, element as HTMLCanvasElement | null)" :aria-label="`第 ${page.number} 页`"></canvas>
          <span v-if="!page.rendered" class="pdf-page-placeholder"><span class="pdf-spinner"></span><small>第 {{ page.number }} 页</small></span>
        </article>
      </div>
    </div>
    <footer v-if="pageCount > 1" class="pdf-mobile-hint">上下滚动连续阅读 · 方向键翻页 · 点击百分比恢复宽度</footer>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { OnProgressParameters, PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist/legacy/build/pdf.mjs';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';
import { getStoredValue, setStoredValue } from '../api/client';

type PageState = { number:number; ratio:number; rendered:boolean };

const props = defineProps<{ src:string; documentId?:string|number }>();
const viewer = ref<HTMLElement>();
const stage = ref<HTMLElement>();
const loading = ref(true);
const error = ref('');
const pageNumber = ref(1);
const pageInput = ref(1);
const pageCount = ref(0);
const zoom = ref(1);
const loadProgress = ref(0);
const isFullscreen = ref(false);
const pages = ref<PageState[]>([]);
const pageWidth = ref(720);
const readingProgress = ref(0);

/** Pages this far from the current one keep their pixels; the rest give them back, so long documents stay light. */
const KEEP_RENDERED_RADIUS = 3;

let pdf:PDFDocumentProxy|undefined;
let loadingTask:PDFDocumentLoadingTask|undefined;
let resizeObserver:ResizeObserver|undefined;
let visibility:IntersectionObserver|undefined;
const pageElements = new Map<number, HTMLElement>();
const canvases = new Map<number, HTMLCanvasElement>();
const renderTasks = new Map<number, RenderTask>();
const rendering = new Set<number>();
let generation = 0;
let resizeFrame = 0;
let scrollFrame = 0;
let restoredPage = 0;
let pdfJsPromise:Promise<typeof import('pdfjs-dist/legacy/build/pdf.mjs')>|undefined;

function loadPdfJs(){
  pdfJsPromise??=import('pdfjs-dist/legacy/build/pdf.mjs').then(module=>{module.GlobalWorkerOptions.workerSrc=workerUrl;return module;});
  return pdfJsPromise;
}

function registerPage(number:number, element:HTMLElement|null){
  if(element){pageElements.set(number,element);visibility?.observe(element);}
  else pageElements.delete(number);
}
function registerCanvas(number:number, element:HTMLCanvasElement|null){
  if(element)canvases.set(number,element);else canvases.delete(number);
}

async function releaseDocument(){
  renderTasks.forEach(task=>task.cancel());renderTasks.clear();rendering.clear();
  visibility?.disconnect();visibility=undefined;
  canvases.clear();pageElements.clear();pages.value=[];
  if(loadingTask){await loadingTask.destroy().catch(()=>undefined);loadingTask=undefined;}
  else if(pdf){await pdf.cleanup().catch(()=>undefined);}
  pdf=undefined;
}

async function loadDocument(){
  const current=++generation;error.value='';loading.value=true;loadProgress.value=0;pageCount.value=0;pageNumber.value=1;pageInput.value=1;zoom.value=1;readingProgress.value=0;
  await releaseDocument();
  if(!props.src){error.value='没有可预览的 PDF 文件';loading.value=false;return;}
  try{
    const pdfJs=await loadPdfJs();if(current!==generation)return;
    loadingTask=pdfJs.getDocument({url:props.src});
    loadingTask.onProgress=({loaded,total}:OnProgressParameters)=>{if(current===generation&&total>0)loadProgress.value=Math.min(100,Math.round(loaded/total*100));};
    const loaded=await loadingTask.promise;
    if(current!==generation){await loaded.cleanup();return;}
    pdf=loaded;pageCount.value=loaded.numPages;
    // The first page gives the shape of the document; a page of a different size corrects itself as it draws.
    const first=await loaded.getPage(1);
    const natural=first.getViewport({scale:1});
    pages.value=Array.from({length:loaded.numPages},(_,index)=>({number:index+1,ratio:natural.height/natural.width,rendered:false}));
    restoreReadingState();
    loading.value=false;loadProgress.value=100;
    await nextTick();
    measureWidth();
    observePages();
    if(restoredPage>1){await nextTick();scrollToPage(restoredPage,'auto');}
    restoredPage=0;
    await renderAround(pageNumber.value);
  }catch(reason){
    if(current!==generation)return;
    loading.value=false;error.value=reason instanceof Error&&reason.message?'请检查文件是否完整，或稍后重试。':'请稍后重试。';
  }
}

function measureWidth(){
  const available=stage.value?Math.max(260,stage.value.clientWidth-24):720;
  pageWidth.value=Math.round(available*zoom.value);
}

/** Pages draw themselves shortly before they scroll into view, and only those. */
function observePages(){
  visibility?.disconnect();
  if(!stage.value)return;
  visibility=new IntersectionObserver(entries=>{
    entries.forEach(entry=>{
      if(!entry.isIntersecting)return;
      const number=Number((entry.target as HTMLElement).dataset.page||0);
      if(number)void renderPage(number);
    });
  },{root:stage.value,rootMargin:'150% 0px',threshold:0.01});
  pageElements.forEach(element=>visibility?.observe(element));
}

async function renderPage(number:number){
  const current=generation;
  const state=pages.value.find(page=>page.number===number);
  if(!pdf||!state||state.rendered||rendering.has(number))return;
  const canvas=canvases.get(number);
  if(!canvas)return;
  rendering.add(number);
  try{
    const page=await pdf.getPage(number);
    if(current!==generation)return;
    const natural=page.getViewport({scale:1});
    state.ratio=natural.height/natural.width;
    const viewport=page.getViewport({scale:pageWidth.value/natural.width});
    const pixelRatio=Math.min(window.devicePixelRatio||1,2);
    const context=canvas.getContext('2d',{alpha:false});
    if(!context)throw new Error('canvas unavailable');
    canvas.width=Math.floor(viewport.width*pixelRatio);canvas.height=Math.floor(viewport.height*pixelRatio);
    canvas.style.width=`${Math.floor(viewport.width)}px`;canvas.style.height=`${Math.floor(viewport.height)}px`;
    const task=page.render({canvas,canvasContext:context,viewport,transform:pixelRatio===1?undefined:[pixelRatio,0,0,pixelRatio,0,0]});
    renderTasks.set(number,task);
    await task.promise;
    if(current!==generation)return;
    state.rendered=true;
    releaseDistantPages();
  }catch(reason){
    if(reason instanceof Error&&reason.name==='RenderingCancelledException')return;
    if(current===generation&&!error.value&&!pages.value.some(page=>page.rendered))error.value='页面渲染失败，请重新加载。';
  }finally{
    rendering.delete(number);renderTasks.delete(number);
  }
}

/** Draws the page the reader is on plus its neighbours, so paging never lands on a blank sheet. */
async function renderAround(number:number){
  for(const page of [number,number+1,number-1,number+2].filter(value=>value>=1&&value<=pageCount.value)){
    await renderPage(page);
  }
}

function releaseDistantPages(){
  pages.value.forEach(page=>{
    if(!page.rendered||Math.abs(page.number-pageNumber.value)<=KEEP_RENDERED_RADIUS)return;
    const canvas=canvases.get(page.number);
    if(!canvas)return;
    canvas.width=0;canvas.height=0;canvas.style.width='';canvas.style.height='';
    page.rendered=false;
  });
}

function redrawAll(){
  renderTasks.forEach(task=>task.cancel());renderTasks.clear();rendering.clear();
  pages.value.forEach(page=>{
    const canvas=canvases.get(page.number);
    if(canvas){canvas.width=0;canvas.height=0;canvas.style.width='';canvas.style.height='';}
    page.rendered=false;
  });
  void renderAround(pageNumber.value);
}

function handleScroll(){
  cancelAnimationFrame(scrollFrame);
  scrollFrame=requestAnimationFrame(()=>{
    const element=stage.value;
    if(!element)return;
    const scrollable=element.scrollHeight-element.clientHeight;
    readingProgress.value=scrollable>0?Math.min(100,Math.max(0,Math.round(element.scrollTop/scrollable*100))):0;
    const marker=element.scrollTop+element.clientHeight*0.35;
    let current=pageNumber.value;
    pageElements.forEach((node,number)=>{
      if(node.offsetTop<=marker&&node.offsetTop+node.offsetHeight>marker)current=number;
    });
    if(current!==pageNumber.value){
      pageNumber.value=current;pageInput.value=current;
      saveReadingState();releaseDistantPages();
    }
  });
}

function scrollToPage(number:number,behavior:ScrollBehavior='smooth'){
  const node=pageElements.get(number);
  if(!node||!stage.value)return;
  stage.value.scrollTo({top:Math.max(0,node.offsetTop-8),behavior});
}

function goToPage(value:number){
  if(!pageCount.value)return;
  const next=Math.min(pageCount.value,Math.max(1,Number(value)||1));
  pageNumber.value=next;pageInput.value=next;
  scrollToPage(next);
  void renderAround(next);
  saveReadingState();
}

function setZoom(value:number){
  const next=Math.min(2.5,Math.max(.75,value));
  if(next===zoom.value)return;
  zoom.value=next;measureWidth();saveReadingState();
  void nextTick().then(()=>{redrawAll();scrollToPage(pageNumber.value,'auto');});
}

function readingStateKey(){return props.documentId===undefined?'':`ai-knowledge-pdf-state-${props.documentId}`;}
function restoreReadingState(){
  const key=readingStateKey();if(!key)return;
  try{
    const saved=JSON.parse(getStoredValue(key,'{}')) as {page?:number;zoom?:number};
    pageNumber.value=Math.min(pageCount.value,Math.max(1,saved.page||1));pageInput.value=pageNumber.value;
    zoom.value=Math.min(2.5,Math.max(.75,saved.zoom||1));restoredPage=pageNumber.value;
  }catch{/* 使用默认阅读位置。 */}
}
function saveReadingState(){const key=readingStateKey();if(key)setStoredValue(key,JSON.stringify({page:pageNumber.value,zoom:zoom.value}));}

function focusViewer(){viewer.value?.focus({preventScroll:true});}
function handleKeydown(event:KeyboardEvent){
  if(event.key==='ArrowLeft'||event.key==='PageUp'){event.preventDefault();goToPage(pageNumber.value-1);}
  else if(event.key==='ArrowRight'||event.key==='PageDown'){event.preventDefault();goToPage(pageNumber.value+1);}
  else if(event.key==='+'||event.key==='='){event.preventDefault();setZoom(zoom.value+.25);}
  else if(event.key==='-'){event.preventDefault();setZoom(zoom.value-.25);}
  else if(event.key==='0'){event.preventDefault();setZoom(1);}
  else if(event.key==='Escape'&&isFullscreen.value&&!document.fullscreenElement){event.preventDefault();void toggleFullscreen();}
}

async function toggleFullscreen(){
  const element=viewer.value;if(!element)return;
  if(document.fullscreenEnabled&&element.requestFullscreen){try{if(document.fullscreenElement)await document.exitFullscreen();else await element.requestFullscreen();return;}catch{/* 使用兼容移动浏览器的页面内全屏。 */}}
  isFullscreen.value=!isFullscreen.value;document.body.classList.toggle('pdf-reader-fullscreen',isFullscreen.value);
  await nextTick();measureWidth();redrawAll();scrollToPage(pageNumber.value,'auto');
}
function syncFullscreen(){
  isFullscreen.value=document.fullscreenElement===viewer.value;
  void nextTick().then(()=>{measureWidth();redrawAll();scrollToPage(pageNumber.value,'auto');});
}

watch(()=>props.src,()=>void loadDocument(),{immediate:true});
onMounted(()=>{
  resizeObserver=new ResizeObserver(()=>{
    cancelAnimationFrame(resizeFrame);
    resizeFrame=requestAnimationFrame(()=>{
      const before=pageWidth.value;
      measureWidth();
      if(Math.abs(before-pageWidth.value)>1){redrawAll();scrollToPage(pageNumber.value,'auto');}
    });
  });
  if(viewer.value)resizeObserver.observe(viewer.value);
  document.addEventListener('fullscreenchange',syncFullscreen);
});
onBeforeUnmount(()=>{
  generation++;cancelAnimationFrame(resizeFrame);cancelAnimationFrame(scrollFrame);
  resizeObserver?.disconnect();document.removeEventListener('fullscreenchange',syncFullscreen);
  document.body.classList.remove('pdf-reader-fullscreen');
  void releaseDocument();
});
</script>
