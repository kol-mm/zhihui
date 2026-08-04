import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('@element-plus/icons-vue')) return 'element-icons';
          if (id.includes('element-plus')) return 'element-plus';
          if (id.includes('node_modules/vue') || id.includes('@vue/')) return 'vue-vendor';
          if (id.includes('axios')) return 'http-vendor';
        }
      }
    }
  }
});
