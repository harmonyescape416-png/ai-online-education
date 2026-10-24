-- D3 第 0 段：备份（CREATE TABLE ... AS SELECT），输出行数清单
CREATE TABLE course_category_bak_20261021 AS SELECT * FROM course_category;
CREATE TABLE course_bak_20261021 AS SELECT * FROM course;
CREATE TABLE course_chapter_bak_20261021 AS SELECT * FROM course_chapter;
CREATE TABLE course_content_bak_20261021 AS SELECT * FROM course_content;
CREATE TABLE `order_bak_20261021` AS SELECT * FROM `order`;
CREATE TABLE order_detail_bak_20261021 AS SELECT * FROM order_detail;
CREATE TABLE sys_notification_bak_20261021 AS SELECT * FROM sys_notification;
SELECT 'course_category' t, COUNT(*) rows_ FROM course_category_bak_20261021
UNION ALL SELECT 'course', COUNT(*) FROM course_bak_20261021
UNION ALL SELECT 'course_chapter', COUNT(*) FROM course_chapter_bak_20261021
UNION ALL SELECT 'course_content', COUNT(*) FROM course_content_bak_20261021
UNION ALL SELECT 'order', COUNT(*) FROM `order_bak_20261021`
UNION ALL SELECT 'order_detail', COUNT(*) FROM order_detail_bak_20261021
UNION ALL SELECT 'sys_notification', COUNT(*) FROM sys_notification_bak_20261021;
