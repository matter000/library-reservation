import React from 'react';
import { Button, Layout, Menu, Typography } from 'antd';
import {
  AppstoreOutlined,
  BarChartOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  LogoutOutlined,
  PartitionOutlined,
  ProfileOutlined,
  SettingOutlined,
  TableOutlined,
  TeamOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../store/AuthContext';

const { Sider, Header, Content } = Layout;

const MENU_ITEMS = [
  { key: '/admin/statistics', icon: <BarChartOutlined />, label: '数据统计' },
  { key: '/admin/floors', icon: <DatabaseOutlined />, label: '楼层管理' },
  { key: '/admin/zones', icon: <PartitionOutlined />, label: '分区管理' },
  { key: '/admin/tables', icon: <TableOutlined />, label: '桌位管理' },
  { key: '/admin/usage', icon: <AppstoreOutlined />, label: '使用情况' },
  { key: '/admin/reservations', icon: <ProfileOutlined />, label: '预约记录' },
  { key: '/admin/violations', icon: <WarningOutlined />, label: '违规管理' },
  { key: '/admin/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/admin/rules', icon: <SettingOutlined />, label: '规则配置' },
  { key: '/admin/open', icon: <ClockCircleOutlined />, label: '开放时间' },
];

/**
 * 管理员布局：左侧固定侧边栏 + 右侧内容区（纯 PC，最小宽度 1280px）。
 *
 * @returns {JSX.Element} 布局
 */
export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();

  /** 座位管理挂在楼层管理下：/admin/floors/:id/seats */
  const pathname = location.pathname;
  const selectedKey =
    MENU_ITEMS.map((item) => item.key).find((key) => pathname.startsWith(key)) ||
    '/admin/statistics';

  return (
    <Layout className="admin-layout">
      <Sider width={200} className="admin-sider" breakpoint="lg" collapsedWidth={64}>
        <div className="admin-sider-title">管理后台</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="admin-header">
          <Typography.Text strong>图书馆座位预约系统 · 管理端</Typography.Text>
          <span>
            <Typography.Text type="secondary">
              {user ? `${user.realName || user.username}（管理员）` : ''}
            </Typography.Text>
            <Button
              type="text"
              icon={<LogoutOutlined />}
              onClick={() => {
                logout();
                navigate('/login');
              }}
            >
              退出
            </Button>
            <Button
              type="link"
              icon={<TableOutlined />}
              onClick={() => navigate('/floors')}
            >
              用户端
            </Button>
          </span>
        </Header>
        <Content className="admin-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
