package com.gym.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.system.entity.Booking;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {
}
