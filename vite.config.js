import { defineConfig } from 'vite';

export default defineConfig({
  define: {
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
  build: {
    outDir: 'www',
    emptyOutDir: false,
    lib: {
      entry: './src/main.js',
      formats: ['iife'],
      name: 'KaihangPlugins',
      fileName: () => 'plugins.js',
    },
  },
});
