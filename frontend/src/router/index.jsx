import React from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import AdminLayout from '../layouts/AdminLayout';
import StaffLayout from '../layouts/StaffLayout';
import MobileLayout from '../layouts/MobileLayout';
import RequireAuth from './RequireAuth';
import Login from '../pages/Login';
import Register from '../pages/Register';
import FloorList from '../pages/FloorList';
import SeatMap from '../pages/SeatMap';
import MyReservations from '../pages/MyReservations';
import GroupCreate from '../pages/GroupCreate';
import MyGroup from '../pages/MyGroup';
import MyWaitlist from '../pages/MyWaitlist';
import Credit from '../pages/Credit';
import Profile from '../pages/Profile';
import FloorManage from '../pages/admin/FloorManage';
import SeatManage from '../pages/admin/SeatManage';
import UsageQuery from '../pages/admin/UsageQuery';
import ReservationList from '../pages/admin/ReservationList';
import Statistics from '../pages/admin/Statistics';
import UserList from '../pages/admin/UserList';
import RuleManage from '../pages/admin/RuleManage';
import ZoneManage from '../pages/admin/ZoneManage';
import TableManage from '../pages/admin/TableManage';
import OpenRuleManage from '../pages/admin/OpenRuleManage';
import ViolationManage from '../pages/admin/ViolationManage';
import RuleHistory from '../pages/admin/RuleHistory';
import ExportCenter from '../pages/admin/ExportCenter';
import StaffHome from '../pages/staff/StaffHome';
import PatrolBoard from '../pages/staff/PatrolBoard';
import Dashboard from '../pages/staff/Dashboard';
import AppealQueue from '../pages/staff/AppealQueue';
import MobileHome from '../pages/mobile/Home';
import MobileSeat from '../pages/mobile/Seat';
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
        <Route path="/my/waitlist" element={<MyWaitlist />} />
        <Route path="/groups/create" element={<GroupCreate />} />
        <Route path="/groups" element={<MyGroup />} />
        <Route path="/credit" element={<Credit />} />
        <Route path="/profile" element={<Profile />} />
      </Route>

      {/* 馆员作业面：巡查上线后三块工作台共用 StaffLayout 外壳（T-V2-21） */}
      <Route
        path="/staff"
        element={
          <RequireAuth roles={[USER_ROLE.LIBRARIAN, USER_ROLE.SYS_ADMIN]}>
            <StaffLayout />
          </RequireAuth>
        }
      >
        <Route index element={<StaffHome />} />
        <Route path="patrol" element={<PatrolBoard />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="appeals" element={<AppealQueue />} />
      </Route>

      {/* 移动优先壳（T-V2-23）：底部 Tab 五格，「我的座位 / 信用 / 我的」直接复用 PC 组件，避免同一业务出现两份实现 */}
      <Route
        path="/m"
        element={
          <RequireAuth>
            <MobileLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/m/home" replace />} />
        <Route path="home" element={<MobileHome />} />
        <Route path="seat" element={<MobileSeat />} />
        <Route path="my" element={<MyReservations />} />
        <Route path="credit" element={<Credit />} />
        <Route path="me" element={<Profile />} />
      </Route>

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
        <Route path="rules" element={<RuleManage />} />
        <Route path="violations" element={<ViolationManage />} />
        <Route path="zones" element={<ZoneManage />} />
        <Route path="tables" element={<TableManage />} />
        <Route path="open" element={<OpenRuleManage />} />
        <Route path="rules/history" element={<RuleHistory />} />
        <Route path="export" element={<ExportCenter />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
