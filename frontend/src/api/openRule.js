import request from './request';

/* ---------------- 开放档案 ---------------- */

/** 开放档案列表。 */
export function fetchProfiles() {
  return request.get('/admin/open/profiles');
}

/** 新增或编辑开放档案（按日型唯一）。 */
export function saveProfile(data) {
  return request.post('/admin/open/profiles', data);
}

/* ---------------- 开放日历 ---------------- */

/**
 * 日历覆盖列表。
 *
 * @param {object} params {from,to}
 */
export function fetchCalendar(params) {
  return request.get('/admin/open/calendar', { params });
}

/** 新增或编辑日历覆盖。 */
export function saveCalendar(data) {
  return request.post('/admin/open/calendar', data);
}

/** 删除日历覆盖。 */
export function deleteCalendar(id) {
  return request.delete(`/admin/open/calendar/${id}`);
}

/* ---------------- 生效自检 ---------------- */

/**
 * 某一天最终生效的规则与来源。
 *
 * @param {string} date yyyy-MM-dd
 */
export function fetchDayRule(date) {
  return request.get('/admin/open/day-rule', { params: { date } });
}
