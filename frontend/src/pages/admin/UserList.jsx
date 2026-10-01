import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Input, Table, Tag, Typography } from 'antd';
import { fetchAdminUsers } from '../../api/admin';
import PageLoading from '../../components/PageLoading';
import { USER_ROLE_META, roleText } from '../../constants';

/**
 * 管理员·用户管理（P1）：只列表，手机号由后端脱敏后返回。
 *
 * @returns {JSX.Element} 用户列表页
 */
export default function UserList() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [list, setList] = useState([]);
  const [total, setTotal] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await fetchAdminUsers({ keyword: keyword || undefined, page, size });
      setList(data && data.list ? data.list : []);
      setTotal(data ? data.total : 0);
    } catch (e) {
      setError(e.message || '加载用户失败');
      setList([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [keyword, page, size]);

  useEffect(() => {
    load();
  }, [load]);

  const columns = [
    { title: 'ID', dataIndex: 'userId', width: 70 },
    { title: '账号', dataIndex: 'username', width: 140 },
    { title: '姓名', dataIndex: 'realName', width: 120 },
    {
      title: '手机号',
      dataIndex: 'phoneMasked',
      width: 150,
      render: (v) => v || <Typography.Text type="secondary">未填写</Typography.Text>,
    },
    {
      title: '角色',
      dataIndex: 'role',
      width: 110,
      render: (v) => (
        <Tag color={(USER_ROLE_META[v] || {}).color || 'default'}>{roleText(v)}</Tag>
      ),
    },
    {
      title: '违约次数',
      dataIndex: 'noShowCount',
      width: 100,
      render: (v) => (
        <Tag color={(v || 0) > 0 ? 'orange' : 'green'}>{v || 0}</Tag>
      ),
    },
    { title: '注册时间', dataIndex: 'createTime', width: 170 },
  ];

  return (
    <div>
      <Typography.Title level={4} className="page-title">
        用户管理
      </Typography.Title>

      <div className="toolbar">
        <Input.Search
          placeholder="账号 / 姓名关键字"
          allowClear
          style={{ width: 240 }}
          onSearch={(v) => {
            setPage(1);
            setKeyword(v);
          }}
        />
        <span className="spacer" />
        <Typography.Text type="secondary">手机号已脱敏展示（如 138****1234）</Typography.Text>
      </div>

      {error ? <Alert type="error" message={error} style={{ marginBottom: 12 }} /> : null}

      {loading && list.length === 0 ? (
        <PageLoading />
      ) : (
        <Table
          rowKey="userId"
          columns={columns}
          dataSource={list}
          loading={loading}
          size="small"
          pagination={{
            current: page,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 个用户`,
            onChange: (p, s) => {
              setPage(p);
              setSize(s);
            },
          }}
        />
      )}
    </div>
  );
}
