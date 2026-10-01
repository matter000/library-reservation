import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Breadcrumb,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  createSeat,
  deleteSeat,
  fetchAdminSeats,
  updateSeat,
  updateSeatStatus,
} from '../../api/admin';
import { ERROR_CODE, SEAT_BASE_STATUS_META, SEAT_STATUS, SEAT_STATUS_META } from '../../constants';
import { fetchZones } from '../../api/space';
import SeatBatchModal from './SeatBatchModal';

/**
 * 管理员·座位管理：分页列表 + 新增/编辑/删除 + 状态切换 + 批量生成。
 *
 * @returns {JSX.Element} 座位管理页
 */
export default function SeatManage() {
  const { floorId } = useParams();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(false);
  const [list, setList] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [seatNo, setSeatNo] = useState('');
  const [status, setStatus] = useState(undefined);
  const [error, setError] = useState('');

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [batchOpen, setBatchOpen] = useState(false);
  const [zones, setZones] = useState([]);
  const [form] = Form.useForm();

  useEffect(() => {
    if (floorId) {
      fetchZones(floorId).then((list) => setZones(list || [])).catch(() => setZones([]));
    }
  }, [floorId]);

  const load = useCallback(async () => {
    if (!floorId) {
      return;
    }
    setLoading(true);
    setError('');
    try {
      const data = await fetchAdminSeats({ floorId, page, size, seatNo, status });
      setList(data && data.list ? data.list : []);
      setTotal(data ? data.total : 0);
    } catch (e) {
      setError(e.message || '加载座位失败');
      setList([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [floorId, page, size, seatNo, status]);

  useEffect(() => {
    load();
  }, [load]);

  /** 打开新增。 */
  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ floorId: Number(floorId), status: SEAT_STATUS.AVAILABLE, rowNo: 1, colNo: 1 });
    setModalOpen(true);
  };

  /** 打开编辑。 */
  const openEdit = (row) => {
    setEditing(row);
    form.setFieldsValue({
      floorId: row.floorId,
      seatNo: row.seatNo,
      rowNo: row.rowNo,
      colNo: row.colNo,
      zone: row.zone,
      status: row.status,
      remark: row.remark,
    });
    setModalOpen(true);
  };

  /** 提交。 */
  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await updateSeat(editing.seatId, values);
        message.success('座位已更新');
      } else {
        await createSeat(values);
        message.success('座位已创建');
      }
      setModalOpen(false);
      form.resetFields();
      await load();
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setSubmitting(false);
    }
  };

  /** 删除（二次确认）。 */
  const handleDelete = (row) => {
    Modal.confirm({
      title: `确认删除座位「${row.seatNo}」？`,
      content: '删除后该座位的历史预约记录也会一并清理。若存在未来有效预约将无法删除。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSeat(row.seatId);
          message.success('座位已删除');
          await load();
        } catch (e) {
          if (e && e.code === ERROR_CODE.DELETE_FORBIDDEN) {
            message.error('存在未来预约，无法删除，请先释放相关预约');
          }
        }
      },
    });
  };

  /** 行内状态切换。 */
  const handleStatus = async (row, nextStatus) => {
    try {
      await updateSeatStatus(row.seatId, nextStatus);
      message.success(`座位 ${row.seatNo} 已设为${SEAT_BASE_STATUS_META[nextStatus].text}`);
      await load();
    } catch (e) {
      // 提示由拦截器弹出
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'seatId', width: 70 },
    { title: '座位编号', dataIndex: 'seatNo', width: 120 },
    { title: '区域', dataIndex: 'zone', width: 100, render: (v) => v || '-' },
    { title: '行', dataIndex: 'rowNo', width: 60 },
    { title: '列', dataIndex: 'colNo', width: 60 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v) => <Tag color={SEAT_BASE_STATUS_META[v]?.color}>{SEAT_BASE_STATUS_META[v]?.text || v}</Tag>,
    },
    {
      title: '未来预约',
      dataIndex: 'hasFutureReservation',
      width: 100,
      render: (v) => (v ? <Tag color="orange">有</Tag> : <Tag>无</Tag>),
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (v) => v || '-' },
    {
      title: '操作',
      width: 260,
      render: (_, row) => (
        <Space size="small">
          <Button size="small" onClick={() => openEdit(row)}>
            编辑
          </Button>
          <Select
            size="small"
            value={row.status}
            style={{ width: 100 }}
            onChange={(v) => handleStatus(row, v)}
            options={Object.keys(SEAT_BASE_STATUS_META).map((key) => ({
              value: key,
              label: SEAT_BASE_STATUS_META[key].text,
            }))}
          />
          <Button size="small" danger onClick={() => handleDelete(row)}>
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Breadcrumb
        style={{ marginBottom: 12 }}
        items={[{ title: <Link to="/admin/floors">楼层管理</Link> }, { title: '座位管理' }]}
      />

      <div className="toolbar">
        <Typography.Title level={4} style={{ margin: 0 }}>
          座位管理
        </Typography.Title>
        <span className="spacer" />
        <Button onClick={() => navigate(`/floors/${floorId}/seats`)}>预览平面图</Button>
        <Button type="primary" icon={<ThunderboltOutlined />} onClick={() => setBatchOpen(true)}>
          批量生成
        </Button>
        <Button type="primary" onClick={openCreate}>
          新增座位
        </Button>
      </div>

      <div className="toolbar">
        <Input.Search
          placeholder="座位编号关键字"
          allowClear
          style={{ width: 200 }}
          onSearch={(v) => {
            setPage(1);
            setSeatNo(v);
          }}
        />
        <Select
          placeholder="状态筛选"
          allowClear
          style={{ width: 140 }}
          value={status}
          onChange={(v) => {
            setPage(1);
            setStatus(v);
          }}
          options={Object.keys(SEAT_BASE_STATUS_META).map((key) => ({
            value: key,
            label: SEAT_BASE_STATUS_META[key].text,
          }))}
        />
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Table
        rowKey="seatId"
        columns={columns}
        dataSource={list}
        loading={loading}
        size="small"
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 个座位`,
          onChange: (p, s) => {
            setPage(p);
            setSize(s);
          },
        }}
      />

      <Modal
        open={modalOpen}
        title={editing ? '编辑座位' : '新增座位'}
        okText="保存"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item label="楼层 ID" name="floorId" hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item label="座位编号" name="seatNo" rules={[{ required: true, message: '请输入座位编号' }]}>
            <Input placeholder="如 A12" maxLength={20} />
          </Form.Item>
          <Form.Item label="行号" name="rowNo" rules={[{ required: true, message: '请输入行号' }]}>
            <InputNumber min={1} max={999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="列号" name="colNo" rules={[{ required: true, message: '请输入列号' }]}>
            <InputNumber min={1} max={999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="区域" name="zone">
            <Input placeholder="选填" maxLength={20} />
          </Form.Item>
          <Form.Item label="状态" name="status">
            <Radio.Group
              options={Object.keys(SEAT_BASE_STATUS_META).map((key) => ({
                label: SEAT_BASE_STATUS_META[key].text,
                value: key,
              }))}
            />
          </Form.Item>
          <Form.Item label="备注" name="remark">
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <SeatBatchModal
        open={batchOpen}
        floorId={Number(floorId)}
        zones={zones}
        onOk={() => load()}
        onCancel={() => setBatchOpen(false)}
      />

      <div className="seat-map-tip">
        提示：座位状态设为「维护中」或「已停用」后，用户端平面图该座位会立即显示为
        {SEAT_STATUS_META.UNAVAILABLE.text}（灰色斜线）。
      </div>
    </div>
  );
}
