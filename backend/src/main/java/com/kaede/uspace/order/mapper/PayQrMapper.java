package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.order.entity.PayQr;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收款码的数据访问接口。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>：全局逻辑删除配置只作用于
 * MyBatis-Plus 自己生成的方法，对注解里手写的 SQL 不生效 —— 漏掉的后果是
 * 「已删除的收款码还挂在收银台上」，而顾客扫了那张码，钱进的是一个
 * 已经不再使用的账号。这与 {@code NoticeMapper} 上那条警告是同一个坑。
 *
 * <p><b>两个查询口径不同，不要合并</b>：
 * <ul>
 *   <li>{@link #selectEnabledByStore} —— 收银台用，<b>只取启用中的</b></li>
 *   <li>{@link #selectAllByStore} —— 后台列表用，<b>含停用的</b>，
 *       否则管理员停用一张码之后就再也找不回它了</li>
 * </ul>
 * 与 {@code DeviceMapper} 的 {@code selectListAll} / {@code selectEnabledList}
 * 是同一组对照。用错不会有任何报错，只表现为「后台少了张码」。
 */
public interface PayQrMapper extends BaseMapper<PayQr> {

    /**
     * 取某门店启用中的收款码，供收银台展示。
     *
     * <p>排序 {@code sort ASC, id ASC}：<b>第二个键不是多余的</b> ——
     * 同 sort 的两张码（新建时都留着默认值 0）若没有稳定的次序键，
     * 每次查出来的顺序可能不同，用户会看到收款码在收银台上跳来跳去。
     *
     * @param storeId 门店 ID
     * @return 启用中的收款码，按 sort 升序；一张都没有时返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_pay_qr
             WHERE deleted = 0
               AND store_id = #{storeId}
               AND enabled = 1
             ORDER BY sort ASC, id ASC
            """)
    List<PayQr> selectEnabledByStore(@Param("storeId") Long storeId);

    /**
     * 取某门店的全部收款码（含停用的），供后台列表。
     *
     * @param storeId 门店 ID
     * @return 全部收款码，按 sort 升序；一张都没有时返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_pay_qr
             WHERE deleted = 0
               AND store_id = #{storeId}
             ORDER BY sort ASC, id ASC
            """)
    List<PayQr> selectAllByStore(@Param("storeId") Long storeId);

    /**
     * 全量替换一张收款码的可编辑字段。
     *
     * <p><b>为什么不用 {@code updateById}</b>：MyBatis-Plus 默认的字段策略
     * 会跳过 null 字段，而这里的字段都是「不传就是清空」的 PUT 语义。
     * 走 {@code updateById} 的话，某些字段根本改不掉，而且<b>不报任何错</b> ——
     * 表现为「改了半天没变化」。模块 1 / 3 / 4 与公告包都踩过同一个坑。
     *
     * <p><b>{@code enabled} 也在这里一起改</b>（不像机台状况与商品上下架那样
     * 单开一个接口）：收款码的启停本来就是编辑表单里的一个字段，
     * 而且它没有「管理员正盯着看的事实」那层含义 —— 那是机台状况才有的。
     *
     * @param id       收款码 ID
     * @param channel  渠道取值，见 {@code PayQrChannel}
     * @param name     显示名
     * @param imageUrl 图片站内路径
     * @param enabled  是否启用（1/0）
     * @param sort     排序值
     * @return 受影响行数；0 表示这张码不存在或已删
     */
    @Update("""
            UPDATE biz_pay_qr
               SET channel    = #{channel},
                   name       = #{name},
                   image_url  = #{imageUrl},
                   enabled    = #{enabled},
                   sort       = #{sort},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateFields(@Param("id") Long id,
                     @Param("channel") String channel,
                     @Param("name") String name,
                     @Param("imageUrl") String imageUrl,
                     @Param("enabled") Integer enabled,
                     @Param("sort") Integer sort);

    // 说明：新增与删除直接用 MyBatis-Plus 自带的 insert / deleteById 即可 ——
    // 新增就是插一行，删除走逻辑删除（框架会把 deleteById 改写成 UPDATE）。
    // 这里不再写同义的方法，免得读代码的人多面对一个「两者有什么区别」的选择。
}
