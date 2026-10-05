import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Empty, Input, Space, Spin, Tag, Typography, message } from 'antd';
import { PlusOutlined, TeamOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { fetchGroup, fetchMyGroups, joinGroup } from '../api/group';
import GroupPanel from '../components/GroupPanel';
import { GROUP_POLL_INTERVAL, GROUP_STATUS_META } from '../constants';

/**
 * 我的队伍页（T-V2-17）。
 *
 * 三块内容：邀请码加入、进行中的队伍列表、选中队伍的完整面板。
 * 列表按 15 秒轮询 —— 锁定与邀请过期都按分钟推进，30 秒会让倒计时看起来卡住；
 * 页面切到后台时跳过本轮，与平面图同一套防护。
 *
 * URL 上的 `?groupId=` 用于从通知 / 建队成功跳转直达某队；已终态的队伍不在
 * 「我的队伍」里（后端只回非终态），此时改走详情接口，避免链接点开是空的。
 *
 * @returns {JSX.Element} 我的队伍页
 */
export default function MyGroup() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const wantGroupId = searchParams.get('groupId') ? Number(searchParams.get('groupId')) : null;

  const [loading, setLoading] = useState(false);
  const [groups, setGroups] = useState([]);
  const [error, setError] = useState('');
  const [selectedId, setSelectedId] = useState(wantGroupId);
  const [selected, setSelected] = useState(null);
  const [code, setCode] = useState('');
  const [joining, setJoining] = useState(false);
  const timerRef = useRef(null);
  const fetchedRef = useRef(null);

  const load = useCallback(async () => {
    setError('');
    try {
      setGroups(await fetchMyGroups() || []);
    } catch (e) {
      setError(e.message || '队伍列表加载失败');
    }
  }, []);

  useEffect(() => {
    setLoading(true);
    load().finally(() => setLoading(false));
  }, [load]);

  /** 轮询：后台标签页不占用请求。 */
  useEffect(() => {
    timerRef.current = setInterval(() => {
      if (!document.hidden) {
        load();
      }
    }, GROUP_POLL_INTERVAL);
    return () => clearInterval(timerRef.current);
  }, [load]);

  /** 选中项：优先 URL 指定，其次仍在列表里的上一选择，最后取最新一队。 */
  useEffect(() => {
    const target = wantGroupId || selectedId;
    if (!target) {
      setSelected(groups[0] || null);
      return;
    }
    const hit = groups.find((g) => g.groupId === target);
    if (hit) {
      setSelected(hit);
      return;
    }
    if (fetchedRef.current === target) {
      // 已经为这个深链取过详情了（终态队伍不在列表里），别每轮轮询都再取一次
      return;
    }
    fetchedRef.current = target;
    fetchGroup(target)
      .then((data) => setSelected(data))
      .catch(() => {
        fetchedRef.current = null;
        setSelected(groups[0] || null);
      });
  }, [groups, selectedId, wantGroupId]);

  /** 加入成功后：清掉深链参数并立刻刷新列表。 */
  const handleJoin = async () => {
    const value = code.trim().toUpperCase();
    if (!value) {
      message.warning('请填写组长给你的邀请码');
      return;
    }
    setJoining(true);
    try {
      const joined = await joinGroup(value);
      message.success(`已加入第 ${joined.groupId} 队，请在锁定前确认参加`);
      setCode('');
      setSelectedId(joined.groupId);
      setSearchParams({ groupId: String(joined.groupId) }, { replace: true });
      await load();
    } catch (e) {
      /* 提示由拦截器弹出 */
    } finally {
      setJoining(false);
    }
  };

  /** 面板内动作成功：用后端返回的新视图就地替换，不等下一轮轮询。 */
  const handleChange = (next) => {
    setSelected(next);
    setGroups((cur) => cur.map((g) => (g.groupId === next.groupId ? next : g)));
  };

  return (
    <div>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          <TeamOutlined /> 我的队伍
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/groups/create')}>
          新建队伍
        </Button>
      </Space>

      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Input
            placeholder="邀请码（组长处复制）"
            value={code}
            style={{ width: 200 }}
            maxLength={6}
            onChange={(e) => setCode(e.target.value)}
            onPressEnter={handleJoin}
          />
          <Button type="primary" ghost loading={joining} onClick={handleJoin}>
            用邀请码加入
          </Button>
          <Typography.Text type="secondary">加入后还需要本人点「确认参加」才会占座</Typography.Text>
        </Space>
      </Card>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Spin spinning={loading && groups.length === 0}>
        {groups.length === 0 && !selected ? (
          <Empty description="你还没有进行中的队伍" style={{ padding: '32px 0' }} />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {groups.map((g) => {
              const meta = GROUP_STATUS_META[g.status] || { color: 'default' };
              const active = selected && selected.groupId === g.groupId;
              return (
                <Card
                  key={g.groupId}
                  size="small"
                  hoverable
                  onClick={() => {
                    setSelectedId(g.groupId);
                    setSelected(g);
                  }}
                  style={active ? { borderColor: '#1677ff' } : undefined}
                >
                  <Space wrap>
                    <Typography.Text strong>{`第 ${g.groupId} 队`}</Typography.Text>
                    <Tag color={meta.color}>{g.statusText}</Tag>
                    <Typography.Text>{`${g.zoneName || ''} ${g.tableNo || ''}`}</Typography.Text>
                    <Typography.Text type="secondary">{`${g.date} ${g.timeRange}`}</Typography.Text>
                    <Typography.Text type="secondary">
                      {g.leader ? '我是组长' : '我是成员'}
                    </Typography.Text>
                    {g.myInviteStatus ? (
                      <Typography.Text type="secondary">{`我的状态：${g.myInviteStatus}`}</Typography.Text>
                    ) : null}
                  </Space>
                </Card>
              );
            })}
            {selected ? <GroupPanel group={selected} onChanged={handleChange} /> : null}
          </Space>
        )}
      </Spin>
    </div>
  );
}
