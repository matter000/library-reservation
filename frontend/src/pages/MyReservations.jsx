import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Pagination,
  Row,
  Space,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { cancelReservation, checkInReservation, fetchMyReservations } from '../api/reservation';
import StatusTag from '../components/StatusTag';
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
              <Tooltip title={item.canCancel ? '' : item.checkInWindowText || '当前不可取消'}>
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
              </Tooltip>
              <Tooltip title={item.canCheckIn ? item.checkInWindowText : item.checkInWindowText || '不在签到窗口内'}>
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
              </Tooltip>
              {item.canCheckIn === false && item.status === 'CONFIRMED' ? (
                <Tag>{item.checkInWindowText}</Tag>
              ) : null}
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
    </div>
  );
}
