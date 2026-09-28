package com.kaede.uspace.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 分页返回体。
 *
 * <p>不直接把 MyBatis-Plus 的 {@code IPage} 返回给前端，原因有二：
 * 一是 {@code IPage} 的实现类带了一堆前端用不上的字段（排序信息、优化计数标记等），
 * 二是它属于持久层类型，直接暴露会让「换掉 MyBatis-Plus」变成一件要动接口契约的事。
 * 中间隔一层自己的结构，前端拿到的就是稳定的四个字段。
 *
 * <p>记录列表的转换用 {@link #of(IPage, Function)}，传入实体到 VO 的映射函数 ——
 * 这样分页查询的结果<b>不必先转成 List 再手动包一层</b>，
 * 也避免了「忘记把实体换成 VO 而把敏感字段漏出去」这类失误。
 *
 * @param <T> 记录的类型
 */
@Data
public class PageResult<T> {

    /** 总记录数（满足条件的全部，不是当前页的条数） */
    private long total;

    /** 当前页码，从 1 开始 */
    private long current;

    /** 每页条数 */
    private long size;

    /** 当前页的记录列表 */
    private List<T> records;

    /**
     * 由分页查询结果构造，记录类型不变。
     *
     * @param page MyBatis-Plus 的分页结果
     * @param <T>  记录类型
     * @return 分页返回体
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return of(page, Function.identity());
    }

    /**
     * 由分页查询结果构造，同时把实体映射成 VO。
     *
     * @param page     MyBatis-Plus 的分页结果
     * @param mapper   单条记录的转换函数，通常是 {@code XxxVo::from}
     * @param <E>      源记录类型（实体）
     * @param <T>      目标记录类型（VO）
     * @return 分页返回体
     */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        result.setRecords(page.getRecords().stream().map(mapper).toList());
        return result;
    }
}
