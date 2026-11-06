package com.gym.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.system.entity.Coach;
import com.gym.system.mapper.CoachMapper;
import org.springframework.stereotype.Service;

/**
 * 教练管理：基础 CRUD（逻辑删除由 @TableLogic 处理）。
 */
@Service
public class CoachService extends ServiceImpl<CoachMapper, Coach> {
}
