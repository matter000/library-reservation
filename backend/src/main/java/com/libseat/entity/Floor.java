package com.libseat.entity;

import com.libseat.enums.FloorStatus;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 楼层实体（t_floor）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_floor")
public class Floor {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 楼层名称。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 楼层编号，唯一。 */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    /** 描述。 */
    @Column(name = "description", length = 200)
    private String description;

    /** 排序号，升序。 */
    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 状态：OPEN / CLOSED。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FloorStatus status = FloorStatus.OPEN;

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
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (status == null) {
            status = FloorStatus.OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
