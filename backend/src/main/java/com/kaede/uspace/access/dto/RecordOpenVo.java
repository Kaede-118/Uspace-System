package com.kaede.uspace.access.dto;

import lombok.Data;

/**
 * 记录一条开门事件的结果。
 *
 * <p>除了落库后的记录本身，还带上 {@link #created} 这个标记 ——
 * 因为「这次调用是否真的新增了一条」是一个必须让调用方知道的事实：
 * 同一把锁在同一秒内重复开门（第一次没推开、再输一遍）应当被合并成一条记录，
 * 第二次调用返回的是已有记录而非新记录。前端据此提示「已记录」还是「该时刻已有记录」。
 */
@Data
public class RecordOpenVo {

    /** 落库后的开门记录。无论是本次新建的还是已存在的，都返回它 */
    private AccessRecordVo record;

    /**
     * 本次调用是否新增了记录。
     *
     * <p>{@code true} = 新写入了一条；{@code false} = 该锁在该时刻已有记录，本次未写入。
     */
    private boolean created;

    /**
     * 构造「已存在」的结果。
     *
     * @param record 库中已有的记录
     * @return created 为 false 的结果
     */
    public static RecordOpenVo existing(AccessRecordVo record) {
        RecordOpenVo vo = new RecordOpenVo();
        vo.setRecord(record);
        vo.setCreated(false);
        return vo;
    }

    /**
     * 构造「新建成功」的结果。
     *
     * @param record 新写入的记录
     * @return created 为 true 的结果
     */
    public static RecordOpenVo created(AccessRecordVo record) {
        RecordOpenVo vo = new RecordOpenVo();
        vo.setRecord(record);
        vo.setCreated(true);
        return vo;
    }
}
