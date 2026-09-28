package com.kaede.uspace.space.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改门店信息的请求。
 *
 * <p><b>语义是全量替换（PUT），不是部分更新</b>：字段传 null 表示「清空该项」。
 * 前端提交表单时会把当前所有值一起带上，因此这个语义与表单行为一致。
 *
 * <p><b>没有「新增门店」与「删除门店」的对应请求</b>：单门店运营下这两个动作
 * 都没有场景 —— 门店记录由建表脚本预置一条，开箱即用。
 * 将来开分店时再加，那时还要一并考虑「新增门店的门锁怎么装」
 * 「历史订单归属哪家店」这类问题。
 */
@Data
public class UpdateStoreRequest {

    /**
     * 门店名称，必填。
     *
     * <p>库里有唯一索引 {@code uk_name}，重名会在数据库层被拒绝
     * （Service 会先查一次给出友好提示，并发下则由索引兜底）。
     */
    @NotBlank(message = "门店名称不能为空")
    @Size(max = 50, message = "门店名称不能超过 50 个字符")
    private String name;

    /** 门店地址。传 null 或空串表示清除 */
    @Size(max = 255, message = "门店地址不能超过 255 个字符")
    private String address;

    /** 门店说明。传 null 或空串表示清除 */
    @Size(max = 255, message = "门店说明不能超过 255 个字符")
    private String description;
}
