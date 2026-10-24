-- 集成测试 SEED 数据（H2，仅测试用）
-- status：1待上架，2已上架，3下架
DELETE FROM `course_chapter`;
DELETE FROM `course_content`;
DELETE FROM `course`;

INSERT INTO `course` (id, name, course_type, first_cate_id, second_cate_id, free, price, status, section_num, score, creater) VALUES
 (2001, 'Java 微服务实战',   2, 101, 201, 0, 29900, 2, 4, 47, 2),
 (2002, '大模型应用开发',     2, 101, 202, 0, 49900, 2, 3, 48, 2),
 (2003, '职场沟通（免费）',   2, 102, 203, 1,     0, 2, 2, 43, 2),
 (2004, '已下架课程',         2, 101, 201, 0, 19900, 3, 1, 40, 2),
 (2005, '草稿课程',           2, 101, 201, 0, 19900, 1, 1, 40, 2),
 (2006, '逻辑删除课程',       2, 101, 201, 0, 19900, 2, 1, 40, 2);

UPDATE `course` SET deleted = 1 WHERE id = 2006;

INSERT INTO `course_content` (id, course_introduce, use_people, course_detail) VALUES
 (2001, '微服务介绍', '后端工程师', '服务拆分/Nacos/Gateway/OpenFeign'),
 (2002, '大模型介绍', '应用开发者', 'Prompt/Tool Calling/SSE');

INSERT INTO `course_chapter` (id, course_id, name, chapter_order, description) VALUES
 (3001, 2001, '第1章 演进', 1, '单体到微服务'),
 (3002, 2001, '第2章 注册中心', 2, 'Nacos'),
 (3003, 2002, '第1章 基础', 1, '模型能力');
