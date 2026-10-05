import React from 'react';
import { Layout, Typography } from 'antd';
import {
  AuditOutlined, HomeOutlined, SafetyOutlined, ScheduleOutlined, UserOutlined,
} from '@ant-design/icons';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../store/AuthContext';
import NoticeBell from '../components/NoticeBell';

const { Header, Content } = Layout;

/**
 * 底部 Tab：五格是手机上「一眼能记住」的上限，再多就要横向滑。
 *
 * ★ 路径都在 `/m/*` 下，PC 页面原样保留：移动壳换的是信息架构与热区，
 *   不是把同一份业务规则再实现一遍（按钮可用性仍由后端给）。
 */
const TABS = [
  { key: '/m/home', text: '首页', icon: <HomeOutlined /> },
  { key: '/m/seat', text: '找座', icon: <ScheduleOutlined /> },
  { key: '/m/my', text: '我的座位', icon: <AuditOutlined /> },
  { key: '/m/credit', text: '信用', icon: <SafetyOutlined /> },
  { key: '/m/me', text: '我的', icon: <UserOutlined /> },
];

/**
 * 移动优先壳（PRD §13.1 / T-V2-23）。
 *
 * @returns {JSX.Element} 移动端布局
 */
export default function MobileLayout() {
  const { user } = useAuth();
  const location = useLocation();
  const current = TABS.find((tab) => location.pathname.startsWith(tab.key));

  return (
    <Layout className="m-layout">
      <Header className="m-header">
        <Typography.Text className="m-header-title">{current ? current.text : '图书馆'}</Typography.Text>
        <span className="m-header-right">
          <Typography.Text className="m-header-user">{user ? user.realName || user.username : ''}</Typography.Text>
          <NoticeBell />
          {/* 演示时经常在两种壳之间跳：留一个明确入口，而不是让人改地址栏 */}
          <Link to="/floors" className="m-header-pc">PC 版</Link>
        </span>
      </Header>
      <Content className="m-content">
        <Outlet />
      </Content>
      {/* role="tablist" + aria-current：手机读屏与键盘都能说出「你现在在第几格」 */}
      <nav className="m-tabs" role="tablist" aria-label="主导航">
        {TABS.map((tab) => (
          <NavLink
            key={tab.key}
            to={tab.key}
            role="tab"
            aria-current={current && current.key === tab.key ? 'page' : undefined}
            className={current && current.key === tab.key ? 'm-tab m-tab-active' : 'm-tab'}
          >
            <span className="m-tab-icon">{tab.icon}</span>
            <span className="m-tab-text">{tab.text}</span>
          </NavLink>
        ))}
      </nav>
    </Layout>
  );
}
