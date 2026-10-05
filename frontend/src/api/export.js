import request from './request';

/**
 * 台账导出接口（V2 S6 / T-V2-22）。
 *
 * ★ 这里的错误处理和别的 api 模块不一样，是被 `responseType: 'blob'` 逼出来的：
 * 导出失败时后端回的是 JSON（如「日期区间超过上限」），但 axios 已经把它当成 Blob 交回来，
 * 拦截器认不出 `code` 就直接放行 —— 不认 MIME，用户就会下载到一个内容为错误页的 .csv。
 */

/**
 * 下载一份台账。
 *
 * @param {string} type reservation / violation / patrol / credit
 * @param {object} params 预约台账的日期区间 {dateFrom, dateTo}
 * @returns {Promise<Blob>} CSV（UTF-8 带 BOM）
 */
export async function downloadLedger(type, params = {}) {
  const blob = await request.get(`/admin/export/${type}`, { params, responseType: 'blob' });
  if (blob && blob.type && blob.type.indexOf('json') >= 0) {
    let payload = {};
    try {
      payload = JSON.parse(await blob.text());
    } catch (e) {
      // 解析不动就用兜底文案，但绝不把这个 Blob 当台账下载
    }
    const error = new Error(payload.message || '导出失败，请稍后重试');
    error.code = payload.code;
    throw error;
  }
  return blob;
}

/**
 * 触发浏览器保存。
 *
 * <p>★ 文件名由前端拼：`responseType: 'blob'` 之后响应头读不到（拦截器只把 Blob 交出来），
 * 而后端 Content-Disposition 里那份文件名只是给人看的建议名，两边内容一致即可。
 *
 * @param {Blob} blob CSV 内容
 * @param {string} fileName 文件名
 */
export function saveBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // 立刻回收会让部分浏览器来不及读完，延到下一个宏任务
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * 台账文件名：`<type>-yyyyMMdd-HHmm.csv`。
 *
 * @param {string} type 台账类型
 * @returns {string} 文件名
 */
export function ledgerFileName(type) {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
  return `${type}-${stamp}.csv`;
}

/** 台账类型与文案（与后端 ExportService 的四个类型一一对应）。 */
export const LEDGER_TYPES = [
  { value: 'reservation', label: '预约台账', note: '按预约日期区间导出，含签到与取消原因' },
  { value: 'violation', label: '违规台账', note: '全馆违规明细，含扣分、撤销与申诉结果' },
  { value: 'patrol', label: '巡查台账', note: '疑点全量（含已处置），含生成依据与处置说明' },
  { value: 'credit', label: '信用台账', note: '全员信用快照与当前生效处罚' },
];
