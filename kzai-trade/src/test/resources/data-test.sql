-- 交易服务测试 SEED（H2，仅测试用）
DELETE FROM mkt_coupon_compensate;
DELETE FROM mkt_order_coupon;
DELETE FROM mkt_coupon_user;
DELETE FROM mkt_coupon_scope;
DELETE FROM mkt_coupon_template;
DELETE FROM enroll_compensation;
DELETE FROM trade_refund;
DELETE FROM trade_payment;
DELETE FROM `order_request_idempotent`;
DELETE FROM `order_detail`;
DELETE FROM `order`;

-- 用户 3 的一笔已支付订单（历史业务状态，用于"已购不可重复购买"校验）
INSERT INTO `order` (id, user_id, status, message, total_amount, real_amount, discount_amount, create_time, update_time, deleted)
VALUES (5001, 3, 2, 'SEED-DEMO 已支付订单（测试种子）', 19900, 19900, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
INSERT INTO `order_detail` (id, order_id, user_id, course_id, price, name, cover_url, valid_duration, discount_amount, real_pay_amount, status, create_time, update_time)
VALUES (6001, 5001, 3, 4001, 19900, '已购课程', null, 12, 0, 19900, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 用户 3 的一笔待支付订单（用于取消与列表测试）
INSERT INTO `order` (id, user_id, status, message, total_amount, real_amount, discount_amount, create_time, update_time, deleted)
VALUES (5002, 3, 1, 'SEED-DEMO 待支付订单（测试种子）', 9900, 9900, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
INSERT INTO `order_detail` (id, order_id, user_id, course_id, price, name, cover_url, valid_duration, discount_amount, real_pay_amount, status, create_time, update_time)
VALUES (6002, 5002, 3, 4002, 9900, '待支付课程', null, 6, 0, 9900, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 用户 9 的订单（用于越权测试）
INSERT INTO `order` (id, user_id, status, message, total_amount, real_amount, discount_amount, create_time, update_time, deleted)
VALUES (5003, 9, 1, 'SEED-DEMO 他人订单（测试种子）', 9900, 9900, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
INSERT INTO `order_detail` (id, order_id, user_id, course_id, price, name, cover_url, valid_duration, discount_amount, real_pay_amount, status, create_time, update_time)
VALUES (6003, 5003, 9, 4002, 9900, '待支付课程', null, 6, 0, 9900, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
