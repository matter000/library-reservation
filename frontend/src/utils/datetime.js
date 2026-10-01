import dayjs from 'dayjs';
import {
  ADVANCE_DAYS,
  SLOT_INDEX_MAX,
  SLOT_INDEX_MIN,
  SLOT_MINUTES,
  SLOTS_PER_HOUR,
} from '../constants';

/** 日期格式。 */
export const DATE_FORMAT = 'YYYY-MM-DD';

/**
 * 取可预约日期列表：今天 ~ 今天+(advanceDays-1)。
 *
 * @param {number} advanceDays 可提前预约天数，来自规则中心；缺省回退本地常量
 * @returns {string[]} yyyy-MM-dd 数组
 */
export function getBookableDates(advanceDays = ADVANCE_DAYS) {
  const days = Number.isInteger(advanceDays) && advanceDays > 0 ? advanceDays : ADVANCE_DAYS;
  const today = dayjs();
  const dates = [];
  for (let i = 0; i < days; i += 1) {
    dates.push(today.add(i, 'day').format(DATE_FORMAT));
  }
  return dates;
}

/**
 * 时段序号 → 时间文本（30 分钟一格，slot = (hour*60+minute)/30）。
 *
 * @param {number} slotIndex 时段序号
 * @returns {string} HH:mm
 */
export function slotText(slotIndex) {
  const minutes = slotIndex * SLOT_MINUTES;
  const hh = String(Math.floor(minutes / 60) % 24).padStart(2, '0');
  const mm = String(minutes % 60).padStart(2, '0');
  return `${hh}:${mm}`;
}

/**
 * 时段标签，如 14:00-14:30。
 *
 * @param {number} slotIndex 时段序号
 * @returns {string} 标签
 */
export function slotLabel(slotIndex) {
  return buildTimeRange(slotIndex, 1);
}

/**
 * 仅起始时间的时段标签，如 14:00。
 *
 * @param {number} slotIndex 时段序号
 * @returns {string} 时间文本
 */
export function slotStartLabel(slotIndex) {
  return slotText(slotIndex);
}

/**
 * 预约区间文本，如 14:00-16:00。
 *
 * <p>duration 是<b>小时</b>（用户可选时长的语义），必须先换算成格数再相加；
 * 后端 BizConstants.rangeLabel 同源，两端不得各写一份算法。
 *
 * @param {number} startSlot 起始时段
 * @param {number} duration 时长（小时）
 * @returns {string} 区间文本
 */
export function buildTimeRange(startSlot, duration) {
  return `${slotText(startSlot)}-${slotText(startSlot + duration * SLOTS_PER_HOUR)}`;
}

/**
 * 当前所处时段序号（收敛到合法区间）。
 *
 * @returns {number} 时段序号
 */
export function nowSlot() {
  const now = dayjs();
  const slot = Math.floor((now.hour() * 60 + now.minute()) / SLOT_MINUTES);
  if (slot < SLOT_INDEX_MIN) {
    return SLOT_INDEX_MIN;
  }
  if (slot > SLOT_INDEX_MAX) {
    return SLOT_INDEX_MAX;
  }
  return slot;
}

/**
 * 生成默认窗口的静态时段选项（接口不可用时的兜底）。
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
      end: slotText(i + 1),
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
