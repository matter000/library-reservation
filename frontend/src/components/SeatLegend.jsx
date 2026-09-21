import React from 'react';
import { Tooltip } from 'antd';
import { SEAT_LEGEND_ORDER, SEAT_STATUS_META } from '../constants';

/**
 * 平面图图例条（常驻，不可折叠）。
 * 每个状态除颜色外带第二重标识（● / ●● / 🔧 + 斜线纹理），保证色盲可用。
 *
 * @returns {JSX.Element} 图例条
 */
export default function SeatLegend() {
  return (
    <div className="seat-legend">
      <span className="seat-legend-title">图例：</span>
      {SEAT_LEGEND_ORDER.map((status) => {
        const meta = SEAT_STATUS_META[status];
        return (
          <Tooltip key={status} title={`${meta.text}${meta.clickable ? '（可点击预约）' : '（不可预约）'}`}>
            <span className="seat-legend-item">
              <span
                className={`seat-swatch seat-swatch-${status.toLowerCase()}`}
                aria-label={meta.text}
              >
                {meta.marker ? <span className="seat-swatch-marker">{meta.marker}</span> : null}
              </span>
              <span className="seat-legend-text">{meta.text}</span>
            </span>
          </Tooltip>
        );
      })}
    </div>
  );
}
