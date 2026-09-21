import React, { useMemo, useState } from 'react';
import { Alert, Form, Input, InputNumber, Modal, Radio, Typography } from 'antd';
import { batchCreateSeats } from '../../api/admin';
import { SEAT_STATUS, SEAT_BASE_STATUS_META } from '../../constants';

/**
 * 计算批量生成的编号预览：前缀 + 补零序号（宽度至少 2 位），行优先递增。
 *
 * @param {object} values 表单值
 * @returns {{first:string,last:string,total:number,width:number}} 预览
 */
export function previewSeatNos(values) {
  const rows = Number(values.rows || 0);
  const cols = Number(values.cols || 0);
  const startNo = Number(values.startNo || 1);
  const prefix = values.prefix || '';
  const total = rows * cols;
  if (total <= 0) {
    return { first: '', last: '', total: 0, width: 2 };
  }
  const lastNo = startNo + total - 1;
  const width = Math.max(2, String(lastNo).length);
  const pad = (n) => String(n).padStart(width, '0');
  return { first: `${prefix}${pad(startNo)}`, last: `${prefix}${pad(lastNo)}`, total, width };
}

/**
 * 批量生成座位弹窗：区域 + 行数 + 列数 + 编号前缀 + 起始号 + 状态，
 * 实时预览「将生成 N 个（A01~A32）」，提交后展示成功/跳过数量与被跳过的编号。
 *
 * @param {object} props {open, floorId, onOk, onCancel}
 * @returns {JSX.Element} 弹窗
 */
export default function SeatBatchModal({ open, floorId, onOk, onCancel }) {
  const [form] = Form.useForm();
  const [values, setValues] = useState({ rows: 1, cols: 1, startNo: 1, prefix: 'A', status: SEAT_STATUS.AVAILABLE });
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState(null);

  const preview = useMemo(() => previewSeatNos(values), [values]);

  const handleOk = async () => {
    const formValues = await form.validateFields();
    setSubmitting(true);
    try {
      const data = await batchCreateSeats({
        floorId,
        zone: formValues.zone,
        rows: formValues.rows,
        cols: formValues.cols,
        prefix: formValues.prefix,
        startNo: formValues.startNo,
        status: formValues.status,
      });
      setResult(data);
      onOk && onOk(data);
    } catch (e) {
      setResult(null);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    setResult(null);
    form.resetFields();
    onCancel && onCancel();
  };

  return (
    <Modal
      open={open}
      title="批量生成座位"
      okText="开始生成"
      cancelText={result ? '关闭' : '取消'}
      confirmLoading={submitting}
      onOk={handleOk}
      onCancel={handleCancel}
      destroyOnClose
      width={520}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ rows: 4, cols: 8, startNo: 1, prefix: 'A', status: SEAT_STATUS.AVAILABLE }}
        onValuesChange={(_, all) => setValues(all)}
      >
        <Form.Item label="区域名" name="zone">
          <Input placeholder="选填，如 A区" maxLength={20} />
        </Form.Item>
        <Form.Item label="行数" name="rows" rules={[{ required: true, message: '请输入行数' }]}>
          <InputNumber min={1} max={100} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="列数" name="cols" rules={[{ required: true, message: '请输入列数' }]}>
          <InputNumber min={1} max={100} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="编号前缀" name="prefix" rules={[{ required: true, message: '请输入编号前缀' }]}>
          <Input placeholder="如 A" maxLength={8} />
        </Form.Item>
        <Form.Item label="起始编号" name="startNo" rules={[{ required: true, message: '请输入起始编号' }]}>
          <InputNumber min={1} max={9999} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="初始状态" name="status">
          <Radio.Group
            options={Object.keys(SEAT_BASE_STATUS_META).map((key) => ({
              label: SEAT_BASE_STATUS_META[key].text,
              value: key,
            }))}
          />
        </Form.Item>
      </Form>

      {preview.total > 0 ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={`将生成 ${preview.total} 个座位（${preview.first} ~ ${preview.last}），编号宽度 ${preview.width} 位，行优先递增`}
        />
      ) : null}

      {result ? (
        <Alert
          type={result.created > 0 ? 'success' : 'warning'}
          showIcon
          message={`成功生成 ${result.created} 个，跳过 ${result.skipped} 个（编号重复）`}
          description={
            result.skippedSeatNos && result.skippedSeatNos.length > 0 ? (
              <span>
                被跳过的编号：<Typography.Text code>{result.skippedSeatNos.join('、')}</Typography.Text>
              </span>
            ) : null
          }
        />
      ) : null}
    </Modal>
  );
}
