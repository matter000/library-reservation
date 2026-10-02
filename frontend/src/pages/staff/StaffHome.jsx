import React from 'react';
import { Button, Card, Col, Row, Space, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../store/AuthContext';
import { roleText } from '../../constants';

/**
 * 馆员工作台入口页。
 *
 * <p>S0 时这里只是 /api/staff 命名空间与角色守卫的验证页；现在按分片把已交付的作业面
 * 挂成真实入口，未交付的模块继续标注所属分片 —— 避免「看起来有、点进去没有」。
 *
 * @returns {JSX.Element} 馆员工作台
 */
export default function StaffHome() {
  const navigate = useNavigate();
  const { user } = useAuth();

  const modules = [
    {
      title: '暂离与实时座位',
      stage: 'S2 暂离',
      path: '/floors',
      note: '座位图已含暂离态与预计返回',
    },
    {
      title: '违规确认与申诉复核',
      stage: 'S3 信用',
      path: '/staff/appeals',
      note: '申诉队列 · 证据 · 裁定',
    },
    { title: '巡查任务看板', stage: 'S6 巡查' },
    { title: '全馆监控大屏', stage: 'S6 巡查' },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card
        title="馆员工作台"
        extra={<Tag color="gold">{roleText(user ? user.role : '')}</Tag>}
      >
        <Typography.Paragraph>
          当前已具备馆员权限（可访问 <Typography.Text code>/api/staff/**</Typography.Text>）。
          以下作业模块将随 V2 分片交付：
        </Typography.Paragraph>
        <Row gutter={[16, 16]}>
          {modules.map((item) => (
            <Col key={item.title} xs={24} sm={12} lg={6}>
              <Card
                size="small"
                title={item.title}
                actions={item.path
                  ? [(
                    <Button
                      key="go"
                      type="link"
                      size="small"
                      onClick={() => navigate(item.path)}
                    >
                      前往
                    </Button>
                  )]
                  : undefined}
              >
                <Space direction="vertical">
                  <Tag color={item.path ? 'green' : 'default'}>{item.stage}</Tag>
                  <Typography.Text type="secondary">
                    {item.path ? item.note : '待交付'}
                  </Typography.Text>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
        <Button style={{ marginTop: 16 }} onClick={() => navigate('/floors')}>
          前往座位总览
        </Button>
      </Card>
    </div>
  );
}
