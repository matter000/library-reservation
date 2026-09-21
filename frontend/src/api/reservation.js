import request from './request';

/**
 * 提交预约。
 *
 * @param {object} data {seatId,date,startSlot,duration}
 * @returns {Promise<object>} 创建结果
 */
export function createReservation(data) {
  return request.post('/reservations', data);
}

/**
 * 我的预约。
 *
 * @param {object} params {tab,page,size}
 * @returns {Promise<{list:Array,total:number,page:number,size:number}>} 分页结果
 */
export function fetchMyReservations(params) {
  return request.get('/reservations/my', { params });
}

/**
 * 取消预约。
 *
 * @param {number|string} id 预约 ID
 * @returns {Promise<object>} 操作结果
 */
export function cancelReservation(id) {
  return request.post(`/reservations/${id}/cancel`);
}

/**
 * 签到。
 *
 * @param {number|string} id 预约 ID
 * @returns {Promise<object>} 操作结果
 */
export function checkInReservation(id) {
  return request.post(`/reservations/${id}/check-in`);
}
