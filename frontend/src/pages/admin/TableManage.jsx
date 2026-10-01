import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Empty, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag,
  Typography, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { fetchAdminFloors } from '../../api/admin';
import {
  createTable, deleteTable, fetchTables, fetchZones, generateTableSeats, updateTable,
} from '../../api/space';
import { FLOOR_STATUS_META, ZONE_TYPE_META } from '../../constants';
import PageLoading from '../../components/PageLoading';

/**
 * 系统管理端·桌位管理。
 *
 * <p>桌位是组队预约的最小单位，所以「按桌生成」必须落在同一行的连续列上；
 * 若声明座位数与真实座位数不一致，表格会给出红标提示，避免组队的锁座数与实际脱节。
 *
 * @returns {JSX.Element} 桌位管理页
 */
export default function TableManage() {
  const [params, setParams] = useSearchParams();
  const [floors, setFloors] = useState([]);
  const [zones, setZones] = useState([]);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const floorId = params.get('floorId') ? Number(params.get('floorId')) : null;
  const zoneId = params.get('zoneId') ? Number(params.get('zoneId')) : null;

  useEffect(() => {
    fetchAdminFloors().then((list) => {
      setFloors(list || []);
      if (list && list.length && !floorId) {
        setParams({ floorId: String(list[0].floorId) });
      }
    }).catch(() => {});
    // 仅首帧取默认楼层，后续切换由 URL 参数驱动
  }, []);

  useEffect(() => {
    if (!floorId) {
      setZones([]);
      return;
    }
    fetchZones(floorId).then((list) => {
      setZones(list || []);
      const stillValid = (list || []).some((z) => z.zoneId === zoneId);
      if (!stillValid && list && list.length) {
        setParams({ floorId: String(floorId), zoneId: String(list[0].zoneId) });
      }
    }).catch(() => setZones([]));
  }, [floorId]);

  const load = useCallback(() => {
    if (!zoneId) {
      setRows([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    fetchTables(zoneId).then((list) => setRows(list || []))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  }, [zoneId]);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditing({});
    form.setFieldsValue({
      tableNo: `T${String(rows.length + 1).padStart(2, '0')}`,
      seatCount: 4,
      status: 'AVAILABLE',
      remark: '',
    });
  };

  const openEdit = (row) => {
    setEditing(row);
    form.setFieldsValue({
      tableNo: row.tableNo,
      seatCount: row.seatCount,
      status: row.status,
      remark: row.remark || '',
    });
  };

  const onSubmit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload = { zoneId, ...values };
      if (editing && editing.tableId) {
        await updateTable(editing.tableId, payload);
        message.success('桌位已更新');
      } else {
        await createTable(payload);
        message.success('桌位已创建，可继续按桌生成座位');
      }
      setEditing(null);
      load();
    } catch (e) {
      // 校验失败保留表单值；权限与冲突由拦截器提示
    } finally {
      setSaving(false);
    }
  };

  const onGenerate = async (row) => {
    try {
      const vo = await generateTableSeats(row.tableId, row.seatCount);
      message.success(`已生成 ${vo.seats.length} 个相邻座位：${vo.seats.map((s) => s.seatNo).join('、')}`);
      load();
    } catch (e) {
      // 「找不到连续空位」等原因由拦截器给出
    }
  };

  const onDelete = async (row) => {
    try {
      await deleteTable(row.tableId);
      message.success('桌位已删除');
      load();
    } catch (e) {
      // 仍挂座位时后端返回 4109
    }
  };

  const columns = [
    {
      title: '桌号',
      dataIndex: 'tableNo',
      width: 120,
      render: (v) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: '座位数（声明/实际）',
      dataIndex: 'seatCount',
      width: 180,
      render: (v, row) => (
        <Space>
          <span>{v} / {row.actualSeatCount}</span>
          {row.seatCountMatches
            ? <Tag color="green">一致</Tag>
            : <Tag color="red">不一致</Tag>}
        </Space>
      ),
    },
    {
      title: '座位清单',
      dataIndex: 'seats',
      render: (seats) => (seats && seats.length
        ? seats.map((s) => `${s.seatNo}(${s.rowNo},${s.colNo})`).join('　')
        : <Typography.Text type="secondary">尚未生成</Typography.Text>),
    },
    {
      title: '状态',
      dataIndex: 'statusText',
      width: 100,
      render: (v, row) => <Tag color={row.status === 'AVAILABLE' ? 'green' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作',
      key: 'ops',
      width: 230,
      render: (_, row) => (
        <Space>
          <Button size="small" icon={<ThunderboltOutlined />} onClick={() => onGenerate(row)}>
            生成座位
          </Button>
          <Button size="small" onClick={() => openEdit(row)}>编辑</Button>
          <Popconfirm title="仅空桌可删除" onConfirm={() => onDelete(row)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  if (loading && !rows.length) {
    return <PageLoading />;
  }

  return (
    <Card
      title="桌位管理"
      extra={(
        <Space>
          <Select
            value={floorId}
            style={{ width: 200 }}
            onChange={(v) => setParams({ floorId: String(v) })}
            options={floors.map((f) => ({
              value: f.floorId,
              label: `${f.name}（${FLOOR_STATUS_META[f.status]?.text || f.status}）`,
            }))}
          />
          <Select
            value={zoneId}
            style={{ width: 220 }}
            onChange={(v) => setParams({ floorId: String(floorId), zoneId: String(v) })}
            options={zones.map((z) => ({
              value: z.zoneId,
              label: `${z.name}（${ZONE_TYPE_META[z.zoneType]?.text || z.zoneType}）`,
            }))}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} disabled={!zoneId} onClick={openCreate}>
            新增桌位
          </Button>
        </Space>
      )}
    >
      <Alert
        style={{ marginBottom: 16 }}
        type="info"
        showIcon
        message="按桌生成的座位会落在同一行的连续列上"
        description="同桌相邻是组队整排锁座的前提；若当前楼层已无连续空位，生成会失败并给出原因，而不是把一桌拆散。"
      />
      {!zoneId
        ? <Empty description="该楼层还没有分区，请先在分区管理中创建" />
        : <Table rowKey="tableId" columns={columns} dataSource={rows} pagination={false} size="middle" />}

      <Modal
        open={!!editing}
        title={(editing && editing.tableId) ? `编辑桌位 ${editing.tableNo}` : '新增桌位'}
        onCancel={() => setEditing(null)}
        onOk={onSubmit}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space size="large" wrap>
            <Form.Item name="tableNo" label="桌号" rules={[{ required: true, message: '请填写桌号' }]}>
              <Input style={{ width: 140 }} maxLength={20} />
            </Form.Item>
            <Form.Item
              name="seatCount"
              label="座位数"
              rules={[{ required: true }]}
              extra="小于当前已挂座位数时会被拒绝"
            >
              <InputNumber min={2} max={12} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="status" label="状态">
              <Select
                style={{ width: 140 }}
                options={[
                  { value: 'AVAILABLE', label: '可用' },
                  { value: 'MAINTENANCE', label: '维护中' },
                  { value: 'DISABLED', label: '已停用' },
                ]}
              />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
