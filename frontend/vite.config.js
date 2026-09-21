import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 后端地址：默认 http://localhost:8080。
 *
 * 若 8080 被其他程序占用（例如本机已有服务监听），可用环境变量覆盖：
 *   PowerShell: $env:VITE_API_TARGET='http://localhost:8081'; npm run dev
 */
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8080';

/** Vite 配置：dev 端口 5173，/api 代理到后端（默认 8080）。 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
      },
      '/h2-console': {
        target: API_TARGET,
        changeOrigin: true,
        ws: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        // 按依赖拆分 chunk，避免单文件过大
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
          echarts: ['echarts', 'echarts-for-react'],
        },
      },
    },
  },
});
