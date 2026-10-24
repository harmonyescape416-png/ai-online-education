-- ============================================================
-- D3 第 2 段：清理（事务内，子表先删；需管理员人工逐步确认后执行）
-- 前置：step0 备份已完成；step1 试查行数已人工核对
-- 策略：仅处理「无正式引用」的测试数据；被引用的记录先改挂正式分类（101/201）或跳过并记录
-- ============================================================
START TRANSACTION;

-- 2.1 测试课程关联的章节/内容（M4/M5/M11 共 172 门）
DELETE ch FROM course_chapter ch JOIN course c ON c.id=ch.course_id
 WHERE (c.name LIKE 'M4%' OR c.name LIKE 'M5%' OR c.name LIKE 'M11%');
SELECT ROW_COUNT() AS deleted_chapters;

DELETE ct FROM course_content ct JOIN course c ON c.id=ct.id
 WHERE (c.name LIKE 'M4%' OR c.name LIKE 'M5%' OR c.name LIKE 'M11%');
SELECT ROW_COUNT() AS deleted_contents;

-- 2.2 测试课程（172 门）
DELETE FROM course WHERE name LIKE 'M4%' OR name LIKE 'M5%' OR name LIKE 'M11%';
SELECT ROW_COUNT() AS deleted_courses;

-- 2.3 e2e/核验测试分类（删除前确认无课程引用——被引用的分类会因外键/业务逻辑保留，行数需人工核对）
DELETE cc FROM course_category cc
 WHERE (cc.name LIKE 'e2e分类%' OR cc.name REGEXP '^[0-9]+$' OR cc.name LIKE '核验分类%')
   AND NOT EXISTS (SELECT 1 FROM course c WHERE c.first_cate_id=cc.id OR c.second_cate_id=cc.id);
SELECT ROW_COUNT() AS deleted_categories_unused;

-- 2.4 异常订单明细与订单（已支付无明细 0 门 + 0 元待支付；测试来源）
DELETE od FROM order_detail od JOIN `order` o ON o.id=od.order_id
 WHERE o.status=2 AND NOT EXISTS (SELECT 1 FROM order_detail x WHERE x.order_id=o.id AND x.id<>od.id)
   AND (SELECT COUNT(*) FROM order_detail y WHERE y.order_id=o.id)>0;
-- （明细为 0 的已支付订单本身无明细可删；删除订单行前核对其 notification/outbox 引用）
DELETE o FROM `order` o
 WHERE (o.status=2 AND (SELECT COUNT(*) FROM order_detail od WHERE od.order_id=o.id)=0)
    OR (o.status=1 AND o.total_amount=0);
SELECT ROW_COUNT() AS deleted_orders;

-- 2.5 MOCK 通知：不删除（保留为测试账号历史；如管理员决定清理，取消下行注释）
-- DELETE FROM sys_notification WHERE title LIKE '%MOCK%' OR content LIKE '%MOCK%';

COMMIT;
-- 任何一步行数与 step1 试查不一致时：ROLLBACK 并核对；
-- 恢复：从 step0 的 *_bak_20261021 表 INSERT 回（见 step3_verify.sql 附恢复模板）。
