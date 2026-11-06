package com.gym.web.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gym.system.entity.Coach;
import com.gym.system.entity.Course;
import com.gym.system.entity.Member;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.CoachMapper;
import com.gym.system.mapper.MemberMapper;
import com.gym.system.mapper.SysUserMapper;
import com.gym.system.service.CardService;
import com.gym.system.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 给 demo 会员铺「场馆能用」的最小种子：教练、课、次卡。
 * 避免答辩时会员「场馆」页全空。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class MemberVenueSeedRunner implements CommandLineRunner {

    private final SysUserMapper userMapper;
    private final MemberMapper memberMapper;
    private final CoachMapper coachMapper;
    private final CourseService courseService;
    private final CardService cardService;

    @Override
    public void run(String... args) {
        SysUser demo = userMapper.selectByUsername("demo");
        if (demo == null) {
            return;
        }
        Long memberId = ensureMember(demo);
        Long coachId = ensureCoach();
        ensureCourses(coachId);
        ensureCard(memberId);
    }

    private Long ensureMember(SysUser demo) {
        if (demo.getMemberId() != null) {
            Member m = memberMapper.selectById(demo.getMemberId());
            if (m != null) {
                return m.getId();
            }
        }
        Member m = new Member();
        m.setName("铁友");
        m.setPhone("13800000001");
        m.setGender(1);
        m.setGoal(3);
        m.setTrainLevel(2);
        m.setHeightCm(BigDecimal.valueOf(175));
        m.setWeightKg(BigDecimal.valueOf(78));
        m.setJoinDate(LocalDate.now().minusMonths(6));
        m.setStatus(1);
        m.setDeleted(0);
        try {
            memberMapper.insert(m);
        } catch (Exception e) {
            Member byPhone = memberMapper.selectOne(new QueryWrapper<Member>().eq("phone", "13800000001").last("LIMIT 1"));
            if (byPhone != null) {
                m = byPhone;
            } else {
                throw e;
            }
        }
        demo.setMemberId(m.getId());
        userMapper.updateById(demo);
        log.info("demo 已关联会员档案 id={}", m.getId());
        return m.getId();
    }

    private Long ensureCoach() {
        Coach existing = coachMapper.selectOne(new QueryWrapper<Coach>().eq("name", "林教练").last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        Coach c = new Coach();
        c.setName("林教练");
        c.setPhone("13900000001");
        c.setLevel(3);
        c.setSpecialty("力量举 / 增肌");
        c.setIntro("答辩演示用教练");
        c.setDeleted(0);
        coachMapper.insert(c);
        log.info("已创建演示教练 id={}", c.getId());
        return c.getId();
    }

    private void ensureCourses(Long coachId) {
        long open = courseService.count(new QueryWrapper<Course>()
                .ge("course_time", LocalDateTime.now().minusHours(1))
                .in("status", 1, 2));
        if (open >= 2) {
            return;
        }
        seedCourse(coachId, "力量基础团课", LocalDate.now().plusDays(1).atTime(LocalTime.of(19, 0)));
        seedCourse(coachId, "臀腿强化课", LocalDate.now().plusDays(3).atTime(LocalTime.of(10, 30)));
        seedCourse(coachId, "推日技术课", LocalDate.now().plusDays(5).atTime(LocalTime.of(18, 30)));
        log.info("已补充演示课程（近一周可约）");
    }

    private void seedCourse(Long coachId, String name, LocalDateTime when) {
        long dup = courseService.count(new QueryWrapper<Course>().eq("name", name).eq("course_time", when));
        if (dup > 0) {
            return;
        }
        Course course = new Course();
        course.setName(name);
        course.setCoachId(coachId);
        course.setCapacity(12);
        course.setBookedCnt(0);
        course.setCourseTime(when);
        course.setDurationMin(60);
        course.setRoom("A 馆力量区");
        course.setStatus(1);
        course.setVersion(0);
        courseService.save(course);
    }

    private void ensureCard(Long memberId) {
        long n = cardService.count(new QueryWrapper<com.gym.system.entity.MembershipCard>()
                .eq("member_id", memberId)
                .eq("status", 1)
                .gt("remain_count", 0));
        if (n > 0) {
            return;
        }
        cardService.openCard(memberId, "演示次卡（30次）", CardService.TYPE_COUNT,
                30, LocalDate.now().minusDays(7), LocalDate.now().plusMonths(6),
                BigDecimal.valueOf(999));
        log.info("已为 demo 会员开通演示次卡");
    }
}
