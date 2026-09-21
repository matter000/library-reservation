import React from 'react';
import AppRoutes from './router';
import ErrorBoundary from './components/ErrorBoundary';

/**
 * 应用根组件：全局错误边界 + 路由表。
 *
 * 错误边界放在最外层，任何页面级渲染异常都会落到兜底 UI，
 * 而不是让用户看到一片空白。
 *
 * @returns {JSX.Element} 应用
 */
export default function App() {
  return (
    <ErrorBoundary>
      <AppRoutes />
    </ErrorBoundary>
  );
}
