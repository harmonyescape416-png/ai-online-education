-- =====================================================================
-- M14 语音系统参数（数据行扩展，无新表；幂等可重复执行）
-- 依据：任务书第九部分——现有 M13 参数表支持新增参数记录，按合法扩展流程增加配置项
-- 说明：原始 12 张核心表零改动；语音不新增任何业务音频表（音频不落库红线）
-- =====================================================================

-- 语音功能开关
INSERT INTO `sys_config_param` (`id`, `param_key`, `param_name`, `description`,
  `effective_value`, `value_type`, `min_value`, `max_value`, `version`,
  `create_time`, `update_time`)
SELECT 888100000000000001, 'voice.enabled', '语音功能开关', '总开关：关闭后 STT/TTS 全部不可用',
  'true', 'BOOL', 'false', 'true', 1, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config_param` WHERE `param_key` = 'voice.enabled');

-- STT 上传大小上限（字节）
INSERT INTO `sys_config_param` (`id`, `param_key`, `param_name`, `description`,
  `effective_value`, `value_type`, `min_value`, `max_value`, `version`,
  `create_time`, `update_time`)
SELECT 888100000000000002, 'stt.max-upload-bytes', '语音上传大小上限', 'STT 音频文件最大字节数',
  '5242880', 'INT', '65536', '20971520', 1, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config_param` WHERE `param_key` = 'stt.max-upload-bytes');

-- STT 录音时长上限（秒）
INSERT INTO `sys_config_param` (`id`, `param_key`, `param_name`, `description`,
  `effective_value`, `value_type`, `min_value`, `max_value`, `version`,
  `create_time`, `update_time`)
SELECT 888100000000000003, 'stt.max-seconds', '语音录音时长上限', 'STT 单次录音最大秒数',
  '60', 'INT', '5', '120', 1, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config_param` WHERE `param_key` = 'stt.max-seconds');

-- TTS 文本长度上限（字符）
INSERT INTO `sys_config_param` (`id`, `param_key`, `param_name`, `description`,
  `effective_value`, `value_type`, `min_value`, `max_value`, `version`,
  `create_time`, `update_time`)
SELECT 888100000000000004, 'tts.max-chars', '播报文本长度上限', 'TTS 单次合成最大字符数',
  '500', 'INT', '50', '2000', 1, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config_param` WHERE `param_key` = 'tts.max-chars');

-- 语音并发上限（JVM 内护栏，不依赖 Redis）
INSERT INTO `sys_config_param` (`id`, `param_key`, `param_name`, `description`,
  `effective_value`, `value_type`, `min_value`, `max_value`, `version`,
  `create_time`, `update_time`)
SELECT 888100000000000005, 'voice.max-concurrent', '语音并发上限', '语音服务全局并发请求数',
  '2', 'INT', '1', '8', 1, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config_param` WHERE `param_key` = 'voice.max-concurrent');
