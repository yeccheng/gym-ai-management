package com.gym.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.ConsumeRecord;
import com.gym.system.entity.MembershipCard;
import com.gym.system.mapper.ConsumeRecordMapper;
import com.gym.system.mapper.MembershipCardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 消费计费：签到消次 / 课程消课。
 *
 * <p>消课用乐观锁（version）防止并发重复扣次；课程消课带 biz_no 唯一键，
 * 供 RocketMQ 重复消费时幂等跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumeService {

    private final MembershipCardMapper cardMapper;
    private final ConsumeRecordMapper consumeMapper;

    /** 入场消次 */
    public void checkIn(Long memberId) {
        MembershipCard card = pickValidCard(memberId);
        if (card == null) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "该会员没有可用次数卡");
        }
        decrement(card);
        ConsumeRecord record = new ConsumeRecord();
        record.setBizNo(newBizNo());
        record.setMemberId(memberId);
        record.setCardId(card.getId());
        record.setConsumeType(2); // 入场消次
        record.setConsumeCount(1);
        consumeMapper.insert(record);
    }

    /** 课程消课（同步入口，自行生成 bizNo） */
    public void courseConsume(Long memberId, Long courseId) {
        courseConsume(newBizNo(), memberId, courseId);
    }

    /**
     * 课程消课（MQ 消费者入口）。
     *
     * @return true 新写入流水；false 已处理过（幂等）
     */
    public boolean courseConsume(String bizNo, Long memberId, Long courseId) {
        if (!StringUtils.hasText(bizNo)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "bizNo 不能为空");
        }
        Long exists = consumeMapper.selectCount(new QueryWrapper<ConsumeRecord>().eq("biz_no", bizNo));
        if (exists != null && exists > 0) {
            log.info("[消课幂等] bizNo={} 已存在流水，跳过", bizNo);
            return false;
        }

        ConsumeRecord record = new ConsumeRecord();
        record.setBizNo(bizNo);
        record.setMemberId(memberId);
        record.setCourseId(courseId);
        record.setConsumeType(1); // 课程消课
        record.setConsumeCount(1);

        MembershipCard card = pickValidCard(memberId);
        if (card != null) {
            record.setCardId(card.getId());
            decrement(card);
        }
        try {
            consumeMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发下两路都查不到、同时插入：唯一键兜底
            log.info("[消课幂等] bizNo={} 唯一键冲突，视为已处理", bizNo);
            return false;
        }
    }

    public static String newBizNo() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private MembershipCard pickValidCard(Long memberId) {
        List<MembershipCard> cards = cardMapper.selectList(new QueryWrapper<MembershipCard>()
                .eq("member_id", memberId)
                .eq("status", 1)
                .gt("remain_count", 0)
                .ge("end_date", LocalDate.now()));
        return cards.isEmpty() ? null : cards.get(0);
    }

    private void decrement(MembershipCard card) {
        int rows = cardMapper.update(null, new UpdateWrapper<MembershipCard>()
                .eq("id", card.getId())
                .eq("version", card.getVersion())
                .gt("remain_count", 0)
                .setSql("remain_count = remain_count - 1, version = version + 1, "
                        + "status = IF(remain_count - 1 <= 0, 2, 1)"));
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "消课冲突，请重试");
        }
    }

    /** 消费流水列表（最新在前） */
    public List<ConsumeRecord> records() {
        return consumeMapper.selectList(new QueryWrapper<ConsumeRecord>().orderByDesc("create_time"));
    }
}
