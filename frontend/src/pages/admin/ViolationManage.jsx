import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Input, Modal, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import {
  fetchConsistency, fetchStaffViolations, revokeViolation,
} from '../../api/credit';
import EvidenceBlock from '../../components/EvidenceBlock';
import HintedButton from '../../components/HintedButton';
import {
  APPEAL_STATUS_META, VIOLATION_STATUS_META, VIOLATION_STATUS_OPTIONS, VIOLATION_TYPE_META,
} from '../../constants';

/** 撤销理由字数上限（与后端 RevokeRequest 的 @Size 一致）。 */
const REASON_MAX = 200;

/**
 * 违规管理与账本巡检（V2 S3 / DESIGN §12.2 F8）。
 *
 * 撤销是「错过 24 小时申诉窗口」之后的唯一救济入口，因此理由必填、动作进审计。
 * 能不能撤销同样由后端逐条给出（正在申诉流程里的违规必须回申诉队列处理），
 * 页面不复刻这套判定 —— 两个入口都能补分时，同一笔扣分有被回补两次的风险。
 *
 * @returns {JSX.Element} 违规管理页
 */
export default function ViolationManage() {
  const [userId, setUserId] = useState('');
  // 输入框值与「已提交的查询值」分开：否则每敲一个字符就打一次接口
  const [queryUserId, setQueryUserId] = useState('');
  const [status, setStatus] = useState(null);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [target, setTarget] = useState(null);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [consistency, setConsistency] = useState(null);
  const [checking, setChecking] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = {};
      if (queryUserId) {
        params.userId = queryUserId;
      }
      if (status) {
        params.status = status;
      }
      setRows(await fetchStaffViolations(params));
    } catch (e) {
      setError(e.message || '违规记录加载失败');
    } finally {
      setLoading(false);
    }
  }, [queryUserId, status]);

  useEffect(() => {
    load();
  }, [load]);

  const handleRevoke = async () => {
    if (!reason.trim()) {
      message.warning('请填写撤销理由');
      return;
    }
    setSubmitting(true);
    try {
      const result = await revokeViolation(target.violationId, { reason: reason.trim() });
      message.success(`违规 #${result.violationId} 已撤销，分数已回补`);
      setTarget(null);
      setReason('');
      await load();
    } catch (e) {
      /* 提示由拦截器弹出 */
    } finally {
      setSubmitting(false);
    }
  };

  const handleCheck = async () => {
    setChecking(true);
    try {
      setConsistency(await fetchConsistency());
    } catch (e) {
      setConsistency(null);
    } finally {
      setChecking(false);
    }
  };

  const columns = [
    { title: '违规号', dataIndex: 'violationId', width: 90 },
    { title: '用户', dataIndex: 'userId', width: 80 },
    {
      title: '类型',
      dataIndex: 'type',
      width: 120,
      render: (value, row) => (
        <Tag color={(VIOLATION_TYPE_META[value] || {}).color || 'default'}>{row.typeText}</Tag>
      ),
    },
    {
      title: '扣分',
      dataIndex: 'scoreDelta',
      width: 80,
      render: (value) => (
        <Typography.Text style={{ color: '#ff4d4f' }}>{value}</Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value, row) => (
        <Tag color={(VIOLATION_STATUS_META[value] || {}).color || 'default'}>{row.statusText}</Tag>
      ),
    },
    {
      title: '申诉',
      dataIndex: 'appealStatusText',
      width: 120,
      render: (value, row) => (row.appealId
        ? (
          <Space direction="vertical" size={0}>
            <Tag color={(APPEAL_STATUS_META[row.appealStatus] || {}).color || 'default'}>
              {value}
            </Tag>
            {row.appealReply ? (
              <Typography.Text type="secondary" ellipsis style={{ maxWidth: 180 }}>
                {row.appealReply}
              </Typography.Text>
            ) : null}
          </Space>
        )
        : <Typography.Text type="secondary">未申诉</Typography.Text>),
    },
    {
      title: '来源',
      dataIndex: 'createdBy',
      width: 110,
      render: (value) => (value === 'SYSTEM' ? '系统自动' : '馆员裁定'),
    },
    { title: '裁定时刻', dataIndex: 'createTime', width: 170 },
    {
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: 100,
      render: (_, row) => (
        <HintedButton tip={row.canRevoke ? '' : row.revokeDisabledReason}>
          <Button
            danger
            size="small"
            disabled={!row.canRevoke}
            onClick={() => {
              setTarget(row);
              setReason('');
            }}
          >
            撤销
          </Button>
        </HintedButton>
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        违规管理
      </Typography.Title>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      {consistency === null ? null : (
        consistency.length === 0 ? (
          <Alert
            type="success"
            showIcon
            message="全库对账通过：每位用户的账本合计都等于信用分快照"
            style={{ marginBottom: 12 }}
          />
        ) : (
          <Alert
            type="error"
            showIcon
            message={`对账不平 ${consistency.length} 人：有任何一处绕过 CreditService 改分都会在这里露出来`}
            description={(
              <ul style={{ paddingLeft: 18, marginBottom: 0 }}>
                {consistency.map((diff) => <li key={diff}>{diff}</li>)}
              </ul>
            )}
            style={{ marginBottom: 12 }}
          />
        )
      )}

      <div className="toolbar">
        <Input.Search
          allowClear
          style={{ width: 220 }}
          placeholder="按用户 ID 查询"
          enterButton="查询"
          value={userId}
          onChange={(e) => setUserId(e.target.value.replace(/[^0-9]/g, ''))}
          onSearch={(value) => setQueryUserId((value || '').trim())}
        />
        <Select
          allowClear
          style={{ width: 160 }}
          placeholder="全部状态"
          options={VIOLATION_STATUS_OPTIONS}
          value={status}
          onChange={(value) => setStatus(value || null)}
        />
        <span className="spacer" />
        <Button onClick={handleCheck} loading={checking}>账本对账巡检</Button>
        <Button type="primary" onClick={load}>刷新</Button>
      </div>

      <Card title="违规记录" loading={loading}>
        <Table
          rowKey="violationId"
          size="small"
          columns={columns}
          dataSource={rows}
          scroll={{ x: 1240 }}
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
          expandable={{
            expandedRowRender: (row) => (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                {row.status === 'REVOKED' ? (
                  <Typography.Text>
                    撤销说明：{row.revokeReason || '—'}（{row.revokeTime}）
                  </Typography.Text>
                ) : null}
                <div>
                  <Typography.Text strong>裁定依据（证据）</Typography.Text>
                  <EvidenceBlock items={row.evidenceItems} />
                </div>
              </Space>
            ),
          }}
        />
      </Card>

      <Modal
        open={Boolean(target)}
        title={`撤销违规 #${target ? target.violationId : ''}`}
        okText="确认撤销"
        cancelText="取消"
        okButtonProps={{ disabled: !reason.trim(), danger: true, loading: submitting }}
        onOk={handleRevoke}
        onCancel={() => setTarget(null)}
        destroyOnClose
      >
        {target ? (
          <>
            <Alert
              type="warning"
              showIcon
              message="撤销会立即回补该笔扣分并写入审计日志；已在申诉流程中的违规请改走申诉裁定。"
              style={{ marginBottom: 12 }}
            />
            <Typography.Paragraph>
              {target.typeText}（{target.scoreDelta} 分）· 用户 #{target.userId} · {target.createTime}
            </Typography.Paragraph>
            <Typography.Paragraph strong>撤销理由（必填，≤{REASON_MAX} 字）</Typography.Paragraph>
            <Input.TextArea
              rows={3}
              maxLength={REASON_MAX}
              showCount
              placeholder="例如：调取当日监控，确认当事人其实在场"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </>
        ) : null}
      </Modal>
    </div>
  );
}
