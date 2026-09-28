package com.kaede.uspace.space.dto;

import com.kaede.uspace.space.entity.Store;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门店信息视图。
 *
 * <p>用户端首页与运营后台共用同一个视图 —— 门店信息没有「管理员能看、
 * 普通用户不能看」的字段，因此不必像用户模块那样分成两个 VO。
 *
 * <p>不含 {@code deleted} 这类内部字段：逻辑删除是持久层的实现细节，
 * 前端拿到它只会困惑「为什么有个恒为 0 的字段」。
 */
@Data
public class StoreVo {

    /** 门店 ID */
    private Long id;

    /** 门店名称 */
    private String name;

    /** 门店地址，可为空 */
    private String address;

    /** 门店说明，可为空 */
    private String description;

    /** 最后更新时间。门店信息极少变动，前端可用它判断本地缓存是否过期 */
    private LocalDateTime updatedAt;

    /**
     * 由实体构造 VO。
     *
     * @param store 门店实体
     * @return 门店视图；入参为 null 时返回 null
     */
    public static StoreVo from(Store store) {
        if (store == null) {
            return null;
        }
        StoreVo vo = new StoreVo();
        vo.setId(store.getId());
        vo.setName(store.getName());
        vo.setAddress(store.getAddress());
        vo.setDescription(store.getDescription());
        vo.setUpdatedAt(store.getUpdatedAt());
        return vo;
    }
}
