import React, { useMemo, useState } from 'react';
import {
  Alert, Button, Form, Input, InputNumber, Modal, Radio, Select, Space, Typography,
} from 'antd';
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
export default function SeatBatchModal({ open, floorId, zones, onOk, onCancel }) {
  const [form] = Form.useForm();
  const [values, setValues] = useState({ rows: 1, cols: 1, startNo: 1, prefix: 'A', status: SEAT_STATUS.AVAILABLE });
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState(null);

  const preview = useMemo(() => previewSeatNos(values), [values]);

  const payload = (formValues) => ({
    floorId,
    zone: formValues.zone,
    zoneId: formValues.zoneId,
    rows: formValues.rows,
    cols: formValues.cols,
    prefix: formValues.prefix,
    startNo: formValues.startNo,
    status: formValues.status,
    rowFrom: formValues.rowFrom,
    colFrom: formValues.colFrom,
  });

  const handleOk = async () => {
    const formValues = await form.validateFields();
    setSubmitting(true);
    try {
      const data = await batchCreateSeats(payload(formValues));
      setResult(data);
      onOk && onOk(data);
    } catch (e) {
      setResult(null);
    } finally {
      setSubmitting(false);
    }
  };

  /** 只预演不写库：把冲突在点「开始生成」之前暴露出来。 */
  const handlePreview = async () => {
    const formValues = await form.validateFields();
    setSubmitting(true);
    try {
      setResult(await previewBatchSeats(payload(formValues)));
    } catch (e) {
      // 拦截器已提示
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    setResult(null);
    form.resetFields();
    onCancel && onCancel();
  };

  const conflictText = (data) => {
    const parts = [];
    if (data.skippedSeatNos?.length) {
      parts.push(`编号重复：${data.skippedSeatNos.join('、')}`);
    }
    if (data.skippedPositions?.length) {
      parts.push(`格子已被占：${data.skippedPositions.join(' ')}`);
    }
    return parts;
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
      footer={(_, { OkBtn, CancelBtn }) => (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={handlePreview} loading={submitting}>冲突预览</Button>
          <CancelBtn />
          <OkBtn />
        </div>
      )}
      destroyOnClose
      width={520}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          rows: 4, cols: 8, startNo: 1, prefix: 'A', status: SEAT_STATUS.AVAILABLE, rowFrom: 1, colFrom: 1,
        }}
        onValuesChange={(_, all) => setValues(all)}
      >
        <Form.Item label="归属分区" name="zoneId" extra="留空则按区域名匹配，匹配不到落到本层兜底分区">
          <Select
            allowClear
            placeholder="选择分区"
            options={(zones || []).map((z) => ({ value: z.zoneId, label: `${z.name}（${z.code}）` }))}
          />
        </Form.Item>
        <Form.Item label="区域名" name="zone">
          <Input placeholder="选填，如 A区" maxLength={20} />
        </Form.Item>
        <Form.Item label="行数" name="rows" rules={[{ required: true, message: '请输入行数' }]}>
          <InputNumber min={1} max={100} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="列数" name="cols" rules={[{ required: true, message: '请输入列数' }]}>
          <InputNumber min={1} max={100} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          label="起始行号"
          name="rowFrom"
          rules={[{ required: true, message: '请输入起始行号' }]}
          extra="从第几行开始排布。已有座位占用的行会被逐格跳过"
        >
          <InputNumber min={1} max={200} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="起始列号" name="colFrom" rules={[{ required: true, message: '请输入起始列号' }]}>
          <InputNumber min={1} max={200} style={{ width: '100%' }} />
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
          message={result.previewOnly
            ? `预演结果：可生成 ${result.created} 个，冲突 ${result.skipped} 个（未写入数据库）`
            : `成功生成 ${result.created} 个，跳过 ${result.skipped} 个`}
          description={conflictText(result).length ? (
            <Space direction="vertical" size={2}>
              {conflictText(result).map((line) => (
                <Typography.Text key={line} style={{ fontSize: 12 }}>{line}</Typography.Text>
              ))}
            </Space>
          ) : null}
        />
      ) : null}
    </Modal>
  );
}
