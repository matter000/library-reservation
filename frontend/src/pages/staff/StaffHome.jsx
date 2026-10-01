import React from 'react';
import { Button, Card, Col, Row, Space, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../store/AuthContext';
import { roleText } from '../../constants';

/**
 * 馆员工作台占位页（S0）。
 *
 * <p>馆员作业面（实时监控大屏、巡查看板、违规与申诉处理）在 S2 / S3 / S6 分片逐步落地；
 * 本页面用于验证 /api/staff 命名空间与前端角色守卫已经打通。
 *
 * @returns {JSX.Element} 占位页
 */
export default function StaffHome() {
  const navigate = useNavigate();
  const { user } = useAuth();

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
          {[
            { title: '暂离与实时座位', stage: 'S2 暂离' },
            { title: '违规确认与申诉复核', stage: 'S3 信用' },
            { title: '巡查任务看板', stage: 'S6 巡查' },
            { title: '全馆监控大屏', stage: 'S6 巡查' },
          ].map((item) => (
            <Col key={item.title} xs={24} sm={12} lg={6}>
              <Card size="small" title={item.title}>
                <Space direction="vertical">
                  <Tag>{item.stage}</Tag>
                  <Typography.Text type="secondary">待交付</Typography.Text>
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
