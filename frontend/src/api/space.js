import request from './request';

/* ---------------- 分区（馆员作业面） ---------------- */

/**
 * 按楼层列分区。
 *
 * @param {number|string} floorId 楼层 ID
 */
export function fetchZones(floorId) {
  return request.get('/staff/zones', { params: { floorId } });
}

/** 新增分区。 */
export function createZone(data) {
  return request.post('/staff/zones', data);
}

/** 编辑分区。 */
export function updateZone(zoneId, data) {
  return request.put(`/staff/zones/${zoneId}`, data);
}

/** 切换分区开放状态。 */
export function updateZoneStatus(zoneId, status) {
  return request.put(`/staff/zones/${zoneId}/status`, { status });
}

/** 删除分区。 */
export function deleteZone(zoneId) {
  return request.delete(`/staff/zones/${zoneId}`);
}

/* ---------------- 桌位（馆员作业面） ---------------- */

/**
 * 按分区列桌位。
 *
 * @param {number|string} zoneId 分区 ID
 */
export function fetchTables(zoneId) {
  return request.get('/staff/tables', { params: { zoneId } });
}

/** 新增桌位。 */
export function createTable(data) {
  return request.post('/staff/tables', data);
}

/** 编辑桌位。 */
export function updateTable(tableId, data) {
  return request.put(`/staff/tables/${tableId}`, data);
}

/** 删除桌位。 */
export function deleteTable(tableId) {
  return request.delete(`/staff/tables/${tableId}`);
}

/**
 * 按桌生成相邻座位。
 *
 * @param {number|string} tableId 桌位 ID
 * @param {number} [count] 生成数量，省略按桌位声明值
 */
export function generateTableSeats(tableId, count) {
  return request.post(`/staff/tables/${tableId}/seats/generate`, count ? { count } : {});
}

/* ---------------- 用户面分区余量 ---------------- */

/**
 * 分区余量卡片（含可见性过滤）。
 *
 * @param {object} params {floorId,date,slot}
 */
export function fetchUserZones(params) {
  return request.get('/zones', { params });
}
