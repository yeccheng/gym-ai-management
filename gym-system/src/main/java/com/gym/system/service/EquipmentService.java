package com.gym.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.Equipment;
import com.gym.system.mapper.EquipmentMapper;
import org.springframework.stereotype.Service;

/**
 * 器材管理：登记(save)、报修 / 维护 / 停用(改 status)。
 */
@Service
public class EquipmentService extends ServiceImpl<EquipmentMapper, Equipment> {

    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_REPAIR = 2;
    public static final int STATUS_DISABLED = 3;

    /** 报修 */
    public void reportRepair(Long id) {
        change(id, STATUS_REPAIR);
    }

    /** 维护完成，恢复可用 */
    public void maintain(Long id) {
        change(id, STATUS_NORMAL);
    }

    /** 停用 */
    public void disable(Long id) {
        change(id, STATUS_DISABLED);
    }

    private void change(Long id, int status) {
        Equipment e = getById(id);
        if (e == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "器材不存在");
        }
        e.setStatus(status);
        updateById(e);
    }
}
