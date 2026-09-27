package ca.pkay.rcloneexplorer;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TaskRowAccessibilityTest {
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    @Test
    public void providerIconIsDecorativeAndTaskRemoteTextRemainsAccessible() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(locateTaskRowLayout());

        Element icon = findView(document, "taskIcon");
        assertEquals("@null", icon.getAttributeNS(ANDROID_NAMESPACE, "contentDescription"));

        assertAccessibleText(document, "taskName");
        assertAccessibleText(document, "fromID");
        assertAccessibleText(document, "toID");
    }

    private static void assertAccessibleText(Document document, String id) {
        Element text = findView(document, id);
        assertEquals("TextView", text.getTagName());
        String importance = text.getAttributeNS(ANDROID_NAMESPACE, "importantForAccessibility");
        assertNotEquals("no", importance);
        assertNotEquals("noHideDescendants", importance);
    }

    private static File locateTaskRowLayout() {
        Path moduleLayout = Paths.get("src", "main", "res", "layout", "fragment_tasks_item.xml");
        Path rootLayout = Paths.get("app").resolve(moduleLayout);
        for (Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
                directory != null;
                directory = directory.getParent()) {
            Path[] candidates = {directory.resolve(moduleLayout), directory.resolve(rootLayout)};
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toFile();
                }
            }
        }
        throw new AssertionError("Unable to locate app/src/main/res/layout/fragment_tasks_item.xml from "
                + System.getProperty("user.dir"));
    }

    private static Element findView(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        String expectedId = "@+id/" + id;
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (expectedId.equals(element.getAttributeNS(ANDROID_NAMESPACE, "id"))) {
                return element;
            }
        }
        throw new AssertionError("Missing task-row view: " + id);
    }
}
