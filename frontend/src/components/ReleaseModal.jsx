import React, { useState } from 'react';
import { Alert, Form, Input, Modal } from 'antd';

/**
 * 强制释放弹窗：原因必填，≤100 字。
 *
 * @param {object} props props
 * @param {boolean} props.open 是否打开
 * @param {string} props.title 标题
 * @param {Function} props.onOk 提交回调（reason）=> Promise
 * @param {Function} props.onCancel 关闭回调
 * @param {boolean} props.confirmLoading 提交中
 * @returns {JSX.Element} 弹窗
 */
export default function ReleaseModal({ open, title = '强制释放', onOk, onCancel, confirmLoading }) {
  const [form] = Form.useForm();
  const [reason, setReason] = useState('');

  const handleOk = async () => {
    await form.validateFields();
    await onOk(reason.trim());
    setReason('');
    form.resetFields();
  };

  const handleCancel = () => {
    setReason('');
    form.resetFields();
    onCancel && onCancel();
  };

  return (
    <Modal
      open={open}
      title={title}
      okText="确认释放"
      cancelText="取消"
      okButtonProps={{ disabled: !reason.trim(), danger: true }}
      confirmLoading={confirmLoading}
      onOk={handleOk}
      onCancel={handleCancel}
      destroyOnClose
    >
      <Alert
        type="warning"
        showIcon
        message="释放后该座位对应时段将立即变为空闲，可被其他用户预约。"
        style={{ marginBottom: 12 }}
      />
      <Form form={form} layout="vertical">
        <Form.Item
          label="释放原因"
          name="reason"
          rules={[
            { required: true, message: '请填写释放原因' },
            { max: 100, message: '释放原因不能超过 100 字' },
          ]}
        >
          <Input.TextArea
            rows={3}
            maxLength={100}
            showCount
            placeholder="请填写释放原因（必填，≤100 字）"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              form.setFieldsValue({ reason: e.target.value });
            }}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
