import { useEffect, useMemo, useState } from 'react';
import { fetchBookingConfig } from '../api/floor';
import {
  ADVANCE_DAYS,
  CHECK_IN_AFTER_MIN,
  CHECK_IN_BEFORE_MIN,
  DEFAULT_DURATION,
  DURATION_OPTIONS,
  GROUP_ATTEND_GRACE_MIN,
  GROUP_INVITE_TTL_MIN,
  GROUP_LOCK_BEFORE_MIN,
  GROUP_MIN_MEMBERS,
} from '../constants';

/** 规则中心不可用时的兜底配置。 */
const FALLBACK = {
  advanceDays: ADVANCE_DAYS,
  allowedDurations: DURATION_OPTIONS.map((option) => option.value),
  defaultDuration: DEFAULT_DURATION,
  checkInBeforeMin: CHECK_IN_BEFORE_MIN,
  checkInAfterMin: CHECK_IN_AFTER_MIN,
  groupLockBeforeMin: GROUP_LOCK_BEFORE_MIN,
  groupInviteTtlMin: GROUP_INVITE_TTL_MIN,
  groupMinMembers: GROUP_MIN_MEMBERS,
  groupAttendGraceMin: GROUP_ATTEND_GRACE_MIN,
};

/**
 * 预约表单配置：来自后端规则中心，管理员改值后刷新页面即生效。
 *
 * <p>读取失败时静默回退本地常量——表单先能填、提交时仍会被后端二次校验拦下，
 * 因此兜底不会造成越权预约。
 *
 * @returns {object} {advanceDays, allowedDurations, durationOptions, defaultDuration, checkInBeforeMin, checkInAfterMin}
 */
export default function useBookingConfig() {
  const [config, setConfig] = useState(FALLBACK);

  useEffect(() => {
    let alive = true;
    fetchBookingConfig()
      .then((data) => {
        if (alive && data) {
          // 逐项忽略 null：缺字段时保留兜底值，避免下游 durationOptions 直接 .map 崩掉
          const defined = Object.fromEntries(Object.entries(data).filter(([, v]) => v != null));
          setConfig({ ...FALLBACK, ...defined });
        }
      })
      .catch(() => {
        /* 兜底已就位，无需提示 */
      });
    return () => {
      alive = false;
    };
  }, []);

  // ★ 选项数组必须稳定：每次 render 新建一份，Select / Radio 的 rc-select 内部
  //   会因 options 变化再 setState，实测能把页面推进「Maximum update depth exceeded」。
  const durationOptions = useMemo(
    () => config.allowedDurations.map((hours) => ({ label: `${hours} 小时`, value: hours })),
    [config.allowedDurations],
  );

  return { ...config, durationOptions };
}
