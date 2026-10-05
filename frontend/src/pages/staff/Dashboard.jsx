import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Col, Progress, Row, Space, Spin, Statistic, Table, Tag, Typography,
} from 'antd';
import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { fetchDashboard } from '../../api/dashboard';
import { DASHBOARD_POLL_INTERVAL } from '../../constants';

/** 热力条的最高高度（px）：其余按当日最大值等比缩放。 */
const HEAT_BAR_MAX = 88;

/**
 * 全馆监控大屏（DESIGN §12.2 / T-V2-22）。
 *
 * <p>★ 这一页只渲染后端算好的数字，一个都不自己算：
 * 楼层与分区占用来自统计页同一入口（其内部是 `OccupancyService` 的单点派生），
 * 异常数来自各自的表。大屏自己算一遍「谁在座」，就会出现第二个真相源，
 * 两处不一致时馆员不知道信哪个 —— 那比屏幕少一格更糟。
 *
 * <p>★ 热力格标的是「今日各时段预约数」而不是「实时在座」：
 * 后者要为 44 个时段各跑一次派生，而实时在座这格已由上方卡片给足。
 * 名字不能含糊，否则馆员会以为 20:00 那一格高就是那时人最多。
 *
 * @returns {JSX.Element} 大屏
 */
export default function Dashboard() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (silent = false) => {
    if (!silent) {
      setLoading(true);
    }
    try {
      setData(await fetchDashboard());
      setError('');
    } catch (e) {
      setError(e.message || '大屏数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    const timer = setInterval(() => {
      if (!document.hidden) {
        load(true);
      }
    }, DASHBOARD_POLL_INTERVAL);
    return () => clearInterval(timer);
  }, [load]);

  const overview = data ? data.overview : null;
  const anomalies = data ? data.anomalies : null;

  /** 楼层 → 分区摊平成一张表：大屏要一眼扫完，不做二级展开。 */
  const zoneRows = useMemo(() => {
    if (!data || !data.floors) {
      return [];
    }
    const out = [];
    data.floors.forEach((floor) => {
      (floor.zones || []).forEach((zone) => {
        out.push({
          key: `${floor.floorId}-${zone.zoneId}`,
          floorName: floor.name,
          zoneName: zone.name,
          zoneType: zone.zoneTypeText,
          total: zone.totalSeats,
          occupied: zone.occupied,
          rate: Math.round(zone.usageRate || 0),
        });
      });
    });
    return out;
  }, [data]);

  const heatMax = useMemo(() => {
    if (!data || !data.heat) {
      return 1;
    }
    return Math.max(1, ...data.heat.map((item) => item.count || 0));
  }, [data]);

  const columns = useMemo(() => ([
    { title: '楼层', dataIndex: 'floorName', width: 120 },
    { title: '分区', dataIndex: 'zoneName', width: 140 },
    { title: '类型', dataIndex: 'zoneType', width: 120 },
    { title: '座位数', dataIndex: 'total', width: 90 },
    { title: '占用', dataIndex: 'occupied', width: 90 },
    {
      title: '占用率',
      dataIndex: 'rate',
      render: (value) => <Progress percent={value} size="small" status={value >= 90 ? 'exception' : 'normal'} />,
    },
  ]), []);

  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>
          全馆监控大屏
          {data ? (
            <Typography.Text type="secondary" style={{ marginLeft: 12, fontSize: 14 }}>
              {data.date} · 当前时段 {data.slotLabel} · 更新于 {data.generatedAt}
            </Typography.Text>
          ) : null}
        </Typography.Title>
        <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
      </Space>

      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}
      {data && data.closedToday ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="今天闭馆：占用为 0 不是异常"
          description="闭馆日的空场是正常状态，别按「为什么没人」去巡查。"
        />
      ) : null}

      <Spin spinning={loading && !data}>
        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
          <Col xs={12} md={6}>
            <Card size="small">
              <Statistic title="全馆座位" value={overview ? overview.totalSeats : 0} />
            </Card>
          </Col>
          <Col xs={12} md={6}>
            <Card size="small">
              <Statistic
                title="此刻在场（已签到）"
                value={overview ? overview.currentOnSite : 0}
                suffix={overview
                  ? <span style={{ fontSize: 14 }}>／占用 {overview.currentUsageRate}%</span>
                  : null}
              />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                占用率与统计页同一出处，含预约未签到
              </Typography.Text>
            </Card>
          </Col>
          <Col xs={12} md={6}>
            <Card size="small">
              <Statistic
                title="暂离保留中"
                value={data ? data.onLeave : 0}
                valueStyle={{ color: '#faad14' }}
              />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                椅子仍被占着，但不该被判违规
              </Typography.Text>
            </Card>
          </Col>
          <Col xs={12} md={6}>
            <Card size="small">
              <Statistic title="今日预约" value={overview ? overview.todayReservations : 0} />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                签到率 {overview ? `${overview.checkInRate}%` : '-'}
                ｜今日爽约 {overview ? overview.todayNoShow : 0}
              </Typography.Text>
            </Card>
          </Col>
        </Row>

        <Card
          size="small"
          title={<span><WarningOutlined /> 等人处理的异常</span>}
          extra={(
            <Space>
              <Button size="small" onClick={() => navigate('/staff/patrol')}>巡查看板</Button>
              <Button size="small" onClick={() => navigate('/staff/appeals')}>申诉复核</Button>
            </Space>
          )}
          style={{ marginBottom: 16 }}
        >
          <Row gutter={[16, 16]}>
            <Col xs={12} md={4}>
              <Statistic
                title="未处理疑点"
                value={anomalies ? anomalies.patrolOpen : 0}
                valueStyle={{ color: anomalies && anomalies.patrolOpen > 0 ? '#cf1322' : undefined }}
              />
            </Col>
            <Col xs={12} md={4}>
              <Space direction="vertical" size={2}>
                <Typography.Text type="secondary">疑点等级</Typography.Text>
                <Space size={4}>
                  <Tag color="red">高 {anomalies ? anomalies.patrolHigh : 0}</Tag>
                  <Tag color="orange">中 {anomalies ? anomalies.patrolMid : 0}</Tag>
                  <Tag>低 {anomalies ? anomalies.patrolLow : 0}</Tag>
                </Space>
              </Space>
            </Col>
            <Col xs={12} md={4}>
              <Statistic
                title="最久等待（分钟）"
                value={anomalies ? anomalies.patrolOldestMinutes : 0}
              />
            </Col>
            <Col xs={12} md={4}>
              <Statistic title="待复核申诉" value={anomalies ? anomalies.appealsPending : 0} />
            </Col>
            <Col xs={12} md={4}>
              <Statistic title="待终审申诉" value={anomalies ? anomalies.appealsEscalated : 0} />
            </Col>
            <Col xs={12} md={4}>
              <Statistic title="生效处罚（人）" value={anomalies ? anomalies.activePenaltyUsers : 0} />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                高峰分区 {anomalies ? anomalies.peakZones : 0} 个
              </Typography.Text>
            </Col>
          </Row>
        </Card>

        <Row gutter={[16, 16]}>
          <Col xs={24} lg={14}>
            <Card size="small" title="分区占用">
              <Table
                rowKey="key"
                size="small"
                columns={columns}
                dataSource={zoneRows}
                pagination={false}
                scroll={{ y: 320 }}
              />
            </Card>
          </Col>
          <Col xs={24} lg={10}>
            <Card
              size="small"
              title="今日各时段预约数"
              extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>不是实时在座</Typography.Text>}
            >
              <div className="dash-heat">
                {(data ? data.heat : []).map((item) => (
                  <div key={item.slotIndex} className="dash-heat-col" title={`${item.label}：${item.count} 单`}>
                    <div
                      className="dash-heat-bar"
                      style={{ height: `${Math.round(((item.count || 0) / heatMax) * HEAT_BAR_MAX)}px` }}
                    />
                    <span className="dash-heat-label">{item.label ? item.label.slice(0, 5) : ''}</span>
                  </div>
                ))}
              </div>
            </Card>
          </Col>
        </Row>
      </Spin>
    </div>
  );
}
