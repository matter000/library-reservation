import request from './request';

/**
 * 监控大屏接口（V2 S6 / T-V2-22）。
 *
 * ★ 大屏只有一个接口，且数字全部由后端聚合：楼层与分区占用和统计页同源，
 *   前端不再算第二次「谁在座」——两处口径不一致时，馆员不知道该信哪个。
 */

/**
 * 大屏整页数据。
 * @returns {Promise<object>} {overview, floors, heat, anomalies, slotLabel, closedToday, ...}
 */
export function fetchDashboard() {
  return request.get('/staff/dashboard');
}
