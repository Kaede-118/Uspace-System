package com.kaede.uspace.order.reconcile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReconcileDiffType} 的单元测试。
 *
 * <p>本枚举<b>此前没有独立测试类</b>，是补 {@link ReconcileDiffType#FIELD_ORDER}
 * 时一并建的 —— 那个常量是拼进 SQL 的编译期字面量，与枚举的声明顺序
 * <b>可能漂移</b>，而漂移了不会报任何错，只表现为「最紧急的那类差异排到了中间」。
 * 与 {@code BookingStatusTests}、{@code OrderStatusTests} 是同一类「专门盯着
 * 某个不会报错的约束」的用例。
 */
class ReconcileDiffTypeTests {

    @Test
    @DisplayName("FIELD_ORDER 与枚举的声明顺序严格一致 —— 这是它唯一的约束")
    void fieldOrder_matchesDeclarationOrder() {
        assertEquals(ReconcileDiffType.declarationOrderLiterals(), ReconcileDiffType.FIELD_ORDER,
                "FIELD_ORDER 是手写的字面量（注解里的 SQL 要求编译期常量，不能由 values() 算），"
                        + "所以它和枚举的先后顺序是两份独立的东西。"
                        + "改枚举顺序时忘了改它，后台列表的排序就会与「最紧急的排最前」不符 —— "
                        + "而排错顺序不会报任何错，只有这条用例看得见");
    }

    @Test
    @DisplayName("六类差异按紧急程度排列：驳回后有款最前，未填流水号最后")
    void declarationOrder_reflectsUrgency() {
        ReconcileDiffType[] types = ReconcileDiffType.values();

        assertEquals(ReconcileDiffType.REJECTED_IN_BILL, types[0],
                "「系统写着这笔钱不认、而账单说钱到了」是已知的错误结论，比「可疑」更该先看");
        assertEquals(ReconcileDiffType.NO_PAYMENT_NO, types[types.length - 1],
                "「没填流水号」是信息不足而不是伪造，且在选填的前提下它不该占着列表头部");
    }

    @Test
    @DisplayName("FIELD_ORDER 里每个类型都出现了，没有漏也没有多")
    void fieldOrder_coversEveryType() {
        for (ReconcileDiffType type : ReconcileDiffType.values()) {
            assertTrue(ReconcileDiffType.FIELD_ORDER.contains("'" + type.name() + "'"),
                    "漏掉 " + type.name() + " 的话，MySQL 的 FIELD() 会把它当 0 排在最前面 —— "
                            + "一个本该垫底的类型会跳到列表头部");
        }
        assertEquals(ReconcileDiffType.values().length,
                ReconcileDiffType.FIELD_ORDER.split(",").length,
                "数量也要对上：多写一个不存在的类型不会报错，只会让排序里多一个永远命不中的项");
    }

    @Test
    @DisplayName("标签：认不出的取值原样返回，null 也不抛异常")
    void labelOf_isLenient() {
        assertEquals("驳回后有款", ReconcileDiffType.labelOf("REJECTED_IN_BILL"));
        assertEquals("NOPE", ReconcileDiffType.labelOf("NOPE"),
                "库列是 VARCHAR，可能存着枚举之外的字符串 —— 让它一眼看得出来，"
                        + "比伪装成一个正常的中文名要好");
        assertNull(ReconcileDiffType.labelOf(null),
                "常量在左，null 安全。写成 Set.of(...).contains() 会在这里抛 NPE");
    }

    @Test
    @DisplayName("每个类型都有非空的标签与说明 —— 页面上不能出现空白")
    void everyTypeHasLabelAndHint() {
        for (ReconcileDiffType type : ReconcileDiffType.values()) {
            assertFalse(type.getLabel().isBlank(), type.name() + " 缺标签");
            assertFalse(type.getHint().isBlank(),
                    type.name() + " 缺说明。六类的处置动作完全不同，"
                            + "让管理员在页面上现推「这条差异意味着什么」是没必要的负担");
        }
    }

    @Test
    @DisplayName("isValid：合法取值、非法取值与 null")
    void isValid() {
        assertTrue(ReconcileDiffType.isValid("BILL_ONLY"));
        assertFalse(ReconcileDiffType.isValid("NOPE"));
        assertFalse(ReconcileDiffType.isValid(null), "null 不能抛 NPE");
    }
}
