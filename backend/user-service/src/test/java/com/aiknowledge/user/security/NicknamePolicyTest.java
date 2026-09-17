package com.aiknowledge.user.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NicknamePolicyTest {
    @Test
    void catchesStaffTitlesHoweverTheyAreWritten() {
        for (String nickname : new String[]{
                "管理员", "社区管理员小王", "管 理-员", "官方客服", "知汇官方", "系统通知", "版主",
                "admin", "Admin_Tom", "ADMIN", "ＡＤＭＩＮ", "adm1n", "the-0fficial", "Moderator 01", "root"}) {
            assertTrue(NicknamePolicy.impersonatesStaff(nickname, ""), nickname);
        }
    }

    @Test
    void leavesOrdinaryNamesAlone() {
        for (String nickname : new String[]{
                "Demo User", "小明", "badminton fan", "supporter", "ecosystem", "操作系统爱好者", "审核中用户", "", "   "}) {
            assertFalse(NicknamePolicy.impersonatesStaff(nickname, ""), nickname);
        }
        assertFalse(NicknamePolicy.impersonatesStaff(null, "知汇"));
    }

    @Test
    void reservesTheConfiguredSiteName() {
        assertTrue(NicknamePolicy.impersonatesStaff("星河 学堂 助手", "星河学堂"));
        assertFalse(NicknamePolicy.impersonatesStaff("星河", "星河学堂"));
        // A one-letter site name would block too much, so it is not reserved.
        assertFalse(NicknamePolicy.impersonatesStaff("Xavier", "X"));
    }
}
