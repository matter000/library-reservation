import React from 'react';
import { Avatar, Button, Layout, Menu, Typography } from 'antd';
import { BookOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../store/AuthContext';

const { Header, Content, Footer } = Layout;

/**
 * 用户端布局：顶部导航（楼层 / 我的预约 / 个人中心 / 退出）+ 内容区。
 *
 * @returns {JSX.Element} 布局
 */
export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, hasRole } = useAuth();

  const menuItems = [
    { key: '/floors', icon: <BookOutlined />, label: '楼层' },
    { key: '/my/reservations', icon: <UserOutlined />, label: '我的预约' },
  ];
  if (hasRole('ADMIN')) {
    menuItems.push({ key: '/admin/statistics', icon: <UserOutlined />, label: '管理后台' });
  }

  const selectedKey = location.pathname.startsWith('/my') ? '/my/reservations' : '/floors';

  return (
    <Layout className="main-layout">
      <Header className="main-header">
        <div className="main-header-brand" onClick={() => navigate('/floors')} role="button">
          📚 图书馆座位预约
        </div>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, minWidth: 0 }}
        />
        <div className="main-header-user">
          <Avatar size="small" icon={<UserOutlined />} />
          <Typography.Text style={{ color: '#fff' }}>
            {user ? user.realName || user.username : ''}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ color: 'rgba(255,255,255,0.65)' }}>
            {user ? `（${user.username}）` : ''}
          </Typography.Text>
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
        </div>
      </Header>
      <Content className="main-content">
        <Outlet />
      </Content>
      <Footer className="main-footer">
        图书馆座位预约系统 · 开放时间 08:00-22:00 · 单次最多 4 小时
      </Footer>
    </Layout>
  );
}
