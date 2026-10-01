import React from 'react';
import { Button, Result } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../store/AuthContext';
import { roleHomePath } from '../constants';

/**
 * 路由守卫：未登录跳登录页；声明了 roles 的路由，角色不在名单内则 403。
 *
 * <p>★ 前端守卫只是体验层，真正的入口权限由后端 {@code RolePermission} 矩阵判定，
 * 前端隐藏菜单不等于安全（PRD 双保险约定）。
 *
 * @param {object} props {children, roles} roles 为允许访问的角色数组，省略表示仅需登录
 * @returns {JSX.Element} 子组件、403 提示或跳转
 */
export default function RequireAuth({ children, roles }) {
  const { isAuthenticated, hasRole, user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  const restricted = Array.isArray(roles) && roles.length > 0;
  if (restricted && !roles.some((role) => hasRole(role))) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="抱歉，当前账号没有权限访问该页面"
        extra={
          <Button type="primary" onClick={() => navigate(roleHomePath(user && user.role), { replace: true })}>
            返回我的主页
          </Button>
        }
      />
    );
  }

  return children;
}
