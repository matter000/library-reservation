import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Badge, Divider, Empty, List, Popover, Space, Tag, Typography } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { fetchNotices, markNoticeRead } from '../api/notice';
import { NOTICE_POLL_INTERVAL } from '../constants';

/**
 * 顶栏铃铛（T-V2-20）。
 *
 * <p>15 秒轮询一次，弹窗打开时暂停 —— 列表在用户正在读的时候被刷掉，比晚 15 秒看到消息更烦人。
 * 页面在后台标签页时不发请求，切回前台立刻补一次，与平面图同一套防护。
 *
 * <p>★ 没有推送通道，所以「保持页面开着」是收到提醒的前提。这句话必须写在面板里：
 * 用户如果以为手机会收到通知，就会在错过候补确认窗口后把责任推给系统，而这是设计取舍。
 *
 * @returns {JSX.Element} 铃铛
 */
export default function NoticeBell() {
  const navigate = useNavigate();
  const [data, setData] = useState({ list: [], unread: 0, total: 0 });
  const [open, setOpen] = useState(false);
  const timerRef = useRef(null);

  const load = useCallback(async () => {
    try {
      const next = await fetchNotices();
      setData(next || { list: [], unread: 0, total: 0 });
    } catch (e) {
      // 静默：拉不到通知不该挡住主流程，也不该在顶栏刷错误提示
    }
  }, []);

  useEffect(() => {
    load();
    timerRef.current = setInterval(() => {
      if (!document.hidden && !open) {
        load();
      }
    }, NOTICE_POLL_INTERVAL);
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
  }, [load, open]);

  /** 点一条：先标已读再跳服务端给的路径（链接由 NoticeType 决定，前端不猜）。 */
  const handleOpen = async (item) => {
    try {
      await markNoticeRead(item.noticeId);
    } catch (e) {
      /* 已读失败不影响跳转：通知只是提醒，不是状态 */
    }
    setOpen(false);
    await load();
    navigate(item.link);
  };

  const panel = (
    <div style={{ width: 330 }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        站内通知没有推送通道：页面关着就收不到提醒。重要时刻（候补确认、暂离到期）请保持本页开着。
      </Typography.Text>
      <Divider style={{ margin: '8px 0' }} />
      {data.list.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无通知" />
      ) : (
        <List
          size="small"
          dataSource={data.list}
          style={{ maxHeight: 360, overflow: 'auto' }}
          renderItem={(item) => (
            <List.Item
              onClick={() => handleOpen(item)}
              style={{ cursor: 'pointer', alignItems: 'flex-start' }}
            >
              <Space direction="vertical" size={2}>
                <Space size={6} wrap>
                  {!item.readFlag ? <Badge status="processing" /> : null}
                  <Typography.Text strong>{item.title}</Typography.Text>
                  <Tag>{item.typeText}</Tag>
                  {item.expired ? <Tag color="default">已过期</Tag> : null}
                </Space>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {item.content}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {item.createTime}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
      {data.total > data.list.length ? (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {`共 ${data.total} 条，仅显示最近 ${data.list.length} 条`}
        </Typography.Text>
      ) : null}
    </div>
  );

  /**
   * 打开时立即补一次拉取：轮询最长 15 秒的间隔意味着面板里的列表可能是旧的，
   * 而用户点开铃铛就是想看「现在有没有要我处理的」。
   *
   * @param {boolean} next 是否打开
   */
  const handleOpenChange = (next) => {
    setOpen(next);
    if (next) {
      load();
    }
  };

  return (
    <Popover
      trigger="click"
      placement="bottomRight"
      open={open}
      onOpenChange={handleOpenChange}
      content={panel}
      title={`通知（未读 ${data.unread}）`}
    >
      <Badge count={data.unread} size="small" offset={[-2, 2]}>
        <BellOutlined style={{ color: '#fff', fontSize: 18, cursor: 'pointer' }} />
      </Badge>
    </Popover>
  );
}
