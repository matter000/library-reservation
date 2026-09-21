import React from 'react';
import { Tag } from 'antd';
import { RESERVATION_STATUS_META } from '../constants';

/**
 * 预约状态标签（文案与配色统一取自 constants）。
 *
 * @param {object} props {status}
 * @returns {JSX.Element} 状态标签
 */
export default function StatusTag({ status }) {
  const meta = RESERVATION_STATUS_META[status] || { text: status || '未知', color: 'default' };
  return <Tag color={meta.color}>{meta.text}</Tag>;
}
