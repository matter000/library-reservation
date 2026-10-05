import React, { useMemo, useState } from 'react';
import {
  Alert, Button, Card, DatePicker, Space, Typography, message,
} from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { downloadLedger, ledgerFileName, saveBlob, LEDGER_TYPES } from '../../api/export';

/**
 * 系统管理端·台账导出（T-V2-22）。
 *
 * <p>★ 只有系统管理员能进这一页（`/api/admin/**` 前缀规则）：导出去的是成批个人信息，
 * 每一次下载都会在后端留一条审计（类型、文件名、行数），所以页面上要明说这件事 ——
 * 让人知道「导出不是无痕的」，比事后查更有用。
 *
 * <p>★ 日期区间只对预约台账有意义，其它三类是全量快照；
 * 把选择器藏掉而不是让它灰着一个不变化的控件，是为了不让管理员以为「选了也没生效」。
 *
 * @returns {JSX.Element} 导出中心
 */
export default function ExportCenter() {
  const [range, setRange] = useState([dayjs(), dayjs()]);
  const [busy, setBusy] = useState('');

  const dateParams = useMemo(() => ({
    dateFrom: range[0] ? range[0].format('YYYY-MM-DD') : undefined,
    dateTo: range[1] ? range[1].format('YYYY-MM-DD') : undefined,
  }), [range]);

  const onDownload = async (item) => {
    setBusy(item.value);
    try {
      const params = item.value === 'reservation' ? dateParams : {};
      const blob = await downloadLedger(item.value, params);
      const fileName = ledgerFileName(item.value);
      saveBlob(blob, fileName);
      message.success(`已开始下载 ${fileName}`);
    } catch (e) {
      // ★ 这里的失败必须自己弹提示：responseType 是 blob 时请求拦截器拿不到业务 code，
      // 识别「下载到一个错误 JSON」的责任在 downloadLedger，它抛出的 message 就是后端那句原因
      message.error(e.message || '导出失败，请稍后重试');
    } finally {
      setBusy('');
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="导出会留审计记录"
        description="每点一次下载，后端都会记一条「谁在什么时候带走了哪一类、多少行」。台账含姓名与学号，请按最小必要范围导出。"
      />

      <Card title="预约台账日期区间" size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <DatePicker.RangePicker
            value={range}
            onChange={(values) => setRange(values || [dayjs(), dayjs()])}
            allowClear={false}
          />
          <Typography.Text type="secondary">
            仅预约台账使用；单次上限 5000 条，超了要缩小区间
          </Typography.Text>
        </Space>
      </Card>

      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {LEDGER_TYPES.map((item) => (
          <Card key={item.value} size="small" title={item.label}>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary">{item.note}</Typography.Text>
              <Button
                type="primary"
                icon={<DownloadOutlined />}
                loading={busy === item.value}
                onClick={() => onDownload(item)}
              >
                下载 CSV
              </Button>
            </Space>
          </Card>
        ))}
      </Space>
    </div>
  );
}
