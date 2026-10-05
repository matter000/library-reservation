package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.ZoneDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Seat;
import com.libseat.entity.Zone;
import com.libseat.enums.DiscussLevel;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.UserRole;
import com.libseat.enums.ZoneType;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分区服务：CRUD、按角色可见性过滤、规则有效值解析。
 *
 * <p>占用统计一律经 {@link OccupancyService} 从 t_reservation_slot 实时派生，
 * 分区 VO 不缓存任何计数，因此不存在「统计与事实不一致」的窗口。
 */
@Service
@RequiredArgsConstructor
public class ZoneService {

    /** 楼层内承接「无分区可归」座位的兜底分区编码（V4 迁移为遗留库自动创建）。 */
    public static final String DEFAULT_ZONE_CODE = "DEFAULT";

    private final ZoneRepository zoneRepository;

    private final SeatRepository seatRepository;

    private final FloorRepository floorRepository;

    private final ReservationRepository reservationRepository;

    private final OccupancyService occupancyService;

    /** 高峰判定（静态窗口按 scope 过滤 + 动态高峰），T-V2-18。 */
    private final PeakService peakService;

    private final FloorService floorService;

    private final AuditService auditService;

    /**
     * 用户面分区列表：只返回当前角色「可见」的分区。
     *
     * @param floorId 楼层 ID，为空表示全部楼层
     * @param date    日期，为空取今天
     * @param slot    时段序号，为空取当前时段
     * @param role    调用方角色（取自令牌，不接受入参）
     * @return 分区列表
     */
    @Transactional(readOnly = true)
    public List<ZoneDTO.ZoneVO> listForUser(Long floorId, LocalDate date, Integer slot, UserRole role) {
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? floorService.currentSlot() : BizConstants.clampToSlotRange(slot);
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap =
                occupancyService.getOccupancy(targetDate, targetSlot);

        List<Floor> floors = (floorId == null)
                ? floorRepository.findAllByOrderBySortOrderAscIdAsc()
                : List.of(floorService.requireFloor(floorId));

        List<ZoneDTO.ZoneVO> result = new ArrayList<>();
        for (Floor floor : floors) {
            for (Zone zone : zoneRepository.findByFloorIdOrderBySortOrderAscIdAsc(floor.getId())) {
                // 不可见的分区对学生面整体不出现（而不是「看得见但约不了」），避免暴露专区存在
                if (!containsRole(zone.getVisibleRoles(), role)) {
                    continue;
                }
                result.add(toVO(zone, floor, occupiedMap, role, targetDate, targetSlot));
            }
        }
        return result;
    }

    /**
     * 管理面分区列表（不做可见性过滤，含已关闭分区）。
     *
     * @param floorId 楼层 ID
     * @return 分区列表
     */
    @Transactional(readOnly = true)
    public List<ZoneDTO.ZoneVO> listForAdmin(Long floorId) {
        Floor floor = floorService.requireFloor(floorId);
        LocalDate targetDate = LocalDate.now();
        int targetSlot = floorService.currentSlot();
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap =
                occupancyService.getOccupancy(targetDate, targetSlot);
        return zoneRepository.findByFloorIdOrderBySortOrderAscIdAsc(floorId).stream()
                .map(zone -> toVO(zone, floor, occupiedMap, UserRole.SYS_ADMIN, targetDate, targetSlot))
                .toList();
    }

    /**
     * 新增分区。
     *
     * @param request 分区内容
     * @return 分区 VO
     */
    @Transactional
    public ZoneDTO.ZoneVO create(ZoneDTO.ZoneSaveRequest request) {
        Floor floor = floorService.requireFloor(request.getFloorId());
        String code = request.getCode().trim();
        if (zoneRepository.existsByFloorIdAndCode(request.getFloorId(), code)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该楼层已存在同名分区编码");
        }
        Zone zone = new Zone();
        apply(zone, request);
        zone.setStatus(FloorStatus.OPEN);
        Zone saved = zoneRepository.save(zone);
        auditService.record(AuditService.ACTION_ZONE_SAVE, "ZONE", saved.getId(),
                Map.of("op", "CREATE", "floorId", saved.getFloorId(), "code", saved.getCode()));
        return toVOWithoutOccupancy(saved, floor);
    }

    /**
     * 编辑分区。
     *
     * @param zoneId  分区 ID
     * @param request 分区内容
     * @return 分区 VO
     */
    @Transactional
    public ZoneDTO.ZoneVO update(Long zoneId, ZoneDTO.ZoneSaveRequest request) {
        Zone zone = requireZone(zoneId);
        Floor floor = floorService.requireFloor(request.getFloorId());
        String code = request.getCode().trim();
        boolean floorChanged = !zone.getFloorId().equals(request.getFloorId());
        if (floorChanged || !zone.getCode().equals(code)) {
            if (zoneRepository.existsByFloorIdAndCode(request.getFloorId(), code)) {
                throw new BusinessException(ErrorCode.CONFLICT, "该楼层已存在同名分区编码");
            }
        }
        if (floorChanged) {
            // 分区换楼层时座位必须跟着走，否则平面图按 floor_id 取数会取不到这批座位
            List<Seat> seats = seatRepository.findByZoneId(zoneId);
            for (Seat seat : seats) {
                seat.setFloorId(request.getFloorId());
            }
            seatRepository.saveAll(seats);
        }
        apply(zone, request);
        Zone saved = zoneRepository.save(zone);
        auditService.record(AuditService.ACTION_ZONE_SAVE, "ZONE", saved.getId(),
                Map.of("op", "UPDATE", "floorId", saved.getFloorId(), "code", saved.getCode()));
        return toVOWithoutOccupancy(saved, floor);
    }

    /**
     * 切换分区开放状态（关闭后本区座位在平面图上转为不可用）。
     *
     * @param zoneId 分区 ID
     * @param status 目标状态
     * @return 分区 VO
     */
    @Transactional
    public ZoneDTO.ZoneVO toggleStatus(Long zoneId, String status) {
        Zone zone = requireZone(zoneId);
        FloorStatus target = parseEnum(FloorStatus.class, status, "分区状态");
        zone.setStatus(target);
        Zone saved = zoneRepository.save(zone);
        auditService.record(AuditService.ACTION_ZONE_SAVE, "ZONE", saved.getId(),
                Map.of("op", "STATUS", "status", target.name()));
        return toVOWithoutOccupancy(saved, floorRepository.findById(saved.getFloorId()).orElseThrow());
    }

    /**
     * 删除分区。存在今天及以后的有效预约时禁止删除（4109）。
     *
     * @param zoneId 分区 ID
     */
    @Transactional
    public void delete(Long zoneId) {
        Zone zone = requireZone(zoneId);
        long active = reservationRepository.countActiveFutureOfZone(
                zoneId, LocalDate.now(), OccupancyService.OCCUPYING_STATUSES);
        if (active > 0) {
            throw new BusinessException(ErrorCode.SPACE_IN_USE, "该分区存在未完成的预约，请先释放");
        }
        List<Seat> seats = seatRepository.findByZoneId(zoneId);
        if (!seats.isEmpty()) {
            Zone fallback = zoneRepository
                    .findByFloorIdAndCode(zone.getFloorId(), DEFAULT_ZONE_CODE).orElse(null);
            for (Seat seat : seats) {
                seat.setZoneId(fallback == null ? null : fallback.getId());
            }
            seatRepository.saveAll(seats);
        }
        zoneRepository.delete(zone);
        auditService.record(AuditService.ACTION_ZONE_DELETE, "ZONE", zoneId,
                Map.of("floorId", zone.getFloorId(), "code", zone.getCode(), "relocatedSeats", seats.size()));
    }

    /**
     * 判断角色是否在 CSV 名单内。
     *
     * @param csv  角色 CSV
     * @param role 角色
     * @return 是否命中
     */
    public static boolean containsRole(String csv, UserRole role) {
        if (role == null || !StringUtils.hasText(csv)) {
            return false;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .anyMatch(token -> role.name().equalsIgnoreCase(token));
    }

    /**
     * 校验并规范化角色列表为 CSV（每段必须是合法 {@link UserRole}）。
     *
     * @param roles 角色名列表
     * @param field 字段名（报错文案用）
     * @return 逗号分隔字符串
     */
    public static String toRoleCsv(List<String> roles, String field) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, field + "不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : roles) {
            normalized.add(parseEnum(UserRole.class, raw, field).name());
        }
        return String.join(",", normalized);
    }

    private void apply(Zone zone, ZoneDTO.ZoneSaveRequest request) {
        zone.setFloorId(request.getFloorId());
        zone.setName(request.getName().trim());
        zone.setCode(request.getCode().trim());
        zone.setZoneType(parseEnum(ZoneType.class, request.getZoneType(), "分区类型"));
        zone.setMaxDurationMin(request.getMaxDurationMin());
        zone.setDailyCapMin(request.getDailyCapMin());
        zone.setAdvanceDays(request.getAdvanceDays());
        zone.setAllowDiscuss(parseEnum(DiscussLevel.class, request.getAllowDiscuss(), "讨论音量等级"));
        zone.setAllowLeave(Boolean.TRUE.equals(request.getAllowLeave()));
        zone.setLeaveMaxMin(request.getLeaveMaxMin() == null ? 60 : request.getLeaveMaxMin());
        zone.setLeaveDailyLimit(request.getLeaveDailyLimit() == null ? 3 : request.getLeaveDailyLimit());
        zone.setAllowGroup(Boolean.TRUE.equals(request.getAllowGroup()));
        zone.setGroupRequired(Boolean.TRUE.equals(request.getGroupRequired()));
        zone.setVisibleRoles(toRoleCsv(request.getVisibleRoles(), "可见角色"));
        zone.setEligibleRoles(toRoleCsv(request.getEligibleRoles(), "可预约角色"));
        zone.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        zone.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark().trim() : null);
    }

    /** 角色 CSV → 列表（管理页回填用）。 */
    private static List<String> csvToList(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private Zone requireZone(Long zoneId) {
        if (zoneId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分区 ID 不能为空");
        }
        return zoneRepository.findById(zoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分区不存在"));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, field + "不能为空");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            String allowed = Arrays.stream(type.getEnumConstants())
                    .map(Enum::name)
                    .collect(Collectors.joining("/"));
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    field + "取值非法：" + value + "，可选值 " + allowed);
        }
    }

    /**
     * 写路径的响应只回显分区自身配置，不叠加实时占用（空快照即「本层无人在座」）。
     */
    private ZoneDTO.ZoneVO toVOWithoutOccupancy(Zone zone, Floor floor) {
        return toVO(zone, floor, Map.of(), UserRole.SYS_ADMIN, LocalDate.now(), floorService.currentSlot());
    }

    /**
     * 分区在指定日期 + 时段的占用统计。
     *
     * <p>★ 计数本身在 {@link OccupancyService#usageOf}：余量卡片与动态高峰必须读同一个口径，
     * 两处各算一遍就会出现「卡片显示 85%，系统却按高峰把时长压了」。
     */
    private OccupancyService.ZoneUsage usageOf(Zone zone, Floor floor,
                                               Map<Long, OccupancyService.SeatOccupancy> occupiedMap, int slot) {
        boolean closed = floor.getStatus() == FloorStatus.CLOSED || zone.getStatus() == FloorStatus.CLOSED;
        return occupancyService.usageOf(seatRepository.findByZoneId(zone.getId()), closed, occupiedMap, slot);
    }

    private ZoneDTO.ZoneVO toVO(Zone zone, Floor floor, Map<Long, OccupancyService.SeatOccupancy> occupiedMap,
                                 UserRole role, LocalDate date, int slot) {
        OccupancyService.ZoneUsage usage = usageOf(zone, floor, occupiedMap, slot);
        long total = usage.total();
        long occupied = usage.occupied();
        long unavailable = usage.unavailable();
        long usable = usage.usable();
        boolean peakFlag = peakService.isPeak(zone, date, slot, LocalDateTime.now());
        return ZoneDTO.ZoneVO.builder()
                .zoneId(zone.getId())
                .floorId(floor.getId())
                .floorName(floor.getName())
                .name(zone.getName())
                .code(zone.getCode())
                .zoneType(zone.getZoneType().name())
                .zoneTypeText(zone.getZoneType().getText())
                .status(zone.getStatus().name())
                .statusText(zone.getStatus().getText())
                .totalSeats(total)
                .freeSeats(usable - occupied)
                .occupiedSeats(occupied)
                .unavailableSeats(unavailable)
                .usageRate(BizConstants.rate(occupied, usable))
                .eligible(containsRole(zone.getEligibleRoles(), role))
                .advanceDays(zone.getAdvanceDays())
                .dailyCapMin(zone.getDailyCapMin())
                .maxDurationMin(zone.getMaxDurationMin())
                .leaveAllowed(zone.getAllowLeave())
                .leaveMaxMin(zone.getLeaveMaxMin())
                .leaveDailyLimit(zone.getLeaveDailyLimit())
                .allowDiscuss(zone.getAllowDiscuss().name())
                .discussText(zone.getAllowDiscuss().getText())
                .visibleRoles(csvToList(zone.getVisibleRoles()))
                .eligibleRoles(csvToList(zone.getEligibleRoles()))
                .allowGroup(zone.getAllowGroup())
                .groupRequired(zone.getGroupRequired())
                .peakFlag(peakFlag)
                .build();
    }
}
