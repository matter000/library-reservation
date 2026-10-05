package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.NoticeDTO;
import com.libseat.security.UserContext;
import com.libseat.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知接口（DESIGN §8.4，T-V2-20）。
 *
 * <p>★ 只有「读」和「标已读」两类动作，没有「写」接口：通知一律由业务事件产生
 * （违规裁定、候补顶位、暂离登记、提醒任务）。开放写入口就等于任何人都能给别人发消息，
 * 而那会把「这条消息是系统判定」这一整套可信度打掉。
 */
@RestController
@RequestMapping("/api/me/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    /**
     * 我的通知（最近一屏 + 未读数 + 总数）。
     *
     * @return 列表视图
     */
    @GetMapping
    public Result<NoticeDTO.NoticeListVO> mine() {
        return Result.ok(noticeService.list(UserContext.getUserId()));
    }

    /**
     * 标为已读。
     *
     * @param id 通知 ID
     * @return 更新后的通知
     */
    @PostMapping("/{id}/read")
    public Result<NoticeDTO.NoticeVO> read(@PathVariable Long id) {
        return Result.ok(noticeService.markRead(UserContext.getUserId(), id));
    }
}
