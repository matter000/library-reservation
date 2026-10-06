import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Input,
  Modal,
  Pagination,
  Row,
  Select,
  Space,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined, CloseCircleOutlined, HourglassOutlined, LogoutOutlined, TeamOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { cancelReservation, checkInReservation, fetchMyReservations, finishReservation } from '../api/reservation';
import { fetchLeaveReasons, returnLeave, startLeave } from '../api/leave';
import StatusTag from '../components/StatusTag';
import HintedButton from '../components/HintedButton';
import PageLoading from '../components/PageLoading';
import { RESERVATION_STATUS } from '../constants';
import { friendlyDate } from '../utils/datetime';

/** Tab 定义（对应后端 tab 参数）。 */
const TABS = [
  { key: 'upcoming', label: '待开始' },
  { key: 'inProgress', label: '使用中' },
  { key: 'finished', label: '已结束' },
  { key: 'cancelled', label: '已取消' },
];

/** 每页条数（与栅格每行 2 张卡片对齐，避免末行残缺）。 */
const PAGE_SIZE = 12;

/**
 * 我的预约页。
 *
 * 按钮可用性直接取后端返回的 canCancel / canCheckIn / checkInWindowText，
 * 前端不重复实现业务规则。
 *
 * @returns {JSX.Element} 我的预约页
 */
export default function MyReservations() {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('upcoming');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState([]);
  const [error, setError] = useState('');
  const [actingId, setActingId] = useState(null);
  const [leaveItem, setLeaveItem] = useState(null);
  const [reasons, setReasons] = useState([]);
  const [reasonCode, setReasonCode] = useState(null);
  const [leaveNote, setLeaveNote] = useState('');
  const [leaveSubmitting, setLeaveSubmitting] = useState(false);

  const load = useCallback(async (tab, currentPage) => {
    setLoading(true);
    setError('');
    try {
      const data = await fetchMyReservations({ tab, page: currentPage, size: PAGE_SIZE });
      const nextList = data && data.list ? data.list : [];
      const nextTotal = data && typeof data.total === 'number' ? data.total : nextList.length;
      // 取消/签到后当前页可能被清空：自动回退一页，避免停在空白页
      if (nextList.length === 0 && currentPage > 1 && nextTotal > 0) {
        setPage(currentPage - 1);
        return;
      }
      setList(nextList);
      setTotal(nextTotal);
    } catch (e) {
      setError(e.message || '加载预约失败');
      setList([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(activeTab, page);
  }, [activeTab, page, load]);

  /** 取消预约。 */
  const handleCancel = async (item) => {
    setActingId(item.reservationId);
    try {
      await cancelReservation(item.reservationId);
      message.success('已取消预约');
      await load(activeTab, page);
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setActingId(null);
    }
  };

  /** 签到。 */
  const handleCheckIn = async (item) => {
    setActingId(item.reservationId);
    try {
      await checkInReservation(item.reservationId);
      message.success('签到成功，祝您学习愉快');
      await load(activeTab, page);
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setActingId(null);
    }
  };

  /** 暂离剩余文案：超时就写超期，不粉饰成 0 分钟。 */
  const leaveRemainText = (item) => (item.leaveRemainMin >= 0
    ? `剩 ${item.leaveRemainMin} 分钟`
    : `已超期 ${-item.leaveRemainMin} 分钟`);

  /** 打开登记暂离弹窗：字典里带着「本人今日各原因已用次数」，每次打开都重新拉。 */
  const openLeaveModal = async (item) => {
    setLeaveItem(item);
    setReasonCode(null);
    setLeaveNote('');
    try {
      const list = await fetchLeaveReasons(item.reservationId);
      setReasons(Array.isArray(list) ? list : []);
    } catch (e) {
      // 错误提示由拦截器统一弹出
    }
  };

  /** 提交暂离。 */
  const submitLeave = async () => {
    if (!reasonCode) {
      message.warning('请选择暂离原因');
      return;
    }
    const reason = reasons.find((item) => item.code === reasonCode);
    if (reason && reason.needNote && !leaveNote.trim()) {
      message.warning(`「${reason.name}」需要填写事由`);
      return;
    }
    setLeaveSubmitting(true);
    try {
      const vo = await startLeave({
        reservationId: leaveItem.reservationId,
        reasonCode,
        note: leaveNote.trim() || undefined,
      });
      message.success(`已登记暂离，请在 ${vo.plannedEndTime} 前返回；剩余使用时间不顺延`);
      setLeaveItem(null);
      await load(activeTab, page);
    } catch (e) {
      // 校验失败（如次数已用完）由拦截器提示，保留弹窗便于换原因重试
    } finally {
      setLeaveSubmitting(false);
    }
  };

  /** 我已返回：座位回到使用中，预约结束时刻不变。 */
  const handleReturn = async (item) => {
    setActingId(item.reservationId);
    try {
      const vo = await returnLeave(item.activeLeaveId);
      message.success(`已返回座位，您还有到 ${vo.reservationEndTime} 的使用时间`);
      await load(activeTab, page);
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setActingId(null);
    }
  };

  /** 主动结束使用：释放不可撤销，先确认。 */
  const handleFinish = (item) => {
    Modal.confirm({
      title: '提前结束使用？',
      content: `座位 ${item.seatNo} 将立即释放给他人，本次不计爽约。原时段 ${item.timeRange} 的剩余时间不顺延。`,
      okText: '结束使用',
      cancelText: '再想想',
      onOk: async () => {
        setActingId(item.reservationId);
        try {
          const result = await finishReservation(item.reservationId);
          message.success(`已结束，连续主动结束 ${result.activeFinishStreak} 次`);
          await load(activeTab, page);
        } catch (e) {
          // 提示由拦截器弹出
        } finally {
          setActingId(null);
        }
      },
    });
  };

  /** 渲染单张预约卡片。 */
  const renderCard = (item) => {
    const isAdminCancelled = item.status === RESERVATION_STATUS.ADMIN_CANCELLED;
    return (
      <Col key={item.reservationId} xs={24} md={12}>
        <Card
          size="small"
          title={
            <Space>
              <span>
                {item.floorName || '-'} · {item.seatNo || '-'}
              </span>
              <StatusTag status={item.status} />
            </Space>
          }
          extra={<Typography.Text type="secondary">#{item.reservationId}</Typography.Text>}
        >
          <Row gutter={[8, 6]}>
            <Col span={12}>
              <Typography.Text type="secondary">日期：</Typography.Text>
              {friendlyDate(item.date)}（{item.date}）
            </Col>
            <Col span={12}>
              <Typography.Text type="secondary">时段：</Typography.Text>
              {item.timeRange}
            </Col>
            <Col span={12}>
              <Typography.Text type="secondary">时长：</Typography.Text>
              {item.durationHours} 小时
            </Col>
            <Col span={12}>
              <Typography.Text type="secondary">创建：</Typography.Text>
              {item.createTime || '-'}
            </Col>
            {item.checkInTime ? (
              <Col span={12}>
                <Typography.Text type="secondary">签到：</Typography.Text>
                {item.checkInTime}
              </Col>
            ) : null}
            {item.cancelTime ? (
              <Col span={12}>
                <Typography.Text type="secondary">取消时间：</Typography.Text>
                {item.cancelTime}
              </Col>
            ) : null}
            {isAdminCancelled && item.adminCancelReason ? (
              <Col span={24}>
                <Alert
                  type="warning"
                  showIcon
                  message={`已被管理员取消：${item.adminCancelReason}`}
                />
              </Col>
            ) : null}
          </Row>

          <div style={{ marginTop: 12 }}>
            <Space>
              {item.groupId ? (
                <>
                  <Tag color="purple">组队预约</Tag>
                  <Typography.Text type="secondary">{item.groupManagedHint}</Typography.Text>
                  <Button
                    size="small"
                    icon={<TeamOutlined />}
                    onClick={() => navigate(`/groups?groupId=${item.groupId}`)}
                  >
                    去队伍页操作
                  </Button>
                </>
              ) : (
                <>
                  <HintedButton tip={item.canCancel ? '' : item.checkInWindowText || '当前不可取消'}>
                    <Button
                      danger
                      size="small"
                      icon={<CloseCircleOutlined />}
                      disabled={!item.canCancel}
                      loading={actingId === item.reservationId}
                      onClick={() => handleCancel(item)}
                    >
                      取消预约
                    </Button>
                  </HintedButton>
                  <HintedButton tip={item.checkInWindowText || '不在签到窗口内'}>
                    <Button
                      type="primary"
                      size="small"
                      icon={<CheckCircleOutlined />}
                      disabled={!item.canCheckIn}
                      loading={actingId === item.reservationId}
                      onClick={() => handleCheckIn(item)}
                    >
                      签到
                    </Button>
                  </HintedButton>
                </>
              )}
              {item.expectedReturnAt ? (
                <Tag color="blue">
                  {`暂离中 · 预计 ${item.expectedReturnAt} 返回（${leaveRemainText(item)}）`}
                </Tag>
              ) : null}
              <HintedButton tip={item.canLeave ? '离座前登记，座位继续为您保留' : item.leaveDisabledReason || '当前不可暂离'}>
                <Button
                  size="small"
                  icon={<HourglassOutlined />}
                  disabled={!item.canLeave}
                  onClick={() => openLeaveModal(item)}
                >
                  登记暂离
                </Button>
              </HintedButton>
              <HintedButton tip={item.canReturn ? '回到座位后点这里，剩余使用时间不顺延' : item.leaveDisabledReason || '当前不在暂离中'}>
                <Button
                  size="small"
                  type="primary"
                  ghost
                  icon={<CheckCircleOutlined />}
                  disabled={!item.canReturn}
                  loading={actingId === item.reservationId}
                  onClick={() => handleReturn(item)}
                >
                  我已返回
                </Button>
              </HintedButton>
              <HintedButton tip={item.canFinish ? '用不完的时间提前释放给他人' : item.leaveDisabledReason || '当前不可结束'}>
                <Button
                  size="small"
                  icon={<LogoutOutlined />}
                  disabled={!item.canFinish}
                  loading={actingId === item.reservationId}
                  onClick={() => handleFinish(item)}
                >
                  结束使用
                </Button>
              </HintedButton>
            </Space>
          </div>
        </Card>
      </Col>
    );
  };

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        我的预约
      </Typography.Title>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          setActiveTab(key);
          setPage(1);
        }}
        items={TABS.map((tab) => ({
          key: tab.key,
          label: tab.label,
          children: loading && list.length === 0 ? (
            <PageLoading />
          ) : list.length === 0 ? (
            <Empty description="暂无记录">
              <Button type="primary" onClick={() => navigate('/floors')}>
                去预约座位
              </Button>
            </Empty>
          ) : (
            <>
              <Row gutter={[12, 12]}>{list.map(renderCard)}</Row>
              {total > PAGE_SIZE ? (
                <div style={{ marginTop: 16, textAlign: 'right' }}>
                  <Pagination
                    current={page}
                    pageSize={PAGE_SIZE}
                    total={total}
                    showSizeChanger={false}
                    showTotal={(count) => `共 ${count} 条`}
                    onChange={(nextPage) => setPage(nextPage)}
                  />
                </div>
              ) : null}
            </>
          ),
        }))}
      />

      <Modal
        open={Boolean(leaveItem)}
        title={leaveItem ? `登记暂离 · ${leaveItem.seatNo}（${leaveItem.timeRange}）` : '登记暂离'}
        okText="确认暂离"
        cancelText="取消"
        confirmLoading={leaveSubmitting}
        onOk={submitLeave}
        onCancel={() => setLeaveItem(null)}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="暂离期间座位仍归您使用，但剩余使用时间不会顺延"
          description="超过「应返回时刻」仍未返回将记为违规并释放座位。"
        />
        <Select
          style={{ width: '100%' }}
          placeholder="选择暂离原因"
          value={reasonCode}
          onChange={setReasonCode}
          options={reasons.map((item) => ({
            value: item.code,
            // 今日用满的原因直接置灰并写清原因（PRD §13：不可用必须给理由，不能选了才被拒）
            disabled: item.exhausted,
            label: `${item.name}（最长 ${item.maxMin} 分钟${item.needNote ? ' · 需填事由' : ''}`
              + `${item.dailyLimit ? ` · 今日 ${item.todayUsed}/${item.dailyLimit} 次` : ''}）`,
          }))}
        />
        <Input.TextArea
          style={{ marginTop: 12 }}
          rows={2}
          maxLength={100}
          showCount
          placeholder="事由（选填，标了「需填事由」的原因必填）"
          value={leaveNote}
          onChange={(e) => setLeaveNote(e.target.value)}
        />
      </Modal>
    </div>
  );
}
