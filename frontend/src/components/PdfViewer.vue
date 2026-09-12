<template>
  <section ref="viewer" class="pdf-viewer" aria-label="PDF 在线阅读器">
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
      </div>
    </header>
    <div ref="stage" class="pdf-stage">
      <div v-if="loading" class="pdf-state" role="status"><span class="pdf-spinner"></span><strong>正在载入 PDF…</strong></div>
      <div v-else-if="error" class="pdf-state pdf-error" role="alert"><strong>PDF 暂时无法显示</strong><p>{{ error }}</p><el-button type="primary" plain @click="loadDocument">重新加载</el-button></div>
      <canvas ref="canvas" v-show="!loading && !error" aria-label="当前 PDF 页面"></canvas>
    </div>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist/legacy/build/pdf.mjs';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';

const props = defineProps<{ src:string }>();
const viewer = ref<HTMLElement>();
const stage = ref<HTMLElement>();
const canvas = ref<HTMLCanvasElement>();
const loading = ref(true);
const error = ref('');
const pageNumber = ref(1);
const pageInput = ref(1);
const pageCount = ref(0);
const zoom = ref(1);
let pdf:PDFDocumentProxy|undefined;
let loadingTask:PDFDocumentLoadingTask|undefined;
let renderTask:RenderTask|undefined;
let resizeObserver:ResizeObserver|undefined;
let generation=0;
let pdfJsPromise:Promise<typeof import('pdfjs-dist/legacy/build/pdf.mjs')>|undefined;

function loadPdfJs(){
  pdfJsPromise??=import('pdfjs-dist/legacy/build/pdf.mjs').then(module=>{module.GlobalWorkerOptions.workerSrc=workerUrl;return module;});
  return pdfJsPromise;
}

async function releaseDocument(){
  renderTask?.cancel();renderTask=undefined;
  if(loadingTask){await loadingTask.destroy().catch(()=>undefined);loadingTask=undefined;}
  else if(pdf){await pdf.cleanup().catch(()=>undefined);}
  pdf=undefined;
}

async function loadDocument(){
  const current=++generation;error.value='';loading.value=true;pageCount.value=0;pageNumber.value=1;pageInput.value=1;
  await releaseDocument();
  if(!props.src){error.value='没有可预览的 PDF 文件';loading.value=false;return;}
  try{
    const pdfJs=await loadPdfJs();if(current!==generation)return;
    loadingTask=pdfJs.getDocument({url:props.src});
    const loaded=await loadingTask.promise;
    if(current!==generation){await loaded.cleanup();return;}
    pdf=loaded;pageCount.value=loaded.numPages;loading.value=false;
    await nextTick();await renderPage();
  }catch(reason){
    if(current!==generation)return;
    loading.value=false;error.value=reason instanceof Error&&reason.message?'请检查文件是否完整，或稍后重试。':'请稍后重试。';
  }
}

async function renderPage(){
  const current=generation;if(!pdf||!canvas.value||!stage.value||loading.value)return;
  renderTask?.cancel();
  try{
    const page=await pdf.getPage(pageNumber.value);if(current!==generation)return;
    const natural=page.getViewport({scale:1});
    const availableWidth=Math.max(260,stage.value.clientWidth-24);
    const viewport=page.getViewport({scale:(availableWidth/natural.width)*zoom.value});
    const pixelRatio=Math.min(window.devicePixelRatio||1,2);
    const target=canvas.value;const context=target.getContext('2d',{alpha:false});if(!context)throw new Error('canvas unavailable');
    target.width=Math.floor(viewport.width*pixelRatio);target.height=Math.floor(viewport.height*pixelRatio);
    target.style.width=`${Math.floor(viewport.width)}px`;target.style.height=`${Math.floor(viewport.height)}px`;
    renderTask=page.render({canvas:target,canvasContext:context,viewport,transform:pixelRatio===1?undefined:[pixelRatio,0,0,pixelRatio,0,0]});
    await renderTask.promise;
  }catch(reason){
    if(reason instanceof Error&&reason.name==='RenderingCancelledException')return;
    if(current===generation)error.value='当前页面渲染失败，请重新加载。';
  }
}

function goToPage(value:number){
  if(!pageCount.value)return;const next=Math.min(pageCount.value,Math.max(1,Number(value)||1));
  pageNumber.value=next;pageInput.value=next;void renderPage();stage.value?.scrollTo({top:0,behavior:'smooth'});
}
function setZoom(value:number){zoom.value=Math.min(2.5,Math.max(.75,value));void renderPage();}

watch(()=>props.src,()=>void loadDocument(),{immediate:true});
onMounted(()=>{resizeObserver=new ResizeObserver(()=>void renderPage());if(viewer.value)resizeObserver.observe(viewer.value);});
onBeforeUnmount(()=>{generation++;resizeObserver?.disconnect();void releaseDocument();});
</script>
