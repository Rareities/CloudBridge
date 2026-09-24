package ca.pkay.rcloneexplorer;

/** Pure validation and containment rules for virtual-provider document IDs. */
final class DocumentIdPolicy {

    private static final int MAX_DOCUMENT_ID_CHARS = 4096;

    private DocumentIdPolicy() {
    }

    /**
     * Returns a short, normalized parent ID after validating its complete structure.
     * Both provider-rooted SAF IDs and the legacy short form are accepted.
     */
    static String requireParent(String documentId, String rootDocumentId, String rootDocumentPrefix) {
        String shortId = requireShortId(documentId, rootDocumentId, rootDocumentPrefix);
        if (shortId.startsWith("remotes/")) {
            shortId = shortId.substring("remotes/".length());
        }

        int colon = shortId.indexOf(':');
        if (colon <= 0) {
            throw invalidId();
        }
        String remoteName = shortId.substring(0, colon);
        if (remoteName.indexOf('/') >= 0 || ".".equals(remoteName) || "..".equals(remoteName)) {
            throw invalidId();
        }

        String path = shortId.substring(colon + 1);
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith("/")) {
            throw invalidId();
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return remoteName + ":/";
        }
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw invalidId();
            }
        }
        return remoteName + ":/" + path;
    }

    /** Returns a canonical short document ID; the provider root is returned unchanged. */
    static String requireDocument(String documentId, String rootDocumentId, String rootDocumentPrefix) {
        if (rootDocumentId.equals(documentId)) {
            return rootDocumentId;
        }
        String canonicalParent = requireParent(documentId, rootDocumentId, rootDocumentPrefix);
        // Preserve the provider's established remote-root ID form (remote:) while normalizing
        // descendants to remote:/path. This keeps remote-root classification unambiguous.
        if (canonicalParent.endsWith(":/")) {
            return canonicalParent.substring(0, canonicalParent.length() - 1);
        }
        return canonicalParent;
    }

    /**
     * Returns true only for a strict descendant on the same canonical remote/path tree.
     * Malformed IDs and sibling names that merely share a string prefix are not descendants.
     */
    static boolean isChildOf(String parentDocumentId, String documentId,
                             String rootDocumentId, String rootDocumentPrefix) {
        try {
            String parent = requireDocument(parentDocumentId, rootDocumentId, rootDocumentPrefix);
            String child = requireDocument(documentId, rootDocumentId, rootDocumentPrefix);
            if (parent.equals(rootDocumentId)) {
                return !child.equals(rootDocumentId);
            }
            if (child.equals(rootDocumentId)) {
                return false;
            }

            int parentColon = parent.indexOf(':');
            int childColon = child.indexOf(':');
            if (parentColon <= 0 || childColon <= 0 ||
                    !parent.substring(0, parentColon).equals(child.substring(0, childColon))) {
                return false;
            }

            String parentPath = pathAfterRemote(parent, parentColon);
            String childPath = pathAfterRemote(child, childColon);
            if (parentPath.equals(childPath)) {
                return false;
            }
            if (parentPath.isEmpty()) {
                return !childPath.isEmpty();
            }
            return childPath.startsWith(parentPath + "/");
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }

    private static String requireShortId(String documentId, String rootDocumentId, String rootDocumentPrefix) {
        if (documentId == null || documentId.isEmpty() || documentId.length() > MAX_DOCUMENT_ID_CHARS ||
                documentId.indexOf('\0') >= 0) {
            throw invalidId();
        }

        String shortId = documentId;
        if (documentId.startsWith(rootDocumentPrefix)) {
            shortId = documentId.substring(rootDocumentPrefix.length());
        } else if (documentId.startsWith(rootDocumentId)) {
            // A root ID without its exact delimiter is ambiguous/malformed.
            throw invalidId();
        }
        if (shortId.isEmpty() || shortId.startsWith(rootDocumentPrefix) ||
                shortId.startsWith(rootDocumentId)) {
            throw invalidId();
        }
        return shortId;
    }

    private static String pathAfterRemote(String documentId, int colon) {
        String path = documentId.substring(colon + 1);
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static IllegalArgumentException invalidId() {
        return new IllegalArgumentException("Invalid document ID");
    }
}
