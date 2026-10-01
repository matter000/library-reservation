import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Input, InputNumber, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { fetchRules, updateRules } from '../../api/rule';
import PageLoading from '../../components/PageLoading';

const GROUP_TEXT = {
  OPEN: { text: '开放时间', color: 'blue' },
  BOOK: { text: '预约规则', color: 'green' },
  LEAVE: { text: '暂离规则', color: 'cyan' },
  CREDIT: { text: '信用与处罚', color: 'gold' },
  PEAK: { text: '高峰调控', color: 'red' },
  PATROL: { text: '巡查', color: 'purple' },
};

/**
 * 系统管理端·规则配置。
 *
 * <p>值类型决定编辑器：INT 用数字框、BOOL 用下拉、其余用文本；
 * 保存后由后端立即刷新内存快照，因此页面提示「无需重启即生效」。
 *
 * @returns {JSX.Element} 规则配置页
 */
export default function RuleManage() {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [rows, setRows] = useState([]);
  const [drafts, setDrafts] = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await fetchRules();
      setRows(list || []);
      setDrafts({});
    } catch (e) {
      // 错误提示由 request 拦截器统一弹出
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const draftKey = (row) => `${row.scope}|${row.scopeId}|${row.rkey}`;

  const onDraft = (row, value) => {
    setDrafts((prev) => ({ ...prev, [draftKey(row)]: value }));
  };

  const dirtyEntries = Object.entries(drafts).filter(([, v]) => v !== undefined && v !== null);

  const onSave = async () => {
    if (!dirtyEntries.length) {
      message.info('没有需要保存的修改');
      return;
    }
    const items = dirtyEntries.map(([key, value]) => {
      const [scope, scopeId, rkey] = key.split('|');
      return { scope, scopeId, rkey, value: String(value) };
    });
    setSaving(true);
    try {
      await updateRules(items);
      message.success(`已保存 ${items.length} 项规则，立即生效`);
      await load();
    } catch (e) {
      // 校验失败（如类型不符）由拦截器提示，保留草稿便于修正后重试
    } finally {
      setSaving(false);
    }
  };

  const renderEditor = (row) => {
    const disabled = row.editable === false;
    const current = drafts[draftKey(row)];
    const value = current === undefined ? row.rvalue : current;
    if (row.valueType === 'INT') {
      return (
        <InputNumber
          value={value === '' ? null : Number(value)}
          disabled={disabled}
          onChange={(v) => onDraft(row, v)}
          style={{ width: 140 }}
        />
      );
    }
    if (row.valueType === 'BOOL') {
      return (
        <Select
          value={String(value).toLowerCase()}
          disabled={disabled}
          onChange={(v) => onDraft(row, v)}
          style={{ width: 140 }}
          options={[{ value: 'true', label: '是' }, { value: 'false', label: '否' }]}
        />
      );
    }
    return (
      <Input
        value={value}
        disabled={disabled}
        onChange={(e) => onDraft(row, e.target.value)}
        style={{ width: 180 }}
      />
    );
  };

  const columns = [
    {
      title: '分组',
      dataIndex: 'rgroup',
      width: 110,
      render: (v) => {
        const meta = GROUP_TEXT[v] || { text: v, color: 'default' };
        return <Tag color={meta.color}>{meta.text}</Tag>;
      },
    },
    {
      title: '规则键',
      dataIndex: 'rkey',
      width: 240,
      render: (v) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: '说明',
      dataIndex: 'description',
      ellipsis: true,
      render: (v, row) => (
        <Space direction="vertical" size={0}>
          <span>{v || '--'}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            作用域：{row.scope}{row.scopeId ? ` / ${row.scopeId}` : ''} · 类型：{row.valueType}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '当前值',
      dataIndex: 'rvalue',
      width: 100,
      render: (v) => <Tag>{v}</Tag>,
    },
    {
      title: '修改为',
      key: 'editor',
      width: 200,
      render: (_, row) => renderEditor(row),
    },
    {
      title: '可改',
      dataIndex: 'editable',
      width: 70,
      render: (v) => (v === false ? <Tag color="default">锁定</Tag> : <Tag color="green">可改</Tag>),
    },
  ];

  if (loading) {
    return <PageLoading />;
  }

  return (
    <Card
      title="规则配置"
      extra={(
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={!dirtyEntries.length}
            onClick={onSave}
          >
            保存（{dirtyEntries.length}）
          </Button>
        </Space>
      )}
    >
      <Alert
        style={{ marginBottom: 16 }}
        type="info"
        showIcon
        message="保存后立即生效，无需重启"
        description="规则值由后端内存快照承载，写入即刷新；值类型不符会被拒绝且不落库。新增规则键需通过版本化迁移脚本引入。"
      />
      <Table
        rowKey={(row) => draftKey(row)}
        columns={columns}
        dataSource={rows}
        pagination={false}
        size="middle"
      />
    </Card>
  );
}
