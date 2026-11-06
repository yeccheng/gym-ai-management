package com.gym.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.Member;
import com.gym.system.mapper.MemberMapper;
import org.springframework.stereotype.Service;

/**
 * 会员管理：注册(save)、信息维护(updateById)、挂失/冻结/注销/恢复(改 status)。
 */
@Service
public class MemberService extends ServiceImpl<MemberMapper, Member> {

    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_LOSS = 2;
    public static final int STATUS_FROZEN = 3;
    public static final int STATUS_CANCELLED = 4;

    public void reportLoss(Long id) {
        changeStatus(id, STATUS_LOSS);
    }

    public void freeze(Long id) {
        changeStatus(id, STATUS_FROZEN);
    }

    public void cancel(Long id) {
        changeStatus(id, STATUS_CANCELLED);
    }

    public void restore(Long id) {
        changeStatus(id, STATUS_NORMAL);
    }

    private void changeStatus(Long id, int status) {
        Member m = getById(id);
        if (m == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会员不存在");
        }
        m.setStatus(status);
        updateById(m);
    }
}
