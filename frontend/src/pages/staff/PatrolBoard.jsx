import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Spin, Switch, Table, Tag,
  Typography, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { fetchPatrolActions, fetchPatrolTasks, handlePatrol, registerPatrol } from '../../api/patrol';
import { fetchFloors } from '../../api/floor';
import { PATROL_BOARD_POLL_INTERVAL, PATROL_LEVEL_META, PATROL_STATUS_META } from '../../constants';
import EvidenceBlock from '../../components/EvidenceBlock';
import HintedButton from '../../components/HintedButton';

/**
 * 巡查看板（DESIGN §11，T-V2-21）。
 *
 * <p>馆员在楼层里走动，看板要回答的只有三件事：<b>哪张椅子、为什么报警、我现在能做什么</b>。
 * 所以列顺序是「等级 → 座位 → 使用人 → 依据 → 动作」，依据一行显示不下就展开看结构化证据。
 *
 * <p>★ 处置动作的名称与可点状态都来自服务端（{@code /staff/patrol/actions} 与 {@code canXxx}）：
 * 「没有预约就不能释放」这类事实由后端判定，前端写死一份名字迟早和枚举漂开。
 *
 * <p>30 秒轮询、弹窗打开时暂停 —— 与平面图同一套做法；处置中的任务被轮询刷掉是最难查的投诉来源。
 *
 * @returns {JSX.Element} 巡查看板
 */
export default function PatrolBoard() {
  const [form] = Form.useForm();
  const [rows, setRows] = useState([]);
  const [floors, setFloors] = useState([]);
  const [actions, setActions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [openOnly, setOpenOnly] = useState(true);
  const [floorId, setFloorId] = useState(null);
  const [level, setLevel] = useState(null);
  const [current, setCurrent] = useState(null);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const modalOpen = Boolean(current) || registerOpen;
  const inFlightRef = useRef(false);

  const load = useCallback(async (silent = false) => {
    if (!silent) {
      setLoading(true);
    }
    setError('');
    try {
      const list = await fetchPatrolTasks({
        openOnly,
        floorId: floorId || undefined,
        level: level || undefined,
      });
      setRows(list || []);
    } catch (e) {
      setError(e.message || '巡查任务加载失败');
    } finally {
      setLoading(false);
    }
  }, [openOnly, floorId, level]);

  useEffect(() => {
    load();
  }, [load]);

  /** 下拉项一次拉够就行：动作字典与楼层列表都不随轮询变化。 */
  useEffect(() => {
    fetchFloors().then((list) => setFloors(list || [])).catch(() => setFloors([]));
    fetchPatrolActions().then((list) => setActions(list || [])).catch(() => setActions([]));
  }, []);

  useEffect(() => {
    const timer = setInterval(() => {
      if (document.hidden || modalOpen || inFlightRef.current) {
        return;
      }
      inFlightRef.current = true;
      load(true).finally(() => {
        inFlightRef.current = false;
      });
    }, PATROL_BOARD_POLL_INTERVAL);
    return () => clearInterval(timer);
  }, [load, modalOpen]);

  const floorOptions = useMemo(
    () => floors.map((f) => ({ value: f.floorId, label: f.name })),
    [floors],
  );
  const levelOptions = useMemo(() => (
    [{ value: 'HIGH', label: '仅高' }, { value: 'MID', label: '仅中' }, { value: 'LOW', label: '仅低' }]
  ), []);

  /**
   * 处置弹窗的动作下拉：文案来自服务端字典，可用性来自这一行的 canXxx。
   *
   * ★ 必须 useMemo —— Select 每次拿到新 options 数组都会重算尺寸，配合弹窗就是死循环。
   */
  const actionOptions = useMemo(() => (
    actions.map((item) => ({
      value: item.action,
      label: item.text,
      // 没有预约的行不能释放、也不能裁定违规：动作名由服务端给，可用性也由服务端给
      disabled: (item.action === 'RELEASE' && !current?.canRelease)
        || (item.action === 'VIOLATION' && !current?.canViolate),
    }))
  ), [actions, current]);

  /** 处置成功后就地替换这一行，不等下一轮轮询（馆员要点着连着处理好几张）。 */
  const replaceRow = (next) => setRows((cur) => cur.map((row) => (row.patrolId === next.patrolId ? next : row)));

  const submitHandle = async () => {
    const values = await form.validateFields();
    setBusy(true);
    try {
      const next = await handlePatrol(current.patrolId, values);
      message.success(`已处置：${next.statusText}`);
      replaceRow(next);
      setCurrent(null);
      form.resetFields();
    } catch (e) {
      /* 提示由拦截器弹出，弹窗保留让馆员改完再交 */
    } finally {
      setBusy(false);
    }
  };

  const submitRegister = async () => {
    const values = await form.validateFields();
    setBusy(true);
    try {
      const created = await registerPatrol(values);
      message.success(`已登记疑点：${created.seatNo}`);
      setRegisterOpen(false);
      form.resetFields();
      await load(true);
    } catch (e) {
      /* 同上 */
    } finally {
      setBusy(false);
    }
  };

  const columns = useMemo(() => ([
    {
      title: '等级',
      dataIndex: 'level',
      width: 76,
      render: (value, row) => <Tag color={(PATROL_LEVEL_META[value] || {}).color}>{row.levelText}</Tag>,
    },
    { title: '座位', dataIndex: 'seatNo', width: 96 },
    {
      title: '疑点',
      dataIndex: 'typeText',
      width: 170,
      render: (value, row) => (
        <Space direction="vertical" size={2}>
          <span>{value}</span>
          <Tag color={(PATROL_STATUS_META[row.status] || {}).color}>{row.statusText}</Tag>
        </Space>
      ),
    },
    {
      title: '使用人',
      dataIndex: 'userName',
      width: 110,
      // 无预约占座时后端不给身份：馆员现场看到的人是谁，由馆员登记，而不是由系统猜
      render: (value) => value || <Typography.Text type="secondary">无预约</Typography.Text>,
    },
    { title: '依据', dataIndex: 'reason', ellipsis: true },
    { title: '生成时间', dataIndex: 'createTime', width: 160 },
    {
      title: '处置',
      key: 'handle',
      width: 120,
      render: (_, row) => (row.canHandle ? (
        <Button size="small" type="primary" onClick={() => { setCurrent(row); form.resetFields(); }}>
          去处置
        </Button>
      ) : (
        <HintedButton tip={row.handleDisabledReason}>
          <Button size="small" disabled>已处置</Button>
        </HintedButton>
      )),
    },
  ]), [form]);

  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>
          <WarningOutlined /> 巡查看板
        </Typography.Title>
        <Space wrap>
          <span>楼层：</span>
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部"
            value={floorId}
            onChange={setFloorId}
            options={floorOptions}
          />
          <span>等级：</span>
          <Select
            allowClear
            style={{ width: 110 }}
            placeholder="全部"
            value={level}
            onChange={setLevel}
            options={levelOptions}
          />
          <Switch checked={openOnly} onChange={setOpenOnly} checkedChildren="未处理" unCheckedChildren="全部" />
          <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setRegisterOpen(true); form.resetFields(); }}>
            现场登记
          </Button>
        </Space>
      </Space>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="机器只登记疑点，不会自动释放任何座位：释放、裁定违规都由馆员当场决定并留下说明。"
      />
      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

      <Spin spinning={loading && rows.length === 0}>
        <Table
          rowKey="patrolId"
          size="small"
          columns={columns}
          dataSource={rows}
          pagination={{ pageSize: 15, showTotal: (total) => `共 ${total} 条` }}
          expandable={{
            expandedRowRender: (row) => (
              <>
                <EvidenceBlock items={row.evidenceItems} />
                {row.handleNote ? (
                  <Typography.Paragraph style={{ marginBottom: 0 }}>
                    处置说明：{row.handleNote}
                    {row.photoUrl ? ` · 照片 ${row.photoUrl}` : ''}
                  </Typography.Paragraph>
                ) : null}
              </>
            ),
            rowExpandable: (row) => (row.evidenceItems || []).length > 0 || Boolean(row.handleNote),
          }}
        />
      </Spin>

      <Modal
        title={current ? `处置：${current.seatNo}（${current.typeText}）` : ''}
        open={Boolean(current)}
        onCancel={() => setCurrent(null)}
        onOk={submitHandle}
        okText="提交处置"
        confirmLoading={busy}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary">
          {current ? `${current.reason}｜证据可在看板行展开后核对，处置一经提交不可重复。` : ''}
        </Typography.Paragraph>
        <Form form={form} layout="vertical">
          <Form.Item name="action" label="处置动作" rules={[{ required: true, message: '请选择动作' }]}>
            <Select options={actionOptions} placeholder="请选择" />
          </Form.Item>
          <Form.Item
            name="note"
            label="处置说明"
            rules={[{ required: true, message: '必须写明当时情况与依据' }]}
          >
            <Input.TextArea rows={3} maxLength={200} showCount placeholder="如：现场核对本人在座且已口头提醒" />
          </Form.Item>
          <Form.Item name="photoUrl" label="现场照片链接（可空）">
            <Input maxLength={200} placeholder="https://..." />
          </Form.Item>
        </Form>
        {/* 下拉里被置灰的动作必须同时给原因：馆员站在椅子前，没时间猜哪个动作为什么不能点 */}
        {current && !current.canRelease ? (
          <Alert type="warning" showIcon message={`不能「强制释放」：${current.releaseDisabledReason}`} />
        ) : null}
        {current && !current.canViolate ? (
          <Alert
            style={{ marginTop: 8 }}
            type="warning"
            showIcon
            message={`不能「裁定违规」：${current.violateDisabledReason}`}
          />
        ) : null}
      </Modal>

      <Modal
        title="现场登记疑点"
        open={registerOpen}
        onCancel={() => setRegisterOpen(false)}
        onOk={submitRegister}
        okText="登记"
        confirmLoading={busy}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          {/* ★ 必须用 InputNumber：本项目的 Jackson 关掉了字符串到数字的隐式转换，
              <Input type="number"> 交上去的是 "2"，会被后端判成 4001「参数类型错误」。 */}
          <Form.Item name="seatId" label="座位 ID" rules={[{ required: true, message: '请填写座位 ID' }]}>
            <InputNumber style={{ width: '100%' }} min={1} placeholder="可在实时座位页查看" />
          </Form.Item>
          <Form.Item name="reservationId" label="关联预约 ID（无预约占用可留空）">
            <InputNumber style={{ width: '100%' }} min={1} />
          </Form.Item>
          <Form.Item name="level" label="等级">
            <Select options={levelOptions} placeholder="默认中" allowClear />
          </Form.Item>
          <Form.Item name="note" label="现场情况" rules={[{ required: true, message: '必须写明现场情况' }]}>
            <Input.TextArea rows={3} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
