-- D3 第 3 段：核验（清理后运行；前后对比）
SELECT 'course' t, (SELECT COUNT(*) FROM course) now_, (SELECT COUNT(*) FROM course_bak_20261021) before_
UNION ALL SELECT 'course_category', (SELECT COUNT(*) FROM course_category), (SELECT COUNT(*) FROM course_category_bak_20261021)
UNION ALL SELECT 'course_chapter', (SELECT COUNT(*) FROM course_chapter), (SELECT COUNT(*) FROM course_chapter_bak_20261021)
UNION ALL SELECT 'course_content', (SELECT COUNT(*) FROM course_content), (SELECT COUNT(*) FROM course_content_bak_20261021)
UNION ALL SELECT 'order', (SELECT COUNT(*) FROM `order`), (SELECT COUNT(*) FROM `order_bak_20261021`)
UNION ALL SELECT 'order_detail', (SELECT COUNT(*) FROM order_detail), (SELECT COUNT(*) FROM order_detail_bak_20261021);
-- 页面抽查：课程浏览分类下拉无 e2e 分类；无 0 节卡片；订单页无 0 课已支付行
-- 恢复模板（示例）：INSERT INTO course SELECT * FROM course_bak_20261021;
