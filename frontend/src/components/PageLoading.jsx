import React from 'react';
import { Spin } from 'antd';

/**
 * 整页加载占位。
 *
 * @param {object} props {tip}
 * @returns {JSX.Element} 加载中
 */
export default function PageLoading({ tip = '加载中…' }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 240,
        padding: '48px 0',
      }}
    >
      <Spin size="large" tip={tip}>
        <div style={{ padding: 24 }} />
      </Spin>
    </div>
  );
}
