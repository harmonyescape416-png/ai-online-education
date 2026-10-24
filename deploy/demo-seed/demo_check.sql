-- 演示数据检查（执行 SEED 后运行；期望值见注释）
SELECT COUNT(*) courses_should_10 FROM course WHERE id BETWEEN 9100000000000000001 AND 9100000000000000010 AND deleted=0;
SELECT COUNT(*) contents_should_10 FROM course_content WHERE id BETWEEN 9100000000000000001 AND 9100000000000000010 AND deleted=0;
SELECT COUNT(*) chapters_should_37 FROM course_chapter WHERE id BETWEEN 9100000000000000101 AND 9100000000000001003 AND deleted=0;
SELECT COUNT(*) null_intro_should_0 FROM course_content WHERE id BETWEEN 9100000000000000001 AND 9100000000000000010 AND (course_introduce IS NULL OR course_introduce='');
SELECT COUNT(*) dup_names_should_0 FROM (SELECT name, COUNT(*) n FROM course WHERE id BETWEEN 9100000000000000001 AND 9100000000000000010 GROUP BY name HAVING n>1) t;
SELECT c.name, c.status, cc.course_introduce IS NOT NULL has_intro, (SELECT COUNT(*) FROM course_chapter ch WHERE ch.course_id=c.id AND ch.deleted=0) chapters
FROM course c JOIN course_content cc ON cc.id=c.id WHERE c.id BETWEEN 9100000000000000001 AND 9100000000000000010 ORDER BY c.id;
