import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],

  build: {
    // 构建产物直接输出到服务端 static 目录，部署时无需手动拷贝
    outDir: path.resolve(__dirname, '../server/static'),
    emptyOutDir: true,
  },

  server: {
    // 本地开发时代理 /api 到服务端，行为与生产一致
    proxy: {
      '/api': 'http://115.29.178.34:2973',
    },
  },
});
