import request from './request';

/**
 * 用户注册（后端强制 role=USER）。
 *
 * @param {object} data {username,password,confirmPassword,realName,phone}
 * @returns {Promise<object>} 用户信息
 */
export function register(data) {
  return request.post('/auth/register', data);
}

/**
 * 登录。
 *
 * @param {object} data {username,password}
 * @returns {Promise<object>} {token,tokenType,expiresIn,user}
 */
export function login(data) {
  return request.post('/auth/login', data);
}

/**
 * 当前登录用户信息。
 *
 * @returns {Promise<object>} 用户信息
 */
export function fetchMe() {
  return request.get('/auth/me');
}

/**
 * 修改当前登录用户的密码（用户 ID 由后端从 Token 解析，前端不传）。
 *
 * @param {object} data {oldPassword,newPassword,confirmPassword}
 * @returns {Promise<void>}
 */
export function changePassword(data) {
  return request.post('/auth/change-password', data);
}
