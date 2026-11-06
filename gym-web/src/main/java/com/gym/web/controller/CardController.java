package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.MembershipCard;
import com.gym.system.service.CardService;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 卡项管理：开卡、续费、延期、停用。
 */
@Tag(name = "卡项管理")
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    @Operation(summary = "卡项列表（不传 memberId 则返回全部）")
    @GetMapping
    public R<List<MembershipCard>> listByMember(@RequestParam(required = false) Long memberId) {
        if (memberId != null) {
            return R.ok(cardService.list(new QueryWrapper<MembershipCard>().eq("member_id", memberId)));
        }
        return R.ok(cardService.list());
    }

    @Operation(summary = "修改卡项")
    @RequireRole(Role.ADMIN)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody MembershipCard card) {
        card.setId(id);
        cardService.updateById(card);
        return R.ok();
    }

    @Operation(summary = "删除卡项")
    @RequireRole(Role.ADMIN)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        cardService.removeById(id);
        return R.ok();
    }

    @Operation(summary = "开卡")
    @RequireRole(Role.ADMIN)
    @PostMapping("/open")
    public R<Long> openCard(@RequestBody MembershipCard card) {
        Long id = cardService.openCard(card.getMemberId(), card.getCardName(),
                card.getCardType(), card.getTotalCount(), card.getStartDate(),
                card.getEndDate(), card.getPrice());
        return R.ok(id);
    }

    @Operation(summary = "续费（追加次数）")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/renew")
    public R<Void> renew(@PathVariable Long id, @RequestParam int addCount) {
        cardService.renew(id, addCount);
        return R.ok();
    }

    @Operation(summary = "延期（顺延到期日）")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/extend")
    public R<Void> extend(@PathVariable Long id, @RequestParam int days) {
        cardService.extend(id, days);
        return R.ok();
    }

    @Operation(summary = "停用")
    @RequireRole(Role.ADMIN)
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id) {
        cardService.disable(id);
        return R.ok();
    }
}
