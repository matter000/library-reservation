import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Row, Select, Statistic, Typography } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
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
 * <p>利用率图支持「楼层 → 分区」下钻：分区明细由后端在同一次占用派生中累加，
 * 因此前端无需再担心两级数据对不上；下钻只切换视图，不额外发请求。
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
  const [drillFloorId, setDrillFloorId] = useState(null);

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

  /** 时段热度窗口文案由数据本身推出，避免写死 08:00-22:00（开放档案一变就失真）。 */
  const slotRangeText = slotHeat.length
    ? `${slotHeat[0].label.split('-')[0]}–${slotHeat[slotHeat.length - 1].label.split('-')[1]}，${slotHeat.length} 格`
    : '30 分钟/格';

  /** 当前下钻到的楼层；楼层被删除或刷新后不存在时自动回到楼层视角。 */
  const drilled = floorUsage.find((item) => item.floorId === drillFloorId) || null;

  /** 利用率图的数据行：未下钻时是楼层，下钻后是该楼层的分区（含「未分区」桶）。 */
  const usageRows = drilled
    ? (drilled.zones || []).map((zone) => ({
      label: zone.name,
      usageRate: zone.usageRate,
      occupied: zone.occupied,
      totalSeats: zone.totalSeats,
      note: zone.zoneTypeText,
    }))
    : floorUsage.map((item) => ({
      label: `${item.name}（${item.code}）`,
      usageRate: item.usageRate,
      occupied: item.occupied,
      totalSeats: item.totalSeats,
      note: null,
    }));

  const usageOption = {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = Array.isArray(params) ? params[0] : params;
        const row = usageRows[p.dataIndex];
        if (!row) {
          return `${p.name}<br/>利用率 ${p.value}%`;
        }
        return `${row.label}<br/>利用率 ${row.usageRate}%（占用 ${row.occupied}/${row.totalSeats} 座）`
          + (row.note ? `<br/>类型：${row.note}` : '');
      },
    },
    grid: { left: 110, right: 44, top: 24, bottom: 24 },
    xAxis: { type: 'value', max: 100, name: '%' },
    yAxis: {
      type: 'category',
      data: usageRows.map((item) => item.label),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        name: '利用率',
        type: 'bar',
        data: usageRows.map((item) => item.usageRate),
        itemStyle: { color: drilled ? '#1677ff' : '#52c41a', borderRadius: [0, 3, 3, 0] },
        barMaxWidth: 20,
        label: { show: true, position: 'right', formatter: '{c}%' },
      },
    ],
  };

  /** 下钻点击：只有楼层视角需要响应（分区视角已是末级）。 */
  const usageEvents = drilled ? undefined : {
    click: (params) => {
      const target = floorUsage[params.dataIndex];
      if (target) {
        setDrillFloorId(target.floorId);
      }
    },
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
          <Card title={`时段热度分布（${slotRangeText}）`} {...chartCardProps}>
            {slotHeat.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 100 }}>{EMPTY_HINT}</div>
            ) : (
              <ReactECharts option={slotHeatOption} style={{ height: 280 }} notMerge />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card
            title={drilled ? `${drilled.name} · 分区利用率下钻` : '各楼层利用率对比'}
            {...chartCardProps}
            extra={(
              drilled ? (
                <Button
                  size="small"
                  icon={<ArrowLeftOutlined />}
                  onClick={() => setDrillFloorId(null)}
                >
                  返回楼层
                </Button>
              ) : (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  点击条形下钻分区
                </Typography.Text>
              )
            )}
          >
            {usageRows.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 100 }}>
                {drilled ? '该楼层暂无分区归属的座位' : EMPTY_HINT}
              </div>
            ) : (
              <ReactECharts
                option={usageOption}
                onEvents={usageEvents}
                style={{ height: 280 }}
                notMerge
              />
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
