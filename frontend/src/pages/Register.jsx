import React, { useState } from 'react';
import { Button, Card, Form, Input, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { register } from '../api/auth';

/**
 * 注册页：账号 3-20、密码 ≥6、确认密码一致、姓名必填、手机号选填。
 * 无角色选项（后端强制 USER）。
 *
 * @returns {JSX.Element} 注册页
 */
export default function Register() {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      await register({
        username: values.username.trim(),
        password: values.password,
        confirmPassword: values.confirmPassword,
        realName: values.realName.trim(),
        phone: values.phone ? values.phone.trim() : undefined,
      });
      navigate('/login', { replace: true, state: { registered: true } });
    } catch (e) {
      // 错误提示由 request 拦截器统一弹出
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-page">
      <Card className="auth-card" title="注册新账号" bordered>
        <Form form={form} layout="vertical" onFinish={handleSubmit} requiredMark>
          <Form.Item
            label="账号"
            name="username"
            rules={[
              { required: true, message: '请输入账号' },
              { min: 3, max: 20, message: '账号长度需为 3-20 位' },
              { pattern: /^[A-Za-z0-9_]+$/, message: '账号只能包含字母、数字和下划线' },
            ]}
          >
            <Input placeholder="3-20 位字母/数字/下划线" size="large" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 6, message: '密码长度至少 6 位' },
            ]}
          >
            <Input.Password placeholder="至少 6 位" size="large" />
          </Form.Item>
          <Form.Item
            label="确认密码"
            name="confirmPassword"
            dependencies={['password']}
            rules={[
              { required: true, message: '请再次输入密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password placeholder="请再次输入密码" size="large" />
          </Form.Item>
          <Form.Item
            label="姓名"
            name="realName"
            rules={[{ required: true, message: '请输入姓名' }]}
          >
            <Input placeholder="请输入真实姓名" size="large" />
          </Form.Item>
          <Form.Item
            label="手机号"
            name="phone"
            rules={[{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]}
            extra="选填"
          >
            <Input placeholder="选填，如 13812345678" size="large" maxLength={11} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
            注册
          </Button>
          <div style={{ marginTop: 12, textAlign: 'right' }}>
            <Link to="/login">已有账号？去登录</Link>
          </div>
        </Form>
        <div className="demo-account-tip">
          <Typography.Text type="secondary">注册后为普通用户，可直接登录预约座位。</Typography.Text>
        </div>
      </Card>
    </div>
  );
}
