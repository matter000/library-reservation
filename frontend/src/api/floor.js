import request from './request';

/**
 * 楼层列表（含空闲数与占用率）。
 *
 * @param {object} params {date,slot}
 * @returns {Promise<Array>} 楼层列表
 */
export function fetchFloors(params) {
  return request.get('/floors', { params });
}

/**
 * 楼层详情。
 *
 * @param {number|string} floorId 楼层 ID
 * @param {object} params {date,slot}
 * @returns {Promise<object>} 楼层详情
 */
export function fetchFloor(floorId, params) {
  return request.get(`/floors/${floorId}`, { params });
}

/**
 * 座位平面图（响应不含占用人身份）。
 *
 * @param {object} params {floorId,date,slot}
 * @returns {Promise<object>} 平面图
 */
export function fetchSeatMap(params) {
  return request.get('/seats/map', { params });
}

/**
 * 时段选项（14 项）。
 *
 * @param {string} date yyyy-MM-dd
 * @returns {Promise<Array>} 时段选项
 */
export function fetchSlots(date) {
  return request.get('/config/slots', { params: { date } });
}

/**
 * 预约表单配置（可约天数 / 可选时长 / 签到窗口），取自后端规则中心。
 *
 * @returns {Promise<object>} {advanceDays, allowedDurations, defaultDuration, checkInBeforeMin, checkInAfterMin}
 */
export function fetchBookingConfig() {
  return request.get('/config/booking');
}
