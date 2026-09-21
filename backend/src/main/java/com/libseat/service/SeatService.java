package com.libseat.service;

import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.entity.Seat;
import com.libseat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 座位查询公共能力：按楼层取座位、按 ID 取座位（带存在性校验）。
 */
@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;

    /**
     * 查询某楼层全部座位（按行、列升序）。
     *
     * @param floorId 楼层 ID
     * @return 座位列表
     */
    @Transactional(readOnly = true)
    public List<Seat> listByFloor(Long floorId) {
        return seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floorId);
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

    /**
     * 查询座位所属楼层是否存在该编号。
     *
     * @param floorId 楼层 ID
     * @param seatNo  座位编号
     * @return 是否存在
     */
    @Transactional(readOnly = true)
    public boolean existsSeatNo(Long floorId, String seatNo) {
        return seatRepository.existsByFloorIdAndSeatNo(floorId, seatNo);
    }
}
