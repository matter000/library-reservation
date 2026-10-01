package com.libseat.service;

import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.TableDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Seat;
import com.libseat.entity.SeatTable;
import com.libseat.entity.Zone;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.SeatTableRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌位服务：CRUD、seat_count 对账、按桌生成相邻座位。
 *
 * <p>组队预约以桌为最小单位，因此「同桌座位必须相邻」是硬要求：
 * 生成时整行扫描找连续空列，找不到就明确报错，绝不把一桌拆散到两个行段。
 */
@Service
@RequiredArgsConstructor
public class TableService {

    /** 扫描的最大行号，防止误配置导致无界循环。 */
    private static final int MAX_SCAN_ROW = 200;

    /** 扫描的最大列号。 */
    private static final int MAX_SCAN_COL = 200;

    private final SeatTableRepository seatTableRepository;

    private final SeatRepository seatRepository;

    private final ZoneRepository zoneRepository;

    private final FloorRepository floorRepository;

    private final AuditService auditService;

    /**
     * 按分区列桌位。
     *
     * @param zoneId 分区 ID
     * @return 桌位列表
     */
    @Transactional(readOnly = true)
    public List<TableDTO.TableVO> listByZone(Long zoneId) {
        Zone zone = requireZone(zoneId);
        Floor floor = requireFloor(zone.getFloorId());
        List<TableDTO.TableVO> result = new ArrayList<>();
        for (SeatTable table : seatTableRepository.findByZoneIdOrderByTableNoAsc(zoneId)) {
            result.add(toVO(table, zone, floor));
        }
        return result;
    }

    /**
     * 新增桌位。
     *
     * @param request 桌位内容
     * @return 桌位 VO
     */
    @Transactional
    public TableDTO.TableVO create(TableDTO.TableSaveRequest request) {
        Zone zone = requireZone(request.getZoneId());
        Floor floor = requireFloor(zone.getFloorId());
        String tableNo = request.getTableNo().trim();
        if (seatTableRepository.existsByZoneIdAndTableNo(zone.getId(), tableNo)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该分区已存在同名桌号");
        }
        SeatTable table = SeatTable.builder()
                .zoneId(zone.getId())
                .floorId(floor.getId())
                .tableNo(tableNo)
                .seatCount(request.getSeatCount())
                .status(parseStatus(request.getStatus()))
                .remark(StringUtils.hasText(request.getRemark()) ? request.getRemark().trim() : null)
                .build();
        SeatTable saved = seatTableRepository.save(table);
        auditService.record(AuditService.ACTION_TABLE_SAVE, "TABLE", saved.getId(),
                Map.of("op", "CREATE", "zoneId", saved.getZoneId(), "tableNo", saved.getTableNo()));
        return toVO(saved, zone, floor);
    }

    /**
     * 编辑桌位。
     *
     * <p>调小 {@code seatCount} 到低于实际挂接座位数时直接拒绝：静默改声明值会让
     * 组队的锁座数与真实座位数脱节，且没有任何一方会被纠正。
     *
     * @param tableId 桌位 ID
     * @param request 桌位内容
     * @return 桌位 VO
     */
    @Transactional
    public TableDTO.TableVO update(Long tableId, TableDTO.TableSaveRequest request) {
        SeatTable table = requireTable(tableId);
        Zone zone = requireZone(request.getZoneId());
        Floor floor = requireFloor(zone.getFloorId());
        String tableNo = request.getTableNo().trim();
        if (!table.getZoneId().equals(zone.getId()) || !table.getTableNo().equals(tableNo)) {
            if (seatTableRepository.existsByZoneIdAndTableNo(zone.getId(), tableNo)) {
                throw new BusinessException(ErrorCode.CONFLICT, "该分区已存在同名桌号");
            }
        }
        long actual = seatRepository.countByTableId(tableId);
        if (request.getSeatCount() < actual) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该桌已挂接 " + actual + " 个座位，座位数不得小于该值；请先删除多余座位");
        }
        table.setZoneId(zone.getId());
        table.setFloorId(floor.getId());
        table.setTableNo(tableNo);
        table.setSeatCount(request.getSeatCount());
        table.setStatus(parseStatus(request.getStatus()));
        table.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark().trim() : null);
        // 换分区时挂接座位必须跟着走，否则会出现「座位在 A 区、桌在 B 区」的错挂
        List<Seat> seats = seatRepository.findByTableId(tableId);
        if (!seats.isEmpty() && seats.stream().anyMatch(s -> !zone.getId().equals(s.getZoneId()))) {
            for (Seat seat : seats) {
                seat.setZoneId(zone.getId());
                seat.setFloorId(floor.getId());
            }
            seatRepository.saveAll(seats);
        }
        SeatTable saved = seatTableRepository.save(table);
        auditService.record(AuditService.ACTION_TABLE_SAVE, "TABLE", saved.getId(),
                Map.of("op", "UPDATE", "zoneId", saved.getZoneId(), "tableNo", saved.getTableNo()));
        return toVO(saved, zone, floor);
    }

    /**
     * 删除桌位。仍挂着座位时禁止删除（4109），要求先迁移或删除座位。
     *
     * @param tableId 桌位 ID
     */
    @Transactional
    public void delete(Long tableId) {
        SeatTable table = requireTable(tableId);
        long actual = seatRepository.countByTableId(tableId);
        if (actual > 0) {
            throw new BusinessException(ErrorCode.SPACE_IN_USE,
                    "该桌仍挂接 " + actual + " 个座位，请先删除或改挂这些座位");
        }
        seatTableRepository.delete(table);
        auditService.record(AuditService.ACTION_TABLE_DELETE, "TABLE", tableId,
                Map.of("zoneId", table.getZoneId(), "tableNo", table.getTableNo()));
    }

    /**
     * ★ 按桌生成座位：在同一行内取连续空列，保证同桌相邻。
     *
     * @param tableId 桌位 ID
     * @param count   生成数量，为空取桌位声明值
     * @return 生成结果
     */
    @Transactional
    public TableDTO.TableVO generateSeats(Long tableId, Integer count) {
        SeatTable table = requireTable(tableId);
        Zone zone = requireZone(table.getZoneId());
        Floor floor = requireFloor(table.getFloorId());
        int want = (count == null) ? table.getSeatCount() : count;
        List<Seat> existingSeats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floor.getId());
        int already = (int) existingSeats.stream().filter(s -> tableId.equals(s.getTableId())).count();
        int need = want - already;
        if (need <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该桌已生成 " + already + " 个座位，无需再生成 " + want + " 个");
        }

        Set<String> usedSeatNos = new HashSet<>();
        Set<String> usedPositions = new HashSet<>();
        int maxRow = 0;
        int tableRow = 0;
        int tableMaxCol = 0;
        for (Seat seat : existingSeats) {
            usedSeatNos.add(seat.getSeatNo());
            usedPositions.add(seat.getRowNo() + ":" + seat.getColNo());
            maxRow = Math.max(maxRow, seat.getRowNo());
            if (tableId.equals(seat.getTableId())) {
                tableRow = seat.getRowNo();
                tableMaxCol = Math.max(tableMaxCol, seat.getColNo());
            }
        }

        int[] segment = findSegment(usedPositions, tableRow, tableMaxCol, need, maxRow);
        if (segment == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, tableRow > 0
                    ? "同桌已有座位在第 " + tableRow + " 行，但该行右侧放不下 " + need + " 个连续空位"
                    : "该楼层 " + MAX_SCAN_ROW + " 行内找不到 " + need + " 个连续空位，无法保证同桌相邻");
        }
        int row = segment[0];
        int colFrom = segment[1];

        List<Seat> toSave = new ArrayList<>(need);
        for (int i = 0; i < need; i++) {
            int col = colFrom + i;
            // 同桌共用桌号前缀、以 -序号 结尾，既不撞既有编号，也能一眼看出属于哪张桌
            String seatNo = table.getTableNo() + "-" + (already + i + 1);
            if (usedSeatNos.contains(seatNo)) {
                throw new BusinessException(ErrorCode.CONFLICT, "座位编号已存在：" + seatNo);
            }
            toSave.add(Seat.builder()
                    .floorId(floor.getId())
                    .zoneId(zone.getId())
                    .tableId(tableId)
                    .seatNo(seatNo)
                    .rowNo(row)
                    .colNo(col)
                    .zone(zone.getName())
                    .status(SeatStatus.AVAILABLE)
                    .build());
        }
        seatRepository.saveAll(toSave);
        seatRepository.flush();
        if (!Integer.valueOf(want).equals(table.getSeatCount())) {
            // 生成数与声明值不一致时以实际为准，否则 seatCountMatches 会一直报警
            table.setSeatCount(want);
            seatTableRepository.save(table);
        }
        return toVO(table, zone, floor);
    }

    /**
     * 为同桌找一段连续空位。
     *
     * <p>已有同桌座位时只能接在该行右侧（否则一桌被拆成两段，组队的整排锁座失去意义）；
     * 首次生成时另起一行，放在最靠下的占用行之后。
     *
     * @return {@code {row, colFrom}}，找不到返回 null
     */
    private int[] findSegment(Set<String> usedPositions, int tableRow, int tableMaxCol, int need, int maxRow) {
        if (tableRow > 0) {
            return freeRun(usedPositions, tableRow, tableMaxCol + 1, need)
                    && tableMaxCol + need <= MAX_SCAN_COL
                    ? new int[]{tableRow, tableMaxCol + 1} : null;
        }
        for (int row = Math.max(1, maxRow + 1); row <= MAX_SCAN_ROW; row++) {
            if (freeRun(usedPositions, row, 1, need)) {
                return new int[]{row, 1};
            }
        }
        return null;
    }

    private boolean freeRun(Set<String> usedPositions, int row, int colFrom, int need) {
        for (int i = 0; i < need; i++) {
            if (usedPositions.contains(row + ":" + (colFrom + i))) {
                return false;
            }
        }
        return true;
    }

    private TableDTO.TableVO toVO(SeatTable table, Zone zone, Floor floor) {
        List<Seat> seats = seatRepository.findByTableId(table.getId()).stream()
                .sorted(Comparator.comparing(Seat::getRowNo).thenComparing(Seat::getColNo))
                .toList();
        long actual = seats.size();
        return TableDTO.TableVO.builder()
                .tableId(table.getId())
                .zoneId(zone.getId())
                .zoneName(zone.getName())
                .floorId(floor.getId())
                .tableNo(table.getTableNo())
                .seatCount(table.getSeatCount())
                .actualSeatCount(actual)
                .seatCountMatches(actual == table.getSeatCount().longValue())
                .status(table.getStatus().name())
                .statusText(table.getStatus().getText())
                .remark(table.getRemark())
                .seats(seats.stream().map(s -> TableDTO.SeatBrief.builder()
                        .seatId(s.getId())
                        .seatNo(s.getSeatNo())
                        .rowNo(s.getRowNo())
                        .colNo(s.getColNo())
                        .status(s.getStatus().name())
                        .build()).toList())
                .build();
    }

    private SeatTable requireTable(Long tableId) {
        if (tableId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "桌位 ID 不能为空");
        }
        return seatTableRepository.findById(tableId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "桌位不存在"));
    }

    private Zone requireZone(Long zoneId) {
        if (zoneId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分区 ID 不能为空");
        }
        return zoneRepository.findById(zoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分区不存在"));
    }

    private Floor requireFloor(Long floorId) {
        return floorRepository.findById(floorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "楼层不存在"));
    }

    private SeatStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return SeatStatus.AVAILABLE;
        }
        try {
            return SeatStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "桌位状态取值非法：" + status);
        }
    }
}
