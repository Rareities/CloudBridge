package ca.pkay.rcloneexplorer.util;

/** Document IDs are already URI-decoded by Android; never decode filename text twice. */
public final class DocumentIdPolicy {
    private DocumentIdPolicy() {}
    public static void requireSafe(String id) {
        if (id == null || id.isEmpty() || id.length() > 32768 || id.indexOf('\0') >= 0 || id.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid document ID");
        String[] components = id.split("/", -1);
        for (int i = 0; i < components.length; i++) {
            String component = components[i];
            if (component.equals(".") || component.equals("..") || (component.isEmpty() && i != components.length - 1))
                throw new IllegalArgumentException("Document ID escapes its selected folder");
        }
    }
    public static void requireName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Choose a single valid file name");
    }
    public static boolean isChild(String parent, String child) {
        try {
            requireSafe(parent); requireSafe(child);
            String canonicalParent = parent.endsWith("/") ? parent.substring(0, parent.length()-1) : parent;
            return child.startsWith(canonicalParent + "/");
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
