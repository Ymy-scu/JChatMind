package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    int insert(User user);

    User selectById(String id);

    User selectByUsername(String username);

    User selectByEmail(String email);

    int updateById(User user);

    int deleteById(String id);
}
