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
 * 桌位实体（t_table）—— 组队预约的最小单位。
 *
 * <p>类名避开 {@code java.sql} 与 JPA {@code @Table} 的歧义，表名仍是 t_table。
 *
 * <p>{@code seatCount} 是「声明值」，实际以 t_seat 中挂接的座位数为准；
 * 服务层在生成座位与增删桌位时双向校验，不一致时给出可执行的提示而不是静默改数。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "t_table",
        uniqueConstraints = @UniqueConstraint(name = "uk_table_zone_no", columnNames = {"zone_id", "table_no"})
)
public class SeatTable {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属分区。 */
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    /** 所属楼层（冗余，便于按楼层统计）。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 分区内唯一桌号，如 T05。 */
    @Column(name = "table_no", nullable = false, length = 20)
    private String tableNo;

    /** 同桌座位数（4~6）。 */
    @Builder.Default
    @Column(name = "seat_count", nullable = false)
    private Integer seatCount = 4;

    /** 桌位状态。 */
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
