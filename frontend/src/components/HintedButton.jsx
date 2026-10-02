import React from 'react';
import { Tooltip } from 'antd';

/**
 * 带说明的按钮包裹层：把 Tooltip 挂在**非禁用**的 span 上，里面放真正 disabled 的按钮。
 *
 * ★ 浏览器不会把鼠标事件派发给 disabled 的 button，Tooltip 直接挂在按钮上时
 * 「置灰」与「给出原因」只能实现前一半；套一层非禁用 span 才能让说明弹出来。
 * 后端的可用性判定（canXxx + xxxDisabledReason）必须原样展示，不能只灰掉不给理由。
 *
 * @param {object} props props
 * @param {string} props.tip 说明文案（空则不显示气泡）
 * @param {JSX.Element} props.children 被包裹的按钮
 * @returns {JSX.Element} 包裹层
 */
export default function HintedButton({ tip, children }) {
  return (
    <Tooltip title={tip}>
      <span style={{ display: 'inline-block' }}>{children}</span>
    </Tooltip>
  );
}
