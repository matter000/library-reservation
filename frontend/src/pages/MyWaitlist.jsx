import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Empty, Space, Tag, Tooltip, Typography, message } from 'antd';
import { HourglassOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { confirmWaitlist, fetchMyWaitlist, quitWaitlist } from '../api/waitlist';
import { WAITLIST_POLL_INTERVAL, WAITLIST_STATUS_META } from '../constants';

/**
 * 我的候补页（T-V2-19）。
 *
 * <p>每 15 秒轮询一次：offer 的确认窗口按分钟推进，页面开着却看不到「轮到你了」
 * 是最糟的体验 —— 窗口只有十分钟，错过就顺延给下一位了。
 *
 * <p>★ 按钮可用性完全来自后端：位置是否还留着、窗口是否已过，都在服务端判定，
 * 前端只做渲染。这里不额外算倒计时，否则会出现「页面说还能确认、接口说已经顺延」。
 *
 * @returns {JSX.Element} 我的候补页
 */
export default function MyWaitlist() {
  const navigate = useNavigate();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState(null);
  const timerRef = useRef(null);

  const load = useCallback(async () => {
    setError('');
    try {
      setItems(await fetchMyWaitlist() || []);
    } catch (e) {
      setError(e.message || '候补列表加载失败');
    }
  }, []);

  useEffect(() => {
    setLoading(true);
    load().finally(() => setLoading(false));
  }, [load]);

  /** 轮询：页面在后台时不发请求，切回来立刻补一次。 */
  useEffect(() => {
    timerRef.current = setInterval(() => {
      if (!document.hidden) {
        load();
      }
    }, WAITLIST_POLL_INTERVAL);
    const onVisible = () => {
      if (!document.hidden) {
        load();
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(timerRef.current);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [load]);

  /** 确认候补：成功就是拿到了一次正常预约，直接去「我的预约」。 */
  const handleConfirm = async (item) => {
    setBusyId(item.waitlistId);
    try {
      const created = await confirmWaitlist(item.waitlistId);
      message.success(`候补成功：${created.seatNo} ${created.date} ${created.timeRange}，已按正常预约处理`);
      navigate('/my/reservations');
    } catch (e) {
      // 位置可能已被别人约走、或本次校验没过：队列已就地顺延，刷新即可看到新状态
      await load();
    } finally {
      setBusyId(null);
    }
  };

  const handleQuit = async (item) => {
    setBusyId(item.waitlistId);
    try {
      await quitWaitlist(item.waitlistId);
      message.success('已退出候补，位置已让给下一位');
      await load();
    } catch (e) {
      /* 提示由拦截器弹出 */
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          <HourglassOutlined /> 我的候补
        </Typography.Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
          <Button type="primary" onClick={() => navigate('/floors')}>去平面图排队</Button>
        </Space>
      </Space>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={'收到「待确认」后请在窗口内点确认：候补只是轮到你尝试，位置并没有给你留着，'
          + '窗口内座位可能被别人直接约走。超时不确认不会记违规，位置自动顺延给下一位。'}
      />

      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}

      {items.length === 0 ? (
        <Empty description="当前没有排队中的候补" />
      ) : (
        items.map((item) => {
          const meta = WAITLIST_STATUS_META[item.status] || { color: 'default' };
          const offered = item.status === 'OFFERED';
          const rankLabel = offered ? '你排在第 1 位' : `队内第 ${item.position ?? '?'} 位`;
          return (
            <Card
              key={item.waitlistId}
              style={{ marginBottom: 12 }}
              title={(
                <Space>
                  <span>{item.zoneName}</span>
                  <Tag color={meta.color}>{item.statusText}</Tag>
                  {offered ? <Tag color="gold">轮到你确认</Tag> : null}
                </Space>
              )}
              extra={(
                <Space>
                  <Tooltip title={item.canConfirm ? null : item.confirmDisabledReason}>
                    <Button
                      type="primary"
                      disabled={!item.canConfirm}
                      loading={busyId === item.waitlistId}
                      onClick={() => handleConfirm(item)}
                    >
                      确认预约
                    </Button>
                  </Tooltip>
                  <Tooltip title={item.canQuit ? null : item.quitDisabledReason}>
                    <Button disabled={!item.canQuit} onClick={() => handleQuit(item)}>退出</Button>
                  </Tooltip>
                </Space>
              )}
            >
              <Typography.Paragraph style={{ marginBottom: 6 }}>
                {item.date} {item.timeRange}（{item.durationHours} 小时）
              </Typography.Paragraph>
              <Space split={<span> · </span>} wrap>
                <Typography.Text type="secondary">
                  {`${rankLabel}（本时段共 ${item.queueLength} 人排队）`}
                </Typography.Text>
                {item.minutesToExpire != null ? (
                  <Typography.Text type={item.minutesToExpire > 0 ? 'warning' : 'secondary'}>
                    {item.minutesToExpire > 0
                      ? `请在 ${item.minutesToExpire} 分钟内确认`
                      : '确认窗口已过'}
                  </Typography.Text>
                ) : null}
                {offered && item.offerExpireTime ? (
                  <Typography.Text type="secondary">截止 {item.offerExpireTime}</Typography.Text>
                ) : null}
              </Space>
            </Card>
          );
        })
      )}
    </div>
  );
}
