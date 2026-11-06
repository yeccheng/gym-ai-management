package com.gym.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.MembershipCard;
import com.gym.system.mapper.MembershipCardMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 卡项管理：开卡 / 续费 / 延期 / 停用。
 */
@Service
public class CardService extends ServiceImpl<MembershipCardMapper, MembershipCard> {

    public static final int TYPE_COUNT = 1;   // 次卡
    public static final int TYPE_MONTH = 2;    // 月卡
    public static final int TYPE_YEAR = 3;     // 年卡
    public static final int TYPE_PRIVATE = 4;  // 私教课包

    public static final int STATUS_VALID = 1;
    public static final int STATUS_USED_UP = 2;
    public static final int STATUS_EXPIRED = 3;
    public static final int STATUS_DISABLED = 4;

    public Long openCard(Long memberId, String cardName, int cardType,
                         int totalCount, LocalDate startDate, LocalDate endDate, BigDecimal price) {
        MembershipCard card = new MembershipCard();
        card.setMemberId(memberId);
        card.setCardName(cardName);
        card.setCardType(cardType);
        card.setTotalCount(totalCount);
        card.setRemainCount(totalCount);
        card.setStartDate(startDate);
        card.setEndDate(endDate);
        card.setPrice(price);
        card.setStatus(STATUS_VALID);
        card.setVersion(0);
        save(card);
        return card.getId();
    }

    /** 续费：追加次数并恢复有效 */
    public void renew(Long id, int addCount) {
        MembershipCard c = getById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会员卡不存在");
        }
        c.setTotalCount(c.getTotalCount() + addCount);
        c.setRemainCount(c.getRemainCount() + addCount);
        if (c.getStatus() == STATUS_USED_UP) {
            c.setStatus(STATUS_VALID);
        }
        updateById(c);
    }

    /** 延期：顺延到期日 */
    public void extend(Long id, int days) {
        MembershipCard c = getById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会员卡不存在");
        }
        c.setEndDate(c.getEndDate().plusDays(days));
        updateById(c);
    }

    /** 停用 */
    public void disable(Long id) {
        MembershipCard c = getById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会员卡不存在");
        }
        c.setStatus(STATUS_DISABLED);
        updateById(c);
    }
}
