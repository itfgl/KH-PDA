import { defineConfig } from 'vite';

export default defineConfig({
  root: 'src',
  build: {
    outDir: '../www',
    emptyOutDir: false,   // 不清空 www/，保留 index.html
    rollupOptions: {
      input: 'src/main.js',
      output: {
        entryFileNames: 'plugins.js',
        format: 'iife',
      },
    },
  },
});
