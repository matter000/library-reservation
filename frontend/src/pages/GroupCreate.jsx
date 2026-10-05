import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, DatePicker, Descriptions, Form, Radio, Select, Space, Spin, Typography, message,
} from 'antd';
import dayjs from 'dayjs';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { createGroup } from '../api/group';
import { fetchFloors, fetchSeatMap, fetchSlots } from '../api/floor';
import { fetchUserZones } from '../api/space';
import useBookingConfig from '../hooks/useBookingConfig';
import { DATE_FORMAT, buildFallbackSlots, buildTimeRange, getBookableDates, nowSlot, slotText } from '../utils/datetime';
import { FLOOR_STATUS } from '../constants';

/**
 * 建队页（T-V2-17）。
 *
 * 选桌不另开接口：直接把平面图按 tableId 分组，空闲数与页面看到的是同一份派生结果。
 * ★ 这里列出的「空闲 x」只是<b>起始时段</b>的实时余量，真正的座位在锁定时刻才抢，
 *   所以文案必须写清时段，否则用户会以为建队即占座。
 *
 * @returns {JSX.Element} 建队页
 */
export default function GroupCreate() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm();

  const {
    advanceDays, allowedDurations, durationOptions, defaultDuration, groupLockBeforeMin, groupMinMembers,
    groupMaxMembers,
  } = useBookingConfig();
  const bookableDates = useMemo(() => getBookableDates(advanceDays), [advanceDays]);

  const [floors, setFloors] = useState([]);
  const [zones, setZones] = useState([]);
  const [slots, setSlots] = useState(buildFallbackSlots());
  const [mapData, setMapData] = useState(null);
  const [mapLoading, setMapLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const floorId = Form.useWatch('floorId', form);
  const zoneId = Form.useWatch('zoneId', form);
  const date = Form.useWatch('date', form);
  const startSlot = Form.useWatch('startSlot', form);
  const duration = Form.useWatch('duration', form);
  const tableId = Form.useWatch('tableId', form);

  const dateText = date ? date.format(DATE_FORMAT) : dayjs().format(DATE_FORMAT);

  /** 楼层列表（只一次）。 */
  useEffect(() => {
    fetchFloors()
      .then((list) => setFloors(list || []))
      .catch(() => setFloors([]));
  }, []);

  /** 时段选项随日期变化（闭馆日返回空列表，与平面图一致）。 */
  useEffect(() => {
    fetchSlots(dateText)
      .then((list) => setSlots(Array.isArray(list) && list.length > 0 ? list : buildFallbackSlots()))
      .catch(() => setSlots(buildFallbackSlots()));
  }, [dateText]);

  /**
   * 可组队的分区：allowGroup 或 groupRequired 任一为真才进得了这张表。
   *
   * @param {number|string} nextFloor 楼层
   * @param {string} nextDate 日期
   * @param {number} nextSlot 时段
   */
  const loadZones = useCallback(async (nextFloor, nextDate, nextSlot) => {
    if (!nextFloor) {
      setZones([]);
      return;
    }
    try {
      const list = await fetchUserZones({ floorId: nextFloor, date: nextDate, slot: nextSlot });
      setZones((list || []).filter((z) => z.allowGroup === true || z.groupRequired === true));
    } catch (e) {
      setZones([]);
    }
  }, []);

  useEffect(() => {
    loadZones(floorId, dateText, startSlot || nowSlot());
  }, [floorId, dateText, startSlot, loadZones]);

  /** 平面图（选桌的数据源）：楼层 / 分区 / 日期 / 时段任一变化就重取。 */
  useEffect(() => {
    if (!floorId || !zoneId) {
      setMapData(null);
      return;
    }
    setMapLoading(true);
    fetchSeatMap({ floorId, zoneId, date: dateText, slot: startSlot || nowSlot() })
      .then(setMapData)
      .catch(() => setMapData(null))
      .finally(() => setMapLoading(false));
  }, [floorId, zoneId, dateText, startSlot]);

  /** 按桌分组：可用=基础状态可用（与后端 freeSeats 同口径），空闲=起始时段 FREE。 */
  const tables = useMemo(() => {
    const seats = mapData && mapData.seats ? mapData.seats : [];
    const grouped = new Map();
    seats.forEach((seat) => {
      if (!seat.tableId) {
        return;
      }
      if (!grouped.has(seat.tableId)) {
        grouped.set(seat.tableId, {
          tableId: seat.tableId, tableNo: seat.tableNo, usable: 0, free: 0,
        });
      }
      const bucket = grouped.get(seat.tableId);
      if (seat.baseStatus === 'AVAILABLE') {
        bucket.usable += 1;
        if (seat.displayStatus === 'FREE') {
          bucket.free += 1;
        }
      }
    });
    return Array.from(grouped.values()).sort((a, b) => String(a.tableNo).localeCompare(String(b.tableNo)));
  }, [mapData]);

  /** 深链参数（从平面图「用这张桌建队」带过来）：只在首帧用于 initialValues，值本身是原始类型。 */
  const initialFloorId = searchParams.get('floorId') ? Number(searchParams.get('floorId')) : undefined;
  const initialZoneId = searchParams.get('zoneId') ? Number(searchParams.get('zoneId')) : undefined;
  const initialTableId = searchParams.get('tableId') ? Number(searchParams.get('tableId')) : undefined;
  const initialDate = searchParams.get('date') || dayjs().format(DATE_FORMAT);
  const initialSlot = searchParams.get('slot') ? Number(searchParams.get('slot')) : nowSlot();

  const selectedTable = useMemo(
    () => tables.find((t) => t.tableId === tableId) || null,
    [tables, tableId],
  );
  const selectedZone = useMemo(
    () => zones.find((z) => z.zoneId === zoneId) || null,
    [zones, zoneId],
  );

  /**
   * 时长上限取分区规则（后端仍会二次校验）。
   *
   * <p>★ 下面每一份 options 都过 useMemo：给 Select 递一份每次渲染都新建的数组，
   *   rc-select 会因为「选项变了」再 setState，实测能把整页推进
   *   Maximum update depth exceeded（平面图跳过来、桌位已预选时必现）。
   */
  const zoneDurationOptions = useMemo(() => (selectedZone && selectedZone.maxDurationMin
    ? durationOptions.filter((option) => option.value * 60 <= selectedZone.maxDurationMin)
    : durationOptions), [durationOptions, selectedZone]);

  const floorChoices = useMemo(() => floors.map((f) => ({
    value: f.floorId,
    label: `${f.name}${f.status === FLOOR_STATUS.CLOSED ? '（已关闭）' : ''}`,
  })), [floors]);

  const zoneChoices = useMemo(() => zones.map((z) => ({
    value: z.zoneId,
    // 空闲数直接取分区卡片的同一份派生结果，前端不再算一次
    label: `${z.name}（${z.zoneTypeText}${z.groupRequired ? ' · 仅整组预约' : ''}） 空闲 ${z.freeSeats}/${z.totalSeats}`,
    disabled: z.eligible === false,
  })), [zones]);

  /**
   * 组队专用时段闸门：今天剩余的格子里，开始时刻不足 groupLockBeforeMin 分钟的来不及让成员确认。
   *
   * @param {object} option 时段选项
   * @returns {boolean} 是否可选
   */
  const slotSelectable = (option) => {
    if (option.bookable === false) {
      return false;
    }
    if (dateText !== dayjs().format(DATE_FORMAT)) {
      return true;
    }
    return dayjs(`${dateText} ${slotText(option.slotIndex)}`).isAfter(dayjs().add(groupLockBeforeMin, 'minute'));
  };

  const slotChoices = useMemo(() => slots.map((item) => ({
    value: item.slotIndex,
    label: slotSelectable(item) ? item.label : `${item.label}（来不及确认）`,
    disabled: !slotSelectable(item),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  })), [slots, dateText, groupLockBeforeMin]);

  const tableChoices = useMemo(() => tables.map((t) => ({
    value: t.tableId,
    label: `${t.tableNo} · 可用 ${t.usable} 座 · ${slotText(startSlot || initialSlot)} 空闲 ${t.free} 座`,
    disabled: t.usable < groupMinMembers,
  })), [tables, startSlot, initialSlot, groupMinMembers]);

  const memberCountChoices = useMemo(() => {
    const usable = selectedTable ? selectedTable.usable : 0;
    // ★ 上限取「本桌座位数与一组人数上限的较小值」：桌子可以建模到 12 座，
    //   但 PRD §6.3 的 2~6 人是流程口径。选项里给出 7~12 就等于让用户挑一个必然被拒的值。
    const cap = Math.min(usable, groupMaxMembers);
    const min = Array.from(
      { length: Math.max(0, cap - groupMinMembers + 1) },
      (_, i) => ({ value: groupMinMembers + i, label: `${groupMinMembers + i} 人` }),
    );
    const max = Array.from(
      { length: cap },
      (_, i) => ({ value: i + 1, label: `${i + 1} 人` }),
    );
    return { min, max, cap };
  }, [selectedTable, groupMinMembers, groupMaxMembers]);

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const created = await createGroup({
        zoneId: values.zoneId,
        tableId: values.tableId,
        date: values.date.format(DATE_FORMAT),
        startSlot: values.startSlot,
        duration: values.duration,
        minMembers: values.minMembers,
        maxMembers: values.maxMembers,
      });
      message.success(`建队成功：第 ${created.groupId} 队，把邀请码发给同学吧`);
      navigate(`/groups?groupId=${created.groupId}`, { replace: true });
    } catch (e) {
      /* 提示由拦截器弹出；4091/4102 等口径都由后端给出理由 */
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <Typography.Title level={4}>组建学习小组</Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={`队伍在时段开始前 ${groupLockBeforeMin} 分钟锁定并按桌下单：锁定时刻给每位已确认成员各分配本桌一个座位。`}
        description="建队本身不占座——只有锁定成功、下单成功，座位才真正归队伍。"
      />
      {floors.length === 0 ? (
        <Alert type="warning" showIcon message="没有可用楼层，请稍后再试" style={{ marginBottom: 12 }} />
      ) : null}
      {mapData && mapData.floorStatus === FLOOR_STATUS.CLOSED ? (
        <Alert type="warning" showIcon message="该楼层已关闭，暂不开放预约" style={{ marginBottom: 12 }} />
      ) : null}

      <Card>
        {/* ★ 用 Spin 包而不是 Card 的 loading：loading 会把整棵表单换成骨架屏，
    *   卸载/重挂与 Select 的尺寸回测撞在一起，实测直接推进 Maximum update depth exceeded。 */}
        <Spin spinning={mapLoading && !!zoneId}>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            floorId: initialFloorId,
            zoneId: initialZoneId,
            tableId: initialTableId,
            date: dayjs(initialDate),
            startSlot: initialSlot,
            duration: defaultDuration,
            minMembers: groupMinMembers,
          }}
        >
          <Form.Item label="楼层" name="floorId" rules={[{ required: true, message: '请选择楼层' }]}>
            <Select
              placeholder="选择楼层"
              options={floorChoices}
              onChange={() => form.setFieldsValue({ zoneId: undefined, tableId: undefined })}
            />
          </Form.Item>

          <Form.Item label="分区" name="zoneId" rules={[{ required: true, message: '请选择支持组队的分区' }]}>
            <Select
              placeholder="仅显示支持组队的分区"
              notFoundContent="这个楼层还没有支持组队的分区"
              options={zoneChoices}
              onChange={() => form.setFieldsValue({ tableId: undefined, maxMembers: undefined })}
            />
          </Form.Item>

          <Space size={12} wrap>
            <Form.Item label="日期" name="date" rules={[{ required: true, message: '请选择日期' }]}>
              <DatePicker
                allowClear={false}
                disabledDate={(current) => !bookableDates.includes(current.format(DATE_FORMAT))}
                onChange={() => form.setFieldsValue({ tableId: undefined })}
              />
            </Form.Item>

            <Form.Item
              label="起始时段"
              name="startSlot"
              rules={[{ required: true, message: '请选择起始时段' }]}
            >
              <Select
                style={{ width: 170 }}
                onChange={() => form.setFieldsValue({ tableId: undefined })}
                options={slotChoices}
              />
            </Form.Item>

            <Form.Item label="时长" name="duration" rules={[{ required: true, message: '请选择时长' }]}>
              <Radio.Group
                optionType="button"
                buttonStyle="solid"
                options={zoneDurationOptions}
              />
            </Form.Item>
          </Space>

          <Form.Item label="桌位" name="tableId" rules={[{ required: true, message: '请选择桌位' }]}>
            <Select
              placeholder={zoneId ? '选择本区的一张桌' : '请先选分区'}
              disabled={!zoneId}
              notFoundContent={zoneId ? '这个分区还没有建模桌位，请到馆员台添加' : ''}
              options={tableChoices}
            />
          </Form.Item>

          <Space size={12} wrap>
            <Form.Item
              label="成团人数"
              name="minMembers"
              rules={[{ required: true, message: '请填写成团人数' }]}
              tooltip="锁定时刻已确认人数不足则整队未成团"
            >
              <Select
                style={{ width: 120 }}
                options={memberCountChoices.min}
              />
            </Form.Item>

            <Form.Item
              label="人数上限"
              name="maxMembers"
              tooltip={`省略时按本桌可用座位数，且不超过 ${groupMaxMembers} 人（PRD §6.3 的一组人数上限）`}
            >
              <Select
                style={{ width: 140 }}
                allowClear
                placeholder="本桌可用数"
                options={memberCountChoices.max}
              />
            </Form.Item>
          </Space>

          {selectedTable ? (
            <Descriptions size="small" column={2} bordered style={{ marginBottom: 12 }}>
              <Descriptions.Item label="选中桌位">{`${selectedZone ? selectedZone.name : ''} ${selectedTable.tableNo}`}</Descriptions.Item>
              <Descriptions.Item label="预约时段">
                {startSlot && duration ? buildTimeRange(startSlot, duration) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="本桌可用座位">{selectedTable.usable}</Descriptions.Item>
              <Descriptions.Item label="起始时段空闲">{selectedTable.free}</Descriptions.Item>
            </Descriptions>
          ) : null}

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting}>
                创建队伍
              </Button>
              <Button onClick={() => navigate('/groups')}>我的队伍</Button>
              <Button onClick={() => navigate(-1)}>返回平面图</Button>
            </Space>
          </Form.Item>
        </Form>
        </Spin>
      </Card>
    </div>
  );
}
