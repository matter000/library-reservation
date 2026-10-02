import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Input, Modal, Radio, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { fetchAppealQueue, judgeAppeal } from '../../api/credit';
import EvidenceBlock from '../../components/EvidenceBlock';
import HintedButton from '../../components/HintedButton';
import {
  APPEAL_STATUS_META, APPEAL_STATUS_OPTIONS, VIOLATION_STATUS_META, VIOLATION_TYPE_META,
} from '../../constants';

/** 裁定说明字数上限（与后端 JudgeRequest 的 @Size 一致）。 */
const REPLY_MAX = 500;

/**
 * 申诉复核队列（V2 S3 / DESIGN §12.2 F9）。
 *
 * 能不能裁定由后端逐条给出（馆员看不到升级件的裁定权），
 * 页面只按 canJudge / judgeDisabledReason 渲染，不复刻角色与状态规则。
 *
 * @returns {JSX.Element} 申诉队列页
 */
export default function AppealQueue() {
  const [status, setStatus] = useState('PENDING');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [target, setTarget] = useState(null);
  const [approve, setApprove] = useState(true);
  const [reply, setReply] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(await fetchAppealQueue(status ? { status } : {}));
    } catch (e) {
      setError(e.message || '申诉队列加载失败');
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const openJudge = (row) => {
    setTarget(row);
    setApprove(true);
    setReply('');
  };

  const closeJudge = () => {
    setTarget(null);
    setReply('');
  };

  const handleJudge = async () => {
    // 驳回必须写理由是后端规则（4001）；这里只是提前拦住，免得用户白点一次
    if (!approve && !reply.trim()) {
      message.warning('驳回必须写明理由');
      return;
    }
    setSubmitting(true);
    try {
      const result = await judgeAppeal(target.appealId, { approve, reply: reply.trim() });
      message.success(`已裁定为「${result.statusText}」`);
      closeJudge();
      await load();
    } catch (e) {
      /* 提示由拦截器弹出 */
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: '申诉号', dataIndex: 'appealId', width: 90 },
    { title: '申诉人', dataIndex: 'userId', width: 90 },
    {
      title: '被申诉违规',
      dataIndex: 'violation',
      width: 190,
      render: (violation) => (violation ? (
        <Space wrap size={4}>
          <Tag color={(VIOLATION_TYPE_META[violation.type] || {}).color || 'default'}>
            {violation.typeText}
          </Tag>
          <Typography.Text>{violation.scoreDelta} 分</Typography.Text>
        </Space>
      ) : <Typography.Text type="secondary">违规记录已缺失</Typography.Text>),
    },
    {
      title: '事由',
      dataIndex: 'reason',
      ellipsis: true,
      render: (value) => value || '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (value, row) => (
        <Tag color={(APPEAL_STATUS_META[value] || {}).color || 'default'}>{row.statusText}</Tag>
      ),
    },
    { title: '提交时刻', dataIndex: 'createTime', width: 170 },
    {
      title: '裁定人',
      dataIndex: 'handlerRole',
      width: 120,
      render: (value, row) => (value
        ? (
          <Space direction="vertical" size={0}>
            <Typography.Text>{value}</Typography.Text>
            <Typography.Text type="secondary">{row.handleTime}</Typography.Text>
          </Space>
        )
        : <Typography.Text type="secondary">待裁定</Typography.Text>),
    },
    {
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: 110,
      render: (_, row) => (
        <HintedButton tip={row.canJudge ? '' : row.judgeDisabledReason}>
          <Button
            type="primary"
            size="small"
            disabled={!row.canJudge}
            onClick={() => openJudge(row)}
          >
            裁定
          </Button>
        </HintedButton>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Typography.Title level={4} className="page-title">
        申诉复核
      </Typography.Title>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Card
        title="申诉队列"
        loading={loading}
        extra={(
          <Space>
            <Select
              value={status}
              style={{ width: 160 }}
              allowClear
              placeholder="全部状态"
              options={APPEAL_STATUS_OPTIONS}
              onChange={(value) => setStatus(value || null)}
            />
            <Button onClick={load}>刷新</Button>
          </Space>
        )}
      >
        <Table
          rowKey="appealId"
          size="small"
          columns={columns}
          dataSource={rows}
          scroll={{ x: 1180 }}
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
          expandable={{
            expandedRowRender: (row) => (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <div>
                  <Typography.Text strong>申诉事由</Typography.Text>
                  <Typography.Paragraph style={{ marginBottom: 0 }}>{row.reason}</Typography.Paragraph>
                </div>
                {row.reply ? (
                  <div>
                    <Typography.Text strong>裁定说明</Typography.Text>
                    <Typography.Paragraph style={{ marginBottom: 0 }}>
                      {row.reply}
                    </Typography.Paragraph>
                  </div>
                ) : null}
                {row.violation ? (
                  <div>
                    <Typography.Text strong>裁定依据（违规证据）</Typography.Text>
                    <Space wrap style={{ marginBottom: 6 }}>
                      <Tag color={(VIOLATION_STATUS_META[row.violation.status] || {}).color || 'default'}>
                        {row.violation.statusText}
                      </Tag>
                      <Typography.Text type="secondary">
                        违规时间 {row.violation.createTime} · 座位预约 {row.violation.reservationId || '无'}
                      </Typography.Text>
                    </Space>
                    <EvidenceBlock items={row.violation.evidenceItems} />
                  </div>
                ) : null}
              </Space>
            ),
          }}
        />
      </Card>

      <Modal
        open={Boolean(target)}
        title={`裁定申诉 #${target ? target.appealId : ''}`}
        okText="提交裁定"
        cancelText="取消"
        okButtonProps={{
          disabled: !approve && !reply.trim(),
          danger: !approve,
          loading: submitting,
        }}
        onOk={handleJudge}
        onCancel={closeJudge}
        destroyOnClose
      >
        {target ? (
          <>
            <Alert
              type="info"
              showIcon
              message={target.violation && target.violation.status === 'APPEALING'
                ? '成立将撤销该违规并回补分数；驳回则该违规回到「生效中」。'
                : '该违规当前状态：' + (target.violation ? target.violation.statusText : '未知')}
              style={{ marginBottom: 12 }}
            />
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <div>
                <Typography.Paragraph strong style={{ marginBottom: 6 }}>裁定结果</Typography.Paragraph>
                <Radio.Group
                  value={approve}
                  onChange={(e) => setApprove(e.target.value)}
                  options={[
                    { value: true, label: '申诉成立（撤销违规并回补分数）' },
                    { value: false, label: '申诉驳回（维持违规）' },
                  ]}
                />
              </div>
              <div>
                <Typography.Paragraph strong style={{ marginBottom: 6 }}>
                  裁定说明{approve ? '（可选）' : '（必填，学生会看到）'}
                </Typography.Paragraph>
                <Input.TextArea
                  rows={4}
                  maxLength={REPLY_MAX}
                  showCount
                  placeholder={approve ? '可写明采信了哪条证据' : '请说明驳回依据，学生会据此决定是否升级终审'}
                  value={reply}
                  onChange={(e) => setReply(e.target.value)}
                />
              </div>
            </Space>
          </>
        ) : null}
      </Modal>
    </div>
  );
}
