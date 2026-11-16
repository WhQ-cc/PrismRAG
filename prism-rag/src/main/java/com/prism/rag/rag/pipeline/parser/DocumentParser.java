package com.prism.rag.rag.pipeline.parser;

import com.prism.rag.rag.pipeline.ParsedDocument;

/**
 * 文档解析器（SPI）。
 *
 * <p>解析是入库流水线的第一站：把"原始内容"转成"干净的、保留结构的纯文本"。</p>
 *
 * <p><b>为什么不直接用原文分块？</b>因为原始内容里全是噪声：
 * HTML 标签、Markdown 符号、PDF 的页眉页脚、Word 的样式残留……
 * 这些噪声会进入向量模型，导致向量被稀释，检索质量下降。</p>
 *
 * <p><b>想支持新格式？</b>实现这个接口，注册成 Spring Bean 即可。
 * 比如接入 PDF 只需引入 Apache PDFBox，写一个 {@code PdfParser}，
 * 把 PDFBox 提取的文本塞进 {@link ParsedDocument}，流水线其他部分完全不用改。</p>
 */
public interface DocumentParser {

    /**
     * 该解析器支持��来源类型（text / markdown / csv / pdf ...）。
     */
    boolean supports(String sourceType);

    /**
     * 解析。
     *
     * @param title    文档标题
     * @param content  原始内容
     * @return 解析结果
     */
    ParsedDocument parse(String title, String content);
}
