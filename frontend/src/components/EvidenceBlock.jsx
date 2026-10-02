import React from 'react';
import { Descriptions, Typography } from 'antd';

/**
 * 证据可读视图。
 *
 * 后端已经把 {@code evidence} JSON 拆成 key / label / value 的有序列表（含未登记键名的兜底），
 * 前端只负责按顺序渲染 —— 学生「我的信用」、馆员申诉队列、管理端违规页共用这一份，
 * 避免出现三处各写一套证据解读。
 *
 * @param {object} props props
 * @param {Array} props.items 证据项 [{key,label,value}]
 * @returns {JSX.Element} 证据块
 */
export default function EvidenceBlock({ items }) {
  if (!items || items.length === 0) {
    return (
      <Typography.Text type="secondary">
        本条违规没有可读证据，请到馆员处索取原始记录。
      </Typography.Text>
    );
  }
  return (
    <Descriptions size="small" column={1} bordered>
      {items.map((item) => (
        <Descriptions.Item key={item.key} label={item.label}>
          <Typography.Text>{item.value}</Typography.Text>
        </Descriptions.Item>
      ))}
    </Descriptions>
  );
}
