package com.zifang.z.llm.gateway.core;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * README 定量主张自守卫。
 *
 * <p>对应 lead 仓 README「项目仓 README 口径（2026-09-30 全量收口）」第 5 条「能力可追溯」：
 * README 里写死的数字与名单必须能从源码重算出来 —— README 改了就红，源码变了 README
 * 没跟上也得红。
 *
 * <p>口径约定（踩过的坑，勿随意改）：
 * <ul>
 *   <li><b>事实对象只装源码侧的事实</b>；README 侧的数字在核对当下从被核对的那份 README 现解析。
 *       两边都从同一份 README 取数 ⇒ 尺子做的是恒等式比较，README 怎么改都是绿的。</li>
 *   <li>同一个数在 README 里出现多处时<b>每一处都单独钉</b>（各自不同锚点），
 *       否则改了一处、另一处悄悄漂了没人管。</li>
 *   <li>定位用「表格行首关键字 / 结构树节点名 / 列表行首」，
 *       <b>不拿数字本身当定位标签</b>（短数字是长数字的子串，按数字定位会匹配到别人的行上）。</li>
 *   <li>解析 pom 前<b>先剥 XML 注释</b>：本仓根 pom 里有一处 {@code maven.deploy.skip}
 *       写在注释里（讲 central-publishing 插件不认这个键），不剥会数出 1，
 *       而 README 的主张是「5 个 POM 里命中 0 处」。</li>
 *   <li>语义判定允许 <b>±1 行窗口</b>（否定词与计数常在下一行）。</li>
 *   <li>只数 <b>{@code src/main}</b>；{@code src/test} 的支数与 {@code @Test} 计数是
 *       <b>会漂的自指读数</b>（本守卫自己就落在 core 里），README 那边写成带日期、
 *       标注「不含本守卫」的快照，本守卫按「减去自己」的口径核对。</li>
 * </ul>
 *
 * <p>错误信息必须带出「README 写的值」与「源码重算的值」——红了要知道真数是多少。
 */
public class ReadmeClaimsTest {

    // ================================================================== 仓根与 README

    /** 仓根：测试跑在模块目录，user.dir 不是仓根 —— 向上找同时有 README.md 与 pom.xml 的目录（最多 5 层）。 */
    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("README.md")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("定位仓根失败：从 " + System.getProperty("user.dir")
                + " 向上 5 层内没有同时含 README.md 与 pom.xml 的目录");
    }

    private static String readmeText() {
        try {
            String raw = new String(Files.readAllBytes(repoRoot().resolve("README.md")), StandardCharsets.UTF_8);
            return raw.replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("读取 README.md 失败", e);
        }
    }

    private static List<String> lines(String readme) {
        return Arrays.asList(readme.split("\n", -1));
    }

    private static String readFile(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取 " + file + " 失败", e);
        }
    }

    // ================================================================== 源码重算（事实侧）

    /** 根 pom 的 project 元素（XML 解析：注释掉的 <module> 天然不计入）。 */
    private static Element rootProject() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(repoRoot().resolve("pom.xml").toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new AssertionError("解析根 pom.xml 失败", e);
        }
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** 根 pom 的 {@code <modules>} 里的模块名（声明顺序）。 */
    private static List<String> rootPomModules() {
        Element modules = directChild(rootProject(), "modules");
        if (modules == null) {
            throw new AssertionError("根 pom.xml 里没有 <modules>");
        }
        List<String> out = new ArrayList<>();
        NodeList children = modules.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "module".equals(node.getNodeName())) {
                out.add(node.getTextContent().trim());
            }
        }
        if (out.isEmpty()) {
            throw new AssertionError("根 pom.xml 的 <modules> 是空的");
        }
        return out;
    }

    /** 根 pom 的 {@code <properties><revision>}。 */
    private static String rootPomRevision() {
        Element props = directChild(rootProject(), "properties");
        if (props == null) {
            throw new AssertionError("根 pom.xml 里没有 <properties>");
        }
        NodeList children = props.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "revision".equals(node.getNodeName())) {
                return node.getTextContent().trim();
            }
        }
        throw new AssertionError("根 pom.xml 的 <properties> 里没有 <revision>");
    }

    /** 根 pom 的 {@code <parent><version>}。 */
    private static String rootPomParentVersion() {
        Element parent = directChild(rootProject(), "parent");
        if (parent == null) {
            throw new AssertionError("根 pom.xml 里没有 <parent>");
        }
        Element version = directChild(parent, "version");
        if (version == null) {
            throw new AssertionError("根 pom.xml 的 <parent> 里没有 <version>");
        }
        return version.getTextContent().trim();
    }

    /**
     * 全部 POM 里 {@code maven.deploy.skip} 的命中数 —— <b>先剥 XML 注释</b>。
     *
     * <p>根 pom 里那处命中在注释里（讲 central-publishing 插件不认这个键），
     * 不剥注释会数出 1，而 README 的主张是「5 个 POM 里命中 0 处」。
     */
    private static int deploySkipHits() {
        int hits = 0;
        for (Path pom : allPoms()) {
            String raw = readFile(pom);
            String stripped = raw.replaceAll("(?s)<!--.*?-->", "");
            hits += countOccurrences(stripped, "maven.deploy.skip");
        }
        return hits;
    }

    private static List<Path> allPoms() {
        List<Path> out = new ArrayList<>();
        out.add(repoRoot().resolve("pom.xml"));
        for (String module : rootPomModules()) {
            Path p = repoRoot().resolve(module).resolve("pom.xml");
            if (Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    /** 全仓 {@code application*} 文件数（README 主张是 0）。 */
    private static int applicationFileCount() {
        try (Stream<Path> stream = Files.walk(repoRoot())) {
            return (int) stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("application"))
                    .filter(n -> !n.contains("target"))
                    .count();
        } catch (IOException e) {
            throw new AssertionError("扫 application* 文件失败", e);
        }
    }

    /** {@code ZLlmAutoConfiguration} 的 {@code @Bean} 方法数。 */
    private static int autoConfigBeanCount() {
        return beanNames().size();
    }

    /**
     * {@code ZLlmAutoConfiguration} 里每个 {@code @Bean} 的 **bean 名**。
     *
     * <p>bean 名取 {@code @Bean("zLlmXxx")} 的**字符串值**（不是方法名）—— 方法名是
     * {@code llmXxx}，bean 名才是 {@code zLlmXxx}，README 说的「bean 名带 zLlm 前缀」指的是后者。
     *
     * <p>本仓 12 个 {@code @Bean} 全部带显式名字；这里同时断言「显式名的个数 == @Bean 的个数」，
     * 一旦出现没有显式名的 {@code @Bean}，尺子会**报口径坏了**而不是默默少算一个。
     */
    private static List<String> beanNames() {
        String src = stripComments(autoConfigSource());
        int total = countOccurrences(src, "@Bean");
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("@Bean\\s*\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(src);
        while (m.find()) {
            names.add(m.group(1));
        }
        if (names.size() != total) {
            throw new AssertionError("尺子口径坏了：ZLlmAutoConfiguration 里 @Bean 共 " + total
                    + " 个，但只解析出 " + names.size() + " 个显式 bean 名（有的 @Bean 没有显式名字）");
        }
        if (names.isEmpty()) {
            throw new AssertionError("ZLlmAutoConfiguration 里没解析出任何 @Bean");
        }
        return names;
    }

    private static String autoConfigSource() {
        Path p = repoRoot().resolve("z-llm-gateway-starter/src/main/java/com/zifang/z/llm/gateway/"
                + "starter/autoconfig/ZLlmAutoConfiguration.java");
        if (!Files.isRegularFile(p)) {
            throw new AssertionError("找不到 ZLlmAutoConfiguration：" + p);
        }
        return readFile(p);
    }

    /** 抽出 {@code @Bean ...} 后面那个方法的名字。 */
    @Deprecated
    private static List<String> beanMethods(String src) {
        String stripped = stripComments(src);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("@Bean(?:\\s*\\(\\s*\"([^\"]*)\"\\s*\\))?\\s*(?:public\\s+|private\\s+|protected\\s+)?"
                + "[\\w<>\\[\\].,\\s]*?(\\w+)\\s*\\(").matcher(stripped);
        while (m.find()) {
            out.add(m.group(2));
        }
        if (out.isEmpty()) {
            throw new AssertionError("ZLlmAutoConfiguration 里没解析出任何 @Bean 方法");
        }
        return out;
    }

    /** {@code Vendor} 枚举的常量名（小写形态，README 里就是这么写的）。 */
    private static List<String> vendorNames() {
        Path p = repoRoot().resolve("z-llm-gateway-api/src/main/java/com/zifang/z/llm/gateway/"
                + "api/dto/Vendor.java");
        if (!Files.isRegularFile(p)) {
            throw new AssertionError("找不到 Vendor 枚举：" + p);
        }
        String src = stripComments(readFile(p));
        Matcher head = Pattern.compile("\\benum\\s+Vendor\\s*\\{").matcher(src);
        if (!head.find()) {
            throw new AssertionError("Vendor.java 里找不到 enum Vendor");
        }
        int start = head.end();
        int end = src.indexOf(';', start);
        if (end < 0) {
            throw new AssertionError("Vendor 枚举体没有以分号结束");
        }
        List<String> out = new ArrayList<>();
        for (String part : src.substring(start, end).split(",")) {
            Matcher m = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)").matcher(part);
            if (m.find()) {
                out.add(m.group(1).toLowerCase());
            }
        }
        if (out.isEmpty()) {
            throw new AssertionError("Vendor 枚举没解析出任何常量");
        }
        return out;
    }

    /** 全部模块 {@code src/main} 下的 .java 支数（只数 src/main，不数 src/test）。 */
    private static int mainSourceFileCount() {
        return mainSourceFiles().size();
    }

    /** 各模块 {@code src/main} 下的 .java 支数（键 = 模块名后缀，如 api / core）。 */
    private static java.util.Map<String, Integer> mainSourceFilesByModule() {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        for (String module : rootPomModules()) {
            Path base = repoRoot().resolve(module).resolve("src/main");
            int n = 0;
            if (Files.isDirectory(base)) {
                try (Stream<Path> stream = Files.walk(base)) {
                    n = (int) stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".java"))
                            .count();
                } catch (IOException e) {
                    throw new AssertionError("扫 " + base + " 失败", e);
                }
            }
            String suffix = module.startsWith("z-llm-gateway-")
                    ? module.substring("z-llm-gateway-".length()) : module;
            out.put(suffix, n);
        }
        return out;
    }

    private static List<Path> mainSourceFiles() {
        List<Path> out = new ArrayList<>();
        for (String module : rootPomModules()) {
            Path base = repoRoot().resolve(module).resolve("src/main");
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(out::add);
            } catch (IOException e) {
                throw new AssertionError("扫 " + base + " 失败", e);
            }
        }
        return out;
    }

    /**
     * core 模块 {@code src/test} 里的 {@code @Test} 出现数，**减去本守卫自己**的。
     *
     * <p>README 那句是带日期的快照（会随新增测试漂移），且本守卫自己就落在 core 里 ⇒
     * 写死就漂。口径统一成「不含本仓 README 自守卫」，两边都按这个口径比。
     */
    private static int coreTestCountExcludingGuard() {
        Path base = repoRoot().resolve("z-llm-gateway-core/src/test");
        int total = 0;
        try (Stream<Path> stream = Files.walk(base)) {
            List<Path> files = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
            for (Path p : files) {
                int n = countOccurrences(readFile(p), "@Test");
                total += n;
                if (p.getFileName().toString().equals("ReadmeClaimsTest.java")) {
                    total -= n;
                }
            }
        } catch (IOException e) {
            throw new AssertionError("扫 core 的 src/test 失败", e);
        }
        return total;
    }

    /** 剥掉 Java 行注释与块注释。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    // ================================================================== 主张模型

    /** 一条数字主张的一个锚点：定位行 → 从该行抠数字。 */
    private static final class NumberAnchor {
        final String desc;
        final Pattern line;
        final Pattern number;

        NumberAnchor(String desc, Pattern line, Pattern number) {
            this.desc = desc;
            this.line = line;
            this.number = number;
        }
    }

    /** 数字主张：README 侧若干处说法 ↔ 同一个源码侧重算值。 */
    private static final class NumberClaim {
        final String label;
        final List<NumberAnchor> anchors;
        final java.util.function.Supplier<String> expected;

        NumberClaim(String label, List<NumberAnchor> anchors, java.util.function.Supplier<String> expected) {
            this.label = label;
            this.anchors = anchors;
            this.expected = expected;
        }
    }

    /** 名单主张：README 侧的一份名单 ↔ 源码侧的名单（双向核对，不只比个数）。 */
    private static final class NameClaim {
        final String label;
        final java.util.function.Function<String, Set<String>> readmeSide;
        final java.util.function.Supplier<Set<String>> expected;
        final java.util.function.Function<String, String> mutator;

        NameClaim(String label, java.util.function.Function<String, Set<String>> readmeSide,
                  java.util.function.Supplier<Set<String>> expected,
                  java.util.function.Function<String, String> mutator) {
            this.label = label;
            this.readmeSide = readmeSide;
            this.expected = expected;
            this.mutator = mutator;
        }
    }

    /** 统一的「一条主张」接口：不一致时返回人可读的描述列表（空 = 一致）。 */
    private interface Claim {
        String label();

        List<String> verify(String readme);

        /** 合成一份把本主张改错的 README。 */
        String mutate(String readme);
    }

    /**
     * 文本主张：README 侧抠出的一段文本 ↔ 事实侧重算出的同一段文本。
     *
     * <p>用在「一组数字排在一起」的场景（如 {@code api 17 / core 25 / ...}）：
     * 用数字正则逐个抠要靠捕获组序号，序号一多就容易抠错 ⇒ 直接整段比对。
     */
    private static final class TextClaim {
        final String label;
        final java.util.function.Function<String, String> readmeSide;
        final java.util.function.Supplier<String> expected;
        final java.util.function.Function<String, String> mutator;

        TextClaim(String label, java.util.function.Function<String, String> readmeSide,
                  java.util.function.Supplier<String> expected,
                  java.util.function.Function<String, String> mutator) {
            this.label = label;
            this.readmeSide = readmeSide;
            this.expected = expected;
            this.mutator = mutator;
        }
    }

    // ================================================================== README 抠数 / 抠名单

    /** 定位命中行的下标：必须唯一命中。 */
    private static List<Integer> locateLines(String readme, NumberAnchor anchor) {
        List<Integer> hits = new ArrayList<>();
        List<String> all = lines(readme);
        for (int i = 0; i < all.size(); i++) {
            if (anchor.line.matcher(all.get(i)).find()) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            throw new AssertionError("README 里定位不到行（正则 " + anchor.line.pattern() + "）");
        }
        if (hits.size() > 1) {
            throw new AssertionError("README 里定位不唯一（命中 " + hits.size() + " 行，正则 "
                    + anchor.line.pattern() + "）");
        }
        return hits;
    }

    private static String lineAt(String readme, int index) {
        return lines(readme).get(index);
    }

    /** 从一行里抠出所有匹配的数字（capture group 1）；抠不到就红。 */
    private static List<String> extractNumbers(String line, Pattern number) {
        List<String> values = new ArrayList<>();
        Matcher m = number.matcher(line);
        while (m.find()) {
            values.add(m.group(1));
        }
        if (values.isEmpty()) {
            throw new AssertionError("行里抠不到数字（正则 " + number.pattern() + "）：" + line);
        }
        return values;
    }

    /** 在整份 README 里定位唯一一行并返回该行。 */
    private static String uniqueLine(String readme, Pattern line, String what) {
        List<String> hits = new ArrayList<>();
        for (String l : lines(readme)) {
            if (line.matcher(l).find()) {
                hits.add(l);
            }
        }
        if (hits.isEmpty()) {
            throw new AssertionError("README 里定位不到" + what + "（正则 " + line.pattern() + "）");
        }
        if (hits.size() > 1) {
            throw new AssertionError("README 里" + what + "定位不唯一（命中 " + hits.size() + " 行）");
        }
        return hits.get(0);
    }

    /** 把一段文本里的反引号内容抠出来（`a` / `b` → [a, b]）。 */
    private static List<String> backticked(String text) {
        List<String> spans = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(text);
        while (m.find()) {
            spans.add(m.group(1));
        }
        return spans;
    }

    /** 把 {@code a / b / c} 拆成三个。 */
    private static Set<String> expandSlashList(String span) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : span.split("/")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    /** 只在命中行内替换（避免误伤 README 其它地方）。 */
    private static String replaceOnLine(String readme, Pattern line, String from, String to) {
        List<String> all = lines(readme);
        for (int i = 0; i < all.size(); i++) {
            if (line.matcher(all.get(i)).find()) {
                if (!all.get(i).contains(from)) {
                    throw new AssertionError("变异没生效：命中行里没有 " + from + " —— " + all.get(i));
                }
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < i; k++) {
                    sb.append(all.get(k)).append('\n');
                }
                sb.append(all.get(i).replace(from, to));
                for (int k = i + 1; k < all.size(); k++) {
                    sb.append('\n').append(all.get(k));
                }
                return sb.toString();
            }
        }
        throw new AssertionError("变异没生效：README 里定位不到要改的行（" + line.pattern() + "）");
    }

    // ================================================================== 主张表

    private static List<NumberClaim> numberClaims() {
        List<NumberClaim> list = new ArrayList<>();

        // 1. 当前版本 —— 两处说法，逐处钉
        list.add(new NumberClaim("当前版本",
                Arrays.asList(
                        new NumberAnchor("基本信息表「当前版本」行",
                                Pattern.compile("^\\|\\s*\\*\\*当前版本\\*\\*"),
                                Pattern.compile("`(\\d+\\.\\d+\\.\\d+)`")),
                        new NumberAnchor("项目结构树 pom.xml 行",
                                Pattern.compile("^├── pom\\.xml"),
                                Pattern.compile("<revision>=(\\d+\\.\\d+\\.\\d+)"))),
                () -> rootPomRevision()));

        // 2. 父项目版本号 —— 两处说法，逐处钉
        list.add(new NumberClaim("父项目版本号",
                Arrays.asList(
                        new NumberAnchor("基本信息表「父项目」行",
                                Pattern.compile("^\\|\\s*\\*\\*父项目\\*\\*"),
                                Pattern.compile("z-boot-parent:(\\d+\\.\\d+\\.\\d+)")),
                        new NumberAnchor("项目结构树 pom.xml 行",
                                Pattern.compile("^├── pom\\.xml"),
                                Pattern.compile("z-boot-parent:(\\d+\\.\\d+\\.\\d+)"))),
                () -> rootPomParentVersion()));

        // 3. 模块数 —— 一处说法
        list.add(new NumberClaim("模块数",
                Arrays.asList(new NumberAnchor("基本信息表「模块数」行",
                        Pattern.compile("^\\|\\s*\\*\\*模块数\\*\\*"),
                        Pattern.compile("\\|\\s*(\\d+)\\s*（"))),
                () -> String.valueOf(rootPomModules().size())));

        // 4. maven.deploy.skip 命中数（剥注释后）—— 一处说法
        list.add(new NumberClaim("maven.deploy.skip 命中数",
                Arrays.asList(new NumberAnchor("基本信息表「模块数」行",
                        Pattern.compile("^\\|\\s*\\*\\*模块数\\*\\*"),
                        Pattern.compile("`maven\\.deploy\\.skip` 命中 (\\d+) 处"))),
                () -> String.valueOf(deploySkipHits())));

        // 5. application* 文件数 —— 一处说法
        list.add(new NumberClaim("application 文件数",
                Arrays.asList(new NumberAnchor("基本信息表「默认端口」行",
                        Pattern.compile("^\\|\\s*\\*\\*默认端口\\*\\*"),
                        Pattern.compile("全仓 (\\d+) 个 `application\\*` 文件"))),
                () -> String.valueOf(applicationFileCount())));

        // 6. @Bean 数 —— 两处说法，逐处钉
        list.add(new NumberClaim("自动装配 @Bean 数",
                Arrays.asList(
                        new NumberAnchor("能力清单「自动装配」行",
                                Pattern.compile("^\\|\\s*自动装配\\s*\\|"),
                                Pattern.compile("（(\\d+) 个 `@Bean`")),
                        new NumberAnchor("「bean 名带 zLlm 前缀」段",
                                Pattern.compile("^-\\s*\\*\\*bean 名带 `zLlm` 前缀\\*\\*"),
                                Pattern.compile("里 (\\d+) 个"))),
                () -> String.valueOf(autoConfigBeanCount())));

        // 7. src/main 支数 —— 总数一处说法
        list.add(new NumberClaim("src/main 支数",
                Arrays.asList(new NumberAnchor("代码体量段「src/main/java 共」",
                        Pattern.compile("^代码体量实测："),
                        Pattern.compile("`src/main/java` 共 (\\d+) 支"))),
                () -> String.valueOf(mainSourceFileCount())));

        // 8. Vendor 枚举家数 —— 一处说法
        list.add(new NumberClaim("Vendor 枚举家数",
                Arrays.asList(new NumberAnchor("「Vendor 枚举共 N 家」行",
                        Pattern.compile("^`Vendor` 枚举共"),
                        Pattern.compile("共 (\\d+) 家"))),
                () -> String.valueOf(vendorNames().size())));

        // 9. core 的 @Test 计数（不含本守卫）—— 一处说法
        list.add(new NumberClaim("core 测试方法数（不含本守卫）",
                Arrays.asList(new NumberAnchor("测试段「静态计数」行",
                        Pattern.compile("^静态计数（"),
                        Pattern.compile("`z-llm-gateway-core` (\\d+) 个测试方法"))),
                () -> String.valueOf(coreTestCountExcludingGuard())));

        return list;
    }

    private static List<TextClaim> textClaims() {
        List<TextClaim> list = new ArrayList<>();

        // A. src/main 分模块支数（api 17 / core 25 / starter 3 / config 13 / admin 5）—— 整段比对
        list.add(new TextClaim("src/main 分模块支数",
                readme -> {
                    String line = uniqueLine(readme, Pattern.compile("^代码体量实测："), "「代码体量实测」行");
                    Matcher m = Pattern.compile("（([^）]*)）").matcher(line);
                    if (!m.find()) {
                        throw new AssertionError("「代码体量实测」行里抠不到分模块括号");
                    }
                    java.util.TreeMap<String, Integer> pairs = new java.util.TreeMap<>();
                    for (String part : m.group(1).split("/")) {
                        Matcher pm = Pattern.compile("([A-Za-z0-9_-]+)\\s+(\\d+)").matcher(part);
                        if (!pm.find()) {
                            throw new AssertionError("分模块括号里抠不出「模块名 + 支数」：" + part.trim());
                        }
                        pairs.put(pm.group(1), Integer.parseInt(pm.group(2)));
                    }
                    if (pairs.isEmpty()) {
                        throw new AssertionError("分模块括号里没抠出任何模块");
                    }
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Map.Entry<String, Integer> e : pairs.entrySet()) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(e.getKey()).append(':').append(e.getValue());
                    }
                    return sb.toString();
                },
                () -> mainSourceBreakdown(),
                readme -> replaceOnLine(readme, Pattern.compile("^代码体量实测："),
                        "api 17", "api 18")));

        return list;
    }

    /**
     * 分模块的 src/main 支数，格式 {@code api17/core25/...}（去掉空白便于比对）。
     *
     * <p>顺序取自**根 pom 的 {@code <modules>} 声明顺序** —— README 那行的顺序与之一致，
     * 顺序本身也顺带被这条主张钉住。
     */
    private static String mainSourceBreakdown() {
        java.util.Map<String, Integer> byModule = mainSourceFilesByModule();
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : byModule.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        if (sb.length() == 0) {
            throw new AssertionError("没算出任何模块的 src/main 支数");
        }
        return sb.toString();
    }

    private static List<NameClaim> nameClaims() {
        List<NameClaim> list = new ArrayList<>();

        // A. 模块名单（基本信息表「模块数」行把 5 个模块逐个点名了）
        list.add(new NameClaim("模块名单",
                readme -> {
                    String line = uniqueLine(readme, Pattern.compile("^\\|\\s*\\*\\*模块数\\*\\*"), "基本信息表「模块数」行");
                    Matcher m = Pattern.compile("（([^）]*)）").matcher(line);
                    if (!m.find()) {
                        throw new AssertionError("「模块数」行里抠不到模块名单");
                    }
                    // 只取模块列表本身：括号里后半段还有 ``maven.deploy.skip`` 等别的反引号内容
                    String listPart = m.group(1).split("，")[0];
                    Set<String> names = new TreeSet<>();
                    for (String span : backticked(listPart)) {
                        names.addAll(expandSlashList(span));
                    }
                    if (names.isEmpty()) {
                        throw new AssertionError("「模块数」行里抠不到任何模块名");
                    }
                    return names;
                },
                () -> new TreeSet<>(rootPomModules()),
                readme -> replaceOnLine(readme, Pattern.compile("^\\|\\s*\\*\\*模块数\\*\\*"),
                        "`z-llm-gateway-core`", "`z-llm-gateway-core_MUTATED`")));

        // B. Vendor 枚举名单（那句把 6 家逐个点名了）
        list.add(new NameClaim("Vendor 枚举名单",
                readme -> {
                    String line = uniqueLine(readme, Pattern.compile("^`Vendor` 枚举共"), "「Vendor 枚举共 N 家」行");
                    Matcher m = Pattern.compile("：(.+)。$").matcher(line);
                    if (!m.find()) {
                        throw new AssertionError("「Vendor 枚举共 N 家」行里抠不到枚举名单");
                    }
                    Set<String> names = new TreeSet<>();
                    for (String span : backticked(m.group(1))) {
                        names.addAll(expandSlashList(span));
                    }
                    if (names.isEmpty()) {
                        throw new AssertionError("「Vendor 枚举共 N 家」行里抠不到任何枚举名");
                    }
                    return names;
                },
                () -> new TreeSet<>(vendorNames()),
                readme -> replaceOnLine(readme, Pattern.compile("^`Vendor` 枚举共"),
                        "`openai`", "`openai_MUTATED`")));

        // C. @Bean 名带 zLlm 前缀（README 说「全部 zLlm 前缀」，那就拿事实侧的最长公共前缀来对）
        list.add(new NameClaim("@Bean 名 zLlm 前缀",
                readme -> {
                    String line = uniqueLine(readme, Pattern.compile("^\\|\\s*自动装配\\s*\\|"), "能力清单「自动装配」行");
                    Matcher m = Pattern.compile("全部 `(\\w+)` 前缀").matcher(line);
                    if (!m.find()) {
                        throw new AssertionError("「自动装配」行里抠不到「全部 X 前缀」这个说法");
                    }
                    return new TreeSet<>(java.util.Collections.singletonList(m.group(1)));
                },
                () -> new TreeSet<>(java.util.Collections.singletonList(commonBeanNamePrefix())),
                readme -> replaceOnLine(readme, Pattern.compile("^\\|\\s*自动装配\\s*\\|"),
                        "全部 `zLlm` 前缀", "全部 `zXxx` 前缀")));

        return list;
    }

    /** 全部 bean 名的最长公共前缀（README 说的「全部 X 前缀」里的那个 X）。 */
    private static String commonBeanNamePrefix() {
        List<String> names = beanNames();
        String prefix = names.get(0);
        for (String n : names) {
            int i = 0;
            while (i < prefix.length() && i < n.length() && prefix.charAt(i) == n.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, i);
        }
        if (prefix.isEmpty()) {
            throw new AssertionError("bean 名没有公共前缀");
        }
        return prefix;
    }

    // ================================================================== 主张 → 可核对对象

    private static List<Claim> claims() {
        List<Claim> list = new ArrayList<>();
        for (NumberClaim c : numberClaims()) {
            list.add(new Claim() {
                @Override
                public String label() {
                    return c.label;
                }

                @Override
                public List<String> verify(String readme) {
                    List<String> problems = new ArrayList<>();
                    String expected = c.expected.get();
                    for (NumberAnchor anchor : c.anchors) {
                        try {
                            for (int idx : locateLines(readme, anchor)) {
                                for (String raw : extractNumbers(lineAt(readme, idx), anchor.number)) {
                                    if (!expected.equals(raw)) {
                                        problems.add(c.label + " [" + anchor.desc + "] —— README 写 " + raw
                                                + "，源码重算 " + expected);
                                    }
                                }
                            }
                        } catch (AssertionError e) {
                            problems.add(c.label + " [" + anchor.desc + "] —— " + e.getMessage());
                        }
                    }
                    return problems;
                }

                @Override
                public String mutate(String readme) {
                    for (NumberAnchor anchor : c.anchors) {
                        for (int idx : locateLines(readme, anchor)) {
                            Matcher m = anchor.number.matcher(lineAt(readme, idx));
                            if (m.find()) {
                                String original = m.group(1);
                                return replaceOnLine(readme, anchor.line, original, shiftNumber(original));
                            }
                        }
                    }
                    throw new AssertionError("主张「" + c.label + "」没有任何一处能抠出数字，无法合成变异");
                }
            });
        }
        for (NameClaim c : nameClaims()) {
            list.add(new Claim() {
                @Override
                public String label() {
                    return c.label;
                }

                @Override
                public List<String> verify(String readme) {
                    try {
                        Set<String> expected = c.expected.get();
                        Set<String> actual = c.readmeSide.apply(readme);
                        if (!expected.equals(actual)) {
                            return Arrays.asList(c.label + " —— README 写 " + actual + "，源码重算 " + expected);
                        }
                        return new ArrayList<>();
                    } catch (AssertionError e) {
                        return Arrays.asList(c.label + " —— " + e.getMessage());
                    }
                }

                @Override
                public String mutate(String readme) {
                    return c.mutator.apply(readme);
                }
            });
        }
        for (TextClaim c : textClaims()) {
            list.add(new Claim() {
                @Override
                public String label() {
                    return c.label;
                }

                @Override
                public List<String> verify(String readme) {
                    try {
                        String expected = c.expected.get();
                        String actual = c.readmeSide.apply(readme);
                        if (!expected.equals(actual)) {
                            return Arrays.asList(c.label + " —— README 写 " + actual + "，源码重算 " + expected);
                        }
                        return new ArrayList<>();
                    } catch (AssertionError e) {
                        return Arrays.asList(c.label + " —— " + e.getMessage());
                    }
                }

                @Override
                public String mutate(String readme) {
                    return c.mutator.apply(readme);
                }
            });
        }
        return list;
    }

    /** 数字 +1（末段自增），用于合成「把某个数改错」的 README。 */
    private static String shiftNumber(String raw) {
        String[] parts = raw.split("\\.");
        int last = parts.length - 1;
        parts[last] = String.valueOf(Integer.parseInt(parts[last]) + 1);
        return String.join(".", parts);
    }

    // ================================================================== 核对

    /** 一次全报式：把所有不一致收集齐再抛（仓里有存量漂移时，一次只说一句等于没诊断力）。 */
    private static void assertNoDrift(String readme) {
        List<String> problems = new ArrayList<>();
        for (Claim claim : claims()) {
            problems.addAll(claim.verify(readme));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("README 定量主张与源码不一致（" + problems.size() + " 处）：\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    // ================================================================== 用例

    /** 用例 A：真实 README 的所有主张与源码重算值逐条对账 —— 必须全绿。 */
    @Test
    public void readmeQuantitativeClaimsMustMatchSource() {
        assertNoDrift(readmeText());
    }

    /** 用例 B（牙口）：每条主张都合成一个改错数字/名单的 README，必须抛 AssertionError，且报错指向那条主张。 */
    @Test
    public void mutatedReadmeMustBeRejected() {
        String readme = readmeText();
        List<Claim> claims = claims();
        // 基线必须先健康，否则「报错指向哪条主张」的判断没意义
        try {
            assertNoDrift(readme);
        } catch (AssertionError e) {
            fail("基线 README 自身已漂移，先把 README 修回与源码一致：" + e.getMessage());
        }
        for (Claim claim : claims) {
            String broken = claim.mutate(readme);
            assertNotEquals("变异没有生效：" + claim.label(), readme, broken);
            try {
                assertNoDrift(broken);
                fail("主张被放过（README 写错但没红）：" + claim.label());
            } catch (AssertionError expected) {
                assertTrue("报错没指向真正对不上的主张：" + claim.label() + " / " + expected.getMessage(),
                        expected.getMessage().contains(claim.label()));
            }
        }
    }

    /** 用例 C（不许冤枉健康页）：真实 README 不得被判红。 */
    @Test
    public void realReadmeMustNotBeFlagged() {
        final String readme = readmeText();
        for (Claim claim : claims()) {
            List<String> problems = claim.verify(readme);
            assertTrue("真实 README 被误判为红（或守卫空跑）：" + claim.label() + " / " + problems,
                    problems.isEmpty());
        }
        try {
            assertNoDrift(readme);
        } catch (AssertionError e) {
            fail("真实 README 被误判为红：" + e.getMessage());
        }
    }
}
