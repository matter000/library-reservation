import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Breadcrumb,
  Button,
  DatePicker,
  Descriptions,
  Drawer,
  Radio,
  Select,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { fetchFloor, fetchSeatMap, fetchSlots } from '../api/floor';
import { fetchUserZones } from '../api/space';
import { createReservation } from '../api/reservation';
import SeatGrid from '../components/SeatGrid';
import SeatLegend from '../components/SeatLegend';
import {
  DEFAULT_DURATION,
  ERROR_CODE,
  FLOOR_STATUS,
  FLOOR_STATUS_META,
  SEAT_STATUS_META,
  SEAT_MAP_POLL_INTERVAL,
} from '../constants';
import useBookingConfig from '../hooks/useBookingConfig';
import { buildFallbackSlots, buildTimeRange, getBookableDates, nowSlot, nowTimeText, slotLabel } from '../utils/datetime';

/**
 * ★ 座位平面图页（核心页）。
 *
 * 30 秒轮询刷新；Drawer 打开时暂停轮询，关闭后立即刷新并恢复轮询。
 * 抢座失败（4091）自动刷新平面图。
 *
 * @returns {JSX.Element} 平面图页
 */
export default function SeatMap() {
  const { floorId } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  const date = searchParams.get('date') || dayjs().format('YYYY-MM-DD');
  const slot = Number(searchParams.get('slot') || nowSlot());
  const zoneId = searchParams.get('zoneId') ? Number(searchParams.get('zoneId')) : null;

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [mapData, setMapData] = useState(null);
  const [floorInfo, setFloorInfo] = useState(null);
  const [slots, setSlots] = useState(buildFallbackSlots());
  const [lastUpdated, setLastUpdated] = useState('');
  const [zones, setZones] = useState([]);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedSeat, setSelectedSeat] = useState(null);
  const [duration, setDuration] = useState(DEFAULT_DURATION);
  const [submitting, setSubmitting] = useState(false);

  const timerRef = useRef(null);
  const pollInFlightRef = useRef(false);
  const {
    advanceDays, allowedDurations, durationOptions, defaultDuration, checkInBeforeMin, checkInAfterMin,
  } = useBookingConfig();
  const bookableDates = useMemo(() => getBookableDates(advanceDays), [advanceDays]);

  // 规则收紧后把已选时长收敛回合法集合
  useEffect(() => {
    setDuration((cur) => (allowedDurations.includes(cur) ? cur : defaultDuration));
  }, [allowedDurations, defaultDuration]);

  /**
   * 拉取平面图。
   *
   * @param {boolean} silent 静默刷新（轮询专用）：不切换 loading 态，避免刷新按钮每 30 秒闪一次；
   *                         失败时也保留上一次数据，不把整页变成错误态
   */
  const loadMap = useCallback(async (silent = false) => {
    if (!floorId) {
      return;
    }
    if (!silent) {
      setLoading(true);
      setError('');
    }
    try {
      const data = await fetchSeatMap({
        floorId, date, slot, zoneId: zoneId || undefined,
      });
      setMapData(data);
      setLastUpdated(nowTimeText());
      setError('');
    } catch (e) {
      if (!silent) {
        setError(e.message || '加载平面图失败');
      }
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, [floorId, date, slot, zoneId]);

  /** 本楼层的分区列表（整层视图下作为可选项）。 */
  useEffect(() => {
    if (!floorId) {
      return;
    }
    fetchUserZones({ floorId, date, slot })
      .then((list) => setZones(list || []))
      .catch(() => setZones([]));
  }, [floorId, date, slot]);

  /** 拉取楼层信息与时段选项（仅日期/楼层变化时）。 */
  useEffect(() => {
    if (!floorId) {
      return;
    }
    fetchFloor(floorId)
      .then(setFloorInfo)
      .catch(() => setFloorInfo(null));
    fetchSlots(date)
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setSlots(data);
        } else {
          setSlots(buildFallbackSlots());
        }
      })
      .catch(() => setSlots(buildFallbackSlots()));
  }, [floorId, date]);

  /**
   * 轮询：Drawer 打开时暂停，关闭后恢复并立即刷新一次。
   *
   * 三轮防护：页面不可见时跳过本轮；上一轮未返回时跳过本轮（避免慢请求堆积）；
   * 从后台切回前台时立即补一次，保证用户看到的不是过期数据。
   */
  useEffect(() => {
    loadMap();

    if (drawerOpen) {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      return undefined;
    }

    timerRef.current = setInterval(() => {
      if (document.hidden || pollInFlightRef.current) {
        return;
      }
      pollInFlightRef.current = true;
      loadMap(true).finally(() => {
        pollInFlightRef.current = false;
      });
    }, SEAT_MAP_POLL_INTERVAL);

    const handleVisibility = () => {
      if (!document.hidden && !pollInFlightRef.current) {
        pollInFlightRef.current = true;
        loadMap(true).finally(() => {
          pollInFlightRef.current = false;
        });
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [loadMap, drawerOpen]);

  /** 更新 URL 参数（日期/时段）。 */
  const updateQuery = (nextDate, nextSlot) => {
    const params = new URLSearchParams();
    params.set('date', nextDate);
    params.set('slot', String(nextSlot));
    setSearchParams(params, { replace: true });
  };

  /** 当前选中的时段选项（用于判断是否可预约）。 */
  const currentSlotOption = slots.find((item) => item.slotIndex === slot);

  /** 分区有效规则：全部由服务端算好，这里只做展示与置灰。 */
  const zoneRules = (mapData && mapData.zoneRules) || null;

  /** 时长选项再按分区上限裁一刀（后端仍会二次校验）。 */
  const selectableDurations = zoneRules && zoneRules.maxDurationMin
    ? durationOptions.filter((option) => option.value * 60 <= zoneRules.maxDurationMin)
    : durationOptions;

  /** 点击空闲座位 → 打开 Drawer。 */
  const handleSelectSeat = (seat) => {
    setSelectedSeat(seat);
    setDuration(defaultDuration);
    setDrawerOpen(true);
  };

  /** 提交预约。 */
  const handleSubmit = async () => {
    if (!selectedSeat) {
      return;
    }
    setSubmitting(true);
    try {
      await createReservation({
        seatId: selectedSeat.seatId,
        date,
        startSlot: slot,
        duration,
      });
      message.success(`预约成功：${selectedSeat.seatNo} ${date} ${buildTimeRange(slot, duration)}，可在「我的预约」查看`);
      setDrawerOpen(false);
      setSelectedSeat(null);
      await loadMap();
    } catch (e) {
      if (e && e.code === ERROR_CODE.SEAT_SLOT_CONFLICT) {
        // 该座位刚被他人抢走：自动刷新平面图
        await loadMap();
      }
    } finally {
      setSubmitting(false);
    }
  };

  const seats = mapData && mapData.seats ? mapData.seats : [];
  const stats = mapData ? mapData.stats : null;
  const floorClosed = mapData ? mapData.floorStatus === FLOOR_STATUS.CLOSED : false;

  return (
    <div>
      {/* 1. 面包屑 */}
      <Breadcrumb
        style={{ marginBottom: 12 }}
        items={[
          { title: <Link to="/floors">楼层</Link> },
          { title: mapData ? mapData.floorName : (floorInfo ? floorInfo.name : `楼层 ${floorId}`) },
          { title: '座位平面图' },
        ]}
      />
      <div style={{ marginBottom: 12 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/floors')}>
          返回楼层
        </Button>
      </div>

      {/* 2. 筛选工具条 */}
      <div className="toolbar">
        <span>日期：</span>
        <DatePicker
          value={dayjs(date)}
          allowClear={false}
          onChange={(next) => updateQuery(next ? next.format('YYYY-MM-DD') : date, slot)}
          disabledDate={(current) => !bookableDates.includes(current.format('YYYY-MM-DD'))}
        />
        <span>时段：</span>
        <Select
          value={slot}
          style={{ width: 150 }}
          onChange={(next) => updateQuery(date, next)}
          options={slots.map((item) => ({
            value: item.slotIndex,
            label: item.label,
            disabled: item.bookable === false,
          }))}
        />
        <Button icon={<ReloadOutlined />} onClick={() => loadMap()} loading={loading}>
          刷新
        </Button>
        {zones.length > 0 ? (
          <>
            <span>分区：</span>
            <Select
              value={zoneId ?? 0}
              style={{ width: 190 }}
              onChange={(next) => {
                const params = { date, slot };
                if (next) {
                  params.zoneId = next;
                }
                setSearchParams(params);
              }}
              options={[
                { value: 0, label: '全部楼层' },
                ...zones.map((z) => ({
                  value: z.zoneId,
                  // 空闲数直接取分区卡片的同一份派生结果，避免前端再算一次
                  label: `${z.name} ${z.freeSeats}/${z.totalSeats}`,
                })),
              ]}
            />
          </>
        ) : null}
        <span className="spacer" />
        <Typography.Text type="secondary">上次更新 {lastUpdated || '--:--:--'}</Typography.Text>
      </div>

      {floorClosed ? (
        <Alert type="warning" showIcon message="该楼层已关闭，暂不开放预约" style={{ marginBottom: 12 }} />
      ) : null}
      {zoneRules && zoneRules.eligible === false ? (
        <Alert
          type="error"
          showIcon
          message={zoneRules.ineligibleReason || '当前角色不可预约该分区'}
          style={{ marginBottom: 12 }}
        />
      ) : null}
      {zoneRules && zoneRules.eligible !== false ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={`${zoneRules.zoneName}（${zoneRules.zoneTypeText}）：`
            + `单次最长 ${zoneRules.maxDurationMin} 分钟 · 提前 ${zoneRules.advanceDays} 天`
            + ` · ${zoneRules.leaveAllowed ? `可暂离 ${zoneRules.leaveMaxMin} 分钟` : '不支持暂离'}`
            + (zoneRules.groupRequired ? ' · 仅整组预约' : '')
            + (zoneRules.peakFlag ? ' · 当前时段为高峰' : '')}
        />
      ) : null}
      {currentSlotOption && currentSlotOption.bookable === false ? (
        <Alert type="info" showIcon message="当前所选时段已过期，仅可查看，不可预约" style={{ marginBottom: 12 }} />
      ) : null}
      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      {/* 3. 图例条（常驻） */}
      <SeatLegend />

      {/* 平面图统计 */}
      {stats ? (
        <div className="seat-map-stats">
          <span className="stat-item">
            总座位<b>{stats.total}</b>
          </span>
          <span className="stat-item">
            空闲<b style={{ color: '#52c41a' }}>{stats.free}</b>
          </span>
          <span className="stat-item">
            已预约<b style={{ color: '#faad14' }}>{stats.reserved}</b>
          </span>
          <span className="stat-item">
            使用中<b style={{ color: '#f5222d' }}>{stats.inUse}</b>
          </span>
          {stats.onLeave > 0 ? (
            <span className="stat-item">
              暂离中<b style={{ color: '#1677ff' }}>{stats.onLeave}</b>
            </span>
          ) : null}
          <span className="stat-item">
            不可用<b style={{ color: '#bfbfbf' }}>{stats.unavailable}</b>
          </span>
          <span className="stat-item">
            占用率<b>{stats.usageRate}%</b>
          </span>
          <span className="stat-item">
            时段<b>{mapData ? mapData.slotLabel : slotLabel(slot)}</b>
          </span>
          {mapData ? (
            <span className="stat-item">
              楼层状态
              <Tag color={FLOOR_STATUS_META[mapData.floorStatus]?.color} style={{ marginLeft: 4 }}>
                {FLOOR_STATUS_META[mapData.floorStatus]?.text || mapData.floorStatus}
              </Tag>
            </span>
          ) : null}
        </div>
      ) : null}

      {/* 4. 平面图主体 */}
      <Spin spinning={loading && !mapData}>
        {mapData ? (
          <SeatGrid
            seats={seats}
            maxRow={mapData.maxRow}
            maxCol={mapData.maxCol}
            selectedSeatId={selectedSeat ? selectedSeat.seatId : null}
            onSelect={handleSelectSeat}
          />
        ) : (
          <div style={{ minHeight: 200 }} />
        )}
      </Spin>

      {/* 5. 底部提示 */}
      <div className="seat-map-tip">点击空闲（绿色）座位即可预约；页面每 30 秒自动刷新。</div>

      {/* 预约抽屉 */}
      <Drawer
        open={drawerOpen}
        title={selectedSeat ? `预约座位 ${selectedSeat.seatNo}` : '预约座位'}
        width={380}
        onClose={() => {
          setDrawerOpen(false);
          setSelectedSeat(null);
        }}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>
              取消
            </Button>
            <Button
              type="primary"
              loading={submitting}
              disabled={floorClosed || (currentSlotOption && currentSlotOption.bookable === false)}
              onClick={handleSubmit}
            >
              确认预约
            </Button>
          </div>
        }
      >
        {selectedSeat ? (
          <>
            <Descriptions column={1} size="small" bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item label="座位编号">{selectedSeat.seatNo}</Descriptions.Item>
              <Descriptions.Item label="楼层">{mapData ? mapData.floorName : '-'}</Descriptions.Item>
              <Descriptions.Item label="区域">{selectedSeat.zone || '-'}</Descriptions.Item>
              <Descriptions.Item label="位置">
                第 {selectedSeat.rowNo} 行 第 {selectedSeat.colNo} 列
              </Descriptions.Item>
              <Descriptions.Item label="日期">{date}</Descriptions.Item>
              <Descriptions.Item label="起始时段">{slotLabel(slot)}</Descriptions.Item>
            </Descriptions>

            <div style={{ marginBottom: 8 }}>
              <Typography.Text strong>时长：</Typography.Text>
            </div>
            <Radio.Group
              value={duration}
              onChange={(e) => setDuration(e.target.value)}
              optionType="button"
              buttonStyle="solid"
              options={selectableDurations}
              style={{ marginBottom: 16 }}
            />

            <Alert
              type="success"
              showIcon
              message={`预约时段：${buildTimeRange(slot, duration)}`}
              description={
                <span>
                  {`签到窗口为开始前 ${checkInBeforeMin} 分钟至开始后 ${checkInAfterMin} 分钟，逾期将记为未签到。当前状态：`}
                  {SEAT_STATUS_META[selectedSeat.displayStatus]?.text || '-'}
                </span>
              }
            />
          </>
        ) : null}
      </Drawer>
    </div>
  );
}
