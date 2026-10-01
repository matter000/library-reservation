package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.PageResult;
import com.libseat.dto.AdminDTO;
import com.libseat.dto.SeatDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理员·座位服务：分页列表 / 新增 / 编辑 / 删除 / 状态切换 / 批量生成。
 */
@Service
@RequiredArgsConstructor
public class AdminSeatService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SeatRepository seatRepository;

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final AdminFloorService adminFloorService;

    private final AuditService auditService;

    /**
     * 座位分页列表（楼层 + 编号模糊 + 状态筛选）。
     *
     * @param floorId 楼层 ID
     * @param seatNo  编号关键字
     * @param status  状态枚举
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<SeatDTO.SeatVO> page(Long floorId, String seatNo, String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        SeatStatus seatStatus = parseSeatStatus(status);

        Specification<Seat> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("floorId"), floorId));
            if (StringUtils.hasText(seatNo)) {
                predicates.add(cb.like(root.get("seatNo"), "%" + seatNo.trim() + "%"));
            }
            if (seatStatus != null) {
                predicates.add(cb.equal(root.get("status"), seatStatus));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Seat> seatPage = seatRepository.findAll(
                spec, PageRequest.of(safePage - 1, safeSize, Sort.by("rowNo").ascending().and(Sort.by("colNo").ascending())));
        List<SeatVOWithFlag> rows = new ArrayList<>(seatPage.getContent().size());
        for (Seat seat : seatPage.getContent()) {
            rows.add(new SeatVOWithFlag(toVO(seat), seat.getId()));
        }
        Map<Long, Boolean> futureMap = batchFutureReservation(rows.stream().map(SeatVOWithFlag::seatId).toList());
        List<SeatDTO.SeatVO> vos = new ArrayList<>(rows.size());
        for (SeatVOWithFlag row : rows) {
            row.vo().setHasFutureReservation(Boolean.TRUE.equals(futureMap.get(row.seatId())));
            vos.add(row.vo());
        }
        return PageResult.<SeatDTO.SeatVO>builder()
                .list(vos)
                .total(seatPage.getTotalElements())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    /**
     * 新增单个座位。
     *
     * @param request 保存请求
     * @return 座位 VO
     */
    @Transactional
    public SeatDTO.SeatVO create(SeatDTO.SeatSaveRequest request) {
        Floor floor = adminFloorService.requireFloor(request.getFloorId());
        String seatNo = request.getSeatNo().trim();
        if (seatRepository.existsByFloorIdAndSeatNo(floor.getId(), seatNo)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该楼层下座位编号已存在");
        }
        Seat seat = Seat.builder()
                .floorId(floor.getId())
                .seatNo(seatNo)
                .rowNo(request.getRowNo())
                .colNo(request.getColNo())
                .zone(request.getZone())
                .status(parseSeatStatusOrDefault(request.getStatus(), SeatStatus.AVAILABLE))
                .remark(request.getRemark())
                .build();
        Seat saved = seatRepository.save(seat);
        return toVO(saved);
    }

    /**
     * 编辑座位。
     *
     * @param seatId  座位 ID
     * @param request 保存请求
     * @return 座位 VO
     */
    @Transactional
    public SeatDTO.SeatVO update(Long seatId, SeatDTO.SeatSaveRequest request) {
        Seat seat = requireSeat(seatId);
        Floor floor = adminFloorService.requireFloor(request.getFloorId());
        String seatNo = request.getSeatNo().trim();
        if (!seatNo.equals(seat.getSeatNo()) && seatRepository.existsByFloorIdAndSeatNo(floor.getId(), seatNo)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该楼层下座位编号已存在");
        }
        seat.setFloorId(floor.getId());
        seat.setSeatNo(seatNo);
        seat.setRowNo(request.getRowNo());
        seat.setColNo(request.getColNo());
        seat.setZone(request.getZone());
        seat.setStatus(parseSeatStatusOrDefault(request.getStatus(), seat.getStatus()));
        seat.setRemark(request.getRemark());
        return toVO(seatRepository.save(seat));
    }

    /**
     * 删除座位（存在未来有效预约 → 4094）。
     *
     * @param seatId 座位 ID
     * @return 删除结果
     */
    @Transactional
    public AdminDTO.DeleteSeatVO delete(Long seatId) {
        Seat seat = requireSeat(seatId);
        List<Reservation> reservations = reservationRepository.findBySeatId(seatId);
        long future = reservations.stream()
                .filter(r -> OccupancyService.OCCUPYING_STATUSES.contains(r.getStatus()))
                .filter(r -> !r.getReserveDate().isBefore(LocalDate.now()))
                .count();
        if (future > 0) {
            throw new BusinessException(ErrorCode.DELETE_FORBIDDEN, "该座位存在未完成的预约，请先释放");
        }
        for (Reservation reservation : reservations) {
            reservationSlotRepository.deleteByReservationId(reservation.getId());
        }
        reservationSlotRepository.deleteBySeatId(seatId);
        reservationRepository.deleteAll(reservations);
        seatRepository.delete(seat);

        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("seatNo", seat.getSeatNo());
        detail.put("floorId", seat.getFloorId());
        detail.put("deletedReservations", reservations.size());
        auditService.record(AuditService.ACTION_SEAT_DELETE, "SEAT", seatId, detail);

        return AdminDTO.DeleteSeatVO.builder()
                .seatId(seatId)
                .deletedReservations((long) reservations.size())
                .build();
    }

    /**
     * 切换座位状态（切为 MAINTENANCE/DISABLED 后用户端平面图立即变 UNAVAILABLE）。
     *
     * @param seatId  座位 ID
     * @param request 状态请求
     * @return 座位 VO
     */
    @Transactional
    public SeatDTO.SeatVO updateStatus(Long seatId, SeatDTO.SeatStatusRequest request) {
        Seat seat = requireSeat(seatId);
        seat.setStatus(parseSeatStatusOrDefault(request.getStatus(), seat.getStatus()));
        return toVO(seatRepository.save(seat));
    }

    /**
     * ★ 批量生成座位：行优先递增，编号 = 前缀 + 补零序号（宽度至少 2 位）。
     *
     * @param request 批量请求
     * @return 生成结果
     */
    @Transactional
    public SeatDTO.SeatBatchResultVO batch(SeatDTO.SeatBatchRequest request) {
        Floor floor = adminFloorService.requireFloor(request.getFloorId());
        int rows = request.getRows();
        int cols = request.getCols();
        int startNo = request.getStartNo();
        String prefix = request.getPrefix().trim();
        SeatStatus status = parseSeatStatusOrDefault(request.getStatus(), SeatStatus.AVAILABLE);

        int totalRequested = rows * cols;
        int lastNo = startNo + totalRequested - 1;
        int width = Math.max(2, String.valueOf(lastNo).length());
        String pattern = prefix + "%0" + width + "d";

        List<Seat> toSave = new ArrayList<>(totalRequested);
        List<String> skippedSeatNos = new ArrayList<>();
        for (int i = 0; i < totalRequested; i++) {
            int row = i / cols + 1;
            int col = i % cols + 1;
            String seatNo = String.format(pattern, startNo + i);
            if (seatRepository.existsByFloorIdAndSeatNo(floor.getId(), seatNo)) {
                skippedSeatNos.add(seatNo);
                continue;
            }
            toSave.add(Seat.builder()
                    .floorId(floor.getId())
                    .seatNo(seatNo)
                    .rowNo(row)
                    .colNo(col)
                    .zone(request.getZone())
                    .status(status)
                    .build());
        }
        seatRepository.saveAll(toSave);
        seatRepository.flush();

        return SeatDTO.SeatBatchResultVO.builder()
                .created(toSave.size())
                .skipped(skippedSeatNos.size())
                .skippedSeatNos(skippedSeatNos)
                .totalRequested(totalRequested)
                .build();
    }

    /**
     * 按 ID 取座位，不存在抛 4040。
     *
     * @param seatId 座位 ID
     * @return 座位
     */
    @Transactional(readOnly = true)
    public Seat requireSeat(Long seatId) {
        if (seatId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "座位 ID 不能为空");
        }
        return seatRepository.findById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "座位不存在"));
    }

    private boolean hasFutureReservation(Long seatId) {
        return reservationRepository.countActiveFutureOfSeat(
                seatId, LocalDate.now(), OccupancyService.OCCUPYING_STATUSES) > 0;
    }

    private Map<Long, Boolean> batchFutureReservation(List<Long> seatIds) {
        Map<Long, Boolean> map = new HashMap<>();
        if (seatIds.isEmpty()) {
            return map;
        }
        Set<Long> withFuture = new HashSet<>();
        for (Long seatId : seatIds) {
            if (hasFutureReservation(seatId)) {
                withFuture.add(seatId);
            }
        }
        for (Long seatId : seatIds) {
            map.put(seatId, withFuture.contains(seatId));
        }
        return map;
    }

    private SeatDTO.SeatVO toVO(Seat seat) {
        String floorName = null;
        try {
            floorName = adminFloorService.requireFloor(seat.getFloorId()).getName();
        } catch (BusinessException e) {
            floorName = null;
        }
        SeatStatus status = seat.getStatus() == null ? SeatStatus.AVAILABLE : seat.getStatus();
        return SeatDTO.SeatVO.builder()
                .seatId(seat.getId())
                .floorId(seat.getFloorId())
                .floorName(floorName)
                .seatNo(seat.getSeatNo())
                .rowNo(seat.getRowNo())
                .colNo(seat.getColNo())
                .zone(seat.getZone())
                .status(status.name())
                .statusText(status.getText())
                .remark(seat.getRemark())
                .hasFutureReservation(false)
                .createTime(BizConstants.formatDateTime(seat.getCreateTime()))
                .build();
    }

    private SeatStatus parseSeatStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return SeatStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "座位状态只能为 AVAILABLE / MAINTENANCE / DISABLED");
        }
    }

    private SeatStatus parseSeatStatusOrDefault(String status, SeatStatus defaultStatus) {
        SeatStatus parsed = parseSeatStatus(status);
        return parsed == null ? defaultStatus : parsed;
    }

    /**
     * 内部记录：座位 VO + 座位 ID（避免重复解包）。
     *
     * @param vo    座位 VO
     * @param seatId 座位 ID
     */
    private record SeatVOWithFlag(SeatDTO.SeatVO vo, Long seatId) {
    }
}
