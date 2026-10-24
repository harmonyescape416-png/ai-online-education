-- D3 第 1 段：试查（只 SELECT，人工确认行数后再进第 2 段）
-- ① e2e/纯数字测试分类（含引用课程计数）
SELECT cc.id, cc.name, cc.status,
       (SELECT COUNT(*) FROM course c WHERE c.first_cate_id=cc.id OR c.second_cate_id=cc.id) courses_ref
FROM course_category cc
WHERE cc.name LIKE 'e2e分类%' OR cc.name REGEXP '^[0-9]+$' OR cc.name LIKE '核验分类%'
ORDER BY cc.id;
-- ② M4/M5/M11 测试课程（含章节/内容计数）
SELECT c.id, c.name, c.status,
       (SELECT COUNT(*) FROM course_chapter ch WHERE ch.course_id=c.id) chapters,
       (SELECT COUNT(*) FROM course_content ct WHERE ct.id=c.id AND ct.course_introduce IS NOT NULL AND ct.course_introduce!='') has_intro
FROM course c WHERE c.name LIKE 'M4%' OR c.name LIKE 'M5%' OR c.name LIKE 'M11%'
ORDER BY c.id LIMIT 20;
SELECT COUNT(*) test_courses_total FROM course WHERE name LIKE 'M4%' OR name LIKE 'M5%' OR name LIKE 'M11%';
-- ③ 异常订单：已支付无明细 / 0 元待支付
SELECT o.id, o.user_id, o.total_amount, o.status,
       (SELECT COUNT(*) FROM order_detail od WHERE od.order_id=o.id) details
FROM `order` o
WHERE (o.status=2 AND (SELECT COUNT(*) FROM order_detail od WHERE od.order_id=o.id)=0)
   OR (o.status=1 AND o.total_amount=0);
-- ④ MOCK 通知（保留，仅统计——不得直接删除，处置由管理员另行决定）
SELECT COUNT(*) mock_notifications FROM sys_notification WHERE title LIKE '%MOCK%' OR content LIKE '%MOCK%';
SELECT recipient_id, COUNT(*) FROM sys_notification WHERE title LIKE '%MOCK%' OR content LIKE '%MOCK%' GROUP BY recipient_id;
