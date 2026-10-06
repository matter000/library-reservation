import request from './request';

/**
 * 暂离原因字典。
 *
 * @param {number} [reservationId] 传本人那条要暂离的预约，服务端按所在分区
 *        合成每个原因的生效上限（不传则回落到原因自身上限）
 * @returns {Promise<Array>} 形如 [{code,name,maxMin,dailyLimit,needNote,relaxable}]
 */
export function fetchLeaveReasons(reservationId) {
  return request.get('/leaves/reasons', {
    params: reservationId ? { reservationId } : undefined
  });
}

/**
 * 登记暂离。
 *
 * @param {object} data {reservationId,reasonCode,note}
 * @returns {Promise<object>} 暂离 VO（含 plannedEndTime / remainMin）
 */
export function startLeave(data) {
  return request.post('/leaves', data);
}

/**
 * 我已返回（剩余使用时间不顺延）。
 *
 * @param {number|string} leaveId 暂离 ID
 * @returns {Promise<object>} 暂离 VO
 */
export function returnLeave(leaveId) {
  return request.post(`/leaves/${leaveId}/return`);
}
