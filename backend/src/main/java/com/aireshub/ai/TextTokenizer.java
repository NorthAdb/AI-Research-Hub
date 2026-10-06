package com.aireshub.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo 环境的中英混合分词:连续拉丁/数字按词,汉字按 bigram(单字成词)。
 * 供 Embedding 的特征哈希、Reranker 的词重叠打分、FTS 的 bigram 预分词共用,
 * 保证三处语义一致。
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        List<Integer> cjk = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i <= n) {
            boolean hasChar = i < n;
            int c = hasChar ? text.charAt(i) : 0;
            if (hasChar && isLatinOrDigit(c)) {
                flushCjk(cjk, tokens);
                latin.append(Character.toLowerCase((char) c));
            } else if (hasChar && isCjk(c)) {
                flushLatin(latin, tokens);
                cjk.add(c);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
            i++;
        }
        return tokens;
    }

    /** 汉字 bigram 用空格拼接,喂给 to_tsvector('simple', …)。 */
    public static String bigramsForFts(String text) {
        return String.join(" ", tokenize(text));
    }

    /** 查询侧 bigram 用 " | " 拼接,构成 OR 的 tsquery。 */
    public static String tsqueryOr(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return "";
        }
        return String.join(" | ", tokens);
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static void flushCjk(List<Integer> cjk, List<String> tokens) {
        if (cjk.size() == 1) {
            tokens.add(String.valueOf((char) (int) cjk.get(0)));
        } else {
            for (int j = 0; j + 1 < cjk.size(); j++) {
                tokens.add("" + (char) (int) cjk.get(j) + (char) (int) cjk.get(j + 1));
            }
        }
        cjk.clear();
    }

    private static boolean isLatinOrDigit(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isCjk(int c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || (c >= 0x3000 && c <= 0x303F)   // CJK 标点
                || (c >= 0x3040 && c <= 0x30FF);  // 假名(容错)
    }
}
