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
    <div ref="stage" class="pdf-stage" @click="focusViewer" @touchstart.passive="startSwipe" @touchend.passive="finishSwipe">
      <div v-if="loading" class="pdf-state" role="status"><span class="pdf-spinner"></span><strong>正在载入 PDF{{ loadProgress ? ` ${loadProgress}%` : '…' }}</strong></div>
      <div v-else-if="error" class="pdf-state pdf-error" role="alert"><strong>PDF 暂时无法显示</strong><p>{{ error }}</p><el-button type="primary" plain @click="loadDocument">重新加载</el-button></div>
      <canvas ref="canvas" v-show="!loading && !error" aria-label="当前 PDF 页面"></canvas>
      <div v-if="rendering && !loading && !error" class="pdf-rendering" role="status"><span class="pdf-spinner"></span><small>正在绘制第 {{ pageNumber }} 页</small></div>
    </div>
    <footer v-if="pageCount > 1" class="pdf-mobile-hint">左右滑动翻页 · 方向键翻页 · 点击百分比恢复宽度</footer>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { OnProgressParameters, PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist/legacy/build/pdf.mjs';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';
import { getStoredValue, setStoredValue } from '../api/client';

const props = defineProps<{ src:string; documentId?:string|number }>();
const viewer = ref<HTMLElement>();
const stage = ref<HTMLElement>();
const canvas = ref<HTMLCanvasElement>();
const loading = ref(true);
const error = ref('');
const pageNumber = ref(1);
const pageInput = ref(1);
const pageCount = ref(0);
const zoom = ref(1);
const loadProgress = ref(0);
const rendering = ref(false);
const isFullscreen = ref(false);
const readingProgress = computed(()=>pageCount.value?Math.round(pageNumber.value/pageCount.value*100):0);
let pdf:PDFDocumentProxy|undefined;
let loadingTask:PDFDocumentLoadingTask|undefined;
let renderTask:RenderTask|undefined;
let resizeObserver:ResizeObserver|undefined;
let generation=0;
let renderSequence=0;
let resizeFrame=0;
let swipeStart:{x:number;y:number;time:number}|undefined;
let pdfJsPromise:Promise<typeof import('pdfjs-dist/legacy/build/pdf.mjs')>|undefined;

function loadPdfJs(){
  pdfJsPromise??=import('pdfjs-dist/legacy/build/pdf.mjs').then(module=>{module.GlobalWorkerOptions.workerSrc=workerUrl;return module;});
  return pdfJsPromise;
}

async function releaseDocument(){
  renderSequence++;
  renderTask?.cancel();renderTask=undefined;
  if(loadingTask){await loadingTask.destroy().catch(()=>undefined);loadingTask=undefined;}
  else if(pdf){await pdf.cleanup().catch(()=>undefined);}
  pdf=undefined;
}

async function loadDocument(){
  const current=++generation;error.value='';loading.value=true;loadProgress.value=0;pageCount.value=0;pageNumber.value=1;pageInput.value=1;zoom.value=1;
  await releaseDocument();
  if(!props.src){error.value='没有可预览的 PDF 文件';loading.value=false;return;}
  try{
    const pdfJs=await loadPdfJs();if(current!==generation)return;
    loadingTask=pdfJs.getDocument({url:props.src});
    loadingTask.onProgress=({loaded,total}:OnProgressParameters)=>{if(current===generation&&total>0)loadProgress.value=Math.min(100,Math.round(loaded/total*100));};
    const loaded=await loadingTask.promise;
    if(current!==generation){await loaded.cleanup();return;}
    pdf=loaded;pageCount.value=loaded.numPages;restoreReadingState();loading.value=false;loadProgress.value=100;
    await nextTick();await renderPage();
  }catch(reason){
    if(current!==generation)return;
    loading.value=false;error.value=reason instanceof Error&&reason.message?'请检查文件是否完整，或稍后重试。':'请稍后重试。';
  }
}

async function renderPage(){
  const current=generation;const sequence=++renderSequence;if(!pdf||!canvas.value||!stage.value||loading.value)return;
  renderTask?.cancel();
  rendering.value=true;
  try{
    const page=await pdf.getPage(pageNumber.value);if(current!==generation||sequence!==renderSequence)return;
    const natural=page.getViewport({scale:1});
    const availableWidth=Math.max(260,stage.value.clientWidth-24);
    const viewport=page.getViewport({scale:(availableWidth/natural.width)*zoom.value});
    const pixelRatio=Math.min(window.devicePixelRatio||1,2);
    const target=canvas.value;const context=target.getContext('2d',{alpha:false});if(!context)throw new Error('canvas unavailable');
    target.width=Math.floor(viewport.width*pixelRatio);target.height=Math.floor(viewport.height*pixelRatio);
    target.style.width=`${Math.floor(viewport.width)}px`;target.style.height=`${Math.floor(viewport.height)}px`;
    renderTask=page.render({canvas:target,canvasContext:context,viewport,transform:pixelRatio===1?undefined:[pixelRatio,0,0,pixelRatio,0,0]});
    await renderTask.promise;
    if(sequence===renderSequence){saveReadingState();prefetchAdjacentPages();}
  }catch(reason){
    if(reason instanceof Error&&reason.name==='RenderingCancelledException')return;
    if(current===generation&&sequence===renderSequence)error.value='当前页面渲染失败，请重新加载。';
  }finally{if(sequence===renderSequence)rendering.value=false;}
}

function goToPage(value:number){
  if(!pageCount.value)return;const next=Math.min(pageCount.value,Math.max(1,Number(value)||1));
  pageNumber.value=next;pageInput.value=next;void renderPage();stage.value?.scrollTo({top:0,behavior:'smooth'});
}
function setZoom(value:number){zoom.value=Math.min(2.5,Math.max(.75,value));void renderPage();}
function readingStateKey(){return props.documentId===undefined?'':`ai-knowledge-pdf-state-${props.documentId}`;}
function restoreReadingState(){
  const key=readingStateKey();if(!key)return;
  try{const saved=JSON.parse(getStoredValue(key,'{}')) as {page?:number;zoom?:number};pageNumber.value=Math.min(pageCount.value,Math.max(1,saved.page||1));pageInput.value=pageNumber.value;zoom.value=Math.min(2.5,Math.max(.75,saved.zoom||1));}catch{/* 使用默认阅读位置。 */}
}
function saveReadingState(){const key=readingStateKey();if(key)setStoredValue(key,JSON.stringify({page:pageNumber.value,zoom:zoom.value}));}
function prefetchAdjacentPages(){if(!pdf)return;[pageNumber.value-1,pageNumber.value+1].filter(page=>page>=1&&page<=pageCount.value).forEach(page=>void pdf?.getPage(page).catch(()=>undefined));}
function focusViewer(){viewer.value?.focus({preventScroll:true});}
function handleKeydown(event:KeyboardEvent){
  if(event.key==='ArrowLeft'||event.key==='PageUp'){event.preventDefault();goToPage(pageNumber.value-1);}
  else if(event.key==='ArrowRight'||event.key==='PageDown'||event.key===' '){event.preventDefault();goToPage(pageNumber.value+1);}
  else if(event.key==='+'||event.key==='='){event.preventDefault();setZoom(zoom.value+.25);}
  else if(event.key==='-'){event.preventDefault();setZoom(zoom.value-.25);}
  else if(event.key==='0'){event.preventDefault();setZoom(1);}
  else if(event.key==='Escape'&&isFullscreen.value&&!document.fullscreenElement){event.preventDefault();void toggleFullscreen();}
}
function startSwipe(event:TouchEvent){const touch=event.changedTouches[0];if(touch)swipeStart={x:touch.clientX,y:touch.clientY,time:Date.now()};}
function finishSwipe(event:TouchEvent){
  const start=swipeStart;swipeStart=undefined;const touch=event.changedTouches[0];if(!start||!touch||zoom.value!==1)return;
  const x=touch.clientX-start.x;const y=touch.clientY-start.y;if(Date.now()-start.time>800||Math.abs(x)<60||Math.abs(x)<Math.abs(y)*1.4)return;
  goToPage(pageNumber.value+(x<0?1:-1));
}
async function toggleFullscreen(){
  const element=viewer.value;if(!element)return;
  if(document.fullscreenEnabled&&element.requestFullscreen){try{if(document.fullscreenElement)await document.exitFullscreen();else await element.requestFullscreen();return;}catch{/* 使用兼容移动浏览器的页面内全屏。 */}}
  isFullscreen.value=!isFullscreen.value;document.body.classList.toggle('pdf-reader-fullscreen',isFullscreen.value);await nextTick();void renderPage();
}
function syncFullscreen(){isFullscreen.value=document.fullscreenElement===viewer.value;void renderPage();}

watch(()=>props.src,()=>void loadDocument(),{immediate:true});
onMounted(()=>{resizeObserver=new ResizeObserver(()=>{cancelAnimationFrame(resizeFrame);resizeFrame=requestAnimationFrame(()=>void renderPage());});if(viewer.value)resizeObserver.observe(viewer.value);document.addEventListener('fullscreenchange',syncFullscreen);});
onBeforeUnmount(()=>{generation++;cancelAnimationFrame(resizeFrame);resizeObserver?.disconnect();document.removeEventListener('fullscreenchange',syncFullscreen);document.body.classList.remove('pdf-reader-fullscreen');void releaseDocument();});
</script>
