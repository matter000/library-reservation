import request from './request';

/** 统计概览（6 指标）。 */
export function fetchOverview() {
  return request.get('/admin/statistics/overview');
}

/** 预约量趋势。 */
export function fetchTrend(days) {
  return request.get('/admin/statistics/trend', { params: { days } });
}

/** 时段热度（14 项）。 */
export function fetchSlotHeat(days) {
  return request.get('/admin/statistics/slot-heat', { params: { days } });
}

/** 各楼层利用率。 */
export function fetchFloorUsage(params) {
  return request.get('/admin/statistics/floor-usage', { params });
}

/** 预约状态分布。 */
export function fetchStatusDistribution(days) {
  return request.get('/admin/statistics/status-distribution', { params: { days } });
}
