package ca.pkay.rcloneexplorer;

/** Pure path-component policy shared by the virtual DocumentsProvider. */
final class DocumentChildNamePolicy {

    private DocumentChildNamePolicy() {
    }

    static String normalizeForCreate(String childName) {
        if (childName == null || childName.isEmpty() || childName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid document child name");
        }
        return requireSingleComponent(childName.replace('/', '_'));
    }

    static String requireSingleComponent(String childName) {
        if (childName == null || childName.isEmpty() || ".".equals(childName) || "..".equals(childName)
                || childName.indexOf('/') >= 0 || childName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid document child name");
        }
        return childName;
    }

    static String targetDocumentId(String parentDocumentId, String childName) {
        if (parentDocumentId == null || parentDocumentId.isEmpty()) {
            throw new IllegalArgumentException("Invalid parent document ID");
        }
        String safeChildName = requireSingleComponent(childName);
        if (parentDocumentId.charAt(parentDocumentId.length() - 1) == '/') {
            return parentDocumentId + safeChildName;
        }
        return parentDocumentId + '/' + safeChildName;
    }
}
