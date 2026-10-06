import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Row,
  Select,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import dayjs from 'dayjs';
import { fetchAdminFloors, fetchUsage, releaseReservation } from '../../api/admin';
import { fetchSlots } from '../../api/floor';
import ReleaseModal from '../../components/ReleaseModal';
import { SEAT_DISPLAY_STATUS, SEAT_STATUS_META } from '../../constants';
import { buildFallbackSlots, nowSlot, slotLabel } from '../../utils/datetime';

/**
 * 管理员·座位使用情况：筛选 + 概览条 + 表格 + 行内强制释放。
 * 表格不展示手机号（后端也只返回 realName + username）。
 *
 * @returns {JSX.Element} 使用情况页
 */
export default function UsageQuery() {
  const [floors, setFloors] = useState([]);
  const [floorId, setFloorId] = useState(undefined);
  const [date, setDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [slot, setSlot] = useState(nowSlot());
  const [only, setOnly] = useState('all');
  const [slots, setSlots] = useState(buildFallbackSlots());

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [data, setData] = useState(null);

  const [releaseOpen, setReleaseOpen] = useState(false);
  const [releaseTarget, setReleaseTarget] = useState(null);
  const [releasing, setReleasing] = useState(false);

  /** 加载楼层选项（用于下拉）。 */
  useEffect(() => {
    fetchAdminFloors()
      .then((list) => {
        setFloors(Array.isArray(list) ? list : []);
        if (Array.isArray(list) && list.length > 0 && !floorId) {
          setFloorId(list[0].floorId);
        }
      })
      .catch(() => setFloors([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    fetchSlots(date)
      .then((list) => setSlots(Array.isArray(list) && list.length ? list : buildFallbackSlots()))
      .catch(() => setSlots(buildFallbackSlots()));
  }, [date]);

  const load = useCallback(async () => {
    if (!floorId) {
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await fetchUsage({ floorId, date, slot, only });
      setData(result);
    } catch (e) {
      setError(e.message || '加载使用情况失败');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [floorId, date, slot, only]);

  useEffect(() => {
    if (floorId) {
      load();
    }
  }, [floorId, load]);

  /** 打开强制释放弹窗。 */
  const openRelease = (row) => {
    setReleaseTarget(row);
    setReleaseOpen(true);
  };

  /** 提交强制释放。 */
  const handleRelease = async (reason) => {
    if (!releaseTarget) {
      return;
    }
    setReleasing(true);
    try {
      await releaseReservation(releaseTarget.reservationId, reason);
      message.success('已强制释放，该座位对应时段已恢复空闲');
      setReleaseOpen(false);
      setReleaseTarget(null);
      await load();
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setReleasing(false);
    }
  };

  const summary = data ? data.summary : null;
  const rows = data && data.rows ? data.rows : [];

  const columns = [
    { title: '座位编号', dataIndex: 'seatNo', width: 110 },
    { title: '区域', dataIndex: 'zone', width: 90, render: (v) => v || '-' },
    { title: '行', dataIndex: 'rowNo', width: 60 },
    { title: '列', dataIndex: 'colNo', width: 60 },
    {
      title: '状态',
      dataIndex: 'displayStatus',
      width: 120,
      render: (v) => (
        <Tag color={SEAT_STATUS_META[v]?.color} style={{ color: '#fff' }}>
          {SEAT_STATUS_META[v]?.text || v}
        </Tag>
      ),
    },
    {
      title: '占用人',
      dataIndex: 'occupant',
      render: (v) =>
        v ? `${v.realName || ''}（学号 ${v.studentNo || '—'} · ${v.username || ''}）` : <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '预约时段', dataIndex: 'timeRange', width: 130, render: (v) => v || '-' },
    {
      title: '操作',
      width: 110,
      render: (_, row) =>
        row.reservationId && row.displayStatus !== SEAT_DISPLAY_STATUS.UNAVAILABLE ? (
          <Button size="small" danger onClick={() => openRelease(row)}>
            强制释放
          </Button>
        ) : (
          '-'
        ),
    },
  ];

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        座位使用情况
      </Typography.Title>

      <div className="toolbar">
        <span>楼层：</span>
        <Select
          style={{ width: 180 }}
          value={floorId}
          onChange={setFloorId}
          options={floors.map((f) => ({ value: f.floorId, label: `${f.name}（${f.code}）` }))}
          placeholder="请选择楼层"
        />
        <span>日期：</span>
        <DatePicker value={dayjs(date)} allowClear={false} onChange={(v) => setDate(v ? v.format('YYYY-MM-DD') : date)} />
        <span>时段：</span>
        <Select
          style={{ width: 150 }}
          value={slot}
          onChange={setSlot}
          options={slots.map((s) => ({ value: s.slotIndex, label: s.label }))}
        />
        <span>筛选：</span>
        <Select
          style={{ width: 140 }}
          value={only}
          onChange={setOnly}
          options={[
            { value: 'all', label: '全部' },
            { value: 'occupied', label: '仅看已占用' },
            { value: 'free', label: '仅看空闲' },
          ]}
        />
        <Button type="primary" onClick={load} loading={loading}>
          查询
        </Button>
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      {summary ? (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small" className="metric-card">
              <Statistic title="总座位" value={summary.total} />
            </Card>
          </Col>
          <Col span={5}>
            <Card size="small" className="metric-card">
              <Statistic title="已占用" value={summary.occupied} valueStyle={{ color: '#f5222d' }} />
            </Card>
          </Col>
          <Col span={5}>
            <Card size="small" className="metric-card">
              <Statistic title="空闲" value={summary.free} valueStyle={{ color: '#52c41a' }} />
            </Card>
          </Col>
          <Col span={5}>
            <Card size="small" className="metric-card">
              <Statistic title="不可用" value={summary.unavailable} />
            </Card>
          </Col>
          <Col span={5}>
            <Card size="small" className="metric-card">
              <Statistic title="占用率" value={summary.usageRate} suffix="%" />
            </Card>
          </Col>
        </Row>
      ) : null}

      <Table
        rowKey="seatId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        size="small"
        pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 条` }}
      />

      <div className="seat-map-tip">
        当前查询：{data ? `${data.floorName} · ${data.date} · ${data.slotLabel || slotLabel(slot)}` : '-'}
        ；出于隐私保护，占用人仅展示姓名与账号，不展示手机号。
      </div>

      <ReleaseModal
        open={releaseOpen}
        title={releaseTarget ? `强制释放座位 ${releaseTarget.seatNo}` : '强制释放'}
        confirmLoading={releasing}
        onOk={handleRelease}
        onCancel={() => {
          setReleaseOpen(false);
          setReleaseTarget(null);
        }}
      />
    </div>
  );
}
