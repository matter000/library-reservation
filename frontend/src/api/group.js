import request from './request';

/**
 * 组队预约接口（V2 S4 / T-V2-17）。
 *
 * ★ 只负责取数：能不能确认、能不能退出、能不能签到，一律用后端返回的
 * {@code canXxx + xxxDisabledReason} 渲染。锁定时刻、邀请码有效期、签到窗口
 * 都在服务端判定，前端自己再算一遍就会出现「按钮说能点、接口说不能点」。
 */

/**
 * 建队（组长＝当前登录人）。
 * @param {object} data {zoneId,tableId,date,startSlot,duration,minMembers,maxMembers}
 * @returns {Promise<object>} GroupVO
 */
export function createGroup(data) {
  return request.post('/groups', data);
}

/**
 * 我的队伍（未终态，组长与成员身份都算）。
 * @returns {Promise<Array>} GroupVO 列表
 */
export function fetchMyGroups() {
  return request.get('/groups/my');
}

/**
 * 队伍详情。
 * @param {number|string} groupId 队伍 ID
 * @returns {Promise<object>} GroupVO
 */
export function fetchGroup(groupId) {
  return request.get(`/groups/${groupId}`);
}

/**
 * 组长按账号邀请成员。
 * @param {number|string} groupId 队伍 ID
 * @param {string} username 被邀请账号
 * @returns {Promise<object>} GroupVO
 */
export function inviteMember(groupId, username) {
  return request.post(`/groups/${groupId}/invite`, { username });
}

/**
 * 用邀请码加入（登记为待确认，需本人再点确认）。
 * @param {string} inviteCode 邀请码
 * @returns {Promise<object>} GroupVO
 */
export function joinGroup(inviteCode) {
  return request.post('/groups/join', { inviteCode });
}

/**
 * 成员确认参加。
 * @param {number|string} groupId 队伍 ID
 * @returns {Promise<object>} GroupVO
 */
export function confirmGroup(groupId) {
  return request.post(`/groups/${groupId}/confirm`);
}

/**
 * 成员退出队伍（仅未锁定时允许）。
 * @param {number|string} groupId 队伍 ID
 * @returns {Promise<object>} GroupVO
 */
export function quitGroup(groupId) {
  return request.post(`/groups/${groupId}/quit`);
}

/**
 * 组长取消队伍。
 * @param {number|string} groupId 队伍 ID
 * @returns {Promise<object>} GroupVO
 */
export function cancelGroup(groupId) {
  return request.post(`/groups/${groupId}/cancel`);
}

/**
 * 组长签到（= 全队预约点开）。
 * @param {number|string} groupId 队伍 ID
 * @returns {Promise<object>} GroupVO
 */
export function checkInGroup(groupId) {
  return request.post(`/groups/${groupId}/check-in`);
}

/**
 * 成员现场确认入座。
 * @param {number|string} groupId 队伍 ID
 * @returns {Promise<object>} GroupVO
 */
export function attendGroup(groupId) {
  return request.post(`/groups/${groupId}/attend`);
}
