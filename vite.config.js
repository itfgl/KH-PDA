import { defineConfig } from 'vite';

export default defineConfig({
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
