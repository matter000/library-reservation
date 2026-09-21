import React from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Result, Button } from 'antd';
import { useAuth } from '../store/AuthContext';

/**
 * 路由守卫：未登录跳登录页；role="ADMIN" 的路由遇到 USER 提示无权限。
 *
 * @param {object} props {children, role}
 * @returns {JSX.Element} 子组件或跳转
 */
export default function RequireAuth({ children, role }) {
  const { isAuthenticated, hasRole } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (role === 'ADMIN' && !hasRole('ADMIN')) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="抱歉，您没有权限访问管理后台"
        extra={
          <Button type="primary" onClick={() => { window.location.href = '/floors'; }}>
            返回楼层页
          </Button>
        }
      />
    );
  }

  return children;
}
