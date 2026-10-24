-- 集成测试 SEED 数据（H2，仅测试用）
DELETE FROM `user`;
INSERT INTO `user` (id, username, cell_phone, password, status, type) VALUES
 (1, 'admin',     '13800000001', '$2a$10$9lkB1snHzCJG1aIdcqqgJeG5tqqr6LzWcjQ5GgDRrh3G7ahBSDEHu', 1, 1),
 (2, 'teacher01', '13800000002', '$2a$10$55iuLKYM7Qv8TwjdST7i3uQGvYNU6Jbe/romOmd9EAzmkMyBhkWye', 1, 3),
 (3, 'student01', '13800000003', '$2a$10$AvKGC7MBJCNHfZIljGX1VOznWFMFj6SolO./J469YnV4OyfcBoPsm', 1, 2),
 (4, 'locked',    '13800000004', '$2a$10$AvKGC7MBJCNHfZIljGX1VOznWFMFj6SolO./J469YnV4OyfcBoPsm', 0, 2);
