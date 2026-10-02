import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Descriptions, Empty, Input, Modal, Popconfirm, Row,
  Space, Spin, Statistic, Table, Tag, Typography, message,
} from 'antd';
import {
  escalateAppeal, fetchMyCredit, fetchMyViolations, submitAppeal,
} from '../api/credit';
import HintedButton from '../components/HintedButton';
import EvidenceBlock from '../components/EvidenceBlock';
import {
  APPEAL_STATUS_META, CREDIT_LOG_KIND_META, VIOLATION_STATUS_META, VIOLATION_TYPE_META,
} from '../constants';

/** 申诉事由字数上限（与后端 AppealRequest 的 @Size 一致，只用于提前拦住明显超长的输入）。 */
const REASON_MAX = 500;

/**
 * 分数色带：只影响观感，不参与任何判定（判定与阈值都在后端规则中心）。
 *
 * @param {number} score 信用分
 * @returns {string} 颜色
 */
function scoreTone(score) {
  if (score >= 90) return '#52c41a';
  if (score >= 70) return '#faad14';
  if (score >= 50) return '#fa8c16';
  return '#ff4d4f';
}

/**
 * 违规类型 Tag。
 *
 * @param {object} row ViolationVO
 * @returns {JSX.Element} Tag
 */
function TypeTag({ row }) {
  const meta = VIOLATION_TYPE_META[row.type] || {};
  return <Tag color={meta.color || 'default'}>{row.typeText}</Tag>;
}

/**
 * 我的信用页（V2 S3 / T-V2-14）。
 *
 * 三块内容来自三个接口：分数与处罚（/api/me/credit）、违规与证据（/api/me/violations）、
 * 账本流水（/api/me/credit 的 logs）。所有按钮可用性取后端返回的
 * canAppeal / canEscalate 与配套原因，前端不重复实现 24 小时等规则。
 *
 * @returns {JSX.Element} 信用页
 */
export default function Credit() {
  const [loading, setLoading] = useState(false);
  const [credit, setCredit] = useState(null);
  const [violations, setViolations] = useState([]);
  const [error, setError] = useState('');
  const [appealRow, setAppealRow] = useState(null);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [actingId, setActingId] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setCredit(await fetchMyCredit());
      setViolations(await fetchMyViolations());
    } catch (e) {
      setError(e.message || '信用数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openAppeal = (row) => {
    setAppealRow(row);
    setReason('');
  };

  const closeAppeal = () => {
    setAppealRow(null);
    setReason('');
  };

  const handleSubmitAppeal = async () => {
    if (!reason.trim()) {
      message.warning('请填写申诉事由');
      return;
    }
    setSubmitting(true);
    try {
      const result = await submitAppeal(appealRow.violationId, { reason: reason.trim() });
      message.success(`申诉已提交（${result.statusText}），请等待馆员复核`);
      closeAppeal();
      await load();
    } catch (e) {
      /* 提示由拦截器弹出，这里只负责结束提交态 */
    } finally {
      setSubmitting(false);
    }
  };

  const handleEscalate = async (row) => {
    setActingId(row.violationId);
    try {
      await escalateAppeal(row.violationId);
      message.success('已升级至系统管理员终审');
      await load();
    } catch (e) {
      /* 提示由拦截器弹出 */
    } finally {
      setActingId(null);
    }
  };

  const penaltyColumns = [
    { title: '处罚', dataIndex: 'typeText', width: 140 },
    { title: '生效时刻', dataIndex: 'startTime', width: 170 },
    { title: '怎么恢复', dataIndex: 'recoverHint' },
    {
      title: '触发原因',
      dataIndex: 'reason',
      render: (value) => value || <Typography.Text type="secondary">—</Typography.Text>,
    },
  ];

  const logColumns = [
    {
      title: '类型',
      dataIndex: 'kind',
      width: 120,
      render: (value, row) => {
        const meta = CREDIT_LOG_KIND_META[value] || {};
        return <Tag color={meta.color || 'default'}>{row.kindText || meta.text || value}</Tag>;
      },
    },
    {
      title: '变化',
      dataIndex: 'delta',
      width: 90,
      render: (value) => (
        <Typography.Text style={{ color: value >= 0 ? '#52c41a' : '#ff4d4f' }}>
          {value > 0 ? `+${value}` : value}
        </Typography.Text>
      ),
    },
    { title: '余额', dataIndex: 'balanceAfter', width: 80 },
    { title: '说明', dataIndex: 'reason' },
    { title: '时刻', dataIndex: 'createTime', width: 170 },
  ];

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        信用与违规
      </Typography.Title>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Spin spinning={loading}>
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={8}>
            <Card title="我的信用分" className="metric-card">
              {credit ? (
                <>
                  <Statistic
                    value={credit.creditScore}
                    suffix="分"
                    valueStyle={{ color: scoreTone(credit.creditScore) }}
                  />
                  <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                    初始 100 分。违规扣分后可在下方逐笔核对；对判定有异议请在 24 小时内提交申诉。
                  </Typography.Paragraph>
                </>
              ) : (
                <Empty description="暂无信用数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </Card>
          </Col>
          <Col xs={24} lg={16}>
            <Card title="生效中的处罚" extra={<Button onClick={load}>刷新</Button>}>
              <Table
                rowKey="penaltyId"
                size="small"
                columns={penaltyColumns}
                dataSource={credit ? credit.penalties : []}
                pagination={false}
                locale={{ emptyText: '当前没有处罚，可正常预约' }}
              />
            </Card>
          </Col>
        </Row>

        <Card title="违规记录" style={{ marginTop: 16 }}>
          {violations.length === 0 ? (
            <Empty description="没有违规记录，继续保持" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {violations.map((row) => {
                const statusMeta = VIOLATION_STATUS_META[row.status] || {};
                const appealMeta = APPEAL_STATUS_META[row.appealStatus] || {};
                return (
                  <Card
                    key={row.violationId}
                    size="small"
                    type="inner"
                    title={(
                      <Space wrap>
                        <TypeTag row={row} />
                        <Typography.Text strong>{row.scoreDelta} 分</Typography.Text>
                        <Tag color={statusMeta.color || 'default'}>{row.statusText}</Tag>
                        <Typography.Text type="secondary">{row.createTime}</Typography.Text>
                      </Space>
                    )}
                    extra={(
                      <Space>
                        <HintedButton
                          tip={row.canAppeal ? '' : row.appealDisabledReason}
                        >
                          <Button
                            size="small"
                            type="primary"
                            disabled={!row.canAppeal}
                            onClick={() => openAppeal(row)}
                          >
                            申诉
                          </Button>
                        </HintedButton>
                        {row.canEscalate ? (
                          <Popconfirm
                            title="升级到系统管理员终审？"
                            description="升级后由系统管理员做最终裁定，结果不可再更改。"
                            okText="确认升级"
                            cancelText="取消"
                            onConfirm={() => handleEscalate(row)}
                          >
                            <Button
                              size="small"
                              danger
                              loading={actingId === row.violationId}
                            >
                              升级终审
                            </Button>
                          </Popconfirm>
                        ) : null}
                      </Space>
                    )}
                  >
                    <Descriptions size="small" column={1}>
                      <Descriptions.Item label="判定依据">
                        {row.createdBy === 'SYSTEM' ? '系统按规则自动裁定' : '馆员现场裁定'}
                      </Descriptions.Item>
                      {row.appealId ? (
                        <Descriptions.Item label="申诉进度">
                          <Space direction="vertical" size={2}>
                            <Space wrap>
                              <Tag color={appealMeta.color || 'default'}>
                                {row.appealStatusText}
                              </Tag>
                              {row.appealHandleTime ? (
                                <Typography.Text type="secondary">
                                  裁定于 {row.appealHandleTime}
                                </Typography.Text>
                              ) : null}
                            </Space>
                            {row.appealReply ? (
                              <Typography.Text>馆员回复：{row.appealReply}</Typography.Text>
                            ) : null}
                          </Space>
                        </Descriptions.Item>
                      ) : null}
                      {row.status === 'REVOKED' ? (
                        <Descriptions.Item label="撤销说明">
                          {row.revokeReason || '—'}（{row.revokeTime}）
                        </Descriptions.Item>
                      ) : null}
                      {row.appealRemainHours !== null
                        && row.appealRemainHours !== undefined ? (
                        <Descriptions.Item label="申诉剩余">
                          {row.appealRemainHours} 小时
                        </Descriptions.Item>
                      ) : null}
                    </Descriptions>
                    <Typography.Paragraph
                      type="secondary"
                      style={{ marginBottom: 6, marginTop: 6 }}
                    >
                      证据（裁定用）
                    </Typography.Paragraph>
                    <EvidenceBlock items={row.evidenceItems} />
                  </Card>
                );
              })}
            </Space>
          )}
        </Card>

        <Card
          title="信用流水"
          style={{ marginTop: 16 }}
          extra={(
            <Typography.Text type="secondary">
              每一笔分数变化都在这里，历史不会被改写
            </Typography.Text>
          )}
        >
          <Table
            rowKey="logId"
            size="small"
            columns={logColumns}
            dataSource={credit ? credit.logs : []}
            pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 笔` }}
          />
        </Card>
      </Spin>

      <Modal
        open={Boolean(appealRow)}
        title="提交申诉"
        okText="提交申诉"
        cancelText="取消"
        okButtonProps={{ disabled: !reason.trim(), loading: submitting }}
        onOk={handleSubmitAppeal}
        onCancel={closeAppeal}
        destroyOnClose
      >
        {appealRow ? (
          <>
            <Alert
              type="info"
              showIcon
              message="申诉由馆员复核；对驳回结果仍有异议可再升级至系统管理员终审。"
              style={{ marginBottom: 12 }}
            />
            <Descriptions size="small" column={1} bordered style={{ marginBottom: 12 }}>
              <Descriptions.Item label="违规">
                {appealRow.typeText}（{appealRow.scoreDelta} 分）
              </Descriptions.Item>
              <Descriptions.Item label="时间">{appealRow.createTime}</Descriptions.Item>
            </Descriptions>
            <Typography.Paragraph strong>申诉事由（必填）</Typography.Paragraph>
            <Input.TextArea
              rows={4}
              maxLength={REASON_MAX}
              showCount
              placeholder="请说明当时情况与你认为判定有误的依据"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </>
        ) : null}
      </Modal>
    </div>
  );
}
