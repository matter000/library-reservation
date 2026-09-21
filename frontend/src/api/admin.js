import request from './request';

/* ---------------- 楼层管理 ---------------- */

/** 楼层列表。 */
export function fetchAdminFloors() {
  return request.get('/admin/floors');
}

/** 新增楼层。 */
export function createFloor(data) {
  return request.post('/admin/floors', data);
}

/** 编辑楼层。 */
export function updateFloor(floorId, data) {
  return request.put(`/admin/floors/${floorId}`, data);
}

/** 删除楼层。 */
export function deleteFloor(floorId) {
  return request.delete(`/admin/floors/${floorId}`);
}

/** 切换楼层状态。 */
export function updateFloorStatus(floorId, status) {
  return request.put(`/admin/floors/${floorId}/status`, { status });
}

/* ---------------- 座位管理 ---------------- */

/**
 * 座位分页列表。
 *
 * @param {object} params {floorId,page,size,seatNo,status}
 */
export function fetchAdminSeats(params) {
  const { floorId, ...rest } = params;
  return request.get(`/admin/floors/${floorId}/seats`, { params: rest });
}

/** 新增座位。 */
export function createSeat(data) {
  return request.post('/admin/seats', data);
}

/** 编辑座位。 */
export function updateSeat(seatId, data) {
  return request.put(`/admin/seats/${seatId}`, data);
}

/** 删除座位。 */
export function deleteSeat(seatId) {
  return request.delete(`/admin/seats/${seatId}`);
}

/** 切换座位状态。 */
export function updateSeatStatus(seatId, status) {
  return request.put(`/admin/seats/${seatId}/status`, { status });
}

/** 批量生成座位。 */
export function batchCreateSeats(data) {
  return request.post('/admin/seats/batch', data);
}

/* ---------------- 使用情况 / 预约记录 ---------------- */

/**
 * 座位使用情况。
 *
 * @param {object} params {floorId,date,slot,only}
 */
export function fetchUsage(params) {
  return request.get('/admin/usage', { params });
}

/**
 * 预约记录。
 *
 * @param {object} params {keyword,floorId,dateFrom,dateTo,status,page,size}
 */
export function fetchAdminReservations(params) {
  return request.get('/admin/reservations', { params });
}

/**
 * 强制释放。
 *
 * @param {number|string} id 预约 ID
 * @param {string} reason 释放原因
 */
export function releaseReservation(id, reason) {
  return request.post(`/admin/reservations/${id}/release`, { reason });
}

/* ---------------- 用户管理（P1） ---------------- */

/**
 * 用户列表。
 *
 * @param {object} params {keyword,page,size}
 */
export function fetchAdminUsers(params) {
  return request.get('/admin/users', { params });
}
