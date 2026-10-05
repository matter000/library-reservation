import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Card, Progress, Select, Space, Spin, Tag, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { fetchFloors, fetchSlots } from '../../api/floor';
import { fetchUserZones } from '../../api/space';
import { FLOOR_STATUS, FLOOR_STATUS_META } from '../../constants';
import { buildFallbackSlots, getBookableDates, nowSlot, nowTimeText } from '../../utils/datetime';

/**
 * 移动端首页：手机上只回答「现在还有没有位子」。
 *
 * <p>★ 余量数字一律来自接口（`/floors` 与 `/staff/zones` 的学生视图同一口径），
 * 这里不把自己的估算写进卡片 —— 首页说「还有 3 个」而平面图一个都没有，
 * 是最伤信任的一种偏差。
 *
 * @returns {JSX.Element} 移动端首页
 */
export default function MobileHome() {
  const navigate = useNavigate();
  const [floors, setFloors] = useState([]);
  const [zones, setZones] = useState([]);
  const [dates, setDates] = useState(() => getBookableDates(7));
  const [date, setDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [slot, setSlot] = useState(nowSlot());
  const [slots, setSlots] = useState(buildFallbackSlots());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [updatedAt, setUpdatedAt] = useState('');

  const load = useCallback(async (silent = false) => {
    if (!silent) {
      setLoading(true);
    }
    try {
      const [floorList, zoneList] = await Promise.all([
        fetchFloors({ date, slot }),
        fetchUserZones({ date, slot }),
      ]);
      setFloors(Array.isArray(floorList) ? floorList : []);
      setZones(Array.isArray(zoneList) ? zoneList : []);
      setUpdatedAt(nowTimeText());
      setError('');
    } catch (e) {
      setError(e.message || '余座信息加载失败');
    } finally {
      setLoading(false);
    }
  }, [date, slot]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    fetchSlots(date)
      .then((data) => setSlots(Array.isArray(data) && data.length ? data : buildFallbackSlots()))
      .catch(() => setSlots(buildFallbackSlots()));
    setDates(getBookableDates(7));
  }, [date]);

  return (
    <div className="m-page">
      <Space className="m-filter-row" wrap size={8}>
        <Select
          size="small"
          value={date}
          style={{ width: 128 }}
          options={dates.map((d) => ({ value: d, label: d.slice(5) }))}
          onChange={setDate}
          aria-label="选择日期"
        />
        <Select
          size="small"
          value={slot}
          style={{ width: 150 }}
          options={slots.filter((s) => s.bookable !== false)
            .map((s) => ({ value: s.slotIndex, label: s.label }))}
          onChange={setSlot}
          aria-label="选择时段"
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          更新于 {updatedAt || '...'}
        </Typography.Text>
      </Space>

      {error ? <Alert type="error" showIcon message={error} className="m-gap" /> : null}

      <Spin spinning={loading}>
        {floors.map((floor) => {
          const closed = floor.status === FLOOR_STATUS.CLOSED;
          const rate = floor.usageRate || 0;
          return (
            <Card
              key={floor.floorId}
              size="small"
              className="m-gap"
              title={(
                <Space>
                  <span>{floor.name}</span>
                  <Tag color={FLOOR_STATUS_META[floor.status]?.color || 'default'}>{floor.code}</Tag>
                  {closed ? <Tag>暂不开放</Tag> : null}
                </Space>
              )}
              extra={<Link to={`/m/seat?floorId=${floor.floorId}&date=${date}&slot=${slot}`}>找座</Link>}
            >
              <div className="m-floor-numbers">
                <span>空闲 <strong className="m-free">{floor.freeSeats || 0}</strong></span>
                <span>共 {floor.totalSeats || 0} 座</span>
              </div>
              <Progress
                percent={Number(rate.toFixed ? rate.toFixed(1) : rate)}
                size="small"
                status={closed ? 'normal' : undefined}
                format={(p) => `占用 ${p}%`}
              />
              <div className="m-zone-list">
                {zones.filter((z) => z.floorId === floor.floorId).map((z) => (
                  <div key={z.zoneId} className="m-zone-row">
                    <span className="m-zone-name">
                      {z.name}
                      {z.peakFlag ? <Tag color="volcano" className="m-zone-peak">高峰</Tag> : null}
                    </span>
                    <span className={z.freeSeats > 0 ? 'm-zone-free' : 'm-zone-full'}>
                      {z.freeSeats}/{z.totalSeats} 空
                    </span>
                  </div>
                ))}
              </div>
              {closed ? null : (
                <button
                  type="button"
                  className="m-primary-button"
                  onClick={() => navigate(`/m/seat?floorId=${floor.floorId}&date=${date}&slot=${slot}`)}
                >
                  看这一层的座位
                </button>
              )}
            </Card>
          );
        })}
        {!loading && !floors.length ? (
          <Alert type="info" showIcon message="暂时没有开放的楼层" description="可稍后再刷新看看。" />
        ) : null}
      </Spin>
    </div>
  );
}
