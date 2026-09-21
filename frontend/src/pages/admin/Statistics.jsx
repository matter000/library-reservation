import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Row, Select, Statistic, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import {
  fetchFloorUsage,
  fetchOverview,
  fetchSlotHeat,
  fetchStatusDistribution,
  fetchTrend,
} from '../../api/statistics';
import { RESERVATION_STATUS_META } from '../../constants';

/** 饼图配色（与状态语义一致）。 */
const PIE_COLORS = ['#1677ff', '#f5222d', '#52c41a', '#bfbfbf', '#faad14', '#fa541c'];

/** 空数据占位文案。 */
const EMPTY_HINT = '暂无数据';

/**
 * 管理员·数据统计：6 指标卡片 + 2×2 图表栅格。
 * 空数据时仍返回完整 option（series.data=[]），ECharts 显示空坐标系，不报错。
 *
 * @returns {JSX.Element} 统计页
 */
export default function Statistics() {
  const [days, setDays] = useState(7);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [overview, setOverview] = useState(null);
  const [trend, setTrend] = useState([]);
  const [slotHeat, setSlotHeat] = useState([]);
  const [floorUsage, setFloorUsage] = useState([]);
  const [statusDist, setStatusDist] = useState([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [ov, tr, sh, fu, sd] = await Promise.all([
        fetchOverview(),
        fetchTrend(days),
        fetchSlotHeat(days),
        fetchFloorUsage({}),
        fetchStatusDistribution(days),
      ]);
      setOverview(ov || null);
      setTrend(Array.isArray(tr) ? tr : []);
      setSlotHeat(Array.isArray(sh) ? sh : []);
      setFloorUsage(Array.isArray(fu) ? fu : []);
      setStatusDist(Array.isArray(sd) ? sd : []);
    } catch (e) {
      setError(e.message || '加载统计数据失败');
    } finally {
      setLoading(false);
    }
  }, [days]);

  useEffect(() => {
    load();
  }, [load]);

  /* ---------------- 图表 option ---------------- */

  const trendOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 16, top: 32, bottom: 32 },
    xAxis: {
      type: 'category',
      data: trend.map((item) => item.date),
      axisLabel: { interval: 0, rotate: trend.length > 7 ? 30 : 0 },
    },
    yAxis: { type: 'value', minInterval: 1, name: '预约数' },
    series: [
      {
        name: '预约量',
        type: 'line',
        smooth: true,
        data: trend.map((item) => item.count),
        areaStyle: { opacity: 0.18 },
        itemStyle: { color: '#1677ff' },
      },
    ],
  };

  const slotHeatOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 16, top: 32, bottom: 48 },
    xAxis: {
      type: 'category',
      data: slotHeat.map((item) => item.label),
      axisLabel: { interval: 0, rotate: 45, fontSize: 10 },
    },
    yAxis: { type: 'value', minInterval: 1, name: '次数' },
    series: [
      {
        name: '时段热度',
        type: 'bar',
        data: slotHeat.map((item) => item.count),
        itemStyle: { color: '#faad14', borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 26,
      },
    ],
  };

  const floorUsageOption = {
    tooltip: { trigger: 'axis', formatter: (params) => {
      const p = Array.isArray(params) ? params[0] : params;
      return `${p.name}<br/>利用率 ${p.value}%`;
    } },
    grid: { left: 90, right: 40, top: 24, bottom: 24 },
    xAxis: { type: 'value', max: 100, name: '%' },
    yAxis: {
      type: 'category',
      data: floorUsage.map((item) => `${item.name}（${item.code}）`),
    },
    series: [
      {
        name: '利用率',
        type: 'bar',
        data: floorUsage.map((item) => item.usageRate),
        itemStyle: { color: '#52c41a', borderRadius: [0, 3, 3, 0] },
        barMaxWidth: 20,
        label: { show: true, position: 'right', formatter: '{c}%' },
      },
    ],
  };

  const statusOption = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 11 } },
    series: [
      {
        name: '预约状态分布',
        type: 'pie',
        radius: ['38%', '62%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{c}' },
        data: statusDist.map((item, index) => ({
          name: RESERVATION_STATUS_META[item.status]?.text || item.statusText || item.status,
          value: item.count,
          itemStyle: { color: PIE_COLORS[index % PIE_COLORS.length] },
        })),
      },
    ],
  };

  const chartCardProps = {
    size: 'small',
    style: { height: '100%' },
    styles: { body: { height: 300 } },
  };

  return (
    <div>
      <div className="toolbar">
        <Typography.Title level={4} style={{ margin: 0 }}>
          数据统计
        </Typography.Title>
        <span className="spacer" />
        <span>统计窗口：</span>
        <Select
          style={{ width: 120 }}
          value={days}
          onChange={setDays}
          options={[
            { value: 7, label: '近 7 天' },
            { value: 14, label: '近 14 天' },
            { value: 30, label: '近 30 天' },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          刷新
        </Button>
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      {/* 第一行：6 个指标卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} md={8} xl={4}>
          <Card className="metric-card">
            <Statistic title="今日预约" value={overview ? overview.todayReservations : 0} />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="metric-card">
            <Statistic
              title="当前在场"
              value={overview ? overview.currentOnSite : 0}
              valueStyle={{ color: '#f5222d' }}
            />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="metric-card">
            <Statistic
              title="今日签到率"
              value={overview ? overview.checkInRate : 0}
              suffix="%"
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="metric-card">
            <Statistic
              title="今日未签到"
              value={overview ? overview.todayNoShow : 0}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="metric-card">
            <Statistic title="座位总数" value={overview ? overview.totalSeats : 0} />
          </Card>
        </Col>
        <Col xs={12} md={8} xl={4}>
          <Card className="metric-card">
            <Statistic
              title="当前利用率"
              value={overview ? overview.currentUsageRate : 0}
              suffix="%"
              valueStyle={{ color: '#1677ff' }}
            />
          </Card>
        </Col>
      </Row>

      {/* 第二行：2×2 图表栅格 */}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <Card title={`近 ${days} 天预约量趋势`} {...chartCardProps}>
            {trend.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 100 }}>{EMPTY_HINT}</div>
            ) : (
              <ReactECharts option={trendOption} style={{ height: 280 }} notMerge />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title="时段热度分布（08:00-22:00）" {...chartCardProps}>
            {slotHeat.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 100 }}>{EMPTY_HINT}</div>
            ) : (
              <ReactECharts option={slotHeatOption} style={{ height: 280 }} notMerge />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title="各楼层利用率对比" {...chartCardProps}>
            {floorUsage.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 100 }}>{EMPTY_HINT}</div>
            ) : (
              <ReactECharts option={floorUsageOption} style={{ height: 280 }} notMerge />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title="预约状态分布" {...chartCardProps}>
            {statusDist.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 100 }}>{EMPTY_HINT}</div>
            ) : (
              <ReactECharts option={statusOption} style={{ height: 280 }} notMerge />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
