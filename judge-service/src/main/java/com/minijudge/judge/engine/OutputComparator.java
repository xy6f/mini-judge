package com.minijudge.judge.engine;

/**
 * 输出比对器（计划书 7.1 判定规则中的"忽略行末空白"逐行比对）。
 *
 * <p>规则（与主流 OJ 的"传统比对模式"对齐，单测逐条锁定）：
 * 1. 逐行比较，每行去掉行尾空白（含 \r、半角/全角空格、Tab）后再比——
 *    用户多打一个尾随空格不应 WA；
 * 2. 文件末尾整体忽略：期望输出末行后的空行/换行不算差异（POSIX 文本文件
 *    惯例 vs 用户 println 不带换行，两种都要能过）；
 * 3. 行中空白不忽略："1 2" 与 "12" 不同（忽略所有空白是 SPJ/特殊判题器的
 *    职责，不做进默认比对器——规则越严格越不容易冤案/放水说不清）。</p>
 */
public final class OutputComparator {

    private OutputComparator() {}

    /**
     * 主入口：actual=用户程序 stdout，expected=标准输出文件内容。
     * null 视为空串（用户程序无输出且期望也无输出 => 通过）。
     */
    public static boolean matches(String actual, String expected) {
        String[] a = normalize(actual);
        String[] e = normalize(expected);
        if (a.length != e.length) {
            return false;   // 行数不同短路，下面逐行只处理等长
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(e[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 归一化：拆行（兼容 \r\n 与 \n）-> 每行 rtrim 行尾空白 -> 丢弃末尾空行。
     * 返回行数组，长度即"有效行数"。
     */
    static String[] normalize(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        // split(-1) 保留尾部空串，随后手工裁掉末尾空行，语义更可控
        String[] lines = text.split("\r\n|\r|\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].stripTrailing().isEmpty()) {
            end--;   // 末尾空行不参与比对
        }
        String[] result = new String[end];
        for (int i = 0; i < end; i++) {
            result[i] = lines[i].stripTrailing();
        }
        return result;
    }
}
