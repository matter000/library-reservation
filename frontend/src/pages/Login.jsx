import React, { useState } from 'react';
import { Button, Card, Form, Input, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../store/AuthContext';

/**
 * 登录页：登录成功按角色跳转（ADMIN → /admin/statistics，USER → /floors）。
 *
 * @returns {JSX.Element} 登录页
 */
export default function Login() {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const user = await login(values.username.trim(), values.password);
      if (user.role === 'ADMIN') {
        navigate('/admin/statistics', { replace: true });
      } else {
        navigate('/floors', { replace: true });
      }
    } catch (e) {
      // 错误提示由 request 拦截器统一弹出
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-page">
      <Card className="auth-card" title="图书馆座位预约系统" bordered>
        <Form form={form} layout="vertical" onFinish={handleSubmit} requiredMark={false}>
          <Form.Item
            label="账号"
            name="username"
            rules={[
              { required: true, message: '请输入账号' },
              { min: 3, max: 20, message: '账号长度需为 3-20 位' },
            ]}
          >
            <Input prefix={<UserOutlined />} placeholder="请输入账号" autoComplete="username" size="large" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="请输入密码"
              autoComplete="current-password"
              size="large"
              onPressEnter={() => form.submit()}
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
            登录
          </Button>
          <div style={{ marginTop: 12, textAlign: 'right' }}>
            <Link to="/register">还没有账号？去注册</Link>
          </div>
        </Form>

        <div className="demo-account-tip">
          <Typography.Text strong>演示账号：</Typography.Text>
          <br />
          管理员：admin / admin123（进入管理后台）
          <br />
          学生：student1 / 123456、student2 / 123456、student3 / 123456
        </div>
      </Card>
    </div>
  );
}
