package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.Equipment;
import com.gym.system.service.EquipmentService;
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
 * 器材管理：登记、报修、维护、停用。
 */
@Tag(name = "器材管理")
@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentService equipmentService;

    @Operation(summary = "器材列表")
    @GetMapping
    public R<List<Equipment>> list() {
        return R.ok(equipmentService.list());
    }

    @Operation(summary = "器材详情")
    @GetMapping("/{id}")
    public R<Equipment> get(@PathVariable Long id) {
        return R.ok(equipmentService.getById(id));
    }

    @Operation(summary = "登记器材")
    @RequireRole(Role.ADMIN)
    @PostMapping
    public R<Long> create(@RequestBody Equipment equipment) {
        equipmentService.save(equipment);
        return R.ok(equipment.getId());
    }

    @Operation(summary = "修改器材")
    @RequireRole(Role.ADMIN)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Equipment equipment) {
        equipment.setId(id);
        equipmentService.updateById(equipment);
        return R.ok();
    }

    @Operation(summary = "删除器材")
    @RequireRole(Role.ADMIN)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        equipmentService.removeById(id);
        return R.ok();
    }

    @Operation(summary = "报修")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/repair")
    public R<Void> repair(@PathVariable Long id) {
        equipmentService.reportRepair(id);
        return R.ok();
    }

    @Operation(summary = "维护完成")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/maintain")
    public R<Void> maintain(@PathVariable Long id) {
        equipmentService.maintain(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id) {
        equipmentService.disable(id);
        return R.ok();
    }
}
