import request from './request';

/**
 * 巡查任务接口（V2 S6 / T-V2-21）。
 *
 * ★ 只有馆员与系统管理员能调（`/api/staff/` 前缀规则），且处置人由后端从登录态取。
 * ★ 看板的「能不能处置、为什么不能」全部读后端返回的 canXxx + xxxDisabledReason：
 *   「没有关联预约就无法释放」这条事实由服务端判定，前端猜就会点出 4093。
 */

/**
 * 看板列表。
 * @param {object} query {openOnly, floorId, level}
 * @returns {Promise<Array>} 任务列表（HIGH 在前）
 */
export function fetchPatrolTasks(query = {}) {
  return request.get('/staff/patrol', { params: query });
}

/**
 * 人工登记现场疑点。
 * @param {object} data {seatId, reservationId, note, level, photoUrl}
 * @returns {Promise<object>} 任务视图
 */
export function registerPatrol(data) {
  return request.post('/staff/patrol', data);
}

/**
 * 处置一条任务。
 * @param {number|string} patrolId 任务 ID
 * @param {object} data {action, note, photoUrl}
 * @returns {Promise<object>} 处置后的任务视图
 */
export function handlePatrol(patrolId, data) {
  return request.post(`/staff/patrol/${patrolId}/handle`, data);
}

/**
 * 处置动作字典（名称与文案都由后端 PatrolAction 枚举给，前端不另写一份）。
 * @returns {Promise<Array>} [{action, text}]
 */
export function fetchPatrolActions() {
  return request.get('/staff/patrol/actions');
}
