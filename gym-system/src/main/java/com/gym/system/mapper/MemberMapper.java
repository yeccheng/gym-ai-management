package com.gym.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.system.entity.Member;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<Member> {
}
