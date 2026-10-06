import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  DatePicker,
  Input,
  Select,
  Space,
  Table,
  Typography,
  message,
} from 'antd';
import dayjs from 'dayjs';
import { fetchAdminFloors, fetchAdminReservations, releaseReservation } from '../../api/admin';
import StatusTag from '../../components/StatusTag';
import ReleaseModal from '../../components/ReleaseModal';
import { RESERVATION_STATUS_META } from '../../constants';

const { RangePicker } = DatePicker;

/**
 * 管理员·预约记录：关键字 / 楼层 / 日期范围 / 状态筛选 + 分页 + 行内强制释放。
 *
 * @returns {JSX.Element} 预约记录页
 */
export default function ReservationList() {
  const [floors, setFloors] = useState([]);
  const [keyword, setKeyword] = useState('');
  const [floorId, setFloorId] = useState(undefined);
  const [range, setRange] = useState(null);
  const [status, setStatus] = useState(undefined);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [list, setList] = useState([]);
  const [total, setTotal] = useState(0);

  const [releaseOpen, setReleaseOpen] = useState(false);
  const [releaseTarget, setReleaseTarget] = useState(null);
  const [releasing, setReleasing] = useState(false);

  useEffect(() => {
    fetchAdminFloors()
      .then((l) => setFloors(Array.isArray(l) ? l : []))
      .catch(() => setFloors([]));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = { page, size };
      if (keyword) {
        params.keyword = keyword;
      }
      if (floorId) {
        params.floorId = floorId;
      }
      if (status) {
        params.status = status;
      }
      if (range && range[0]) {
        params.dateFrom = range[0].format('YYYY-MM-DD');
      }
      if (range && range[1]) {
        params.dateTo = range[1].format('YYYY-MM-DD');
      }
      const data = await fetchAdminReservations(params);
      setList(data && data.list ? data.list : []);
      setTotal(data ? data.total : 0);
    } catch (e) {
      setError(e.message || '加载预约记录失败');
      setList([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [keyword, floorId, status, range, page, size]);

  useEffect(() => {
    load();
  }, [load]);

  /** 打开释放弹窗。 */
  const openRelease = (row) => {
    setReleaseTarget(row);
    setReleaseOpen(true);
  };

  /** 提交释放。 */
  const handleRelease = async (reason) => {
    if (!releaseTarget) {
      return;
    }
    setReleasing(true);
    try {
      await releaseReservation(releaseTarget.reservationId, reason);
      message.success('已强制释放');
      setReleaseOpen(false);
      setReleaseTarget(null);
      await load();
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setReleasing(false);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'reservationId', width: 70 },
    {
      title: '用户',
      width: 150,
      render: (_, row) => `${row.realName || ''}（学号 ${row.studentNo || '—'} · ${row.username || ''}）`,
    },
    { title: '楼层', dataIndex: 'floorName', width: 130, render: (v) => v || '-' },
    { title: '座位', dataIndex: 'seatNo', width: 90 },
    { title: '日期', dataIndex: 'date', width: 110 },
    { title: '时段', dataIndex: 'timeRange', width: 120 },
    { title: '时长', dataIndex: 'durationHours', width: 70, render: (v) => `${v} 小时` },
    { title: '状态', dataIndex: 'status', width: 140, render: (v) => <StatusTag status={v} /> },
    { title: '签到时间', dataIndex: 'checkInTime', width: 160, render: (v) => v || '-' },
    { title: '创建时间', dataIndex: 'createTime', width: 160, render: (v) => v || '-' },
    {
      title: '操作',
      width: 110,
      fixed: 'right',
      render: (_, row) =>
        row.canRelease ? (
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
        预约记录
      </Typography.Title>

      <div className="toolbar">
        <Input.Search
          placeholder="账号 / 姓名 / 座位号"
          allowClear
          style={{ width: 220 }}
          onSearch={(v) => {
            setPage(1);
            setKeyword(v);
          }}
        />
        <Select
          placeholder="楼层"
          allowClear
          style={{ width: 160 }}
          value={floorId}
          onChange={(v) => {
            setPage(1);
            setFloorId(v);
          }}
          options={floors.map((f) => ({ value: f.floorId, label: `${f.name}（${f.code}）` }))}
        />
        <RangePicker
          value={range}
          onChange={(v) => {
            setPage(1);
            setRange(v);
          }}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 160 }}
          value={status}
          onChange={(v) => {
            setPage(1);
            setStatus(v);
          }}
          options={Object.keys(RESERVATION_STATUS_META).map((key) => ({
            value: key,
            label: RESERVATION_STATUS_META[key].text,
          }))}
        />
        <Button type="primary" onClick={load} loading={loading}>
          查询
        </Button>
        <span className="spacer" />
        <Space>
          <Typography.Text type="secondary">默认按创建时间倒序</Typography.Text>
        </Space>
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Table
        rowKey="reservationId"
        columns={columns}
        dataSource={list}
        loading={loading}
        size="small"
        scroll={{ x: 1400 }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p);
            setSize(s);
          },
        }}
      />

      <ReleaseModal
        open={releaseOpen}
        title={releaseTarget ? `强制释放 #${releaseTarget.reservationId}` : '强制释放'}
        confirmLoading={releasing}
        onOk={handleRelease}
        onCancel={() => {
          setReleaseOpen(false);
          setReleaseTarget(null);
        }}
      />

      <div className="seat-map-tip">
        提示：日期范围筛选使用 {dayjs().format('YYYY-MM-DD')} 格式的起止日期，为空则不限。
      </div>
    </div>
  );
}
