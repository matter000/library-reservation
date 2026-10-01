import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Progress,
  Row,
  Select,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { fetchFloors, fetchSlots } from '../api/floor';
import { FLOOR_STATUS, FLOOR_STATUS_META } from '../constants';
import useBookingConfig from '../hooks/useBookingConfig';
import { buildFallbackSlots, getBookableDates, nowSlot, slotLabel } from '../utils/datetime';

/**
 * 占用率进度条颜色：<50% 绿、50-80% 黄、>80% 红。
 *
 * @param {number} rate 占用率
 * @returns {string} 颜色
 */
function progressColor(rate) {
  if (rate > 80) {
    return '#f5222d';
  }
  if (rate >= 50) {
    return '#faad14';
  }
  return '#52c41a';
}

/**
 * 楼层选择页：卡片网格 + 日期/时段筛选。
 *
 * @returns {JSX.Element} 楼层页
 */
export default function FloorList() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [floors, setFloors] = useState([]);
  const [slots, setSlots] = useState(buildFallbackSlots());
  const [date, setDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [slot, setSlot] = useState(nowSlot());

  const { advanceDays } = useBookingConfig();
  const bookableDates = getBookableDates(advanceDays);

  /** 加载时段选项。 */
  const loadSlots = useCallback(async (targetDate) => {
    try {
      const data = await fetchSlots(targetDate);
      if (Array.isArray(data) && data.length > 0) {
        setSlots(data);
      }
    } catch (e) {
      setSlots(buildFallbackSlots());
    }
  }, []);

  /** 加载楼层列表。 */
  const loadFloors = useCallback(async (targetDate, targetSlot) => {
    setLoading(true);
    setError('');
    try {
      const data = await fetchFloors({ date: targetDate, slot: targetSlot });
      setFloors(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message || '加载楼层失败');
      setFloors([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSlots(date);
    loadFloors(date, slot);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 日期变化：重新拉时段与楼层。 */
  const handleDateChange = (nextDate) => {
    const value = nextDate ? nextDate.format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD');
    setDate(value);
    loadSlots(value);
    loadFloors(value, slot);
  };

  /** 时段变化。 */
  const handleSlotChange = (nextSlot) => {
    setSlot(nextSlot);
    loadFloors(date, nextSlot);
  };

  /** 进入平面图。 */
  const enterSeatMap = (floor) => {
    if (!floor || floor.status === FLOOR_STATUS.CLOSED) {
      return;
    }
    navigate(`/floors/${floor.floorId}/seats?date=${date}&slot=${slot}`);
  };

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        选择楼层
      </Typography.Title>

      <div className="toolbar">
        <span>日期：</span>
        <DatePicker
          value={dayjs(date)}
          allowClear={false}
          onChange={handleDateChange}
          disabledDate={(current) => !bookableDates.includes(current.format('YYYY-MM-DD'))}
        />
        <span>时段：</span>
        <Select
          value={slot}
          onChange={handleSlotChange}
          style={{ width: 140 }}
          options={slots.map((item) => ({
            value: item.slotIndex,
            label: item.label,
          }))}
        />
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            loadSlots(date);
            loadFloors(date, slot);
          }}
        >
          刷新
        </Button>
        <span className="spacer" />
        <Typography.Text type="secondary">
          可预约 {bookableDates[0]} ~ {bookableDates[bookableDates.length - 1]}
        </Typography.Text>
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 16 }} /> : null}

      <Spin spinning={loading}>
        <Row gutter={[16, 16]}>
          {floors.map((floor) => {
            const closed = floor.status === FLOOR_STATUS.CLOSED;
            const rate = floor.usageRate || 0;
            const total = floor.totalSeats || 0;
            const free = floor.freeSeats || 0;
            return (
              <Col key={floor.floorId} xs={24} sm={12} lg={8} xl={6}>
                <Card
                  hoverable={!closed}
                  onClick={() => enterSeatMap(floor)}
                  style={{
                    cursor: closed ? 'not-allowed' : 'pointer',
                    opacity: closed ? 0.6 : 1,
                  }}
                  title={
                    <span>
                      {floor.name}
                      <Tag color={FLOOR_STATUS_META[floor.status]?.color || 'default'} style={{ marginLeft: 8 }}>
                        {floor.code}
                      </Tag>
                    </span>
                  }
                  extra={closed ? <Tag>暂不开放</Tag> : <Tag color="green">可预约</Tag>}
                >
                  <Typography.Paragraph
                    type="secondary"
                    ellipsis={{ rows: 2 }}
                    style={{ minHeight: 44, marginBottom: 12 }}
                  >
                    {floor.description || '暂无描述'}
                  </Typography.Paragraph>

                  <Row gutter={8} style={{ marginBottom: 10 }}>
                    <Col span={12}>
                      <Typography.Text type="secondary">总座位</Typography.Text>
                      <div>
                        <Typography.Text strong style={{ fontSize: 18 }}>
                          {total}
                        </Typography.Text>
                      </div>
                    </Col>
                    <Col span={12}>
                      <Typography.Text type="secondary">空闲</Typography.Text>
                      <div>
                        <Typography.Text strong style={{ fontSize: 18, color: '#52c41a' }}>
                          {free}
                        </Typography.Text>
                      </div>
                    </Col>
                  </Row>

                  <Progress
                    percent={Number(rate.toFixed ? rate.toFixed(1) : rate)}
                    strokeColor={progressColor(rate)}
                    size="small"
                    format={(p) => `占用率 ${p}%`}
                  />

                  <div style={{ marginTop: 10, textAlign: 'right' }}>
                    <Button type="primary" size="small" disabled={closed} onClick={(e) => {
                      e.stopPropagation();
                      enterSeatMap(floor);
                    }}>
                      查看座位
                    </Button>
                  </div>
                </Card>
              </Col>
            );
          })}
          {!loading && floors.length === 0 ? (
            <Col span={24}>
              <Alert type="info" showIcon message="暂无楼层数据" />
            </Col>
          ) : null}
        </Row>
      </Spin>

      <div className="seat-map-tip">
        当前时段 {slotLabel(slot)}，点击楼层卡片进入座位平面图。
      </div>
    </div>
  );
}
