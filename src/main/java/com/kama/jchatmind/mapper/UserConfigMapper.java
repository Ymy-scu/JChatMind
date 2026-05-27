package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.UserConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserConfigMapper {

    int insert(UserConfig userConfig);

    UserConfig selectById(String id);

    List<UserConfig> selectByUserId(String userId);

    UserConfig selectDefaultByUserId(String userId);

    int updateById(UserConfig userConfig);

    int deleteById(String id);

    int deleteByUserId(String userId);
}
