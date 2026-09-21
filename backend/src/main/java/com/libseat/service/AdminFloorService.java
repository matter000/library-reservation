package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.AdminDTO;
import com.libseat.dto.FloorDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.enums.FloorStatus;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员·楼层服务：楼层 CRUD + 开放/关闭切换（删除前校验未来预约）。
 */
@Service
@RequiredArgsConstructor
public class AdminFloorService {

    private final FloorRepository floorRepository;

    private final SeatRepository seatRepository;

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final OccupancyService occupancyService;

    /**
     * 楼层管理列表。
     *
     * @return 楼层 VO 列表
     */
    @Transactional(readOnly = true)
    public List<FloorDTO.FloorVO> list() {
        List<Floor> floors = floorRepository.findAllByOrderBySortOrderAscIdAsc();
        List<FloorDTO.FloorVO> list = new ArrayList<>(floors.size());
        for (Floor floor : floors) {
            list.add(buildVO(floor, seatRepository.countByFloorId(floor.getId())));
        }
        return list;
    }

    /**
     * 新增楼层。
     *
     * @param request 保存请求
     * @return 楼层 VO
     */
    @Transactional
    public FloorDTO.FloorVO create(FloorDTO.FloorSaveRequest request) {
        String code = request.getCode().trim();
        String name = request.getName().trim();
        assertCodeAvailable(code, null);
        Floor floor = Floor.builder()
                .name(name)
                .code(code)
                .description(request.getDescription())
                .sortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder())
                .status(parseStatus(request.getStatus(), FloorStatus.OPEN))
                .build();
        Floor saved = floorRepository.save(floor);
        return buildVO(saved, 0L);
    }

    /**
     * 编辑楼层。
     *
     * @param floorId 楼层 ID
     * @param request 保存请求
     * @return 楼层 VO
     */
    @Transactional
    public FloorDTO.FloorVO update(Long floorId, FloorDTO.FloorSaveRequest request) {
        Floor floor = requireFloor(floorId);
        String code = request.getCode().trim();
        String name = request.getName().trim();
        assertCodeAvailable(code, floorId);
        floor.setName(name);
        floor.setCode(code);
        floor.setDescription(request.getDescription());
        floor.setSortOrder(request.getSortOrder() == null ? floor.getSortOrder() : request.getSortOrder());
        if (StringUtils.hasText(request.getStatus())) {
            floor.setStatus(parseStatus(request.getStatus(), floor.getStatus()));
        }
        Floor saved = floorRepository.save(floor);
        return buildVO(saved, seatRepository.countByFloorId(saved.getId()));
    }

    /**
     * 删除楼层（存在未来有效预约 → 4094）。
     *
     * @param floorId 楼层 ID
     * @return 删除结果
     */
    @Transactional
    public AdminDTO.DeleteFloorVO delete(Long floorId) {
        Floor floor = requireFloor(floorId);
        long futureCount = reservationRepository.countActiveFutureOfFloor(
                floorId, LocalDate.now(), OccupancyService.OCCUPYING_STATUSES);
        if (futureCount > 0) {
            throw new BusinessException(ErrorCode.DELETE_FORBIDDEN, "该楼层存在未完成的预约，请先释放");
        }

        List<Seat> seats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floorId);
        List<Reservation> reservations = reservationRepository.findByFloorId(floorId);
        for (Reservation reservation : reservations) {
            reservationSlotRepository.deleteByReservationId(reservation.getId());
        }
        for (Seat seat : seats) {
            reservationSlotRepository.deleteBySeatId(seat.getId());
        }
        reservationRepository.deleteAll(reservations);
        seatRepository.deleteAll(seats);
        floorRepository.delete(floor);

        return AdminDTO.DeleteFloorVO.builder()
                .deletedSeats((long) seats.size())
                .deletedReservations((long) reservations.size())
                .build();
    }

    /**
     * 切换楼层开放 / 关闭状态。
     *
     * @param floorId 楼层 ID
     * @param request 状态请求
     * @return 楼层 VO
     */
    @Transactional
    public FloorDTO.FloorVO updateStatus(Long floorId, FloorDTO.FloorStatusRequest request) {
        Floor floor = requireFloor(floorId);
        floor.setStatus(parseStatus(request.getStatus(), floor.getStatus()));
        Floor saved = floorRepository.save(floor);
        return buildVO(saved, seatRepository.countByFloorId(saved.getId()));
    }

    /**
     * 按 ID 取楼层，不存在抛 4040。
     *
     * @param floorId 楼层 ID
     * @return 楼层
     */
    @Transactional(readOnly = true)
    public Floor requireFloor(Long floorId) {
        if (floorId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "楼层 ID 不能为空");
        }
        return floorRepository.findById(floorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "楼层不存在"));
    }

    private void assertCodeAvailable(String code, Long excludeId) {
        Floor exist = floorRepository.findByCode(code).orElse(null);
        if (exist != null && (excludeId == null || !exist.getId().equals(excludeId))) {
            throw new BusinessException(ErrorCode.CONFLICT, "楼层编号已存在");
        }
    }

    private FloorStatus parseStatus(String status, FloorStatus defaultStatus) {
        if (!StringUtils.hasText(status)) {
            return defaultStatus;
        }
        try {
            return FloorStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "楼层状态只能为 OPEN 或 CLOSED");
        }
    }

    private FloorDTO.FloorVO buildVO(Floor floor, long seatCount) {
        FloorStatus status = floor.getStatus() == null ? FloorStatus.OPEN : floor.getStatus();
        return FloorDTO.FloorVO.builder()
                .floorId(floor.getId())
                .name(floor.getName())
                .code(floor.getCode())
                .description(floor.getDescription())
                .sortOrder(floor.getSortOrder())
                .status(status.name())
                .statusText(status.getText())
                .seatCount(seatCount)
                .totalSeats(seatCount)
                .createTime(BizConstants.formatDateTime(floor.getCreateTime()))
                .build();
    }
}
