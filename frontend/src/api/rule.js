import request from './request';

/* ---------------- 规则配置（V2 S0） ---------------- */

/**
 * 规则列表。
 *
 * @param {string} [rgroup] 分组（OPEN/BOOK/LEAVE/CREDIT/PEAK/PATROL），省略表示全部
 * @returns {Promise<Array>} 规则项
 */
export function fetchRules(rgroup) {
  return request.get('/admin/rules', { params: rgroup ? { rgroup } : {} });
}

/**
 * 批量更新规则值（保存后立即生效，无需重启）。
 *
 * @param {Array} items [{scope,scopeId,rkey,value}]
 * @returns {Promise<Array>} 更新后的规则项
 */
export function updateRules(items) {
  return request.put('/admin/rules', items);
}

/**
 * 规则变更历史（读审计里的 oldValue/newValue，T-V2-22）。
 *
 * @param {string} [rkey] 只看某个键，省略表示全部
 * @returns {Promise<Array>} 新的在前
 */
export function fetchRuleHistory(rkey) {
  return request.get('/admin/rules/history', { params: rkey ? { rkey } : {} });
}
