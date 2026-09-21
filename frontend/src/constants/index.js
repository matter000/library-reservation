/**
 * 全局常量：座位展示态、预约状态、楼层/座位状态、时段与时长选项。
 * 所有枚举值必须与后端 DTO 完全一致。
 */

/** 座位派生展示态（后端 SeatDisplayStatus，平面图使用）。 */
export const SEAT_DISPLAY_STATUS = {
  FREE: 'FREE',
  RESERVED: 'RESERVED',
  IN_USE: 'IN_USE',
  UNAVAILABLE: 'UNAVAILABLE',
};

/** 展示态 → 中文文案 + 色值 + 是否可点击预约。 */
export const SEAT_STATUS_META = {
  FREE: { text: '空闲', color: '#52c41a', clickable: true, marker: '' },
  RESERVED: { text: '已预约', color: '#faad14', clickable: false, marker: '●' },
  IN_USE: { text: '使用中', color: '#f5222d', clickable: false, marker: '●●' },
  UNAVAILABLE: { text: '不可用', color: '#bfbfbf', clickable: false, marker: '🔧' },
};

/** 图例顺序（常驻图例条按此顺序展示）。 */
export const SEAT_LEGEND_ORDER = ['FREE', 'RESERVED', 'IN_USE', 'UNAVAILABLE'];

/** 预约状态（后端 ReservationStatus）。 */
export const RESERVATION_STATUS = {
  CONFIRMED: 'CONFIRMED',
  CHECKED_IN: 'CHECKED_IN',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  NO_SHOW: 'NO_SHOW',
  ADMIN_CANCELLED: 'ADMIN_CANCELLED',
};

/** 预约状态 → 中文文案 + Tag 颜色。 */
export const RESERVATION_STATUS_META = {
  CONFIRMED: { text: '已预约', color: 'blue' },
  CHECKED_IN: { text: '使用中', color: 'red' },
  COMPLETED: { text: '已完成', color: 'green' },
  CANCELLED: { text: '已取消', color: 'default' },
  NO_SHOW: { text: '未签到', color: 'orange' },
  ADMIN_CANCELLED: { text: '已被管理员取消', color: 'volcano' },
};

/** 楼层状态（后端 FloorStatus）。 */
export const FLOOR_STATUS = { OPEN: 'OPEN', CLOSED: 'CLOSED' };

export const FLOOR_STATUS_META = {
  OPEN: { text: '开放中', color: 'green' },
  CLOSED: { text: '已关闭', color: 'default' },
};

/** 座位基础状态（后端 SeatStatus）。 */
export const SEAT_STATUS = {
  AVAILABLE: 'AVAILABLE',
  MAINTENANCE: 'MAINTENANCE',
  DISABLED: 'DISABLED',
};

export const SEAT_BASE_STATUS_META = {
  AVAILABLE: { text: '可用', color: 'green' },
  MAINTENANCE: { text: '维护中', color: 'orange' },
  DISABLED: { text: '已停用', color: 'default' },
};

/** 开放时间与时段（后端 BizConstants：08:00-22:00，粒度 1 小时，slot_index 8~21）。 */
export const SLOT_INDEX_MIN = 8;
export const SLOT_INDEX_MAX = 21;

/** 可选时长。 */
export const DURATION_OPTIONS = [
  { label: '1 小时', value: 1 },
  { label: '2 小时', value: 2 },
  { label: '3 小时', value: 3 },
  { label: '4 小时', value: 4 },
];

/** 默认时长。 */
export const DEFAULT_DURATION = 2;

/** 可预约窗口天数（今天 ~ 今天+6）。 */
export const ADVANCE_DAYS = 7;

/** localStorage key。 */
export const STORAGE_KEYS = {
  token: 'lsr_token',
  user: 'lsr_user',
};

/** 平面图 30 秒轮询间隔。 */
export const SEAT_MAP_POLL_INTERVAL = 30000;

/** 业务错误码。 */
export const ERROR_CODE = {
  PARAM_ERROR: 4001,
  UNAUTHORIZED: 4010,
  FORBIDDEN: 4030,
  NOT_FOUND: 4040,
  SEAT_SLOT_CONFLICT: 4091,
  USER_SLOT_OVERLAP: 4092,
  STATUS_NOT_ALLOWED: 4093,
  DELETE_FORBIDDEN: 4094,
};
