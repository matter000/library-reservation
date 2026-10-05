import request from './request';

/**
 * 候补队列接口（V2 S5 / T-V2-19）。
 *
 * ★ 加入候补只提交「分区 + 日期 + 起始时段 + 时长」，没有 seatId：
 * 排队要的是「一个位置」而不是「那一把椅子」，座位由服务端在确认那一刻现场挑。
 *
 * ★ 能不能确认、还剩几分钟，一律读后端的 canConfirm / minutesToExpire。
 * offer 窗口会自己走完（超时即顺延），前端自己倒计时就会出现「按钮还亮着、位置已经给别人了」。
 */

/**
 * 加入候补（该时段还有空位时后端会直接拒绝）。
 * @param {object} data {zoneId,date,startSlot,duration}
 * @returns {Promise<object>} WaitlistVO
 */
export function joinWaitlist(data) {
  return request.post('/waitlist', data);
}

/**
 * 我的活动候补。
 * @returns {Promise<Array>} WaitlistVO 列表
 */
export function fetchMyWaitlist() {
  return request.get('/waitlist/my');
}

/**
 * 确认候补：服务端挑座并走完整预约校验链，成功即生成正常预约。
 * @param {number|string} waitlistId 候补记录 ID
 * @returns {Promise<object>} 下单结果（与直接预约同构）
 */
export function confirmWaitlist(waitlistId) {
  return request.post(`/waitlist/${waitlistId}/confirm`);
}

/**
 * 退出队列（手里若正拿着 offer，位置当场顺延给下一位）。
 * @param {number|string} waitlistId 候补记录 ID
 * @returns {Promise<object>} WaitlistVO
 */
export function quitWaitlist(waitlistId) {
  return request.post(`/waitlist/${waitlistId}/quit`);
}
