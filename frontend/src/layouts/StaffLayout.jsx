import React from 'react';
import { Button, Layout, Menu, Typography } from 'antd';
import { AuditOutlined, DesktopOutlined, LogoutOutlined, WarningOutlined } from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../store/AuthContext';
import NoticeBell from '../components/NoticeBell';
import { USER_ROLE, roleText } from '../constants';

const { Header, Content } = Layout;

const MENU_ITEMS = [
  { key: '/staff', icon: <DesktopOutlined />, label: '作业台' },
  { key: '/staff/patrol', icon: <WarningOutlined />, label: '巡查看板' },
  { key: '/staff/appeals', icon: <AuditOutlined />, label: '申诉复核' },
];

/**
 * 馆员作业面布局（T-V2-21 建立）。
 *
 * <p>S0 时馆员面只有一个 /staff 占位页 + 后来的申诉队列，各自带返回按钮；
 * 巡查任务上线后这里成为三块工作台的共同外壳 —— 馆员在楼层里走动，
 * 页面之间跳来跳去却没有统一导航，比屏幕少一块区域更影响效率。
 *
 * <p>★ 顶部保留铃铛：馆员自己也可能有预约与违规，且巡查处置的通知体验要和学生端一致。
 *
 * @returns {JSX.Element} 布局
 */
export default function StaffLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, hasRole } = useAuth();

  // 前缀匹配取第一个命中项：/staff/patrol 不能因为 /staff 排在前面就被高亮成「作业台」
  const sortedByLength = [...MENU_ITEMS].sort((a, b) => b.key.length - a.key.length);
  const selectedKey =
    sortedByLength.map((item) => item.key).find((key) => location.pathname.startsWith(key))
    || '/staff';

  return (
    <Layout className="staff-layout">
      <Header className="staff-header">
        <Typography.Text strong style={{ color: '#fff', marginRight: 24 }}>
          馆员工作台
        </Typography.Text>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, minWidth: 240 }}
        />
        <span className="staff-header-user">
          <NoticeBell />
          <Typography.Text style={{ color: '#fff' }}>
            {user ? `${user.realName || user.username}（${roleText(user.role)}）` : ''}
          </Typography.Text>
          {hasRole(USER_ROLE.SYS_ADMIN) ? (
            <Button type="text" onClick={() => navigate('/admin/statistics')} style={{ color: '#fff' }}>
              管理后台
            </Button>
          ) : null}
          <Button
            type="text"
            icon={<LogoutOutlined />}
            style={{ color: '#fff' }}
            onClick={() => {
              logout();
              navigate('/login');
            }}
          >
            退出
          </Button>
        </span>
      </Header>
      <Content className="staff-content">
        <Outlet />
      </Content>
    </Layout>
  );
}
