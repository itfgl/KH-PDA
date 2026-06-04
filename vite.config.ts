import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // 本地开发时把 /api 转发到服务端，解决跨域
      '/api': 'http://115.29.178.34:2973',
    },
  },
});
