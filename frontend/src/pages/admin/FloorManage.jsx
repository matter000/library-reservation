import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  createFloor,
  deleteFloor,
  fetchAdminFloors,
  updateFloor,
  updateFloorStatus,
} from '../../api/admin';
import { ERROR_CODE, FLOOR_STATUS, FLOOR_STATUS_META } from '../../constants';
import PageLoading from '../../components/PageLoading';

/**
 * 管理员·楼层管理：CRUD + 状态切换。
 *
 * @returns {JSX.Element} 楼层管理页
 */
export default function FloorManage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState([]);
  const [error, setError] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await fetchAdminFloors();
      setList(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message || '加载楼层失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  /** 打开新增弹窗。 */
  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ sortOrder: 0, status: FLOOR_STATUS.OPEN });
    setModalOpen(true);
  };

  /** 打开编辑弹窗。 */
  const openEdit = (row) => {
    setEditing(row);
    form.setFieldsValue({
      name: row.name,
      code: row.code,
      description: row.description,
      sortOrder: row.sortOrder,
      status: row.status,
    });
    setModalOpen(true);
  };

  /** 提交新增/编辑。 */
  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editing) {
        await updateFloor(editing.floorId, values);
        message.success('楼层已更新');
      } else {
        await createFloor(values);
        message.success('楼层已创建');
      }
      setModalOpen(false);
      form.resetFields();
      await load();
    } catch (e) {
      // 提示由拦截器弹出
    } finally {
      setSubmitting(false);
    }
  };

  /** 删除楼层（二次确认）。 */
  const handleDelete = (row) => {
    Modal.confirm({
      title: `确认删除楼层「${row.name}」？`,
      content: `该楼层下 ${row.seatCount || 0} 个座位将一并移除，历史预约记录也会被清理。`,
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteFloor(row.floorId);
          message.success('楼层已删除');
          await load();
        } catch (e) {
          if (e && e.code === ERROR_CODE.DELETE_FORBIDDEN) {
            message.error('存在未来预约，无法删除，请先释放相关预约');
          }
        }
      },
    });
  };

  /** 切换开放/关闭。 */
  const handleStatus = async (row, status) => {
    try {
      await updateFloorStatus(row.floorId, status);
      message.success(status === FLOOR_STATUS.OPEN ? '楼层已开放' : '楼层已关闭');
      await load();
    } catch (e) {
      // 提示由拦截器弹出
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'floorId', width: 60 },
    { title: '楼层名称', dataIndex: 'name' },
    { title: '编号', dataIndex: 'code', width: 90, render: (v) => <Tag>{v}</Tag> },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (v) => v || <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    {
      title: '座位数',
      dataIndex: 'seatCount',
      width: 100,
      render: (v, row) => (
        <Button type="link" size="small" onClick={() => navigate(`/admin/floors/${row.floorId}/seats`)}>
          {v || 0}
        </Button>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v) => <Tag color={FLOOR_STATUS_META[v]?.color}>{FLOOR_STATUS_META[v]?.text || v}</Tag>,
    },
    {
      title: '操作',
      width: 230,
      render: (_, row) => (
        <Space size="small">
          <Button size="small" onClick={() => openEdit(row)}>
            编辑
          </Button>
          {row.status === FLOOR_STATUS.OPEN ? (
            <Button size="small" onClick={() => handleStatus(row, FLOOR_STATUS.CLOSED)}>
              关闭
            </Button>
          ) : (
            <Button size="small" type="primary" onClick={() => handleStatus(row, FLOOR_STATUS.OPEN)}>
              开放
            </Button>
          )}
          <Button size="small" danger onClick={() => handleDelete(row)}>
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="toolbar">
        <Typography.Title level={4} style={{ margin: 0 }}>
          楼层管理
        </Typography.Title>
        <span className="spacer" />
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增楼层
        </Button>
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      {loading && list.length === 0 ? (
        <PageLoading />
      ) : (
        <Table rowKey="floorId" columns={columns} dataSource={list} pagination={false} size="middle" />
      )}

      <Modal
        open={modalOpen}
        title={editing ? '编辑楼层' : '新增楼层'}
        okText="保存"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item label="楼层名称" name="name" rules={[{ required: true, message: '请输入楼层名称' }]}>
            <Input placeholder="如：一楼自习区" maxLength={50} />
          </Form.Item>
          <Form.Item label="楼层编号" name="code" rules={[{ required: true, message: '请输入楼层编号' }]}>
            <Input placeholder="如：1F" maxLength={20} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
          <Form.Item label="排序号" name="sortOrder">
            <InputNumber min={0} max={999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="状态" name="status">
            <Radio.Group
              options={[
                { label: '开放中', value: FLOOR_STATUS.OPEN },
                { label: '已关闭', value: FLOOR_STATUS.CLOSED },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
