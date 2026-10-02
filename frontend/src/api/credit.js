import request from './request';

/**
 * 信用与申诉接口（V2 S3）。
 *
 * ★ 这里只负责取数：能不能申诉、能不能裁定，一律用后端返回的
 * {@code canAppeal / canEscalate / canJudge} 与配套原因渲染，前端不自己复刻 24 小时等规则。
 */

/**
 * 我的信用概览（分数 + 生效处罚 + 流水）。
 * @returns {Promise<object>} {creditScore, penalties, logs}
 */
export function fetchMyCredit() {
  return request.get('/me/credit');
}

/**
 * 我的违规明细（含证据可读视图与申诉可用性）。
 * @returns {Promise<Array>} ViolationVO 列表（新→旧）
 */
export function fetchMyViolations() {
  return request.get('/me/violations');
}

/**
 * 提交申诉。
 * @param {number} violationId 违规 ID
 * @param {object} data {reason}
 * @returns {Promise<object>} AppealVO
 */
export function submitAppeal(violationId, data) {
  return request.post(`/violations/${violationId}/appeal`, data);
}

/**
 * 驳回后升级至系统管理员终审。
 * @param {number} violationId 违规 ID
 * @returns {Promise<object>} AppealVO
 */
export function escalateAppeal(violationId) {
  return request.post(`/violations/${violationId}/appeal/escalate`);
}

/**
 * 馆员申诉队列。
 * @param {object} params {status}
 * @returns {Promise<Array>} AppealVO 列表（旧→新）
 */
export function fetchAppealQueue(params) {
  return request.get('/staff/appeals', { params });
}

/**
 * 裁定申诉。
 * @param {number} appealId 申诉 ID
 * @param {object} data {approve, reply}
 * @returns {Promise<object>} AppealVO
 */
export function judgeAppeal(appealId, data) {
  return request.post(`/staff/appeals/${appealId}/judge`, data);
}

/**
 * 馆员违规查询。
 * @param {object} params {userId, status}
 * @returns {Promise<Array>} ViolationVO 列表（新→旧）
 */
export function fetchStaffViolations(params) {
  return request.get('/staff/violations', { params });
}

/**
 * 馆员直接撤销违规（超过申诉时限后的唯一救济入口）。
 * @param {number} violationId 违规 ID
 * @param {object} data {reason}
 * @returns {Promise<object>} ViolationVO
 */
export function revokeViolation(violationId, data) {
  return request.post(`/staff/violations/${violationId}/revoke`, data);
}

/**
 * 全库信用账本对账（正常应返回空数组）。
 * @returns {Promise<Array>} 差异说明列表
 */
export function fetchConsistency() {
  return request.get('/admin/rules/consistency');
}
