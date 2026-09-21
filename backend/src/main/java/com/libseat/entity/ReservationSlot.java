package com.libseat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 占用明细实体（t_reservation_slot）。
 *
 * <p>只保存「当前有效」的占用行；预约被取消 / NO_SHOW / COMPLETED / 管理员释放时物理删除，
 * 使唯一索引 {@code uk_slot_seat_date_idx} 天然可复用。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "t_reservation_slot",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_SLOT_SEAT_DATE_IDX",
                columnNames = {"seat_id", "reserve_date", "slot_index"}
        )
)
public class ReservationSlot {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属预约。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    /** 座位（冗余，便于唯一约束与查询）。 */
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /** 日期。 */
    @Column(name = "reserve_date", nullable = false)
    private LocalDate reserveDate;

    /** 时段序号 8~21。 */
    @Column(name = "slot_index", nullable = false)
    private Integer slotIndex;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }
}
