package com.libseat.entity;

import com.libseat.enums.PatrolLevel;
import com.libseat.enums.PatrolStatus;
import com.libseat.enums.PatrolType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 巡查任务（t_patrol_task，DESIGN §11）。
 *
 * <p>★ 机器只登记疑点，<b>绝不自动释放座位</b>（PRD §12-25）：本实体的终态只能由
 * {@link com.libseat.enums.PatrolAction} 由馆员写入，生成器永远只写 {@code PENDING}。
 *
 * <p>★ 状态只能通过 {@link #changeStatus} 改：{@code openFlag} 必须与状态同步
 * （PENDING=1、终态=NULL），否则要么把座位永久卡在一个已处理的任务上，
 * 要么让 {@code uk_patrol_open} 失效 —— 这与 {@code LeaveRecord.open_flag}、
 * {@code Waitlist.activeFlag} 是同一套写法。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_patrol_task")
public class PatrolTask {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 楼层。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 分区。 */
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    /** 疑点座位。 */
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /** 关联预约；无预约占用为 null。 */
    @Column(name = "reservation_id")
    private Long reservationId;

    /** 疑点类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private PatrolType type;

    /** 等级（生成时由事实算出）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 10)
    private PatrolLevel level;

    /** 生成依据的可读文案。 */
    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    /** 判定输入 JSON（无证据不落账）。 */
    @Lob
    @Column(name = "evidence", nullable = false)
    private String evidence;

    /** 状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PatrolStatus status;

    /** 未处理 = 1，已处置 = null（配合唯一索引）。 */
    @Column(name = "open_flag")
    private Integer openFlag;

    /** 处置人。 */
    @Column(name = "handler_id")
    private Long handlerId;

    /** 处置说明（必填）。 */
    @Column(name = "handle_note", length = 200)
    private String handleNote;

    /** 现场照片链接。 */
    @Column(name = "photo_url", length = 200)
    private String photoUrl;

    /** 生成时刻。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 处置时刻。 */
    @Column(name = "handle_time")
    private LocalDateTime handleTime;

    @PrePersist
    void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (status == null) {
            changeStatus(PatrolStatus.PENDING);
        }
    }

    /**
     * 改状态的唯一入口：状态与 {@code openFlag} 一起变。
     *
     * @param next 目标状态
     */
    public void changeStatus(PatrolStatus next) {
        this.status = next;
        this.openFlag = next.open() ? 1 : null;
    }
}
