import request from './request';

/**
 * 站内通知接口（V2 S5 / T-V2-20）。
 *
 * ★ 本系统没有第三方推送通道：没有 MQ、没有 WebSocket、没有短信。
 * 「推送」在这里就是「后端写一条通知 + 前端轮询」，所以页面关着就不会收到任何提醒 ——
 * 这不是故障，是设计取舍（与 V1「不引入重型组件」一致）。UI 必须把这句话讲出来，
 * 否则用户会以为手机能收到候补 offer 的通知，然后怪系统「没提醒我」。
 *
 * ★ 通知只是提醒，不是判定：能不能签到、候补还剩几分钟、暂离是否超时，
 * 一律以业务接口返回的字段为准。铃铛里的文案不能当依据。
 */

/**
 * 我的通知（最近一屏 + 未读数 + 总数）。
 * @returns {Promise<object>} {list, unread, total}
 */
export function fetchNotices() {
  return request.get('/me/notices');
}

/**
 * 标为已读（后端按「ID + 收件人」取，别人的通知返回 4040）。
 * @param {number|string} noticeId 通知 ID
 * @returns {Promise<object>} 更新后的通知
 */
export function markNoticeRead(noticeId) {
  return request.post(`/me/notices/${noticeId}/read`);
}
