package com.gym.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gym.system.entity.Booking;
import com.gym.system.entity.Coach;
import com.gym.system.entity.ConsumeRecord;
import com.gym.system.entity.Course;
import com.gym.system.entity.Member;
import com.gym.system.entity.MembershipCard;
import com.gym.system.mapper.BookingMapper;
import com.gym.system.mapper.ConsumeRecordMapper;
import com.gym.system.mapper.CoachMapper;
import com.gym.system.mapper.CourseMapper;
import com.gym.system.mapper.MemberMapper;
import com.gym.system.mapper.MembershipCardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营统计：会员活跃数、教练数、课程数、有效卡数、预约与消课量。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final MemberMapper memberMapper;
    private final MembershipCardMapper cardMapper;
    private final CoachMapper coachMapper;
    private final CourseMapper courseMapper;
    private final BookingMapper bookingMapper;
    private final ConsumeRecordMapper consumeMapper;

    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeMember", memberMapper.selectCount(new QueryWrapper<Member>().eq("status", 1)));
        data.put("coachTotal", coachMapper.selectCount(null));
        data.put("courseTotal", courseMapper.selectCount(null));
        data.put("validCard", cardMapper.selectCount(new QueryWrapper<MembershipCard>().eq("status", 1)));
        data.put("bookingTotal", bookingMapper.selectCount(null));
        data.put("doneBooking", bookingMapper.selectCount(new QueryWrapper<Booking>().eq("status", 2)));
        data.put("consumeTotal", consumeMapper.selectCount(null));
        return data;
    }
}
