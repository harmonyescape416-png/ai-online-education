package com.kzai.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.user.entity.User;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT id, username, cell_phone, password, status, type, create_time, update_time, creater, updater "
            + "FROM `user` WHERE username = #{username} LIMIT 1")
    User selectByUsername(String username);
}
