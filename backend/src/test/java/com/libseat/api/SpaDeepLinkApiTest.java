package com.libseat.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SPA 深链白名单（V2 S7 / T-V2-23，同时兜住 S0 起每次加分片都要改的那份清单）。
 *
 * <p>为什么值得单独立一个用例：{@code WebConfig} 的白名单是**手写的**，
 * 每个分片加路由都要同步一次；漏了不会在开发时暴露（前端跳转子页面是客户端路由，
 * 不重新请求服务器），只有用户刷新或直接把地址发给同事时才白屏。
 * 而「未命中的路径必须还是 404」是这份白名单的另一半：
 * 若改成 capture-all，任何拼错的地址都会返回 200 + index.html，真正的 404 就再也查不出来了。
 */
class SpaDeepLinkApiTest extends ApiTestBase {

    /** 每个分片新增的前端路由，逐条对应 {@code WebConfig.SpaFallbackController} 的白名单。 */
    private static final List<String> DEEP_LINKS = List.of(
            "/login", "/register", "/floors", "/my/reservations", "/my/waitlist",
            "/groups", "/groups/create", "/credit", "/profile",
            "/staff", "/staff/patrol", "/staff/dashboard", "/staff/appeals",
            "/admin", "/admin/statistics", "/admin/floors", "/admin/usage", "/admin/reservations",
            "/admin/users", "/admin/rules", "/admin/rules/history", "/admin/violations",
            "/admin/zones", "/admin/tables", "/admin/open", "/admin/export",
            "/m", "/m/home", "/m/seat", "/m/my", "/m/credit", "/m/me");

    @Test
    @DisplayName("已登记的深链刷新会转发到应用外壳（不是 404）")
    void whitelistedDeepLinksServeTheAppShell() throws Exception {
        for (String path : DEEP_LINKS) {
            MvcResult result = exec(get(path));
            int status = result.getResponse().getStatus();
            String forwarded = result.getResponse().getForwardedUrl();
            assertEquals(200, status, "深链 " + path + " 刷新应该回到应用外壳（漏配白名单就会白屏）");
            // ★ 断言转发目标而不是 Content-Type：MockMvc 不真的执行 forward，
            //   响应头因此是空的，只有 getForwardedUrl() 说得出「这条路径确实被交给 index.html 了」
            assertEquals("/index.html", forwarded,
                    "深链 " + path + " 没有被转发到应用外壳，刷新时会 404/白屏，实际转发目标是 " + forwarded);
        }
    }

    @Test
    @DisplayName("★ 未登记的路径仍然是 404：白名单不能退化成 capture-all")
    void unknownPathStillFourOhFour() throws Exception {
        MvcResult result = exec(get("/nothing/here"));
        assertEquals(404, result.getResponse().getStatus(),
                "任意路径都返回 200 + index.html 会让真正的 404 再也查不出来");
    }

    @Test
    @DisplayName("带路径参数的深链（楼层平面图）也要能刷新")
    void parameterisedDeepLinkWorks() throws Exception {
        MvcResult result = exec(get("/floors/1/seats"));
        assertEquals(200, result.getResponse().getStatus(), "/floors/{id}/seats 是用户最常分享的地址");
        MvcResult admin = exec(get("/admin/floors/1/seats"));
        assertEquals(200, admin.getResponse().getStatus(), "管理端座位维护页同样要能直接刷新");
    }
}
