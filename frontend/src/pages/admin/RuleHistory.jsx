import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Card, Select, Space, Table, Tag, Typography } from 'antd';
import { fetchRuleHistory, fetchRules } from '../../api/rule';

/** 分组颜色与规则配置页保持一致（同一份语义不该在两个页面里长成两种样子）。 */
const GROUP_COLOR = {
  OPEN: 'blue',
  BOOK: 'green',
  LEAVE: 'cyan',
  CREDIT: 'gold',
  PEAK: 'red',
  PATROL: 'purple',
};

/**
 * 系统管理端·规则变更历史（T-V2-22）。
 *
 * <p>★ 每一行显示的是当时审计里的 `oldValue → newValue`，不是「当前值 + 猜的旧值」：
 * 同一条规则改过三次，第二条必须还能看出它是 30→20，而不是 25→25。
 * 这页存在的意义就是让「谁把高峰闸门调开了」事后问得出。
 *
 * @returns {JSX.Element} 变更历史页
 */
export default function RuleHistory() {
  const [rows, setRows] = useState([]);
  const [keys, setKeys] = useState([]);
  const [rkey, setRkey] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(await fetchRuleHistory(rkey || undefined));
    } catch (e) {
      setError(e.message || '变更历史加载失败');
    } finally {
      setLoading(false);
    }
  }, [rkey]);

  useEffect(() => {
    load();
  }, [load]);

  /** 键下拉一次拉够：它不随筛选变化，每次进页面重新取即可。 */
  useEffect(() => {
    fetchRules()
      .then((list) => setKeys((list || []).map((item) => item.rkey)))
      .catch(() => setKeys([]));
  }, []);

  const keyOptions = useMemo(() => (
    Array.from(new Set(keys)).sort().map((value) => ({ value, label: value }))
  ), [keys]);

  const columns = useMemo(() => ([
    { title: '变更时刻', dataIndex: 'createTime', width: 170 },
    { title: '规则键', dataIndex: 'rkey', width: 200 },
    {
      title: '说明',
      dataIndex: 'description',
      ellipsis: true,
      // 规则被删除后审计仍在：这一行要照样列出来，只是认不出它是哪条规则了
      render: (value, row) => (
        <Space direction="vertical" size={0}>
          <span>{value || '（该规则已不在配置表中）'}</span>
          {row.rgroup ? <Tag color={GROUP_COLOR[row.rgroup] || 'default'}>{row.rgroup}</Tag> : null}
        </Space>
      ),
    },
    {
      title: '改动',
      key: 'change',
      width: 240,
      render: (_, row) => (
        <Space size={6}>
          <Typography.Text delete type="secondary">{row.oldValue ?? '（空）'}</Typography.Text>
          <span>→</span>
          <Typography.Text strong>{row.newValue ?? '（空）'}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '操作人',
      dataIndex: 'operatorName',
      width: 160,
      render: (value, row) => (
        <Space direction="vertical" size={0}>
          <span>{value || `用户 ${row.operatorId ?? '未知'}`}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.operatorRole || ''}</Typography.Text>
        </Space>
      ),
    },
  ]), []);

  return (
    <div style={{ padding: 16 }}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="这里只列改过值的规则：改一条规则不需要重启，所以事后必须能查到当时改了什么、谁改的。"
      />
      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

      <Card
        title="规则变更历史"
        extra={(
          <Select
            allowClear
            showSearch
            style={{ width: 260 }}
            placeholder="按规则键筛选"
            value={rkey}
            options={keyOptions}
            onChange={(value) => setRkey(value || null)}
          />
        )}
      >
        <Table
          rowKey="logId"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={rows}
          pagination={{ pageSize: 20, showTotal: (total) => `共 ${total} 条` }}
        />
      </Card>
    </div>
  );
}
