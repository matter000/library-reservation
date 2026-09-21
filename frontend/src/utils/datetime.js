import dayjs from 'dayjs';
import {
  ADVANCE_DAYS,
  SLOT_INDEX_MAX,
  SLOT_INDEX_MIN,
} from '../constants';

/** 日期格式。 */
export const DATE_FORMAT = 'YYYY-MM-DD';

/** 日期时间格式。 */
export const DATETIME_FORMAT = 'YYYY-MM-DD HH:mm:ss';

/**
 * 取可预约日期列表：今天 ~ 今天+6（后端 ADVANCE_DAYS=7）。
 *
 * @returns {string[]} yyyy-MM-dd 数组
 */
export function getBookableDates() {
  const today = dayjs();
  const dates = [];
  for (let i = 0; i < ADVANCE_DAYS; i += 1) {
    dates.push(today.add(i, 'day').format(DATE_FORMAT));
  }
  return dates;
}

/**
 * 判断日期是否在可预约窗口内。
 *
 * @param {string} date yyyy-MM-dd
 * @returns {boolean} 是否可预约
 */
export function isBookableDate(date) {
  if (!date) {
    return false;
  }
  const target = dayjs(date);
  return target.isAfter(dayjs().startOf('day').subtract(1, 'second'))
    && target.isBefore(dayjs().add(ADVANCE_DAYS, 'day').startOf('day'));
}

/**
 * 时段标签，如 14:00-15:00。
 *
 * @param {number} slotIndex 时段序号
 * @returns {string} 标签
 */
export function slotLabel(slotIndex) {
  const start = String(slotIndex).padStart(2, '0');
  const end = String(slotIndex + 1).padStart(2, '0');
  return `${start}:00-${end}:00`;
}

/**
 * 仅起始时间的时段标签，如 14:00。
 *
 * @param {number} slotIndex 时段序号
 * @returns {string} 时间文本
 */
export function slotStartLabel(slotIndex) {
  return `${String(slotIndex).padStart(2, '0')}:00`;
}

/**
 * 预约区间文本，如 14:00-16:00。
 *
 * @param {number} startSlot 起始时段
 * @param {number} duration 时长（小时）
 * @returns {string} 区间文本
 */
export function buildTimeRange(startSlot, duration) {
  const start = String(startSlot).padStart(2, '0');
  const end = String(startSlot + duration).padStart(2, '0');
  return `${start}:00-${end}:00`;
}

/**
 * 当前所处时段序号（收敛到 8~21）。
 *
 * @returns {number} 时段序号
 */
export function nowSlot() {
  const hour = dayjs().hour();
  if (hour < SLOT_INDEX_MIN) {
    return SLOT_INDEX_MIN;
  }
  if (hour > SLOT_INDEX_MAX) {
    return SLOT_INDEX_MAX;
  }
  return hour;
}

/**
 * 生成 14 个时段的静态选项（接口不可用时的兜底）。
 *
 * @returns {Array<{slotIndex:number,label:string}>} 时段选项
 */
export function buildFallbackSlots() {
  const list = [];
  for (let i = SLOT_INDEX_MIN; i <= SLOT_INDEX_MAX; i += 1) {
    list.push({
      slotIndex: i,
      label: slotLabel(i),
      start: slotStartLabel(i),
      end: slotStartLabel(i + 1),
      bookable: true,
      isCurrent: i === nowSlot(),
    });
  }
  return list;
}

/**
 * 友好的日期展示：今天 / 明天 / 2026-09-20。
 *
 * @param {string} date yyyy-MM-dd
 * @returns {string} 展示文本
 */
export function friendlyDate(date) {
  if (!date) {
    return '--';
  }
  const today = dayjs().format(DATE_FORMAT);
  const tomorrow = dayjs().add(1, 'day').format(DATE_FORMAT);
  if (date === today) {
    return '今天';
  }
  if (date === tomorrow) {
    return '明天';
  }
  return date;
}

/**
 * 当前时间 HH:mm:ss（用于「上次更新」）。
 *
 * @returns {string} 时间文本
 */
export function nowTimeText() {
  return dayjs().format('HH:mm:ss');
}
