import React from 'react';
import { Button, Result, Typography } from 'antd';

/**
 * 全局错误边界：捕获子树渲染期异常，避免整页白屏。
 *
 * React 的错误边界只能用 class 组件实现（getDerivedStateFromError / componentDidCatch）。
 * 这里不引入额外依赖，给用户一个可恢复的出口：优先原地重试，退而刷新整页。
 */
export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
    this.handleReset = this.handleReset.bind(this);
    this.handleReload = this.handleReload.bind(this);
  }

  /**
   * 渲染失败时切换到兜底 UI。
   *
   * @param {Error} error 错误对象
   * @returns {object} 状态补丁
   */
  static getDerivedStateFromError(error) {
    return { error };
  }

  /**
   * 记录错误现场（生产环境可在此接入前端监控上报）。
   *
   * @param {Error} error     错误对象
   * @param {object} errorInfo React 组件栈信息
   */
  componentDidCatch(error, errorInfo) {
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary] 页面渲染异常', error, errorInfo);
  }

  /** 仅清除错误态，让用户在原页面重试（不丢路由状态）。 */
  handleReset() {
    this.setState({ error: null });
  }

  /** 整页重载，用于错误态无法原地恢复的场景。 */
  handleReload() {
    window.location.reload();
  }

  render() {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }

    return (
      <div style={{ padding: 48 }}>
        <Result
          status="error"
          title="页面出了点问题"
          subTitle="已记录该异常。你可以先重试，或刷新页面回到初始状态。"
          extra={[
            <Button type="primary" key="reset" onClick={this.handleReset}>
              重试
            </Button>,
            <Button key="reload" onClick={this.handleReload}>
              刷新页面
            </Button>,
          ]}
        >
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            错误信息：{error.message || String(error)}
          </Typography.Paragraph>
        </Result>
      </div>
    );
  }
}
