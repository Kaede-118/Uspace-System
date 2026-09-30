/**
 * 实体商品接口（商品包）。
 *
 * <p>路径前缀有<b>两个</b>：{@code /api/products}（目录与详情）与
 * {@code /api/product-orders}（交易，注意是连字符）。全部需登录。
 *
 * <p><b>不做核销</b>：无人值守店里没有店员，付了钱自己取。
 * 代价是分不清货被拿了没有 —— 这是明知的取舍，不是遗漏。
 */
import http from './http'

/**
 * 查上架商品列表。
 *
 * <p>卡片式展示：封面 / 名称 / 价格 / 是否售罄。
 *
 * <p>⚠️ 「是否售罄」在<b>列表与下单两处是同一个口径</b>（都算上未支付的占用），
 * 所以不会出现「页面说还有货、下单却说售罄」。
 *
 * @returns {Promise<{data:Array<{id, name, cover, description, price, soldOut, availableStock}>}>}
 */
export function listProducts() {
  return http.get('/api/products')
}

/**
 * 查商品详情。
 *
 * <p><b>下架商品照常返回</b>（带 {@code enabled: false}）：用户的订单里可能还
 * 指着它，点进去看不了会很莫名其妙。下架只影响「能不能下单」。
 *
 * @param {number|string} id 商品 ID
 * @returns {Promise<{data:{...}}>}
 */
export function getProduct(id) {
  return http.get(`/api/products/${id}`)
}

/**
 * 下单买商品。
 *
 * <p>下单时后端做一次<b>软检查</b>：可售量 = 库存 − 未支付的待支付单占用量。
 * 它不锁库存，所以仍有竞态窗口，最后一道防线是支付时的条件 UPDATE。
 *
 * <p>⚠️ <b>超时的待支付单不再占库存，但不会被自动关闭</b>（用户仍可把它付掉）——
 * 与月卡刻意不同：月卡是「一人一卡」，未关闭的旧单会把用户自己卡死；
 * 商品可以买多笔，关掉旧单反而是替用户做了「不买了」的决定。
 *
 * @param {object} params
 * @param {number|string} params.productId 商品 ID
 * @param {number} params.quantity 数量
 * @returns {Promise<{data:{id, orderNo, productName, unitPrice, quantity, amount, status}}>}
 * @throws 40932 商品已售罄
 */
export function createProductOrder({ productId, quantity }) {
  return http.post('/api/product-orders', { productId, quantity })
}

/**
 * 查我的商品订单（分页）。
 *
 * <p>⚠️ <b>分页参数是 {@code pageNum} / {@code pageSize}，不是 {@code page} / {@code size}</b>。
 * 本项目的分页参数不统一：order / space / notice / user / access 用后者，
 * <b>promotion 与 product 用前者</b>。传错不会报错，只会永远返回第一页。
 *
 * @param {object} [params]
 * @param {number} [params.pageNum]  页码
 * @param {number} [params.pageSize] 每页条数
 * @param {string} [params.status]   PENDING_PAYMENT / PAID / CLOSED
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listMyProductOrders(params = {}) {
  return http.get('/api/product-orders/me', { params })
}

/**
 * 取消未支付的商品订单。
 *
 * <p>没有它，用户误下单之后只能干等超时，而超时又不关单，
 * 界面上会一直挂着一笔「待支付」。
 *
 * @param {number|string} id 商品订单 ID
 * @returns {Promise}
 */
export function cancelProductOrder(id) {
  return http.post(`/api/product-orders/${id}/cancel`)
}
