package com.libseat.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 分页包装：{@code {list, total, page, size}}。
 *
 * @param <T> 列表元素类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据。 */
    private List<T> list;

    /** 总条数。 */
    private long total;

    /** 当前页码（从 1 开始）。 */
    private int page;

    /** 每页条数。 */
    private int size;

    /**
     * 空分页。
     *
     * @param page 页码
     * @param size 每页条数
     * @param <T>  元素类型
     * @return 空分页
     */
    public static <T> PageResult<T> empty(int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setList(Collections.emptyList());
        result.setTotal(0L);
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    /**
     * 由完整列表手工分页（用于内存排序后再分页的场景）。
     *
     * @param all  完整列表
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @param <T>  元素类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        long total = all.size();
        int fromIndex = Math.min((safePage - 1) * safeSize, all.size());
        int toIndex = Math.min(fromIndex + safeSize, all.size());
        List<T> sub = all.subList(fromIndex, toIndex);
        return PageResult.<T>builder()
                .list(sub)
                .total(total)
                .page(safePage)
                .size(safeSize)
                .build();
    }
}
