package com.excel.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * xlsx 行数预扫描：
 * xlsx 本质是 zip，直接 SAX 流式读取 xl/worksheets/sheetN.xml 统计 &lt;row&gt; 数量，
 * 不经过 POI 模型、不加载整张表，5 万行只有几十 MB 级临时读取且堆占用极低。
 * xls（BIFF 二进制）无法廉价预扫描，返回 null，前端按"行数未知"展示 ETA。
 */
@Component
public class XlsxRowCounter {

    private static final Logger logger = LoggerFactory.getLogger(XlsxRowCounter.class);

    /**
     * @return 数据行数（不含表头）；无法统计时返回 null
     */
    public Integer countDataRows(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String firstSheetPath = resolveFirstSheetPath(zip);
            if (firstSheetPath == null) {
                return null;
            }
            ZipEntry sheetEntry = zip.getEntry(firstSheetPath);
            if (sheetEntry == null) {
                return null;
            }

            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            SAXParser parser = factory.newSAXParser();

            RowCountHandler handler = new RowCountHandler();
            try (InputStream in = zip.getInputStream(sheetEntry)) {
                parser.parse(new InputSource(in), handler);
            }
            // 去掉表头一行
            return Math.max(handler.rows - 1, 0);
        } catch (Exception e) {
            logger.warn("预扫描xlsx行数失败，将按未知总行数处理: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按 workbook.xml 中第一个 sheet 的 r:id，经 workbook.xml.rels 解析出实际文件路径
     */
    private String resolveFirstSheetPath(ZipFile zip) throws Exception {
        String firstRid = readFirstSheetRid(zip);
        String target = firstRid == null ? null : readRelTarget(zip, firstRid);
        if (target == null) {
            // 兜底：绝大多数工作簿第一个表就是它
            return "xl/worksheets/sheet1.xml";
        }
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        return "xl/" + target.replace("\\", "/").replaceFirst("^xl/", "");
    }

    private String readFirstSheetRid(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("xl/workbook.xml");
        if (entry == null) {
            return null;
        }
        final String[] rid = new String[1];
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        SAXParser parser = factory.newSAXParser();
        try (InputStream in = zip.getInputStream(entry)) {
            parser.parse(new InputSource(in), new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    if (rid[0] == null && "sheet".equalsIgnoreCase(localName)) {
                        // r:id 属性（命名空间不敏感，直接遍历找）
                        for (int i = 0; i < attributes.getLength(); i++) {
                            String n = attributes.getLocalName(i);
                            if ("id".equalsIgnoreCase(n)) {
                                rid[0] = attributes.getValue(i);
                                break;
                            }
                        }
                    }
                }
            });
        }
        return rid[0];
    }

    private String readRelTarget(ZipFile zip, String rid) throws Exception {
        ZipEntry entry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (entry == null) {
            return null;
        }
        final String[] target = new String[1];
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        SAXParser parser = factory.newSAXParser();
        try (InputStream in = zip.getInputStream(entry)) {
            parser.parse(new InputSource(in), new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    if ("Relationship".equalsIgnoreCase(localName)
                            && rid.equals(attributes.getValue("Id"))) {
                        target[0] = attributes.getValue("Target");
                    }
                }
            });
        }
        return target[0];
    }

    private static class RowCountHandler extends DefaultHandler {
        int rows = 0;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("row".equalsIgnoreCase(localName)) {
                rows++;
            }
        }
    }
}
