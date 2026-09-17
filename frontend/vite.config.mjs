import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { gzip } from 'node:zlib';
import { promisify } from 'node:util';

const COMPRESSIBLE = /\.(?:js|mjs|css|html|svg|json)$/;

// Writes a .gz next to every text asset so nginx can send it as is (gzip_static) instead of compressing on
// each request. Small files and files that do not shrink are left alone.
function precompress() {
  let outDir = 'dist';
  return {
    name: 'zhihui-precompress',
    apply: 'build',
    configResolved(config) { outDir = config.build.outDir; },
    async closeBundle() {
      const compress = promisify(gzip);
      const walk = async (dir) => (await Promise.all((await readdir(dir, { withFileTypes: true }))
        .map(entry => entry.isDirectory() ? walk(join(dir, entry.name)) : [join(dir, entry.name)]))).flat();
      for (const file of await walk(outDir)) {
        if (!COMPRESSIBLE.test(file) || (await stat(file)).size < 1024) continue;
        const source = await readFile(file);
        const packed = await compress(source, { level: 9 });
        if (packed.length < source.length * 0.9) await writeFile(`${file}.gz`, packed);
      }
    }
  };
}

export default defineConfig({
  plugins: [vue(), precompress()],
  build: {
    // pdf.js (about 540 kB) is the one larger chunk, and it is only fetched when a PDF is opened.
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('@element-plus/icons-vue')) return 'element-icons';
          if (id.includes('element-plus/es/components/table/')) return 'element-table';
          if (id.includes('element-plus')) return 'element-plus';
          if (id.includes('node_modules/vue') || id.includes('@vue/')) return 'vue-vendor';
          if (id.includes('axios')) return 'http-vendor';
        }
      }
    }
  }
});
