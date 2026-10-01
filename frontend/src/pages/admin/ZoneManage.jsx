import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag,
  Typography, message,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { fetchAdminFloors } from '../../api/admin';
import { createZone, deleteZone, fetchZones, updateZone, updateZoneStatus } from '../../api/space';
import {
  DISCUSS_OPTIONS, FLOOR_STATUS_META, USER_ROLE_META, ZONE_TYPE_META, ZONE_TYPE_OPTIONS,
} from '../../constants';
import PageLoading from '../../components/PageLoading';

const ALL_ROLES = Object.keys(USER_ROLE_META);

const roleOptions = ALL_ROLES.map((value) => ({ value, label: USER_ROLE_META[value].text }));

/** 后端返回角色数组，这里兜住 null 与非数组的脏值。 */
const asRoles = (list) => (Array.isArray(list) ? list : []);

/**
 * 把分区的后端 VO 转换成表单初值。
 *
 * @param {object} zone 分区 VO
 * @returns {object} 表单值
 */
function toForm(zone) {
  return {
    name: zone.name,
    code: zone.code,
    zoneType: zone.zoneType,
    maxDurationMin: zone.maxDurationMin,
    dailyCapMin: zone.dailyCapMin ?? null,
    advanceDays: zone.advanceDays,
    allowDiscuss: zone.allowDiscuss,
    allowLeave: zone.leaveAllowed,
    leaveMaxMin: zone.leaveMaxMin,
    leaveDailyLimit: zone.leaveDailyLimit,
    allowGroup: zone.allowGroup,
    groupRequired: zone.groupRequired,
    visibleRoles: asRoles(zone.visibleRoles),
    eligibleRoles: asRoles(zone.eligibleRoles),
    sortOrder: zone.sortOrder,
    remark: zone.remark,
  };
}

/**
 * 系统管理端·分区管理。
 *
 * <p>分区是规则的唯一载体，因此这里改的就是「谁能约、能约多久、能不能暂离」。
 * 后端仅 SYS_ADMIN 与馆员可写（/api/staff/**），菜单入口只对这两类角色开放。
 *
 * @returns {JSX.Element} 分区管理页
 */
export default function ZoneManage() {
  const [floors, setFloors] = useState([]);
  const [floorId, setFloorId] = useState(null);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    fetchAdminFloors()
      .then((list) => {
        setFloors(list || []);
        setFloorId((list && list.length ? list[0].floorId : null));
      })
      .catch(() => {});
  }, []);

  const load = useCallback(() => {
    if (!floorId) {
      setRows([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    fetchZones(floorId)
      .then((list) => setRows(list || []))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  }, [floorId]);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditing({});
    form.setFieldsValue({
      name: '',
      code: '',
      zoneType: 'SILENT',
      maxDurationMin: 240,
      advanceDays: 3,
      allowDiscuss: 'NO',
      allowLeave: true,
      leaveMaxMin: 60,
      leaveDailyLimit: 3,
      allowGroup: false,
      groupRequired: false,
      visibleRoles: ALL_ROLES,
      eligibleRoles: ALL_ROLES,
      sortOrder: (rows.length + 1) * 10,
    });
  };

  const openEdit = (zone) => {
    setEditing(zone);
    form.setFieldsValue(toForm(zone));
  };

  const onSubmit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload = { ...values, floorId };
      if (editing && editing.zoneId) {
        await updateZone(editing.zoneId, payload);
        message.success('分区已更新，立即对新建预约生效');
      } else {
        await createZone(payload);
        message.success('分区已创建');
      }
      setEditing(null);
      load();
    } catch (e) {
      // 校验失败留在弹窗里便于修正；网络/权限错误由 request 拦截器提示
    } finally {
      setSaving(false);
    }
  };

  const onToggle = async (zone, open) => {
    try {
      await updateZoneStatus(zone.zoneId, open ? 'OPEN' : 'CLOSED');
      message.success(open ? '分区已开放' : '分区已关闭，本区座位转为不可用');
      load();
    } catch (e) {
      // 拦截器已提示
    }
  };

  const onDelete = async (zone) => {
    try {
      await deleteZone(zone.zoneId);
      message.success('分区已删除，其座位已归入兜底分区');
      load();
    } catch (e) {
      // 存在未完成预约时后端返回 4109，拦截器给出原因
    }
  };

  const columns = useMemo(() => [
    { title: '分区', dataIndex: 'name', width: 140 },
    {
      title: '编码',
      dataIndex: 'code',
      width: 130,
      render: (v) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: '类型',
      dataIndex: 'zoneType',
      width: 110,
      render: (v) => {
        const meta = ZONE_TYPE_META[v] || { text: v, color: 'default' };
        return <Tag color={meta.color}>{meta.text}</Tag>;
      },
    },
    {
      title: '座位（空闲/总数）',
      dataIndex: 'totalSeats',
      width: 150,
      render: (_, row) => `${row.freeSeats} / ${row.totalSeats}　占用率 ${row.usageRate}%`,
    },
    {
      title: '规则',
      key: 'rules',
      render: (_, row) => (
        <Space size={4} wrap>
          <Tag>{`≤${Math.round((row.maxDurationMin / 60) * 10) / 10}h`}</Tag>
          <Tag>{`提前 ${row.advanceDays} 天`}</Tag>
          <Tag color={row.leaveAllowed ? 'green' : 'default'}>
            {row.leaveAllowed ? `可暂离 ${row.leaveMaxMin}min×${row.leaveDailyLimit}` : '不可暂离'}
          </Tag>
          {row.groupRequired ? <Tag color="purple">仅整组</Tag> : null}
          {row.allowGroup && !row.groupRequired ? <Tag color="blue">可组队</Tag> : null}
        </Space>
      ),
    },
    {
      title: '可见 / 可约角色',
      key: 'roles',
      width: 200,
      render: (_, row) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {asRoles(row.visibleRoles).map((r) => USER_ROLE_META[r]?.text || r).join('、') || '-'}
          {' / '}
          {asRoles(row.eligibleRoles).map((r) => USER_ROLE_META[r]?.text || r).join('、') || '-'}
        </Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v, row) => (
        <Space>
          <Tag color={v === 'OPEN' ? 'green' : 'red'}>{row.statusText}</Tag>
          <Switch size="small" checked={v === 'OPEN'} onChange={(checked) => onToggle(row, checked)} />
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'ops',
      width: 130,
      render: (_, row) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)} />
          <Popconfirm title="删除后本区座位并入兜底分区" onConfirm={() => onDelete(row)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ], [rows, floorId]);

  if (loading && !rows.length) {
    return <PageLoading />;
  }

  return (
    <Card
      title="分区管理"
      extra={(
        <Space>
          <Select
            value={floorId}
            style={{ width: 220 }}
            onChange={setFloorId}
            options={floors.map((f) => ({
              value: f.floorId,
              label: `${f.name}（${FLOOR_STATUS_META[f.status]?.text || f.status}）`,
            }))}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增分区</Button>
        </Space>
      )}
    >
      <Table rowKey="zoneId" columns={columns} dataSource={rows} pagination={false} size="middle" />

      <Modal
        open={!!editing}
        title={(editing && editing.zoneId) ? `编辑分区 ${editing.code}` : '新增分区'}
        onCancel={() => setEditing(null)}
        onOk={onSubmit}
        confirmLoading={saving}
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space size="large" wrap>
            <Form.Item name="name" label="分区名称" rules={[{ required: true, message: '请填写名称' }]}>
              <Input style={{ width: 200 }} maxLength={50} />
            </Form.Item>
            <Form.Item
              name="code"
              label="楼层内编码"
              rules={[{ required: true, message: '请填写编码' }]}
              extra="同一楼层唯一，如 1F-SILENT"
            >
              <Input style={{ width: 160 }} maxLength={20} />
            </Form.Item>
            <Form.Item name="zoneType" label="类型" rules={[{ required: true }]}>
              <Select style={{ width: 140 }} options={ZONE_TYPE_OPTIONS} />
            </Form.Item>
          </Space>
          <Space size="large" wrap>
            <Form.Item name="maxDurationMin" label="单次上限(分钟)" rules={[{ required: true }]}>
              <InputNumber min={30} max={840} step={30} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="dailyCapMin" label="单日上限(分钟)" extra="留空表示不设分区级上限">
              <InputNumber min={30} max={840} step={30} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="advanceDays" label="可提前天数" rules={[{ required: true }]}>
              <InputNumber min={1} max={30} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="allowDiscuss" label="讨论音量" rules={[{ required: true }]}>
              <Select style={{ width: 130 }} options={DISCUSS_OPTIONS} />
            </Form.Item>
          </Space>
          <Space size="large" wrap>
            <Form.Item name="allowLeave" label="允许暂离" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="leaveMaxMin" label="暂离封顶(分钟)">
              <InputNumber min={0} max={240} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="leaveDailyLimit" label="每日暂离次数">
              <InputNumber min={0} max={20} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="allowGroup" label="支持组队" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="groupRequired" label="仅整组预约" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Space size="large" wrap>
            <Form.Item
              name="visibleRoles"
              label="可见角色"
              rules={[{ required: true, message: '至少选一个' }]}
              extra="未勾选的角色在用户端完全看不到本区"
            >
              <Select mode="multiple" style={{ width: 260 }} options={roleOptions} />
            </Form.Item>
            <Form.Item
              name="eligibleRoles"
              label="可约角色"
              rules={[{ required: true, message: '至少选一个' }]}
            >
              <Select mode="multiple" style={{ width: 260 }} options={roleOptions} />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序号">
              <InputNumber min={0} max={9999} style={{ width: 110 }} />
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
