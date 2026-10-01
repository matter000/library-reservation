import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Spin,
  Statistic,
  Tag,
  Typography,
  message,
} from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { changePassword, fetchMe } from '../api/auth';
import { useAuth } from '../store/AuthContext';
import { USER_ROLE_META, roleText } from '../constants';

/**
 * 个人中心：基本信息 + 违约次数 + 修改密码。
 *
 * 改密成功后主动清除本地登录态并跳转登录页 —— JWT 无状态，
 * 旧 Token 在过期前仍然有效，需要前端做这层补偿。
 *
 * @returns {JSX.Element} 个人中心
 */
export default function Profile() {
  const navigate = useNavigate();
  const { user: cachedUser, logout } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState(cachedUser);

  const [pwdOpen, setPwdOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    setLoading(true);
    fetchMe()
      .then((data) => {
        setInfo(data);
        setError('');
      })
      .catch((e) => setError(e.message || '加载个人信息失败'))
      .finally(() => setLoading(false));
  }, []);

  /** 关闭改密弹窗并清空表单。 */
  const closePwdModal = () => {
    setPwdOpen(false);
    form.resetFields();
  };

  /** 提交修改密码。 */
  const handleChangePassword = async (values) => {
    setSubmitting(true);
    try {
      await changePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
        confirmPassword: values.confirmPassword,
      });
      message.success('密码修改成功，请使用新密码重新登录');
      closePwdModal();
      logout();
      navigate('/login', { replace: true });
    } catch (e) {
      // 错误提示由 axios 拦截器统一弹出
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        个人中心
      </Typography.Title>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      <Spin spinning={loading}>
        <Card style={{ maxWidth: 640 }}>
          {info ? (
            <>
              <Descriptions column={1} bordered size="small">
                <Descriptions.Item label="账号">{info.username}</Descriptions.Item>
                <Descriptions.Item label="姓名">{info.realName}</Descriptions.Item>
                <Descriptions.Item label="手机号">{info.phoneMasked || '未填写'}</Descriptions.Item>
                <Descriptions.Item label="角色">
                  <Tag color={(USER_ROLE_META[info.role] || {}).color || 'default'}>
                    {roleText(info.role)}
                  </Tag>
                </Descriptions.Item>
              </Descriptions>

              <div style={{ marginTop: 20 }}>
                <Statistic
                  title="超时未签到次数（违约次数）"
                  value={info.noShowCount || 0}
                  suffix="次"
                  valueStyle={{ color: (info.noShowCount || 0) > 0 ? '#faad14' : '#52c41a' }}
                />
                <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                  预约开始后 15 分钟内未签到将自动记为未签到，并累计违约次数。
                </Typography.Paragraph>
              </div>

              <div style={{ marginTop: 20 }}>
                <Button icon={<LockOutlined />} onClick={() => setPwdOpen(true)}>
                  修改密码
                </Button>
              </div>
            </>
          ) : (
            <Typography.Text type="secondary">暂无个人信息</Typography.Text>
          )}
        </Card>
      </Spin>

      <Modal
        open={pwdOpen}
        title="修改密码"
        okText="确认修改"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => form.submit()}
        onCancel={closePwdModal}
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          message="修改成功后需使用新密码重新登录"
          style={{ marginBottom: 16 }}
        />
        <Form form={form} layout="vertical" onFinish={handleChangePassword} preserve={false}>
          <Form.Item
            name="oldPassword"
            label="原密码"
            rules={[{ required: true, message: '请输入原密码' }]}
          >
            <Input.Password placeholder="请输入当前密码" autoComplete="current-password" />
          </Form.Item>

          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, max: 32, message: '密码长度需为 6-32 位' },
            ]}
          >
            <Input.Password placeholder="6-32 位" autoComplete="new-password" />
          </Form.Item>

          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的新密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password placeholder="请再次输入新密码" autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
