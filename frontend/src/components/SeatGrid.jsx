import React from 'react';
import { Tooltip } from 'antd';
import { SEAT_STATUS_META } from '../constants';

/**
 * 座位平面图网格（原生 CSS Grid，行号/列号标尺 + 座位方块）。
 *
 * 座位方块固定 56×56px：窗口过窄时由外层容器横向滚动，不缩放（保证热区 ≥44px）。
 *
 * @param {object} props props
 * @param {Array} props.seats 座位列表（含 seatId/seatNo/rowNo/colNo/displayStatus）
 * @param {number} props.maxRow 最大行号
 * @param {number} props.maxCol 最大列号
 * @param {number} props.selectedSeatId 当前选中的座位（Drawer 打开的那个）
 * @param {Function} props.onSelect 点击回调（仅空闲座位触发）
 * @returns {JSX.Element} 网格
 */
export default function SeatGrid({ seats, maxRow, maxCol, selectedSeatId, onSelect }) {
  const safeMaxRow = maxRow && maxRow > 0 ? maxRow : 1;
  const safeMaxCol = maxCol && maxCol > 0 ? maxCol : 1;

  const rows = Array.from({ length: safeMaxRow }, (_, i) => i + 1);
  const cols = Array.from({ length: safeMaxCol }, (_, i) => i + 1);

  return (
    <div className="seat-grid-scroll">
      <div
        className="seat-grid"
        style={{
          gridTemplateColumns: `44px repeat(${safeMaxCol}, 56px)`,
          gridTemplateRows: `28px repeat(${safeMaxRow}, 56px)`,
        }}
      >
        <div className="seat-ruler-corner" style={{ gridColumn: 1, gridRow: 1 }} />

        {cols.map((col) => (
          <div key={`col-${col}`} className="seat-ruler-col" style={{ gridColumn: col + 1, gridRow: 1 }}>
            {col}
          </div>
        ))}

        {rows.map((row) => (
          <div key={`row-${row}`} className="seat-ruler-row" style={{ gridColumn: 1, gridRow: row + 1 }}>
            {row}
          </div>
        ))}

        {seats.map((seat) => {
          const meta = SEAT_STATUS_META[seat.displayStatus] || SEAT_STATUS_META.UNAVAILABLE;
          const clickable = Boolean(meta.clickable);
          const selected = selectedSeatId === seat.seatId;
          const className = [
            'seat-cell',
            `seat-cell-${(seat.displayStatus || '').toLowerCase()}`,
            clickable ? 'seat-cell-clickable' : 'seat-cell-disabled',
            selected ? 'seat-cell-selected' : '',
          ].join(' ');

          const content = (
            <span className="seat-cell-inner">
              <span className="seat-cell-no">{seat.seatNo}</span>
              {meta.marker ? <span className="seat-cell-marker">{meta.marker}</span> : null}
              {/* 暂离把「预计返回」直接写进格子：文案优先于颜色（PRD §13.3），不能只靠 hover 才看到 */}
              {seat.expectedReturnAt ? (
                <span className="seat-cell-return">{seat.expectedReturnAt}</span>
              ) : null}
            </span>
          );

          return (
            <Tooltip
              key={seat.seatId}
              title={
                clickable
                  ? `${seat.seatNo} · ${meta.text} · 点击预约`
                  : `${seat.seatNo} · ${meta.text}${seat.expectedReturnAt ? ` · 预计 ${seat.expectedReturnAt} 返回` : ''}`
              }
            >
              {clickable ? (
                <button
                  type="button"
                  className={className}
                  style={{ gridColumn: seat.colNo + 1, gridRow: seat.rowNo + 1 }}
                  onClick={() => onSelect && onSelect(seat)}
                  aria-label={`座位 ${seat.seatNo} ${meta.text}`}
                >
                  {content}
                </button>
              ) : (
                <div
                  className={className}
                  style={{ gridColumn: seat.colNo + 1, gridRow: seat.rowNo + 1 }}
                  aria-disabled="true"
                >
                  {content}
                </div>
              )}
            </Tooltip>
          );
        })}
      </div>
    </div>
  );
}
