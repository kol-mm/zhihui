package com.aiknowledge.user.security;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps members from choosing a nickname that passes for platform staff. Admin accounts are not checked.
 *
 * <p>Chinese titles are matched anywhere once spacing and punctuation are removed, so "管 理-员" is caught.
 * English titles only count as whole words, so "badminton" is fine while "Admin_Tom" and "adm1n" are not.
 */
public final class NicknamePolicy {
    private static final List<String> CHINESE_TITLES = List.of(
            "管理员", "管理員", "管理组", "管理組", "超管", "官方", "客服", "系统管理", "系統管理",
            "系统消息", "系統消息", "系统通知", "系統通知", "版主", "站长", "站長", "运营团队", "運營團隊",
            "审核员", "審核員", "知汇");
    private static final Pattern ENGLISH_TITLES = Pattern.compile(
            "(?<![a-z])(admin|administrator|sysadmin|official|moderator|staff|support|system|root)(?![a-z])");

    private NicknamePolicy() {
    }

    /**
     * @param platformName the configured site name, also reserved; may be blank
     */
    public static boolean impersonatesStaff(String nickname, String platformName) {
        if (nickname == null || nickname.isBlank()) return false;
        String folded = Normalizer.normalize(nickname, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String compact = folded.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        for (String title : CHINESE_TITLES) {
            if (compact.contains(title)) return true;
        }
        if (platformName != null && !platformName.isBlank()) {
            String reservedName = Normalizer.normalize(platformName, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (reservedName.length() >= 2 && compact.contains(reservedName)) return true;
        }
        return ENGLISH_TITLES.matcher(folded).find()
                || ENGLISH_TITLES.matcher(undoLookalikes(folded)).find();
    }

    /** Reads digits and symbols that stand in for letters, as in "adm1n" or "0fficial". */
    private static String undoLookalikes(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            result.append(switch (character) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> character;
            });
        }
        return result.toString();
    }
}
