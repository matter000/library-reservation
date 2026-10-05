import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Segmented, Select, Space, Spin, Tag, Typography, message } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { fetchFloors, fetchSeatMap, fetchSlots } from '../../api/floor';
import { fetchUserZones } from '../../api/space';
import { createReservation } from '../../api/reservation';
import { joinWaitlist } from '../../api/waitlist';
import SeatGrid from '../../components/SeatGrid';
import SeatLegend from '../../components/SeatLegend';
import useBookingConfig from '../../hooks/useBookingConfig';
import { ERROR_CODE, FLOOR_STATUS } from '../../constants';
import { buildFallbackSlots, buildTimeRange, nowSlot, nowTimeText, slotLabel } from '../../utils/datetime';

/** 平面图轮询间隔：与 PC 端同一档，页面不可见或抽屉打开时暂停。 */
const POLL_MS = 30000;

/**
 * 移动端找座 → 预约（PRD §13.2：进入平面图 → 点座 → 确认，三次点击内完成）。
 *
 * <p>★ 这页只收窄布局，不重写规则：时长选项与默认值来自 `useBookingConfig`（后端规则中心），
 * 能不能预约、为什么不能仍由服务端判定。移动壳最容易犯的错是为了「省一步」在本地再实现一条判断，
 * 然后与 PC 行为不一致 —— 那才是真的把同一个业务写出了两个版本。
 *
 * <p>★ 楼层与分区都自动落到第一个可用项：从首页点「找座」过来时已经带着 floorId/date/slot，
 * 少一次选择就少一次点击，而分区也是排候补的必要条件，不收窄到分区反而没法用。
 *
 * @returns {JSX.Element} 移动端平面图
 */
export default function MobileSeat() {
  const [params] = useSearchParams();
  const [floors, setFloors] = useState([]);
  const [zones, setZones] = useState([]);
  const [floorId, setFloorId] = useState(params.get('floorId') ? Number(params.get('floorId')) : null);
  const [zoneId, setZoneId] = useState(null);
  const [date, setDate] = useState(params.get('date') || new Date().toISOString().slice(0, 10));
  const [slot, setSlot] = useState(params.get('slot') ? Number(params.get('slot')) : nowSlot());
  const [slots, setSlots] = useState(buildFallbackSlots());
  const [mapData, setMapData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [updatedAt, setUpdatedAt] = useState('');
  const [picked, setPicked] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const inflight = useRef(false);

  const { durationOptions, defaultDuration } = useBookingConfig();

  /** 楼层列表：URL 没带楼层时自动落到第一个开放的层。 */
  useEffect(() => {
    fetchFloors({ date, slot })
      .then((list) => {
        const arr = Array.isArray(list) ? list : [];
        setFloors(arr);
        setFloorId((cur) => {
          if (cur && arr.some((f) => f.floorId === cur)) {
            return cur;
          }
          const first = arr.find((f) => f.status === FLOOR_STATUS.OPEN) || arr[0];
          return first ? first.floorId : null;
        });
      })
      .catch(() => setFloors([]));
  }, [date, slot]);

  const loadMap = useCallback(async (silent = false) => {
    if (!floorId) {
      return;
    }
    if (!silent) {
      setLoading(true);
    }
    try {
      const [data, zoneList] = await Promise.all([
        fetchSeatMap({ floorId, date, slot, zoneId: zoneId || undefined }),
        fetchUserZones({ floorId, date, slot }),
      ]);
      setMapData(data);
      const list = Array.isArray(zoneList) ? zoneList : [];
      setZones(list);
      setZoneId((cur) => (cur && list.some((z) => z.zoneId === cur) ? cur : (list.length ? list[0].zoneId : null)));
      setUpdatedAt(nowTimeText());
      setError('');
    } catch (e) {
      if (!silent) {
        setError(e.message || '平面图加载失败');
      }
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, [floorId, date, slot, zoneId]);

  useEffect(() => {
    loadMap();
  }, [loadMap]);

  useEffect(() => {
    fetchSlots(date)
      .then((data) => setSlots(Array.isArray(data) && data.length ? data : buildFallbackSlots()))
      .catch(() => setSlots(buildFallbackSlots()));
  }, [date]);

  /**
   * 手机上不该让人从「当前时刻」开始自己找哪格还能订：
   * 已经过去的格点下去只会拿到一句「不能预约已过去的时段」，所以默认落到第一个可订的格。
   */
  useEffect(() => {
    const first = slots.find((item) => item.bookable !== false);
    if (first && !slots.some((item) => item.slotIndex === slot && item.bookable !== false)) {
      setSlot(first.slotIndex);
    }
  }, [slots, slot]);

  useEffect(() => {
    const timer = setInterval(() => {
      if (document.hidden || picked || inflight.current) {
        return;
      }
      inflight.current = true;
      Promise.resolve(loadMap(true)).finally(() => {
        inflight.current = false;
      });
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [loadMap, picked]);

  const seats = mapData && mapData.seats ? mapData.seats : [];
  const stats = mapData ? mapData.stats : null;
  const freeCount = stats ? stats.free : 0;
  const floorClosed = mapData ? mapData.floorStatus === FLOOR_STATUS.CLOSED : false;
  const activeZoneId = mapData && mapData.zoneId ? mapData.zoneId : zoneId;
  const currentZone = useMemo(
    () => zones.find((z) => z.zoneId === activeZoneId) || null,
    [zones, activeZoneId],
  );
  const bookableSlots = useMemo(
    () => slots.filter((s) => s.bookable !== false).map((s) => ({ value: s.slotIndex, label: s.label })),
    [slots],
  );

  const openPick = (seat) => {
    // 抽屉里的时长挂在 picked 上：确认与「改排候补」用的是同一个值，不存在两处不同步
    setPicked({ ...seat, duration: defaultDuration || 2 });
  };

  const submit = async () => {
    setSubmitting(true);
    try {
      await createReservation({ seatId: picked.seatId, date, startSlot: slot, duration: picked.duration });
      message.success(`已预约 ${picked.seatNo} ${buildTimeRange(slot, picked.duration)}`);
      setPicked(null);
      await loadMap(true);
    } catch (e) {
      if (e && e.code === ERROR_CODE.SEAT_SLOT_CONFLICT) {
        // 座位刚被别人抢走：立刻刷新平面图，不让人对着过期数据再点一次（PRD §13.4）
        await loadMap(true);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const submitWaitlist = async () => {
    if (!currentZone) {
      message.warning('请先选一个分区再排候补');
      return;
    }
    setSubmitting(true);
    try {
      const joined = await joinWaitlist({
        zoneId: currentZone.zoneId, date, startSlot: slot, duration: picked ? picked.duration : defaultDuration,
      });
      message.success(`已加入候补：第 ${joined.position} 位 / 共 ${joined.queueLength} 人`);
      setPicked(null);
    } catch (e) {
      // 原因（还有空位 / 名额已满 / 时段已过）由拦截器弹出，这里补一次余量刷新
      await loadMap(true);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="m-page">
      <div className="m-chip-row" role="group" aria-label="楼层">
        {floors.map((f) => (
          <button
            key={f.floorId}
            type="button"
            className={f.floorId === floorId ? 'm-chip m-chip-active' : 'm-chip'}
            aria-pressed={f.floorId === floorId}
            onClick={() => setFloorId(f.floorId)}
          >
            {f.name}
          </button>
        ))}
      </div>

      <Space wrap size={8} className="m-filter-row">
        <Select
          size="small"
          value={slot}
          style={{ width: 150 }}
          aria-label="时段"
          options={bookableSlots}
          onChange={setSlot}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {date} {slotLabel(slot)} · 更新于 {updatedAt || '...'}
        </Typography.Text>
      </Space>

      <div className="m-chip-row" role="group" aria-label="分区">
        {zones.map((z) => (
          <button
            key={z.zoneId}
            type="button"
            className={z.zoneId === activeZoneId ? 'm-chip m-chip-active' : 'm-chip'}
            aria-pressed={z.zoneId === activeZoneId}
            onClick={() => setZoneId(z.zoneId)}
          >
            {z.name} {z.freeSeats}/{z.totalSeats}
            {z.peakFlag ? <Tag color="volcano" className="m-zone-peak">高峰</Tag> : null}
          </button>
        ))}
      </div>

      {error ? <Alert type="error" showIcon message={error} className="m-gap" /> : null}
      {floorClosed ? (
        <Alert type="warning" showIcon className="m-gap" message="本层暂不开放，换一层看看" />
      ) : null}
      {!floorClosed && freeCount === 0 ? (
        <Alert
          type="info"
          showIcon
          className="m-gap"
          message={`${currentZone ? currentZone.name : '本分区'} 这个时段已无空位`}
          description="可以排候补：轮到时会给你一条站内通知，需要你在规定时间内确认（站内通知，不是推送）。"
        />
      ) : null}

      <Spin spinning={loading}>
        <SeatLegend />
        <SeatGrid
          seats={seats}
          maxRow={mapData ? mapData.maxRow : 1}
          maxCol={mapData ? mapData.maxCol : 1}
          selectedSeatId={picked ? picked.seatId : null}
          selectedTableIds={[]}
          tableMode={false}
          onSelect={openPick}
        />
        <Typography.Text type="secondary" className="m-note">
          绿=空闲可点，共 {seats.length} 格、空闲 {freeCount}。窄屏横向滑动看全，不缩放（缩了反而点不准）。
        </Typography.Text>
      </Spin>

      {picked ? (
        <div className="m-sheet-mask" role="presentation" onClick={() => setPicked(null)}>
          <div className="m-sheet" role="dialog" aria-modal="true" aria-label="确认预约" onClick={(e) => e.stopPropagation()}>
            <Typography.Text strong className="m-sheet-title">
              {picked.seatNo} · {buildTimeRange(slot, picked.duration)}
            </Typography.Text>
            <Typography.Text type="secondary" className="m-note">
              {date} {slotLabel(slot)}，时长可一键调整
            </Typography.Text>
            <Segmented
              options={durationOptions}
              value={picked.duration}
              onChange={(value) => setPicked((cur) => ({ ...cur, duration: value }))}
              aria-label="预约时长"
            />
            <button type="button" className="m-primary-button" disabled={submitting} onClick={submit}>
              {submitting ? '提交中…' : '确认预约'}
            </button>
            {freeCount === 0 ? (
              <button type="button" className="m-secondary-button" disabled={submitting} onClick={submitWaitlist}>
                改排本分区候补
              </button>
            ) : null}
            <button type="button" className="m-link-button" onClick={() => setPicked(null)}>先不订</button>
            <Typography.Text type="secondary" className="m-note">
              预约后请在签到窗口内到馆签到，超时未签到会记一次爽约并扣信用分。
            </Typography.Text>
          </div>
        </div>
      ) : null}
    </div>
  );
}
