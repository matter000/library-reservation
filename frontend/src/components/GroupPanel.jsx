import React, { useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Input, Popconfirm, Space, Table, Tag, Typography, message,
} from 'antd';
import {
  attendGroup, cancelGroup, checkInGroup, confirmGroup, inviteMember, quitGroup,
} from '../api/group';
import HintedButton from './HintedButton';
import { ATTEND_STATUS_META, GROUP_STATUS_META, INVITE_STATUS_META } from '../constants';
import useBookingConfig from '../hooks/useBookingConfig';

/**
 * 队伍面板（T-V2-17 核心组件，建队页与我的队伍页共用）。
 *
 * 数据全部来自 GroupVO：按钮可用性一律用后端返回的 canXxx + xxxDisabledReason 渲染。
 * ★ 成员那一行的「确认 / 退出 / 入座」只有本人能点：后端 confirm 作用在调用者身上，
 * 组长替别人点确认改的是组长自己的状态，所以按钮只挂在 self 那一行。
 *
 * @param {object} props props
 * @param {object} props.group GroupVO
 * @param {Function} props.onChanged 操作成功后的新 GroupVO 回调
 * @returns {JSX.Element} 面板
 */
export default function GroupPanel({ group, onChanged }) {
  const { groupLockBeforeMin, groupInviteTtlMin, groupAttendGraceMin } = useBookingConfig();
  const [inviteName, setInviteName] = useState('');
  const [acting, setActing] = useState('');

  const members = group.members || [];
  const me = members.find((m) => m.self) || null;

  /**
   * 执行一个队伍动作：成功后用后端返回的新视图刷新，不本地拼状态。
   *
   * @param {string} key 动作标识（loading 态）
   * @param {Function} fn 接口调用
   * @param {string} okText 成功提示
   */
  const run = async (key, fn, okText) => {
    setActing(key);
    try {
      const next = await fn();
      message.success(okText);
      onChanged(next);
    } catch (e) {
      /* 提示由拦截器统一弹出 */
    } finally {
      setActing('');
    }
  };

  const handleInvite = async () => {
    const name = inviteName.trim();
    if (!name) {
      message.warning('请填写要邀请的账号');
      return;
    }
    await run('invite', () => inviteMember(group.groupId, name), `已向 ${name} 发出邀请`);
    setInviteName('');
  };

  const statusMeta = GROUP_STATUS_META[group.status] || { color: 'default' };
  const lockText = group.status === 'FORMING'
    ? (group.minutesToLock > 0
      ? `距锁定还剩 ${group.minutesToLock} 分钟`
      : '锁定时刻已到，等待系统按桌下单')
    : '-';

  const columns = [
    {
      title: '成员',
      dataIndex: 'realName',
      render: (text, row) => (
        <Space size={4}>
          <Typography.Text>{text}</Typography.Text>
          {row.memberRole === 'LEADER' ? <Tag color="gold">组长</Tag> : null}
          {row.self ? <Tag color="blue">我</Tag> : null}
        </Space>
      ),
    },
    { title: '账号', dataIndex: 'username', width: 120 },
    {
      title: '邀请状态',
      dataIndex: 'inviteStatus',
      width: 100,
      render: (value, row) => (
        <Tag color={(INVITE_STATUS_META[value] || {}).color || 'default'}>{row.inviteStatusText}</Tag>
      ),
    },
    { title: '确认时刻', dataIndex: 'confirmTime', width: 160, render: (text) => text || '-' },
    {
      title: '座位',
      dataIndex: 'seatNo',
      width: 90,
      // 座位在锁定下单时才分配，组队中这一列必然为空
      render: (text) => text || '待分配',
    },
    {
      title: '入座',
      dataIndex: 'attendStatus',
      width: 110,
      render: (value, row) => (
        <Tag color={(ATTEND_STATUS_META[value] || {}).color || 'default'}>{row.attendStatusText}</Tag>
      ),
    },
  ];

  return (
    <Card
      title={(
        <Space>
          <span>{`第 ${group.groupId} 队 · ${group.zoneName || ''} ${group.tableNo || ''}`}</span>
          <Tag color={statusMeta.color}>{group.statusText}</Tag>
        </Space>
      )}
      extra={(
        <Space>
          <Typography.Text type="secondary">
            {`已确认 ${group.confirmedCount} / 成团 ${group.minMembers} · 上限 ${group.maxMembers}`}
          </Typography.Text>
          {group.invitedCount > 0 ? (
            <Tag color="gold">{`${group.invitedCount} 人待确认`}</Tag>
          ) : null}
        </Space>
      )}
    >
      <Descriptions column={2} size="small" bordered style={{ marginBottom: 12 }}>
        <Descriptions.Item label="组长">{group.leaderName || '-'}</Descriptions.Item>
        <Descriptions.Item label="桌位">{`${group.zoneName || ''} ${group.tableNo || ''}`}</Descriptions.Item>
        <Descriptions.Item label="日期">{group.date}</Descriptions.Item>
        <Descriptions.Item label="时段">{group.timeRange}</Descriptions.Item>
        <Descriptions.Item label="锁定时刻">{group.lockTime || '-'}</Descriptions.Item>
        <Descriptions.Item label="倒计时">{lockText}</Descriptions.Item>
        {group.leader ? (
          <Descriptions.Item label="邀请码" span={2}>
            {group.inviteCode ? (
              <Space>
                <Typography.Text copyable strong style={{ fontSize: 16 }}>
                  {group.inviteCode}
                </Typography.Text>
                <Tag color={group.inviteAlive ? 'green' : 'red'}>
                  {group.inviteAlive ? `有效至 ${group.inviteExpireTime}` : '已失效'}
                </Tag>
              </Space>
            ) : '-' }
          </Descriptions.Item>
        ) : null}
      </Descriptions>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={`时段开始前 ${groupLockBeforeMin} 分钟锁定队伍并按桌下单；`
          + `到点还未确认的成员（邀请超过 ${groupInviteTtlMin} 分钟未响应即过期）会被移出队伍。`}
        description={`组长签到即代表全队签到，组长缺席则整桌取消；`
          + `成员请在组长签到后 ${groupAttendGraceMin} 分钟内确认入座，`
          + '未确认记为组队缺席（座位仍归你，不会被当场释放）。'}
      />

      {group.leader ? (
        <Space wrap style={{ marginBottom: 12 }}>
          <Input
            placeholder="输入同学的学习号 / 账号"
            value={inviteName}
            style={{ width: 220 }}
            onChange={(e) => setInviteName(e.target.value)}
            onPressEnter={handleInvite}
          />
          <HintedButton tip={group.inviteDisabledReason}>
            <Button
              type="primary"
              ghost
              loading={acting === 'invite'}
              disabled={!group.canInvite}
              onClick={handleInvite}
            >
              邀请
            </Button>
          </HintedButton>
          <HintedButton tip={group.checkInDisabledReason}>
            <Button
              type="primary"
              loading={acting === 'check-in'}
              disabled={!group.canCheckIn}
              onClick={() => run('check-in', () => checkInGroup(group.groupId), '组长签到成功，全队预约已点开')}
            >
              组长签到
            </Button>
          </HintedButton>
          <HintedButton tip={group.cancelDisabledReason}>
            <Popconfirm
              title="取消后已占的座位会全部释放"
              okText="确认取消"
              cancelText="再想想"
              disabled={!group.canCancel}
              onConfirm={() => run('cancel', () => cancelGroup(group.groupId), '队伍已取消')}
            >
              <Button danger disabled={!group.canCancel} loading={acting === 'cancel'}>
                取消队伍
              </Button>
            </Popconfirm>
          </HintedButton>
        </Space>
      ) : null}

      {me ? (
        <Space wrap style={{ marginBottom: 12 }}>
          <HintedButton tip={me.confirmDisabledReason}>
            <Button
              type="primary"
              loading={acting === 'confirm'}
              disabled={!me.canConfirm}
              onClick={() => run('confirm', () => confirmGroup(group.groupId), '已确认参加，等待锁定下单')}
            >
              确认参加
            </Button>
          </HintedButton>
          <HintedButton tip={me.attendDisabledReason}>
            <Button
              loading={acting === 'attend'}
              disabled={!me.canAttend}
              onClick={() => run('attend', () => attendGroup(group.groupId), '已确认入座')}
            >
              我入座了
            </Button>
          </HintedButton>
          <HintedButton tip={me.quitDisabledReason}>
            <Popconfirm
              title="退出后队伍人数可能不足，导致整队未成团"
              okText="确认退出"
              cancelText="取消"
              disabled={!me.canQuit}
              onConfirm={() => run('quit', () => quitGroup(group.groupId), '已退出队伍')}
            >
              <Button danger disabled={!me.canQuit} loading={acting === 'quit'}>
                退出队伍
              </Button>
            </Popconfirm>
          </HintedButton>
        </Space>
      ) : null}

      <Table
        rowKey="memberId"
        size="small"
        pagination={false}
        columns={columns}
        dataSource={members}
      />
    </Card>
  );
}
