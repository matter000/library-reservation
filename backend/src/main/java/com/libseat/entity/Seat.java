package com.libseat.entity;

import com.libseat.enums.SeatStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 座位实体（t_seat）。
 *
 * <p><b>设计红线</b>：本表不存储「是否被占用」，占用状态由 t_reservation_slot 实时派生。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "t_seat",
        uniqueConstraints = @UniqueConstraint(name = "uk_seat_floor_no", columnNames = {"floor_id", "seat_no"})
)
public class Seat {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属楼层。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 座位编号（楼层内唯一）。 */
    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    /** 行号（平面图 Y 轴，从 1 开始）。 */
    @Column(name = "row_no", nullable = false)
    private Integer rowNo;

    /** 列号（平面图 X 轴，从 1 开始）。 */
    @Column(name = "col_no", nullable = false)
    private Integer colNo;

    /** 区域名。 */
    @Column(name = "zone", length = 20)
    private String zone;

    /**
     * 所属分区（V2 起规则与归属的唯一来源）。
     *
     * <p>可为 null 只出现在遗留库未回填的中间态；迁移脚本与种子都保证回填，
     * 因此查询侧按「无分区即不可约」处理，不做隐式兜底。
     */
    @Column(name = "zone_id")
    private Long zoneId;

    /**
     * 所属桌位（组队预约的最小单位）。
     *
     * <p>非组队区（电子阅览、休闲阅览）不建模桌位，此处为 null。
     */
    @Column(name = "table_id")
    private Long tableId;

    /** 基础状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /** 备注。 */
    @Column(name = "remark", length = 200)
    private String remark;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) {
            createTime = now;
        }
        if (updateTime == null) {
            updateTime = now;
        }
        if (status == null) {
            status = SeatStatus.AVAILABLE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
