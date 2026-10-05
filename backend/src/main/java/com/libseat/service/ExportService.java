package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.PageResult;
import com.libseat.dto.AdminDTO;
import com.libseat.dto.AppealDTO;
import com.libseat.dto.PatrolDTO;
import com.libseat.entity.Penalty;
import com.libseat.entity.User;
import com.libseat.enums.PenaltyStatus;
import com.libseat.repository.PenaltyRepository;
import com.libseat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运营台账 CSV 导出（TASKS-V2 T-V2-22，DESIGN §12 A7）。
 *
 * <p>★ 三类数据细节：
 * <ul>
 *   <li><b>UTF-8 BOM 必须有</b>：Excel 打开无 BOM 的 UTF-8 CSV 会按本地代码页猜，中文就成乱码；
 *       这是验收标准里写死的一条（导出给馆员看，乱码等于没有导出）。</li>
 *   <li><b>每格都加引号并转义引号</b>：馆员填写的处置说明里出现逗号是常态，
 *       不引用就会把一行撑成两列。</li>
 *   <li><b>以 {@code = + - @ Tab} 开头的值前置一个单引号</b>：这些是会被 Excel 当公式执行的引导字符，
 *       而导出列里有很多是用户输入（姓名、说明、理由）。CSV 注入不是理论问题。</li>
 * </ul>
 *
 * <p>★ 取数一律复用各自服务的读方法：导出列和页面列出自同一个 VO，
 * 页面加了字段而导出没跟上，是这类台账最容易烂掉的方式。
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    /** 单次导出上限：超了就要求缩小范围，而不是静默截断（台账少几行没人看得出来）。 */
    private static final int EXPORT_LIMIT = 5000;

    /** UTF-8 BOM，Excel 识别用。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 会被 Excel 当公式执行的引导字符。 */
    private static final String FORMULA_LEADERS = "=+-@\t";

    /** 预约台账。 */
    private static final String TYPE_RESERVATION = "reservation";

    /** 违规台账。 */
    private static final String TYPE_VIOLATION = "violation";

    /** 巡查台账。 */
    private static final String TYPE_PATROL = "patrol";

    /** 信用台账。 */
    private static final String TYPE_CREDIT = "credit";

    private static final Map<String, String> FILE_BASE = Map.of(
            TYPE_RESERVATION, "reservations",
            TYPE_VIOLATION, "violations",
            TYPE_PATROL, "patrol",
            TYPE_CREDIT, "credit");

    private final AdminUsageService adminUsageService;

    private final ViolationService violationService;

    private final PatrolService patrolService;

    private final UserRepository userRepository;

    private final PenaltyRepository penaltyRepository;

    /**
     * 一份导出文件。
     *
     * @param fileName 建议文件名（纯 ASCII，避免 Content-Disposition 的中文编码问题）
     * @param content  CSV 内容（含 BOM）
     * @param rows     数据行数（不含表头），由调用方写进审计 —— 导出了多少条人，要能查
     */
    public record ExportFile(String fileName, byte[] content, int rows) {
    }

    /**
     * 按类型导出。
     *
     * @param type     {@code reservation|violation|patrol|credit}
     * @param dateFrom 起始日期（仅预约台账用），null 表示今天
     * @param dateTo   结束日期（仅预约台账用），null 表示与起始同一天
     * @param now      判定时刻（信用台账的「当前生效处罚」按这一刻算，测试可注入）
     * @return 文件
     */
    @Transactional(readOnly = true)
    public ExportFile export(String type, LocalDate dateFrom, LocalDate dateTo, LocalDateTime now) {
        String base = FILE_BASE.get(type == null ? "" : type);
        if (base == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "不支持的导出类型，可选：" + String.join("/", FILE_BASE.keySet().stream().sorted().toList()));
        }
        List<List<Object>> rows = switch (type) {
            case TYPE_RESERVATION -> reservationRows(dateFrom, dateTo);
            case TYPE_VIOLATION -> violationRows();
            case TYPE_PATROL -> patrolRows();
            default -> creditRows(now);
        };
        byte[] body = toCsv(headerOf(type), rows);
        String stamp = BizConstants.formatDateTime(now).replace("-", "").replace(":", "").replace(" ", "-");
        return new ExportFile(base + "-" + stamp + ".csv", body, rows.size());
    }

    // ==================== 各类台账 ====================

    private List<String> headerOf(String type) {
        return switch (type) {
            case TYPE_RESERVATION -> List.of("预约号", "用户ID", "学号", "姓名", "楼层", "座位", "日期",
                    "时段", "时长(小时)", "状态", "签到时刻", "取消时刻", "取消/释放原因", "创建时刻");
            case TYPE_VIOLATION -> List.of("违规号", "用户ID", "学号", "姓名", "预约号", "类型", "扣分",
                    "状态", "裁定人", "裁定依据", "创建时刻", "撤销时刻", "撤销原因", "申诉状态", "申诉回复");
            case TYPE_PATROL -> List.of("任务号", "楼层ID", "分区ID", "座位", "关联预约", "使用人", "疑点类型",
                    "等级", "生成依据", "证据", "状态", "处置人", "处置说明", "照片", "生成时刻", "处置时刻");
            default -> List.of("用户ID", "学号", "姓名", "角色", "信用分", "当前生效处罚", "累计超时未签到",
                    "主动结束连续次数", "账号状态");
        };
    }

    private List<List<Object>> reservationRows(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate from = dateFrom == null ? LocalDate.now() : dateFrom;
        LocalDate to = dateTo == null ? from : dateTo;
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "导出日期范围的结束早于开始");
        }
        AdminDTO.AdminReservationQuery query = new AdminDTO.AdminReservationQuery();
        query.setDateFrom(from);
        query.setDateTo(to);
        query.setPage(1);
        query.setSize(EXPORT_LIMIT + 1);
        PageResult<AdminDTO.AdminReservationVO> page = adminUsageService.reservations(query);
        requireWithinLimit(page.getTotal(), from, to);
        return page.getList().stream().map(row -> List.<Object>of(
                id(row.getReservationId()), nullSafe(row.getUserId()), nullSafe(row.getUsername()),
                nullSafe(row.getRealName()), nullSafe(row.getFloorName()), nullSafe(row.getSeatNo()),
                nullSafe(row.getDate()), nullSafe(row.getTimeRange()), nullSafe(row.getDurationHours()),
                nullSafe(row.getStatusText()), nullSafe(row.getCheckInTime()), nullSafe(row.getCancelTime()),
                nullSafe(row.getAdminCancelReason()), nullSafe(row.getCreateTime()))).toList();
    }

    private List<List<Object>> violationRows() {
        Map<Long, User> people = usersByIds(violationService.listForStaff(null, null).stream()
                .map(AppealDTO.ViolationVO::getUserId).collect(Collectors.toSet()));
        return violationService.listForStaff(null, null).stream().map(row -> List.<Object>of(
                id(row.getViolationId()), nullSafe(row.getUserId()),
                name(people, row.getUserId(), false), name(people, row.getUserId(), true),
                id(row.getReservationId()), nullSafe(row.getTypeText()), nullSafe(row.getScoreDelta()),
                nullSafe(row.getStatusText()), nullSafe(row.getCreatedBy()), nullSafe(row.getEvidence()),
                nullSafe(row.getCreateTime()), nullSafe(row.getRevokeTime()), nullSafe(row.getRevokeReason()),
                nullSafe(row.getAppealStatusText()), nullSafe(row.getAppealReply()))).toList();
    }

    private List<List<Object>> patrolRows() {
        // 全部行（含已处置）：台账要能回答「这个季度报过多少疑点、都怎么收的尾」
        return patrolService.board(false, null, null, null).stream().map(row -> List.<Object>of(
                id(row.getPatrolId()), nullSafe(row.getFloorId()), nullSafe(row.getZoneId()),
                nullSafe(row.getSeatNo()), id(row.getReservationId()),
                row.getUserName() == null ? "无预约" : row.getUserName(), nullSafe(row.getTypeText()),
                nullSafe(row.getLevelText()), nullSafe(row.getReason()), evidenceText(row),
                nullSafe(row.getStatusText()), id(row.getHandlerId()), nullSafe(row.getHandleNote()),
                nullSafe(row.getPhotoUrl()), nullSafe(row.getCreateTime()), nullSafe(row.getHandleTime()))).toList();
    }

    private List<List<Object>> creditRows(LocalDateTime now) {
        Map<Long, List<Penalty>> activeByUser = penaltyRepository
                .findByStatusOrderByIdAsc(PenaltyStatus.ACTIVE).stream()
                .filter(penalty -> penalty.activeAt(now))
                .collect(Collectors.groupingBy(Penalty::getUserId, LinkedHashMap::new, Collectors.toList()));
        List<List<Object>> rows = new ArrayList<>();
        for (User user : userRepository.findAll(Sort.by("id"))) {
            List<Penalty> penalties = activeByUser.getOrDefault(user.getId(), List.of());
            String penaltyText = penalties.isEmpty() ? "" : penalties.stream()
                    .map(penalty -> penalty.getType().getText()
                            + (penalty.getEndTime() == null ? "" : "（至 " + BizConstants.formatDateTime(penalty.getEndTime()) + "）"))
                    .collect(Collectors.joining("；"));
            rows.add(List.<Object>of(id(user.getId()), nullSafe(user.getUsername()),
                    nullSafe(user.getRealName()), user.getRole().getText(), id(user.getCreditScore()),
                    penaltyText, id(user.getNoShowCount()), id(user.getActiveFinishStreak()),
                    nullSafe(user.getStatus())));
        }
        return rows;
    }

    // ==================== CSV 组装 ====================

    private byte[] toCsv(List<String> headers, List<List<Object>> rows) {
        StringBuilder text = new StringBuilder();
        text.append(joinRow(headers));
        for (List<Object> row : rows) {
            text.append("\r\n").append(joinRow(row));
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(body, 0, withBom, BOM.length, body.length);
        return withBom;
    }

    private String joinRow(List<?> cells) {
        return cells.stream().map(this::cell).collect(Collectors.joining(","));
    }

    private String cell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace("\r\n", "\n");
        if (!text.isEmpty() && FORMULA_LEADERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private void requireWithinLimit(long total, LocalDate from, LocalDate to) {
        if (total > EXPORT_LIMIT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该区间共 " + total + " 条，超过单次导出上限 " + EXPORT_LIMIT + " 条，请缩小日期范围");
        }
    }

    /** 证据列导原始 JSON：台账要能复核裁定依据，人在 Excel 里自己看键值。 */
    private String evidenceText(PatrolDTO.TaskVO row) {
        if (row.getEvidenceItems() == null || row.getEvidenceItems().isEmpty()) {
            return "";
        }
        return row.getEvidenceItems().stream()
                .map(item -> item.getLabel() + "=" + item.getValue())
                .collect(Collectors.joining(" | "));
    }

    private Map<Long, User> usersByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Set<Long> clean = new LinkedHashSet<>(ids);
        clean.remove(null);
        return userRepository.findAllById(clean).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
    }

    private String name(Map<Long, User> people, Long userId, boolean real) {
        User user = userId == null ? null : people.get(userId);
        if (user == null) {
            return "";
        }
        return real ? nullSafe(user.getRealName()) : nullSafe(user.getUsername());
    }

    /** 数值列统一走这个：null 与 0 在台账里必须都能显示，但 ID 类为 null 时留空而不是「null」。 */
    private String id(Number value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
