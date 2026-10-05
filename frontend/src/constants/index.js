/**
 * 全局常量：座位展示态、预约状态、楼层/座位状态、时段与时长选项。
 * 所有枚举值必须与后端 DTO 完全一致。
 */

/** 座位派生展示态（后端 SeatDisplayStatus，平面图使用）。 */
export const SEAT_DISPLAY_STATUS = {
  FREE: 'FREE',
  RESERVED: 'RESERVED',
  IN_USE: 'IN_USE',
  ON_LEAVE: 'ON_LEAVE',
  UNAVAILABLE: 'UNAVAILABLE',
};

/** 展示态 → 中文文案 + 色值 + 是否可点击预约。 */
export const SEAT_STATUS_META = {
  FREE: { text: '空闲', color: '#52c41a', clickable: true, marker: '' },
  RESERVED: { text: '已预约', color: '#faad14', clickable: false, marker: '●' },
  IN_USE: { text: '使用中', color: '#f5222d', clickable: false, marker: '●●' },
  // 暂离仍算占用，只是人不在座位上；⌛ 是颜色之外的第二重标识（色盲可用）
  ON_LEAVE: { text: '暂离中', color: '#1677ff', clickable: false, marker: '⌛' },
  UNAVAILABLE: { text: '不可用', color: '#bfbfbf', clickable: false, marker: '🔧' },
};

/** 图例顺序（常驻图例条按此顺序展示）。 */
export const SEAT_LEGEND_ORDER = ['FREE', 'RESERVED', 'IN_USE', 'ON_LEAVE', 'UNAVAILABLE'];

/** 预约状态（后端 ReservationStatus）。 */
export const RESERVATION_STATUS = {
  CONFIRMED: 'CONFIRMED',
  CHECKED_IN: 'CHECKED_IN',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  NO_SHOW: 'NO_SHOW',
  VIOLATED: 'VIOLATED',
  ADMIN_CANCELLED: 'ADMIN_CANCELLED',
};

/** 预约状态 → 中文文案 + Tag 颜色。 */
export const RESERVATION_STATUS_META = {
  CONFIRMED: { text: '已预约', color: 'blue' },
  CHECKED_IN: { text: '使用中', color: 'red' },
  COMPLETED: { text: '已完成', color: 'green' },
  CANCELLED: { text: '已取消', color: 'default' },
  NO_SHOW: { text: '未签到', color: 'orange' },
  // 暂离超时未归：文案要说清成因，与「未签到」区分（两者处罚不同）
  VIOLATED: { text: '暂离未归', color: 'volcano' },
  ADMIN_CANCELLED: { text: '已被管理员取消', color: 'volcano' },
};

/** 楼层状态（后端 FloorStatus）。 */
export const FLOOR_STATUS = { OPEN: 'OPEN', CLOSED: 'CLOSED' };

export const FLOOR_STATUS_META = {
  OPEN: { text: '开放中', color: 'green' },
  CLOSED: { text: '已关闭', color: 'default' },
};

/** 座位基础状态（后端 SeatStatus）。 */
export const SEAT_STATUS = {
  AVAILABLE: 'AVAILABLE',
  MAINTENANCE: 'MAINTENANCE',
  DISABLED: 'DISABLED',
};

export const SEAT_BASE_STATUS_META = {
  AVAILABLE: { text: '可用', color: 'green' },
  MAINTENANCE: { text: '维护中', color: 'orange' },
  DISABLED: { text: '已停用', color: 'default' },
};

/** 开放时间与时段（后端 BizConstants：08:00-22:00，V2 起内部格长 30 分钟）。 */
/** 每个时段的分钟数。 */
export const SLOT_MINUTES = 30;

/** 每小时格数。 */
export const SLOTS_PER_HOUR = 60 / SLOT_MINUTES;

/** 时段序号下界（08:00）。 */
export const SLOT_INDEX_MIN = 8 * SLOTS_PER_HOUR;

/** 时段序号上界（21:30，最后一格的起始）。 */
export const SLOT_INDEX_MAX = 22 * SLOTS_PER_HOUR - 1;

/** 可选时长（★ 仅作为规则中心不可用时的兜底，真实值取自 /api/config/booking）。 */
export const DURATION_OPTIONS = [
  { label: '1 小时', value: 1 },
  { label: '2 小时', value: 2 },
  { label: '3 小时', value: 3 },
  { label: '4 小时', value: 4 },
];

/** 默认时长（兜底）。 */
export const DEFAULT_DURATION = 2;

/** 可预约窗口天数（今天 ~ 今天+6）。★ 仅作为规则中心不可用时的兜底，真实值取自 /api/config/booking。 */
export const ADVANCE_DAYS = 7;

/** 签到窗口：开始前多少分钟（兜底）。 */
export const CHECK_IN_BEFORE_MIN = 15;

/** 签到窗口：开始后多少分钟（兜底）。 */
export const CHECK_IN_AFTER_MIN = 15;

/** 分区类型（与后端 ZoneType 对齐）。 */
export const ZONE_TYPE_META = {
  SILENT: { text: '静音自习', color: 'blue' },
  SEMINAR: { text: '研讨协作', color: 'purple' },
  E_READING: { text: '电子阅览', color: 'cyan' },
  LOUNGE: { text: '休闲阅览', color: 'orange' },
  EXAM_PREP: { text: '考研专座', color: 'red' },
  TEACHER_ONLY: { text: '教师专区', color: 'gold' },
};

/** 分区类型下拉项。 */
export const ZONE_TYPE_OPTIONS = Object.entries(ZONE_TYPE_META)
  .map(([value, meta]) => ({ value, label: meta.text }));

/** 讨论音量等级（与后端 DiscussLevel 对齐）。 */
export const DISCUSS_OPTIONS = [
  { value: 'NO', label: '禁止交谈' },
  { value: 'LOW', label: '低声交流' },
  { value: 'YES', label: '允许讨论' },
];

/** 日型（与后端 DayType 对齐）。 */
export const DAY_TYPE_OPTIONS = [
  { value: 'WORKDAY', label: '工作日' },
  { value: 'WEEKEND', label: '周末' },
  { value: 'HOLIDAY', label: '节假日' },
];

/**
 * 违规类型 → 文案 + Tag 颜色（与后端 ViolationType 对齐）。
 *
 * ★ 列表里的文案一律用后端返回的 typeText / statusText，本表的 text 只用于筛选下拉：
 * 扣分口径与状态名都可能在后端演进，两处各写一份就会出现「页面上写的和后端判的不一样」。
 */
export const VIOLATION_TYPE_META = {
  NO_SHOW: { text: '爽约', color: 'red' },
  LEAVE_TIMEOUT: { text: '暂离未归', color: 'volcano' },
  GROUP_ABSENT: { text: '组队缺席', color: 'orange' },
  LEADER_ABSENT: { text: '组长缺席', color: 'magenta' },
  ON_SITE_OCCUPY: { text: '现场占座', color: 'purple' },
};

/** 违规状态（与后端 ViolationStatus 对齐）。 */
export const VIOLATION_STATUS_META = {
  ACTIVE: { text: '生效中', color: 'red' },
  APPEALING: { text: '申诉中', color: 'gold' },
  REVOKED: { text: '已撤销', color: 'green' },
};

/** 申诉状态（与后端 AppealStatus 对齐）。 */
export const APPEAL_STATUS_META = {
  PENDING: { text: '待复核', color: 'gold' },
  APPROVED: { text: '申诉成立', color: 'green' },
  REJECTED: { text: '申诉驳回', color: 'red' },
  ESCALATED: { text: '已升级终审', color: 'blue' },
  FINAL_JUDGED: { text: '已终审', color: 'purple' },
};

/** 信用流水类型（与后端 CreditLogKind 对齐）。 */
export const CREDIT_LOG_KIND_META = {
  INIT: { text: '初始分', color: 'default' },
  PENALTY: { text: '违规扣分', color: 'red' },
  STREAK_BONUS: { text: '良好使用奖励', color: 'green' },
  WEEKLY_BONUS: { text: '周恢复奖励', color: 'green' },
  RESTORE: { text: '申诉回补', color: 'blue' },
};

/**
 * 队伍状态（与后端 GroupStatus 对齐）。
 *
 * ★ 文案一律用后端返回的 statusText，本表只提供配色：状态机在 FORMING 之后
 * 还会插入 LOCKED（已锁定、正在下单）这一瞬时态，两处各写一份就会漏。
 */
export const GROUP_STATUS_META = {
  FORMING: { text: '组队中', color: 'blue' },
  LOCKED: { text: '已锁定', color: 'processing' },
  RESERVED: { text: '已订座', color: 'cyan' },
  CHECKED_IN: { text: '使用中', color: 'red' },
  COMPLETED: { text: '已完成', color: 'green' },
  CANCELLED: { text: '已取消', color: 'default' },
  FAILED: { text: '未成团', color: 'orange' },
};

/** 邀请状态（与后端 InviteStatus 对齐）。 */
export const INVITE_STATUS_META = {
  INVITED: { text: '待确认', color: 'gold' },
  CONFIRMED: { text: '已确认', color: 'green' },
  DECLINED: { text: '已婉拒', color: 'default' },
  EXPIRED: { text: '已过期', color: 'orange' },
};

/** 入座状态（与后端 AttendStatus 对齐；只有组长签到后才会离开 UNKNOWN）。 */
export const ATTEND_STATUS_META = {
  UNKNOWN: { text: '待确认入座', color: 'default' },
  PRESENT: { text: '已入座', color: 'green' },
  ABSENT: { text: '未入座', color: 'red' },
};

/**
 * 组队规则兜底值（真实值取自 /api/config/booking，与后端 BizConstants 同源）。
 *
 * <p>这些数字只用于「表单提示与倒计时展示」，判定全在服务端；兜底写错也拦不住越权，
 * 但服务端返回值到手后必须覆盖它。
 */
export const GROUP_LOCK_BEFORE_MIN = 30;

export const GROUP_INVITE_TTL_MIN = 10;

export const GROUP_MIN_MEMBERS = 2;

export const GROUP_ATTEND_GRACE_MIN = 30;

/** 组队页轮询间隔：锁定与邀请过期都按分钟推进，30 秒会让倒计时看起来「卡住」。 */
export const GROUP_POLL_INTERVAL = 15000;

/** 候补页轮询间隔：同理，offer 确认窗口也是按分钟推进的。 */
export const WAITLIST_POLL_INTERVAL = 15000;

/** 铃铛轮询间隔：没有推送通道，站内通知只能靠轮询送达，15 秒是「像即时」与「不打扰后端」的折中。 */
export const NOTICE_POLL_INTERVAL = 15000;

/** 候补 offer 窗口兜底分钟数（真实值取自 /api/config/booking 的 waitlist.offer-min）。 */
export const WAITLIST_OFFER_MIN = 10;

/** 每人可同时挂几条候补的兜底值（真实值取自 waitlist.max-active）。 */
export const WAITLIST_MAX_ACTIVE = 2;

/**
 * 候补状态（与后端 WaitlistStatus 对齐）。
 *
 * ★ 与队伍状态同样只在这里配色：文案一律用后端返回的 statusText，
 * 状态机将来加一个瞬时态（比如「已通知」）不至于出现「后端发了、前端不会显示」。
 */
export const WAITLIST_STATUS_META = {
  WAITING: { color: 'blue' },
  OFFERED: { color: 'gold' },
  CONFIRMED: { color: 'green' },
  EXPIRED: { color: 'orange' },
  CANCELLED: { color: 'default' },
};

/**
 * 巡查等级与状态（T-V2-21）。
 *
 * ★ 这里只配色：文案一律用后端返回的 levelText / statusText。
 * 等级与状态都可能随规则演进（比如将来加「超时未处理自动升级」），两处各写一份就会漏。
 */
export const PATROL_LEVEL_META = {
  HIGH: { color: 'red' },
  MID: { color: 'orange' },
  LOW: { color: 'default' },
};

export const PATROL_STATUS_META = {
  PENDING: { color: 'gold' },
  REMIND: { color: 'blue' },
  RELEASED: { color: 'volcano' },
  VIOLATION: { color: 'red' },
  IGNORED: { color: 'default' },
};

/** 巡查看板轮询间隔：任务由 10 分钟一轮的生成器产生，30 秒足够「刚报的能在巡楼路上看到」。 */
export const PATROL_BOARD_POLL_INTERVAL = 30000;

/** 违规状态下拉项。 */
export const VIOLATION_STATUS_OPTIONS = Object.entries(VIOLATION_STATUS_META)
  .map(([value, meta]) => ({ value, label: meta.text }));

/** 申诉状态下拉项。 */
export const APPEAL_STATUS_OPTIONS = Object.entries(APPEAL_STATUS_META)
  .map(([value, meta]) => ({ value, label: meta.text }));

/** localStorage key。 */
export const STORAGE_KEYS = {
  token: 'lsr_token',
  user: 'lsr_user',
};

/** 平面图 30 秒轮询间隔。 */
export const SEAT_MAP_POLL_INTERVAL = 30000;

/** 业务错误码。 */
export const ERROR_CODE = {
  PARAM_ERROR: 4001,
  UNAUTHORIZED: 4010,
  FORBIDDEN: 4030,
  NOT_FOUND: 4040,
  SEAT_SLOT_CONFLICT: 4091,
  USER_SLOT_OVERLAP: 4092,
  STATUS_NOT_ALLOWED: 4093,
  DELETE_FORBIDDEN: 4094,
};

/** 用户角色（与后端 UserRole 枚举逐字一致，V2 四角色）。 */
export const USER_ROLE = {
  STUDENT: 'STUDENT',
  TEACHER: 'TEACHER',
  LIBRARIAN: 'LIBRARIAN',
  SYS_ADMIN: 'SYS_ADMIN',
};

/**
 * 角色 → 中文文案 + Tag 颜色 + 登录后落地路由。
 *
 * ★ 落地路由只在这里定义一次，Login / RoleHome / RequireAuth 全部从这里取，
 *   避免「同一个信息写两处」导致改一处漏一处。
 */
export const USER_ROLE_META = {
  STUDENT: { text: '学生', color: 'blue', home: '/floors' },
  TEACHER: { text: '教师', color: 'cyan', home: '/floors' },
  LIBRARIAN: { text: '馆员', color: 'gold', home: '/staff' },
  SYS_ADMIN: { text: '系统管理员', color: 'volcano', home: '/admin/statistics' },
};

/**
 * 角色中文文案（未知角色回落到原始值，便于排查）。
 *
 * @param {string} role 角色枚举值
 * @returns {string} 文案
 */
export function roleText(role) {
  const meta = USER_ROLE_META[role];
  return meta ? meta.text : role || '未知角色';
}

/**
 * 该角色登录后的默认落地路由。
 *
 * @param {string} role 角色枚举值
 * @returns {string} 路径
 */
export function roleHomePath(role) {
  const meta = USER_ROLE_META[role];
  return meta ? meta.home : '/floors';
}
