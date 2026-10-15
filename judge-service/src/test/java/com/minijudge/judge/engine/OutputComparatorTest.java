package com.minijudge.judge.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分比对器单测：锁死"忽略行尾空白/忽略末尾空行，但不忽略行中空白"的规则边界。
 * 这些用例就是 OJ 判分冤案/放水事故的回归集——每条规则都有真实翻车原型。
 */
class OutputComparatorTest {

    @Test
    @DisplayName("完全相同的多行输出判 AC")
    void identicalMultiLineMatches() {
        assertTrue(OutputComparator.matches("1\n2\n3", "1\n2\n3"));
    }

    @Test
    @DisplayName("行尾空格/制表符差异被忽略：多打一个尾随空格不该 WA")
    void trailingWhitespaceIgnored() {
        assertTrue(OutputComparator.matches("42   \n", "42"));
        assertTrue(OutputComparator.matches("hello\t \nworld \r\n", "hello\nworld\n"));
    }

    @Test
    @DisplayName("末尾空行/换行差异被忽略：println 带不带末尾换行等价")
    void trailingBlankLinesIgnored() {
        assertTrue(OutputComparator.matches("1\n2\n\n\n", "1\n2"));
        assertTrue(OutputComparator.matches("1\n2", "1\n2\n"));
    }

    @Test
    @DisplayName("CRLF 与 LF 行尾等价（Windows 用户程序 stdout 是 \r\n）")
    void crlfAndLfEquivalent() {
        assertTrue(OutputComparator.matches("a\r\nb\r\n", "a\nb\n"));
    }

    @Test
    @DisplayName("行中空白不忽略：默认比对器保持严格，忽略全部空白是特殊判题器的事")
    void innerWhitespaceMatters() {
        assertFalse(OutputComparator.matches("1 2", "12"));
        assertFalse(OutputComparator.matches("1  2", "1 2"));
    }

    @Test
    @DisplayName("行数不同判 WA：少输出/多输出都不放过")
    void lineCountMismatchFails() {
        assertFalse(OutputComparator.matches("1\n2", "1\n2\n3"));
        assertFalse(OutputComparator.matches("1\n2\n3", "1\n2"));
    }

    @Test
    @DisplayName("空输出与空期望等价；空输出与非空期望 WA")
    void emptyHandling() {
        assertTrue(OutputComparator.matches("", ""));
        assertTrue(OutputComparator.matches(null, ""));
        assertFalse(OutputComparator.matches("", "1"));
        assertFalse(OutputComparator.matches("1", ""));
    }
}
