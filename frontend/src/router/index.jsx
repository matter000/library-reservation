import React from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import AdminLayout from '../layouts/AdminLayout';
import RequireAuth from './RequireAuth';
import Login from '../pages/Login';
import Register from '../pages/Register';
import FloorList from '../pages/FloorList';
import SeatMap from '../pages/SeatMap';
import MyReservations from '../pages/MyReservations';
import Profile from '../pages/Profile';
import FloorManage from '../pages/admin/FloorManage';
import SeatManage from '../pages/admin/SeatManage';
import UsageQuery from '../pages/admin/UsageQuery';
import ReservationList from '../pages/admin/ReservationList';
import Statistics from '../pages/admin/Statistics';
import UserList from '../pages/admin/UserList';
import StaffHome from '../pages/staff/StaffHome';
import { useAuth } from '../store/AuthContext';
import { USER_ROLE, roleHomePath } from '../constants';

/**
 * 登录成功后按角色跳转的目标地址（落地路由只在 USER_ROLE_META 里定义一次）。
 *
 * @returns {JSX.Element} 跳转
 */
function RoleHome() {
  const { user } = useAuth();
  return <Navigate to={roleHomePath(user && user.role)} replace />;
}

/**
 * 应用路由表。
 *
 * @returns {JSX.Element} 路由
 */
export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      {/* 用户端 */}
      <Route
        element={
          <RequireAuth>
            <MainLayout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<RoleHome />} />
        <Route path="/floors" element={<FloorList />} />
        <Route path="/floors/:floorId/seats" element={<SeatMap />} />
        <Route path="/my/reservations" element={<MyReservations />} />
        <Route path="/profile" element={<Profile />} />
      </Route>

      {/* 馆员作业面（S0 先占位，实时监控 / 巡查 / 违规处理随 S2·S3·S6 交付） */}
      <Route
        path="/staff"
        element={
          <RequireAuth roles={[USER_ROLE.LIBRARIAN, USER_ROLE.SYS_ADMIN]}>
            <StaffHome />
          </RequireAuth>
        }
      />

      {/* 系统管理面 */}
      <Route
        path="/admin"
        element={
          <RequireAuth roles={[USER_ROLE.SYS_ADMIN]}>
            <AdminLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/admin/statistics" replace />} />
        <Route path="statistics" element={<Statistics />} />
        <Route path="floors" element={<FloorManage />} />
        <Route path="floors/:floorId/seats" element={<SeatManage />} />
        <Route path="usage" element={<UsageQuery />} />
        <Route path="reservations" element={<ReservationList />} />
        <Route path="users" element={<UserList />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
