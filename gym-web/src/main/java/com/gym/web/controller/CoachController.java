package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.Coach;
import com.gym.system.service.CoachService;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教练管理：基础 CRUD（删除为逻辑删除）。
 */
@Tag(name = "教练管理")
@RestController
@RequestMapping("/api/coaches")
@RequiredArgsConstructor
public class CoachController {

    private final CoachService coachService;

    @Operation(summary = "教练列表")
    @GetMapping
    public R<List<Coach>> list() {
        return R.ok(coachService.list());
    }

    @Operation(summary = "教练详情")
    @GetMapping("/{id}")
    public R<Coach> get(@PathVariable Long id) {
        return R.ok(coachService.getById(id));
    }

    @Operation(summary = "新增教练")
    @RequireRole(Role.ADMIN)
    @PostMapping
    public R<Long> create(@RequestBody Coach coach) {
        coachService.save(coach);
        return R.ok(coach.getId());
    }

    @Operation(summary = "修改教练")
    @RequireRole(Role.ADMIN)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Coach coach) {
        coach.setId(id);
        coachService.updateById(coach);
        return R.ok();
    }

    @Operation(summary = "删除教练")
    @RequireRole(Role.ADMIN)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        coachService.removeById(id);
        return R.ok();
    }
}
