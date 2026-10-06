import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, DatePicker, Form, Input, InputNumber, Modal, Popconfirm, Row, Select,
  Space, Switch, Table, Tag, Typography, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  deleteCalendar, fetchCalendar, fetchDayRule, fetchProfiles, saveCalendar, saveProfile,
} from '../../api/openRule';
import { DAY_TYPE_OPTIONS, SLOTS_PER_HOUR } from '../../constants';
import PageLoading from '../../components/PageLoading';

const slotTextOf = (slot) => {
  if (slot === null || slot === undefined) {
    return '-';
  }
  const minutes = slot * 30;
  return `${String(Math.floor(minutes / 60) % 24).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
};

/** 小时输入 → 时段格数。 */
const hoursToSlot = (hours) => Math.round(hours * SLOTS_PER_HOUR);

/**
 * 系统管理端·开放时间。
 *
 * <p>档案管「某一日型什么时候开门」，日历管「某一天具体怎样」。
 * 页面右侧的生效自检直接问后端「这天最终按哪条走」，避免管理员靠猜。
 *
 * @returns {JSX.Element} 开放时间页
 */
export default function OpenRuleManage() {
  const [profiles, setProfiles] = useState([]);
  const [calendar, setCalendar] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editingProfile, setEditingProfile] = useState(null);
  const [editingDay, setEditingDay] = useState(null);
  const [saving, setSaving] = useState(false);
  const [probeDate, setProbeDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [probe, setProbe] = useState(null);
  const [profileForm] = Form.useForm();
  const [dayForm] = Form.useForm();

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([fetchProfiles(), fetchCalendar({})])
      .then(([p, c]) => {
        setProfiles(p || []);
        setCalendar(c || []);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const probeRun = useCallback((date) => {
    if (!date) {
      setProbe(null);
      return;
    }
    fetchDayRule(date).then(setProbe).catch(() => setProbe(null));
  }, []);

  useEffect(() => {
    probeRun(probeDate);
  }, [probeDate, probeRun, profiles, calendar]);

  const openProfileEdit = (row) => {
    setEditingProfile(row || {});
    profileForm.setFieldsValue(row
      ? {
        dayType: row.dayType,
        name: row.name,
        openStartHours: row.openStart / SLOTS_PER_HOUR,
        openEndHours: row.openEnd / SLOTS_PER_HOUR,
        closed: row.closed,
        enabled: row.enabled,
        remark: row.remark,
      }
      : { dayType: 'WORKDAY', name: '', openStartHours: 8, openEndHours: 22, closed: false, enabled: true });
  };

  const submitProfile = async () => {
    const v = await profileForm.validateFields();
    setSaving(true);
    try {
      await saveProfile({
        dayType: v.dayType,
        name: v.name,
        openStart: hoursToSlot(v.openStartHours),
        openEnd: hoursToSlot(v.openEndHours),
        closed: !!v.closed,
        enabled: v.enabled !== false,
        remark: v.remark,
      });
      message.success('开放时间已保存，立即对新预约生效');
      setEditingProfile(null);
      load();
    } catch (e) {
      // 拦截器已提示（如起止非法）
    } finally {
      setSaving(false);
    }
  };

  const openDayEdit = () => {
    setEditingDay({});
    dayForm.setFieldsValue({
      date: dayjs().add(7, 'day'), closed: true, dayType: undefined, remark: '设备检修，全天闭馆',
    });
  };

  const submitDay = async () => {
    const v = await dayForm.validateFields();
    setSaving(true);
    try {
      await saveCalendar({
        date: v.date.format('YYYY-MM-DD'),
        dayType: v.dayType || null,
        openStart: v.openStartHours == null ? null : hoursToSlot(v.openStartHours),
        openEnd: v.openEndHours == null ? null : hoursToSlot(v.openEndHours),
        closed: !!v.closed,
        remark: v.remark,
      });
      message.success('日历已保存');
      setEditingDay(null);
      load();
    } catch (e) {
      // 拦截器已提示
    } finally {
      setSaving(false);
    }
  };

  const removeDay = async (row) => {
    try {
      await deleteCalendar(row.id);
      message.success('已删除覆盖，该日回到日型档案');
      load();
    } catch (e) {
      // 拦截器已提示
    }
  };

  const profileColumns = [
    {
      title: '日型',
      dataIndex: 'dayTypeText',
      width: 100,
      render: (v, row) => <Tag color={row.closed ? 'red' : 'blue'}>{v}</Tag>,
    },
    { title: '档案', dataIndex: 'name' },
    {
      title: '开放窗口',
      key: 'window',
      width: 190,
      render: (_, row) => (row.closed
        ? <Typography.Text type="danger">全天闭馆</Typography.Text>
        : `${row.startTime} - ${row.endTime}（${row.slotCount} 格 / 30 分钟）`),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (v) => (v ? <Tag color="green">是</Tag> : <Tag>否</Tag>),
    },
    {
      title: '操作',
      key: 'ops',
      width: 90,
      render: (_, row) => <Button size="small" onClick={() => openProfileEdit(row)}>编辑</Button>,
    },
  ];

  const calendarColumns = [
    { title: '日期', dataIndex: 'date', width: 120 },
    { title: '星期', dataIndex: 'weekdayText', width: 70 },
    {
      title: '覆盖',
      key: 'override',
      render: (_, row) => {
        const parts = [];
        if (row.dayType) {
          parts.push(`日型 ${row.dayType}`);
        }
        if (row.openStart != null || row.openEnd != null) {
          parts.push(`${slotTextOf(row.openStart)} - ${slotTextOf(row.openEnd)}`);
        }
        return parts.length ? parts.join('　') : <Typography.Text type="secondary">仅状态</Typography.Text>;
      },
    },
    {
      title: '闭馆',
      dataIndex: 'closed',
      width: 80,
      render: (v) => (v ? <Tag color="red">闭馆</Tag> : <Tag color="green">开放</Tag>),
    },
    { title: '备注', dataIndex: 'remark', width: 180, render: (v) => v || '-' },
    {
      title: '操作',
      key: 'ops',
      width: 90,
      render: (_, row) => (
        <Popconfirm title="删除后该日回到日型档案" onConfirm={() => removeDay(row)}>
          <Button size="small" danger>删除</Button>
        </Popconfirm>
      ),
    },
  ];

  if (loading && !profiles.length) {
    return <PageLoading />;
  }

  return (
    <Row gutter={16}>
      <Col span={16}>
        <Card
          title="开放档案（按日型）"
          extra={(
            <Space>
              <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openProfileEdit(null)}>
                新增/覆盖档案
              </Button>
            </Space>
          )}
          style={{ marginBottom: 16 }}
        >
          <Alert
            style={{ marginBottom: 12 }}
            type="info"
            showIcon
            message="时段边界以 30 分钟为一格"
            description="格长 30 分钟是内部精度；每档窗口的起止由本页随时可改，V23 起默认值是 PRD §12-1 的工作日 07:30–22:30 / 周末 08:00–22:00 / 考试周 07:00–23:00。"
          />
          <Table rowKey="id" columns={profileColumns} dataSource={profiles} pagination={false} size="middle" />
        </Card>

        <Card
          title="开放日历（单日覆盖）"
          extra={(
            <Space>
              <Button icon={<PlusOutlined />} onClick={openDayEdit}>新增覆盖</Button>
            </Space>
          )}
        >
          <Table rowKey="id" columns={calendarColumns} dataSource={calendar} pagination={{ pageSize: 8 }} size="middle" />
        </Card>
      </Col>

      <Col span={8}>
        <Card title="生效自检：某一天到底按哪条走">
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <DatePicker
              value={probeDate ? dayjs(probeDate) : null}
              onChange={(d) => setProbeDate(d ? d.format('YYYY-MM-DD') : null)}
              allowClear={false}
              style={{ width: '100%' }}
            />
            {probe ? (
              <Space direction="vertical" size={4}>
                <Typography.Text>
                  日型：<Tag>{probe.dayType}</Tag>
                  {probe.closed ? <Tag color="red">闭馆</Tag> : <Tag color="green">{probe.startTime} - {probe.endTime}</Tag>}
                </Typography.Text>
                <Typography.Text type="secondary">
                  可约格数：{probe.slotCount}（每格 30 分钟）
                </Typography.Text>
                <Typography.Text type="secondary">来源：{probe.source}</Typography.Text>
                {probe.peaks?.length ? (
                  <Typography.Text type="secondary">高峰窗口：{probe.peaks.join('、')}</Typography.Text>
                ) : null}
              </Space>
            ) : <Typography.Text type="warning">未取到生效规则</Typography.Text>}
            <Button icon={<ThunderboltOutlined />} onClick={() => probeRun(probeDate)}>重新查询</Button>
          </Space>
        </Card>
      </Col>

      <Modal
        open={!!editingProfile}
        title="编辑开放档案"
        onCancel={() => setEditingProfile(null)}
        onOk={submitProfile}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={profileForm} layout="vertical">
          <Form.Item name="dayType" label="日型" rules={[{ required: true }]}>
            <Select options={DAY_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
            <Input maxLength={50} />
          </Form.Item>
          <Space size="large">
            <Form.Item name="openStartHours" label="开门（小时）" rules={[{ required: true }]}>
              <InputNumber min={0} max={23.5} step={0.5} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item
              name="openEndHours"
              label="关门（小时）"
              rules={[{ required: true }]}
              extra="支持 21.5 表示 21:30"
            >
              <InputNumber min={0.5} max={24} step={0.5} style={{ width: 130 }} />
            </Form.Item>
          </Space>
          <Space size="large">
            <Form.Item name="closed" label="全天闭馆" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={!!editingDay}
        title="新增单日覆盖"
        onCancel={() => setEditingDay(null)}
        onOk={submitDay}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={dayForm} layout="vertical">
          <Form.Item name="date" label="日期" rules={[{ required: true, message: '请选择日期' }]}>
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dayType" label="覆盖日型" extra="留空表示沿用星期推断">
            <Select allowClear options={DAY_TYPE_OPTIONS} />
          </Form.Item>
          <Space size="large">
            <Form.Item name="openStartHours" label="开门（小时）" extra="留空沿用档案">
              <InputNumber min={0} max={23.5} step={0.5} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="openEndHours" label="关门（小时）" extra="留空沿用档案">
              <InputNumber min={0.5} max={24} step={0.5} style={{ width: 130 }} />
            </Form.Item>
          </Space>
          <Form.Item name="closed" label="当天闭馆" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Row>
  );
}
