package ca.pkay.rcloneexplorer;

import static android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME;
import static android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED;
import static android.provider.DocumentsContract.Document.COLUMN_SIZE;
import static android.provider.DocumentsContract.Document.MIME_TYPE_DIR;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.RcloneRcd.ListItem;
import ca.pkay.rcloneexplorer.Services.RcdService;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.RemoteErrorMessagePolicy;
import ca.pkay.rcloneexplorer.util.VcpJobCompletion;
import io.github.x0b.safdav.provider.SingleRootProvider;
import java9.util.concurrent.CompletableFuture;

// Beta quality notes
//
// - Most clients don't supply a projection or retrieve the results by column
//   name. This class was largely tested against such clients and may thus not
//   perform to documentation when tested against clients that rely on
//   projection ordering (e.g. TotalCommander)
//
// - The default client has a timeout of <10 seconds. This means that
//   operations taking longer than that (moves, copy, etc.) will be continued
//   in background. There is currently no background killer protection, so if
//   you run on a low ram device, long running operations may fail randomly.
//
// - The content provider relies on the new RCD service, which is much less
//   tested than the previous rclone.
//
// - Some functionality is not yet implemented (web links, recents) or only
//   partially implemented (pretendLocal)
public class VirtualContentProvider extends SingleRootProvider {

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_DOCUMENT_ID};

    private static final String TAG = "VirtualContentProvider";
    private static final String ROOT_ID = "rclone";
    @VisibleForTesting()
    static final String ROOT_DOC_ID = ROOT_ID + "/remotes";
    @VisibleForTesting()
    static final String ROOT_DOC_PREFIX = ROOT_DOC_ID + '/';
    private static final ListItem ROOT_ITEM = new ListItem();

    static {
        ROOT_ITEM.name = "remotes";
    }

    private volatile ConcurrentHashMap<String, RemoteItem> remotes;
    volatile Executor asyncExc;
    private volatile FsCache fsCache;
    private volatile long configModifiedTimestamp = 0L;

    private FsState remoteState;
    private Rclone rclone;
    private SharedPreferences preferences;
    private File configFile;
    volatile RcloneRcd rcd;
    volatile RcdService rcdService;
    volatile boolean rcdAvailable;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            FLog.d(TAG, "onServiceConnected: service connected");
            RcdService.RcdBinder binder = (RcdService.RcdBinder) service;
            synchronized (VirtualContentProvider.this) {
                rcdService = binder.getService();
                rcd = rcdService.getLocalRcd();
                rcdAvailable = true;
                VirtualContentProvider.this.notifyAll();
            }
            CompletableFuture.runAsync(() -> reloadRemotesIfRequired(), asyncExc);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            FLog.d(TAG, "onServiceDisconnected: service disconnected");
            synchronized (VirtualContentProvider.this) {
                rcdAvailable = false;
                rcd = null;
                rcdService = null;
                VirtualContentProvider.this.notifyAll();
            }
        }
    };

    private synchronized boolean acquireRcd() {
        Context context = getContext();
        if (null == context) {
            return false;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(context.getString(R.string.pref_key_enable_vcp), false)) {
            return false;
        }
        if (rcdAvailable && rcd != null && rcd.isAlive()) {
            return true;
        }
        FLog.d(TAG, "acquireRcdService: connecting to RcdService");
        Context appCtx = context.getApplicationContext();
        if (rcdService != null && rcdService.isShutdown()) {
            FLog.d(TAG, "Removing old binding");
            rcdAvailable = false;
            rcd = null;
            rcdService = null;
            appCtx.unbindService(connection);
        }
        startBindService(context);
        if (!awaitRcdService()) {
            FLog.w(TAG, "acquireRcd: rcd not ready in time");
            return false;
        }
        return rcdAvailable && rcdService != null && rcd != null;
    }

    private void startBindService(Context context) {
        Context appCtx = context.getApplicationContext();
        Intent startIntent = new Intent(context, RcdService.class);
        appCtx.bindService(startIntent, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appCtx.startForegroundService(startIntent);
        } else {
            appCtx.startService(startIntent);
        }
    }

    private boolean awaitRcdService() {
        return awaitRcdServiceConnection(this, 500,
                () -> rcdAvailable && rcdService != null && rcd != null);
    }

    @VisibleForTesting
    static boolean awaitRcdServiceConnection(Object connectionMonitor, long timeoutMillis,
                                             RcdServiceConnectionState connectionState) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (connectionMonitor) {
            while (!connectionState.isConnected()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int waitNanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis));
                try {
                    connectionMonitor.wait(waitMillis, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @VisibleForTesting
    interface RcdServiceConnectionState {
        boolean isConnected();
    }

    private void initAsyncPool() {
        asyncExc = Executors.newFixedThreadPool(4);
    }

    public VirtualContentProvider() {
        super(ROOT_ID, ROOT_DOC_ID);
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        this.rclone = new Rclone(context);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.configFile = new File(context.getFilesDir().getPath() + "/rclone.conf");
        this.remotes = new ConcurrentHashMap<>();
        this.remoteState = new FsState();
        this.fsCache = new FsCache(400000);
        initAsyncPool();
        CompletableFuture.runAsync(this::reloadRemotesIfRequired, asyncExc);
    }

    @Override
    public void onLowMemory() {
        remoteState.clearCache();
        // todo: persist stickies to disk in case the provider is killed before
        //       they could be uploaded.
        Map<String, FsStateNode> remaining = remoteState.getStickies();
        if (remaining.size() > 0) {
            FLog.w(TAG, "Device is running low on memory, losing changes if killed");
        }

    }

    // Required for OPEN_DOCUMENT_TREE
    @Override
    public boolean isChildDocument(String parentDocumentId, @NonNull String documentId) {
        return DocumentIdPolicy.isChildOf(
                parentDocumentId, documentId, ROOT_DOC_ID, ROOT_DOC_PREFIX);
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection) throws FileNotFoundException {
        // TODO: Track recents (DB required)
        if (!isValidRecentRootId(rootId)) {
            throw invalidDocumentInput(new IllegalArgumentException("Recent query root is not a provider root"));
        }
        return super.queryRecentDocuments(ROOT_ID, projection);
    }

    @VisibleForTesting()
    static boolean isValidRecentRootId(String rootId) {
        return ROOT_ID.equals(rootId);
    }

    @Override
    public IntentSender createWebLinkIntent(String documentId, @Nullable Bundle options) throws FileNotFoundException {
        // TODO: rclone link adapter
        String validatedDocumentId = requireNonRootDocumentId(documentId);
        requireConfiguredRemote(validatedDocumentId);
        return super.createWebLinkIntent(getRootedDocumentId(validatedDocumentId), options);
    }

    // Called when documents-ui is launched
    @NonNull
    @Override
    public Cursor queryRoots(@Nullable String[] projection) {
        FLog.v(TAG, "queryRoots: root document requested");
        Context context = Objects.requireNonNull(getContext());
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean vcpEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_vcp), false);
        boolean pretendLocal = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_declare_local), true);
        int flags = Root.FLAG_SUPPORTS_CREATE
                | Root.FLAG_SUPPORTS_IS_CHILD
                | Root.FLAG_SUPPORTS_SEARCH;

        // Why would rclone declare itself to be a  strictly 'local'
        // in contrast to https://developer.android.com/reference/android/provider/DocumentsContract.Root.html#FLAG_LOCAL_ONLY ?
        // Because otherwise, we'll be filtered from apps such as google docs
        // without a good reason - in fact, google docs does not support any
        // other cloud storage for opening except google drive. This is not
        // just anti-competitive behavior, but outright user-hostile.
        // There is no technical reason for this - it works just fine when we
        // fake this. It will download the document, allow you to make edits,
        // and upload it afterwards without any problem.
        if (pretendLocal) {
            flags |= Root.FLAG_LOCAL_ONLY;
        }
        if (!vcpEnabled) {
            FLog.v(TAG, "queryRoots: VCP disabled");
            return new MatrixCursor(DEFAULT_ROOT_PROJECTION);
        } else if (null != remotes) {
            FLog.v(TAG, "queryRoots: VCP ready");
            Set<String> oldRemotes = new HashSet<>(remotes.keySet());
            CompletableFuture.runAsync(this::acquireRcd, asyncExc).thenRunAsync(() -> {
                // Only notify if the data has actually changed, otherwise the
                // client(s) can get into loops.
                if (!oldRemotes.equals(remotes.keySet())) {
                    FLog.v(TAG, "queryRoots: notify remote data change");
                    context.getContentResolver().notifyChange(getRootUri(), null);
                } else {
                    FLog.v(TAG, "queryRoots: no remote data change");
                }
            }, asyncExc);
            int size = remotes.size();
            String summary = context.getResources().getQuantityString(R.plurals.virtual_content_provider_summary, size, size);
            return buildRoot(R.mipmap.ic_launcher, R.string.app_name, summary, flags);
        } else {
            FLog.v(TAG, "queryRoots: VCP loading");
            CompletableFuture.runAsync(this::acquireRcd, asyncExc).thenRunAsync(() -> {
                context.getContentResolver().notifyChange(getRootUri(), null);
            }, asyncExc);
            String loading = context.getString(R.string.loading);
            return buildRoot(R.mipmap.ic_launcher, R.string.app_name, loading, flags);
        }
    }

    @SuppressLint("InlinedApi")
    private void addRootDocProjectedRow(MatrixCursor cursor) {
        String[] projection = cursor.getColumnNames();
        Object[] row = new Object[projection.length];
        for (int i = 0, projectionLength = projection.length; i < projectionLength; i++) {
            String column = projection[i];
            switch (column) {
                case Document.COLUMN_FLAGS:
                    row[i] = Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_DELETE
                            | Document.FLAG_SUPPORTS_COPY | Document.FLAG_SUPPORTS_MOVE
                            | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_SETTINGS;
                    continue;
                case Document.COLUMN_DOCUMENT_ID:
                case Document.COLUMN_DISPLAY_NAME:
                    row[i] = ROOT_DOC_ID;
                    continue;
                case Document.COLUMN_MIME_TYPE:
                    row[i] = MIME_TYPE_DIR;
                    continue;
                default:
                    row[i] = null;
                    continue;
            }
        }
        cursor.addRow(row);
    }

    @Override
    public Cursor queryDocument(@NonNull String documentId, String[] projection) throws FileNotFoundException {
        String shortDocumentId = requireDocumentId(documentId);
        documentId = ROOT_DOC_ID.equals(shortDocumentId)
                ? ROOT_DOC_ID : getRootedDocumentId(shortDocumentId);
        if (rcdAvailable && rcdService != null) {
            rcdService.onNotifyUse();
        }
        FLog.v(TAG, "queryDocument(): %s", documentId);
        if (null == projection) {
            projection = DEFAULT_DOCUMENT_PROJECTION;
        }
        // Return the current topmost level, e.g. rclone/remotes
        if (ROOT_DOC_ID.equals(documentId)) {
            try (MatrixCursor result = new MatrixCursor(projection)) {
                addRootDocProjectedRow(result);
                grantPermission(documentId);
                return result;
            }
        // Return the level below, e.g. rclone/remotes/gdrive:
        } else if (isRemoteDocument(documentId)) {
            String remoteDocumentId = getShortId(documentId);
            String remoteName = getRemoteName(remoteDocumentId);
            requireConfiguredRemote(remoteDocumentId);
            Cursor cursor = getRemotesAsCursor(projection, remoteName);
            grantPermission(documentId);
            return cursor;
        // Return another level below, e.g. rclone/remotes/gdrive:/brochure.pdf
        } else {
            String fileDocumentId = requireNonRootDocumentId(documentId);
            requireConfiguredRemote(fileDocumentId);
            ListItem cached = getFileItem(fileDocumentId);
            MatrixCursor cursor;
            if (null != cached) {
                cached.mimeType = FileItem.getMimeType(cached.mimeType, cached.path);
                FLog.v(TAG, "queryDocument(%s): %s, %s, %s, %d", documentId, cached.path, cached.mimeType, cached.name, cached.lastModified);
                cursor = new MatrixCursor(projection);
                cursor.addRow(getForProjection(cached, getRootedDocumentId(documentId), projection));
                grantPermission(documentId);
            } else {
                Bundle bundle = new Bundle();
                Context context = Objects.requireNonNull(getContext());
                bundle.putString(DocumentsContract.EXTRA_ERROR, context.getString(R.string.virtual_content_provider_null_content));
                bundle.putString(DocumentsContract.EXTRA_INFO, context.getString(R.string.virtual_content_provider_null_content));
                cursor = new ExtrasMatrixCursor(projection, bundle);
            }
            return cursor;
        }
    }

    // Helper function to generate the root
    private void addFileItemRow(MatrixCursor cursor, String documentId, ListItem item) {
        Object[] columnValues = new Object[]{
                ROOT_DOC_ID + "/" + documentId,
                item.name,
                item.isDir ? MIME_TYPE_DIR : item.mimeType,
                getFlags(item),
                item.size,
                item.lastModified
        };
        String[] columnNames = cursor.getColumnNames();
        if (Arrays.equals(DEFAULT_DOCUMENT_PROJECTION, columnNames)) {
            cursor.addRow(columnValues);
        } else {
            cursor.newRow()
                    .add(Document.COLUMN_DOCUMENT_ID, columnValues[0])
                    .add(Document.COLUMN_DISPLAY_NAME, columnValues[1])
                    .add(Document.COLUMN_MIME_TYPE, columnValues[2])
                    .add(Document.COLUMN_FLAGS, columnValues[3])
                    .add(Document.COLUMN_SIZE, columnValues[4])
                    .add(Document.COLUMN_LAST_MODIFIED, columnValues[5]);
        }
    }

    /**
     * Note: Usually, DocumentIds are strictly hierarchical, e.g. the folder
     * structure is mapped onto the documentId. However, for some reason,
     * DocumentsUI will prepend the id of the documentIds returned by this
     * function to the documentIds passed to openDocument(...) and others.
     * @param rootId the root id to query (ignored)
     * @param query user search term(s)
     * @param projection required answer columns and their order
     * @return a list of results, if any
     * @throws FileNotFoundException shouldn't be thrown at all?
     */
    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        if (!ROOT_ID.equals(rootId)) {
            throw new FileNotFoundException("Unknown virtual-provider root");
        }
        MatrixCursor cursor = new MatrixCursor(null != projection ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Map<String, FsStateNode> res = remoteState.search(query);
        for (Map.Entry<String, FsStateNode> result : res.entrySet()) {
            addFileItemRow(cursor, getRootedDocumentId(result.getKey()), result.getValue().item);
        }
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final boolean rootDocument = ROOT_DOC_ID.equals(parentDocumentId);
        final String validatedParentId = rootDocument ? null : requireParentDocumentId(parentDocumentId);
        FLog.v(TAG, "queryChildDocuments(): parent=%s", parentDocumentId);
        if (null != sortOrder && !sortOrder.equals("_display_name ASC")) {
            FLog.w(TAG, "Specified sort order not supported, using default");
        }
        if (rootDocument) {
            if (null == projection) {
                projection = DEFAULT_REMOTE_PROJECTION;
            }
            return getRemotesAsCursor(projection, null);
        } else {
            final RemoteItem remoteItem = requireConfiguredRemote(validatedParentId);
            if (null == projection) {
                projection = DEFAULT_DOCUMENT_PROJECTION;
            }
            String originalParentDocId = getRootedDocumentId(validatedParentId);
            parentDocumentId = validatedParentId;
            String remoteName = getRemoteName(validatedParentId);
            String dirPath = getRclonePath(parentDocumentId);

            // TODO: relies on legacyExternalStorage
            // Adjust remote-specific behaviors
            if (RemoteItem.LOCAL == remoteItem.getType() && "".equals(dirPath)) {
                dirPath = Rclone.getLocalRemotePathPrefix(remoteItem, getContext());
            }
            try (MatrixCursor result = new MatrixCursor(projection)) {
                ListItem[] directFiles = null;
                String extraInfo = "unknown error";
                Bundle bundle = new Bundle();
                try {
                    if (acquireRcd()) {
                        directFiles = rcd.list(remoteItem.getName(), dirPath);
                    } else {
                        ContentResolver contentResolver = getContext().getContentResolver();
                        Uri targetUri = buildUriFromId(getRootedDocumentId(getShortId(originalParentDocId)));
                        result.setNotificationUri(contentResolver, targetUri);
                        bundle.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                        CompletableFuture.runAsync(() -> {
                            FLog.d(TAG, "queryChildDocuments: notifying %s", targetUri);
                            contentResolver.notifyChange(targetUri, null);
                        }, asyncExc);
                    }

                } catch (RcloneRcd.RcdOpException e) {
                    extraInfo = RemoteErrorMessagePolicy.forUser(e.getError());
                }
                if (null == directFiles) {
                    Context context = Objects.requireNonNull(getContext());
                    if (null == extraInfo) {
                        extraInfo = context.getString(R.string.virtual_content_provider_null_content);
                    } else {
                        extraInfo = context.getString(R.string.virtual_content_provider_exception_error, extraInfo);
                    }
                    bundle.putString(DocumentsContract.EXTRA_ERROR, extraInfo);
                    bundle.putString(DocumentsContract.EXTRA_INFO, context.getString(R.string.virtual_content_provider_exception_advice));
                    return new ExtrasMatrixCursor(DEFAULT_DOCUMENT_PROJECTION, bundle);
                }
                Arrays.sort(directFiles, decodeSorting(sortOrder));
                int flags = getFlags(remoteItem);
                for (ListItem item : directFiles) {
                    String shortId = remoteName + ":" + getRelativeItemPath(item.path);
                    fsCache.put(shortId, item);
                    remoteState.put(shortId, item, FsStateNode.CACHED);
                    Object[] resultValues = getForProjection(item, getRootedDocumentId(shortId), projection);
                    result.addRow(resultValues);
                }
                return result;
            }
        }
    }

    // Only supports ASC, DESC of a single level
    // example: _display_name ASC
    private Comparator<ListItem> decodeSorting(String sortOrder) {
        Comparator<ListItem> comparator = (a, b) -> 0;
        if (sortOrder == null) {
            return comparator;
        }

        if (sortOrder.contains(",")) {
            sortOrder = sortOrder.substring(0, sortOrder.indexOf(","));
        }
        String column = sortOrder.substring(0, sortOrder.lastIndexOf(" "));
        int asc = sortOrder.endsWith(" ASC") ? -1 : 1;

        switch (column) {
            case COLUMN_DISPLAY_NAME:
                return (a, b) -> a.name != null && b.name != null ? asc * a.name.compareTo(b.name) : 0;
            case COLUMN_LAST_MODIFIED:
                return (a, b) -> a.lastModified > 0 && b.lastModified >= 0 ? (int) (asc * (a.lastModified - b.lastModified)) : 0;
            case COLUMN_SIZE:
                return (a, b) -> a.size > 0 && b.size >= 0 ? (int) (asc * (a.size - b.size)) : 0;
        }
        return comparator;
    }

    // see https://rclone.org/overview/#optional-features
    @SuppressLint("InlinedApi")
    private int getFlags(ListItem item) {
        // There are actually some file managers (e.g. com.github.axet.filemanager)
        // that will openOutputStream() on a folder if it has FLAG_SUPPORTS_WRITE
        // set, so these two flags are exclusive.
        int dirFlags = item.isDir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
        return Document.FLAG_SUPPORTS_COPY
                | Document.FLAG_SUPPORTS_DELETE
                | Document.FLAG_SUPPORTS_MOVE
                | Document.FLAG_SUPPORTS_RENAME
                | dirFlags;
    }

    @SuppressLint("InlinedApi")
    private int getFlags(RemoteItem item) {
        return Document.FLAG_SUPPORTS_COPY
                | Document.FLAG_SUPPORTS_DELETE
                | Document.FLAG_SUPPORTS_MOVE
                | Document.FLAG_SUPPORTS_WRITE
                | Document.FLAG_SUPPORTS_RENAME
                | Document.FLAG_DIR_SUPPORTS_CREATE;
    }

    // This is surprisingly just as fast as hardcoded projections because of VM optimisations
    private Object[] getForProjection(ListItem item, String path, String[] columns) {
        if (columns == DEFAULT_DOCUMENT_PROJECTION) {
            return new Object[]{path, item.name, item.isDir ? MIME_TYPE_DIR : item.mimeType,
                    getFlags(item), item.size, item.lastModified};
        }
        Object[] result = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            String column = columns[i];
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID:
                    result[i] = path;
                    continue;
                case Document.COLUMN_DISPLAY_NAME:
                    result[i] = item.name;
                    continue;
                case Document.COLUMN_FLAGS:
                    result[i] = getFlags(item);
                    continue;
                case Document.COLUMN_MIME_TYPE:
                    result[i] = item.isDir ? MIME_TYPE_DIR : item.mimeType;
                    continue;
                case Document.COLUMN_LAST_MODIFIED:
                    result[i] = item.lastModified;
                    continue;
                case Document.COLUMN_SIZE:
                    result[i] = item.size;
                    continue;
                case Document.COLUMN_ICON:
                    result[i] = null;
                    continue;
                case Document.COLUMN_SUMMARY:
                    result[i] = null;
                    continue;
            }
        }
        return result;
    }

    @SuppressLint("InlinedApi")
    private Object[] getForProjection(RemoteItem item, String[] columns) {
        String documentId = ROOT_DOC_ID + "/" + item.getName() + ":";
        if (columns == DEFAULT_DOCUMENT_PROJECTION) {
            return new Object[]{documentId, item.getDisplayName(), MIME_TYPE_DIR,
                    getFlags(item), null, null};
        }
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getContext());
        Set<String> renamedRemotes = pref.getStringSet(getContext().getString(R.string.pref_key_renamed_remotes), new HashSet<>());

        Object[] result = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            String column = columns[i];
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID:
                    result[i] = documentId;
                    break;
                case Document.COLUMN_DISPLAY_NAME:
                    String documentName = item.getName();
                    if (renamedRemotes.contains(item.getName())) {
                        documentName = pref.getString(getContext()
                                .getString(R.string.pref_key_renamed_remote_prefix, item.getName()), item.getName());
                    }
                    result[i] = documentName;
                    break;
                case Document.COLUMN_FLAGS:
                    result[i] = Document.FLAG_SUPPORTS_SETTINGS | getFlags(item);
                    break;
                case Document.COLUMN_MIME_TYPE:
                    result[i] = MIME_TYPE_DIR;
                    break;
                case Document.COLUMN_ICON:
                    result[i] = item.getRemoteIcon();
                    break;
            }
        }
        return result;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        displayName = normalizeCreateDocumentName(displayName);
        parentDocumentId = ROOT_DOC_PREFIX + requireParentDocumentId(parentDocumentId);
        requireConfiguredRemote(getShortId(parentDocumentId));

        String documentId = getTargetByChild(parentDocumentId, displayName);
        ListItem existingItem = getFileItem(getNoRootId(documentId));
        int noConflictId = 2;
        while (existingItem != null) {
            documentId = getTargetByChild(parentDocumentId, displayName + " (" + noConflictId++ + ")");
            existingItem = getFileItem(getNoRootId(documentId));
        }

        FLog.v(TAG, "createDocument: %s", documentId);

        String cacheId = getNoRootId(documentId);
        String rclonePath = getRclonePath(documentId);

        ListItem item = new ListItem();
        item.path = rclonePath;
        item.name = displayName;
        item.lastModified = System.currentTimeMillis();
        item.mimeType = mimeType;
        item.isDir = MIME_TYPE_DIR.equals(mimeType);

        // Directories are lightweight enough to directly pass the change to
        // the underlying cloud storage. We don't create placeholders for
        // normal files since they might be sync'ed by a different client.
        if (item.isDir) {
            try {
                String remoteName = getRemoteName(getNoRootId(documentId));
                String path = getRclonePath(documentId);
                if (acquireRcd()) {
                    rcd.mkDir(remoteName, path);
                } else {
                    throw new FileNotFoundException();
                }
            } catch (RcloneRcd.RcdIOException e) {
                throw new FileNotFoundException(RemoteErrorMessagePolicy.forUser(e.getError()));
            } catch (RcloneRcd.RcdOpException e) {
                FLog.e(TAG, "Unexpected RCD error", e);
                throw new FileNotFoundException(RemoteErrorMessagePolicy.forUser(e.getError()));
            }
        } else {
            // However, we need to add this to the cache to capture the subsequent queryDocument call
            fsCache.put(cacheId, item);
            remoteState.put(cacheId, item, FsStateNode.STICKY);
        }

        notifyChange(documentId);
        FLog.v(TAG, "createDocument: created %s, %s, %s, %s", documentId, mimeType, displayName, item.lastModified);
        return documentId;
    }

    @Override
    public String renameDocument(final String documentId, final String displayName) throws FileNotFoundException {
        final String safeDisplayName = requireValidDocumentChildName(displayName);
        final String sourceDocumentId = requireNonRootDocumentId(documentId);
        requireConfiguredRemote(sourceDocumentId);
        final String rootedDocumentId = getRootedDocumentId(sourceDocumentId);
        FLog.v(TAG, "renameDocument: %s -> %s", rootedDocumentId, safeDisplayName);
        final String remoteName = getRemoteName(sourceDocumentId);
        final String srcPath = getRclonePath(sourceDocumentId);
        final ListItem sourceItem = getFileItem(sourceDocumentId);
        if (sourceItem == null) {
            throw new FileNotFoundException("Document metadata is unavailable");
        }
        final String targetDocId = getTargetByChild(getParent(rootedDocumentId), safeDisplayName);
        final String dstPath = getRclonePath(targetDocId);
        FLog.v(TAG, "remoteName: %s, srcPath: %s, targetDocId: %s, dstPath: %s", remoteName, srcPath, targetDocId, dstPath);
        if (!acquireRcd()) {
            throw new FileNotFoundException();
        }
        VcpJobCompletion completion = new VcpJobCompletion();
        OnJobFinishListener listener = new OnJobFinishListener(completion) {
            @Override
            void onFinish(RcloneRcd.JobStatusResponse status) {
                FLog.v(TAG, "rename job reached a terminal callback");
                invalidateMutationState(sourceItem.isDir, rootedDocumentId, targetDocId);
                if (isConfirmedSuccess(status)) {
                    revokeDocumentPermission(rootedDocumentId);
                }
            }
        };
        try {
            rcd.moveFile(remoteName, srcPath, remoteName, dstPath, listener);
            requireMutationSuccess(completion, 5000, sourceItem.isDir, rootedDocumentId, targetDocId);
            return targetDocId;
        } catch (RcloneRcd.RcdOpException e) {
            FLog.e(TAG, "RCD move failure", e);
            invalidateMutationState(sourceItem.isDir, rootedDocumentId, targetDocId);
            throw mutationNotConfirmed(VcpJobCompletion.Outcome.UNKNOWN);
        }
    }

    /** Runs mutation state cleanup before waking the synchronous DocumentsProvider caller. */
    private static class OnJobFinishListener implements RcloneRcd.JobStatusHandler {

        private final VcpJobCompletion completion;

        public OnJobFinishListener(VcpJobCompletion completion) {
            this.completion = completion;
        }

        @Override
        public final void handleJobStatus(RcloneRcd.JobStatusResponse jobStatusResponse) {
            try {
                onFinish(jobStatusResponse);
                completion.complete(jobStatusResponse != null && jobStatusResponse.finished,
                        jobStatusResponse != null && jobStatusResponse.success);
            } catch (RuntimeException failure) {
                FLog.e(TAG, "Unable to refresh provider state after terminal RCD job callback", failure);
                completion.complete(false, false);
            }
        }

        @Override
        public final boolean dispatchOnMainThread() {
            return false;
        }

        void onFinish(RcloneRcd.JobStatusResponse jobStatusResponse) {
        }
    }

    private static boolean isConfirmedSuccess(RcloneRcd.JobStatusResponse status) {
        return status != null && VcpJobCompletion.isConfirmedSuccess(status.finished, status.success);
    }

    private void requireMutationSuccess(VcpJobCompletion completion, long timeoutMillis,
                                       boolean includeDescendants,
                                       String... affectedDocumentIds) throws FileNotFoundException {
        VcpJobCompletion.Outcome outcome = completion.await(timeoutMillis);
        if (outcome != VcpJobCompletion.Outcome.SUCCEEDED) {
            FLog.w(TAG, "Provider mutation did not receive confirmed success: %s", outcome);
            invalidateMutationState(includeDescendants, affectedDocumentIds);
            throw mutationNotConfirmed(outcome);
        }
    }

    private static FileNotFoundException mutationNotConfirmed(VcpJobCompletion.Outcome outcome) {
        String message = outcome == VcpJobCompletion.Outcome.FAILED
                ? "Remote operation did not complete successfully"
                : "Remote operation may still be running; completion is not confirmed";
        return new FileNotFoundException(message);
    }

    /** Drops stale provider metadata and tells SAF clients to re-query affected documents. */
    private void invalidateMutationState(boolean includeDescendants, String... affectedDocumentIds) {
        if (fsCache != null) fsCache.evictAll();
        if (remoteState != null) remoteState.clearCache();
        if (affectedDocumentIds == null) return;
        for (String affectedDocumentId : affectedDocumentIds) {
            if (affectedDocumentId == null) continue;
            String shortId = getShortId(affectedDocumentId);
            if (fsCache != null) fsCache.remove(shortId);
            if (remoteState != null) {
                if (includeDescendants) {
                    remoteState.removeSubtree(shortId);
                } else {
                    remoteState.remove(shortId);
                }
            }
            notifyChange(shortId);
            String parent = getParent(shortId);
            if (!parent.equals(shortId)) notifyChange(parent);
        }
    }

    @Override
    public void deleteDocument(String rawDocumentId) throws FileNotFoundException {
        final String shortDocumentId = requireDocumentId(rawDocumentId);
        if (ROOT_DOC_ID.equals(shortDocumentId)) {
            throw invalidDocumentInput(new IllegalArgumentException("Provider root is not a document"));
        }
        final String rootedDocumentId = getRootedDocumentId(shortDocumentId);
        FLog.v(TAG, "deleteDocument: %s", rootedDocumentId);

        if (isRemoteDocument(rootedDocumentId)) {
            FLog.e(TAG, "deleteDocument: deleting remotes not supported");
            throw new UnsupportedOperationException();
        }
        final String documentId = getNoRootId(rootedDocumentId);
        requireConfiguredRemote(documentId);
        String remoteName = getRemoteName(documentId);
        ListItem document = getFileItem(documentId);
        if (null == document) {
            throw new FileNotFoundException();
        }

        VcpJobCompletion completion = new VcpJobCompletion();
        OnJobFinishListener listener = new OnJobFinishListener(completion) {
            @Override
            void onFinish(RcloneRcd.JobStatusResponse jobStatusResponse) {
                FLog.v(TAG, "delete job reached a terminal callback");
                invalidateMutationState(document.isDir, rootedDocumentId);
                if (isConfirmedSuccess(jobStatusResponse)) {
                    revokeDocumentPermission(rootedDocumentId);
                }
            }
        };

        // Service guard
        if (!acquireRcd()) {
            throw new FileNotFoundException();
        }

        try {
            if (document.isDir) {
                rcd.purge(remoteName, document.path, listener);
            } else {
                rcd.deleteFile(remoteName, document.path, listener);
            }
            requireMutationSuccess(completion, 10000, document.isDir, rootedDocumentId);
        } catch (RcloneRcd.RcdOpException e) {
            FLog.e(TAG, "deleteDocument() failed", e);
            invalidateMutationState(document.isDir, rootedDocumentId);
            throw mutationNotConfirmed(VcpJobCompletion.Outcome.UNKNOWN);
        }
    }

    @Override
    public String copyDocument(final String rootedSrcDocId, String rootedTargetParentDocId) throws FileNotFoundException {
        String targetParentDocumentId = requireParentDocumentId(rootedTargetParentDocId);
        String sourceDocumentId = requireNonRootDocumentId(rootedSrcDocId);
        String rootedSourceDocumentId = getRootedDocumentId(sourceDocumentId);
        requireConfiguredRemote(sourceDocumentId);
        requireConfiguredRemote(targetParentDocumentId);
        FLog.v(TAG, "copyDocument: %s -> %s", rootedSourceDocumentId, rootedTargetParentDocId);
        final String targetDocumentId = requireTargetDocumentId(sourceDocumentId, targetParentDocumentId);
        ListItem document = getFileItem(sourceDocumentId);
        if (null == document) {
            throw new FileNotFoundException();
        }
        final String srcPath = getRcloneFullPath(sourceDocumentId);
        final String srcRemoteName = getRemoteName(sourceDocumentId);
        final String dstFullPath = getRcloneFullPath(targetDocumentId);
        final String dstPath = getRclonePath(targetDocumentId);
        final String dstRemoteName = getRemoteName(targetDocumentId);

        // copyDocument is a data-transfer operation; allow up to 30s for the rcd job so we don't
        // report success to the client while bytes are still moving.
        final VcpJobCompletion completion = new VcpJobCompletion();
        OnJobFinishListener listener = new OnJobFinishListener(completion) {
            @Override
            void onFinish(RcloneRcd.JobStatusResponse jobStatusResponse) {
                FLog.v(TAG, "copy job reached a terminal callback");
                invalidateMutationState(document.isDir, targetDocumentId);
            }
        };

        // Service guard
        if (!acquireRcd()) {
            throw new FileNotFoundException();
        }

        try {
            if (document.isDir) {
                rcd.copy(srcPath, dstFullPath, listener);
            } else {
                rcd.copyFile(srcRemoteName, document.path, dstRemoteName, dstPath, listener);
            }
            requireMutationSuccess(completion, 30000, document.isDir, targetDocumentId);
            return getRootedDocumentId(targetDocumentId);
        } catch (RcloneRcd.RcdOpException e) {
            FLog.e(TAG, "copyDocument() failed", e);
            invalidateMutationState(document.isDir, targetDocumentId);
            throw mutationNotConfirmed(VcpJobCompletion.Outcome.UNKNOWN);
        }
    }

    @Override
    public String moveDocument(final String rootedSrcDocId, String sourceParentDocumentId, final String rootedTargetDocParentId) throws FileNotFoundException {
        String targetParentDocumentId = requireParentDocumentId(rootedTargetDocParentId);
        final String sourceDocumentId = requireNonRootDocumentId(rootedSrcDocId);
        final String rootedSourceDocumentId = getRootedDocumentId(sourceDocumentId);
        String validatedSourceParent = requireParentDocumentId(sourceParentDocumentId);
        String actualSourceParent = requireParentDocumentId(getParent(sourceDocumentId));
        if (!validatedSourceParent.equals(actualSourceParent)) {
            throw invalidDocumentInput(new IllegalArgumentException("Source parent does not match document ID"));
        }
        requireConfiguredRemote(sourceDocumentId);
        requireConfiguredRemote(targetParentDocumentId);
        FLog.d(TAG, "moveDocument: %s -> %s", rootedSourceDocumentId, rootedTargetDocParentId);
        final String targetDocumentId = requireTargetDocumentId(sourceDocumentId, targetParentDocumentId);

        ListItem document = getFileItem(sourceDocumentId);
        if (null == document) {
            throw new FileNotFoundException();
        }

        // moveDocument is a data-transfer operation; allow up to 30s for the rcd job.
        final VcpJobCompletion completion = new VcpJobCompletion();
        OnJobFinishListener listener = new OnJobFinishListener(completion) {
            @Override
            void onFinish(RcloneRcd.JobStatusResponse status) {
                FLog.v(TAG, "move job reached a terminal callback");
                invalidateMutationState(document.isDir, rootedSourceDocumentId, targetDocumentId);
                if (isConfirmedSuccess(status)) {
                    revokeDocumentPermission(rootedSourceDocumentId);
                }
            }
        };

        // Service guard
        if (!acquireRcd()) {
            throw new FileNotFoundException();
        }

        try {
            if (document.isDir) {
                String srcFs = getRcloneFullPath(sourceDocumentId);
                String dstFs = getRcloneFullPath(targetDocumentId);
                rcd.move(srcFs, dstFs, listener);
            } else {
                String srcRemote = getRemoteName(sourceDocumentId);
                String srcPath = getRclonePath(sourceDocumentId);
                String dstRemote = getRemoteName(targetDocumentId);
                String dstPath = getRclonePath(targetDocumentId);
                rcd.moveFile(srcRemote, srcPath, dstRemote, dstPath, listener);
            }

            requireMutationSuccess(completion, 30000, document.isDir,
                    rootedSourceDocumentId, targetDocumentId);
            return getRootedDocumentId(targetDocumentId);
        } catch (RcloneRcd.RcdOpException e) {
            FLog.e(TAG, "moveDocument() failed", e);
            invalidateMutationState(document.isDir, rootedSourceDocumentId, targetDocumentId);
            throw mutationNotConfirmed(VcpJobCompletion.Outcome.UNKNOWN);
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        String validatedDocumentId = requireNonRootDocumentId(documentId);
        String validatedParentId = requireParentDocumentId(parentDocumentId);
        String actualParentId = requireParentDocumentId(getParent(validatedDocumentId));
        if (!validatedParentId.equals(actualParentId)) {
            throw invalidDocumentInput(new IllegalArgumentException("Parent does not contain the document"));
        }
        requireConfiguredRemote(validatedDocumentId);
        deleteDocument(validatedDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        String shortDocumentId = requireDocumentId(documentId);
        if (ROOT_DOC_ID.equals(shortDocumentId)) {
            return MIME_TYPE_DIR;
        }
        String rootedDocumentId = getRootedDocumentId(shortDocumentId);
        requireConfiguredRemote(shortDocumentId);
        if (isRemoteDocument(rootedDocumentId)) {
            return MIME_TYPE_DIR;
        }
        ListItem item = remoteState.get(shortDocumentId).item;
        if (null == item) {
            int lastSlash = rootedDocumentId.lastIndexOf('/');
            String name = lastSlash >= 0 ? rootedDocumentId.substring(lastSlash + 1) : rootedDocumentId;
            int lastDot = name.lastIndexOf('.');
            if (lastDot < 0 || lastDot == name.length() - 1) {
                return "application/octet-stream";
            }
            String extension = name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            return mimeType != null ? mimeType : "application/octet-stream";
        }
        return item.mimeType;
    }


    /**
     * Example Ids: {@code remotes/dropbox:/sheet.xls}, {@code dropbox:/sheet.xls}
     * @param documentId a documentId
     * @return remote name without ':'
     */
    @NonNull
    @VisibleForTesting
    static String getRemoteName(@NonNull String documentId) {
        int nameEnd = documentId.indexOf(':');
        // 0 if there is no path separator, or the index of the first path name character
        int nameStart = documentId.lastIndexOf('/') + 1;
        if (nameStart > nameEnd) {
            nameStart = 0;
        }
        return documentId.substring(nameStart, nameEnd);
    }

    /**
     * Get a full path that can be passed to rclone without any further modification.
     * {@code remotes/dropbox:/sheet.xls} -> {@code dropbox:sheet.xls}
     * @param documentId a short documentId
     * @return a rclone style remote path identifier
     */
    private static String getRcloneFullPath(String documentId) {
        String remoteName = getRemoteName(documentId);
        String path = "";
        int pathStart = documentId.indexOf(':') + 1;
        if (pathStart < documentId.length()) {
            if ('/' == documentId.charAt(pathStart)) {
                pathStart++;
            }
            path = documentId.substring(pathStart);
        }
        return remoteName + ':' + path;
    }

    // should handle
    // remote:/     => remote:
    // remote:      => remote:
    // remote:str   => str
    // remote:str/  => str
    // remote:/str  => str
    // remote:/str/ => str
    // remote:/str/b => b
    static String getChildName(String documentId) {
        int lastSlash = documentId.lastIndexOf('/');
        boolean endsWithSlash = documentId.length() - 1 == lastSlash;
        if (endsWithSlash) {
            lastSlash = documentId.lastIndexOf('/', lastSlash - 1);
        }
        int colon = documentId.indexOf(':');
        int firstSlash = documentId.indexOf('/', colon);

        if (lastSlash > colon) {
            if (!endsWithSlash) {
                return documentId.substring(lastSlash + 1);
            } else if (firstSlash > colon && firstSlash <= lastSlash) {
                return documentId.substring(lastSlash + 1, documentId.length() - 1);
            } else {
                return documentId;
            }
        }
        if (documentId.length() - 1 <= colon) {
            return documentId;
        } else {
            return documentId.substring(colon + 1);
        }
    }

    @NonNull
    @VisibleForTesting()
    static String getParent(@NonNull String documentId) {
        int lastSlash = documentId.lastIndexOf('/');
        boolean endsWithSlash = documentId.length() - 1 == lastSlash;
        if (endsWithSlash) {
            lastSlash = documentId.lastIndexOf('/', lastSlash - 1);
        }
        int colon = documentId.indexOf(':');
        int firstSlash = documentId.indexOf('/', colon);

        if (lastSlash > colon) {
            if (!endsWithSlash) {
                return documentId.substring(0, lastSlash);
            } else if (firstSlash > colon && firstSlash <= lastSlash) {
                return documentId.substring(0, lastSlash);
            } else {
                return documentId;
            }
        }
        if (documentId.length() - 1 <= colon) {
            return documentId;
        } else {
            return documentId.substring(0, colon + 1);
        }
    }

    /**
     * Composes a DocumentID for a new document
     * @param srcDocumentId a short source documentId
     * @param targetParentDocId a short documentId of the target's parent
     * @return a short documentId of the target document
     */
    @VisibleForTesting()
    static String getTargetDocumentId(String srcDocumentId, String targetParentDocId) {
        throwOnRootId(srcDocumentId);
        throwOnRootId(targetParentDocId);
        String childName = getChildName(srcDocumentId);
        return DocumentChildNamePolicy.targetDocumentId(targetParentDocId, childName);
    }

    @VisibleForTesting()
    static String getTargetByChild(String parentDocId, String childName) {
        return DocumentChildNamePolicy.targetDocumentId(parentDocId, childName);
    }

    private static String requireValidDocumentChildName(@Nullable String childName) throws FileNotFoundException {
        try {
            return DocumentChildNamePolicy.requireSingleComponent(childName);
        } catch (IllegalArgumentException e) {
            throw invalidDocumentInput(e);
        }
    }

    private static String normalizeCreateDocumentName(@Nullable String childName) throws FileNotFoundException {
        try {
            return DocumentChildNamePolicy.normalizeForCreate(childName);
        } catch (IllegalArgumentException e) {
            throw invalidDocumentInput(e);
        }
    }

    private static String requireTargetDocumentId(String sourceDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        try {
            return getTargetDocumentId(sourceDocumentId, targetParentDocumentId);
        } catch (IllegalArgumentException e) {
            throw invalidDocumentInput(e);
        }
    }

    private static FileNotFoundException invalidDocumentInput(IllegalArgumentException cause) {
        FileNotFoundException failure = new FileNotFoundException("Invalid document identifier or name");
        failure.initCause(cause);
        return failure;
    }

    private static String requireParentDocumentId(String documentId) throws FileNotFoundException {
        try {
            return DocumentIdPolicy.requireParent(documentId, ROOT_DOC_ID, ROOT_DOC_PREFIX);
        } catch (IllegalArgumentException e) {
            throw invalidDocumentInput(e);
        }
    }

    private static String requireDocumentId(String documentId) throws FileNotFoundException {
        try {
            return DocumentIdPolicy.requireDocument(documentId, ROOT_DOC_ID, ROOT_DOC_PREFIX);
        } catch (IllegalArgumentException e) {
            throw invalidDocumentInput(e);
        }
    }

    private static String requireNonRootDocumentId(String documentId) throws FileNotFoundException {
        String shortId = requireDocumentId(documentId);
        int separator = shortId.indexOf(':');
        if (ROOT_DOC_ID.equals(shortId) || separator == shortId.length() - 1) {
            throw invalidDocumentInput(new IllegalArgumentException("A provider or remote root is not a file document"));
        }
        return shortId;
    }

    private RemoteItem requireConfiguredRemote(String shortDocumentId) throws FileNotFoundException {
        if (remotes == null) {
            throw new FileNotFoundException("Remote configuration is not ready");
        }
        String remoteName = getRemoteName(shortDocumentId);
        RemoteItem known = remotes.get(remoteName);
        if (known != null) {
            return known;
        }
        try {
            return getRemoteItem(remoteName);
        } catch (IllegalArgumentException e) {
            throw invalidDocumentInput(e);
        }
    }

    // Extract the path within the remote, e.g.
    // remotes/remote:bucket/item => bucket/item
    @VisibleForTesting()
    static String getRclonePath(String documentId) {
        int idx = documentId.indexOf(':');
        if ('/' == documentId.charAt(idx + 1)) {
            idx++;
        }
        return documentId.substring(idx + 1);
    }

    /**
     * Remove the root document as it is used only for the icon. Does not
     * remove extranous roots, see {@link #getShortId} for that.
     * @param rootedDocumentId a root-prefixed documentId
     * @return a short document id
     */
    @VisibleForTesting()
    static String getNoRootId(String rootedDocumentId) {
        if (rootedDocumentId.length() < ROOT_DOC_ID.length() + 1) {
            throw new IllegalArgumentException("Invalid document id");
        }
        return rootedDocumentId.substring(ROOT_DOC_ID.length() + 1);
    }

    @VisibleForTesting
    static boolean isSameOrDescendantCacheId(String candidateId, String rootId) {
        String normalizedRoot = normalizeCacheId(rootId);
        String normalizedCandidate = normalizeCacheId(candidateId);
        if (normalizedCandidate.equals(normalizedRoot)) {
            return true;
        }
        String subtreePrefix = normalizedRoot.endsWith("/")
                ? normalizedRoot : normalizedRoot + "/";
        return normalizedCandidate.startsWith(subtreePrefix);
    }

    private static String normalizeCacheId(String documentId) {
        String shortId = getShortId(documentId);
        int colon = shortId.indexOf(':');
        if (colon >= 0 && shortId.length() == colon + 1) {
            return shortId + "/";
        }
        while (colon >= 0 && shortId.length() > colon + 2 && shortId.endsWith("/")) {
            shortId = shortId.substring(0, shortId.length() - 1);
        }
        return shortId;
    }

    @VisibleForTesting()
    static String getRootedDocumentId(String unrootedDocumentId) {
        if (unrootedDocumentId.startsWith(ROOT_DOC_PREFIX)) {
            return unrootedDocumentId;
        } else {
            return ROOT_DOC_PREFIX + unrootedDocumentId;
        }
    }

    @NonNull
    @VisibleForTesting()
    static String getShortId(@NonNull String documentIdMaybeMalformed) {
        int extraneousRoots = -1;
        // malformed case 1: multiply prepended root id
        while (documentIdMaybeMalformed.startsWith(ROOT_DOC_PREFIX)) {
            documentIdMaybeMalformed = getNoRootId(documentIdMaybeMalformed);
            extraneousRoots++;
        }
        if (extraneousRoots > 0) {
            FLog.w(TAG, "getNormalRootId: documentId malformed, multiple root doc id: %d", extraneousRoots);
        }
        return documentIdMaybeMalformed;
    }

    /**
     * Check if the document id is for a remote
     * @return true if the id is a remote, otherwise false
     */
    @VisibleForTesting()
    static boolean isRemoteDocument(@NonNull String rootedDocumentId) {
        return rootedDocumentId.startsWith(ROOT_DOC_PREFIX)
                && rootedDocumentId.indexOf(':') == rootedDocumentId.length() - 1
                && rootedDocumentId.lastIndexOf('/') == ROOT_DOC_PREFIX.length() - 1;
    }

    @VisibleForTesting
    static Uri buildUriFromId(@NonNull String rootedId) {
        String documentId = getRootedDocumentId(rootedId);
        return DocumentsContract.buildDocumentUri(BuildConfig.VCP_AUTHORITY, documentId);
    }

    @VisibleForTesting
    static Uri buildUriFromRemote(@NonNull String remoteName) {
        String documentId = getRootedDocumentId(remoteName + ":");
        return DocumentsContract.buildDocumentUri(BuildConfig.VCP_AUTHORITY, documentId);
    }

    @VisibleForTesting
    static Uri buildRemotesUri() {
        return DocumentsContract.buildDocumentUri(BuildConfig.VCP_AUTHORITY, ROOT_DOC_ID);
    }

    private static void throwOnRootId(String documentId) {
        if (documentId.startsWith(ROOT_DOC_ID + '/')) {
            throw new DocumentIdException();
        }
    }

    private void notifyChange(String documentId) {
        Context context = getContext();
        if (null == context) return;
        ContentResolver cr = context.getContentResolver();
        String rootedDocumentId = getRootedDocumentId(documentId);
        Uri documentUri = DocumentsContract.buildTreeDocumentUri(BuildConfig.VCP_AUTHORITY, rootedDocumentId);
        cr.notifyChange(documentUri, null);
    }

    @VisibleForTesting()
    static class DocumentIdException extends RuntimeException {
    }

    /**
     * Get item from cache, otherwise re-read the parent-directory.
     * @param documentId a short documentId
     * @return Item or null if not found
     */
    @Nullable
    private ListItem getFileItem(String documentId) {
        int sep = documentId.indexOf(':') + 1;
        if (documentId.length() == sep) {
            FLog.w(TAG, "getFileItem: naked root! Your SAF client may be buggy.");
            documentId += '/';
        }
        if (!"/".equals(documentId.substring(sep, sep + 1))) {
            documentId = documentId.substring(0, sep) + "/" + documentId.substring(sep);
        }
        ListItem cachedItem = remoteState.get(documentId).item;
        if (null == cachedItem) {
            FLog.v(TAG, "getFileItem: refresh meta for %s", documentId);
            String[] remote = documentId.split(":");
            // TODO: missing guard if remote name is garbage => IllegalArgumentException
            getRemoteItem(remote[0]);
            // retrieve parent dir path or root
            int idx = documentId.indexOf("/");
            int ldx = documentId.lastIndexOf("/");
            String dirPath = -1 != idx && ldx > idx ? documentId.substring(idx + 1, ldx) : "";
            String name = documentId.substring(ldx + 1);
            // retrieve parent content
            ListItem[] items;
            try {
                items = rcd.list(remote[0], dirPath);
            } catch (RcloneRcd.RcdOpException e) {
                return null;
            }
            for (ListItem newItem : items) {
                String id = remote[0] + ":/" + newItem.path;
                remoteState.put(id, newItem, FsStateNode.CACHED);
                if (newItem.name.equals(name)) {
                    cachedItem = newItem;
                }
            }
        } else {
            FLog.v(TAG, "getFileItem: using cached for %s", documentId);
        }
        return cachedItem;
    }

    private Cursor getRemotesAsCursor(@NonNull String[] projection, @Nullable String remoteName) {
        Context context = Objects.requireNonNull(getContext());
        if (!acquireRcd()) {
            Bundle extras = new Bundle(1);
            extras.putString(DocumentsContract.EXTRA_ERROR, context.getString(R.string.virtual_content_provider_service_error));
            return new ExtrasMatrixCursor(projection, extras);
        }
        try (MatrixCursor cursor = new MatrixCursor(projection)) {
            final Uri notificationUri;
            if (null != remoteName) {
                notificationUri = buildUriFromRemote(remoteName);
            } else {
                notificationUri = buildRemotesUri();
            }
            cursor.setNotificationUri(getContext().getContentResolver(), notificationUri);

            CompletableFuture.runAsync(this::reloadRemotesIfRequired, asyncExc).thenRun(() -> {
                getContext().getContentResolver().notifyChange(notificationUri, null);
            });

            // Todo: don't call this on main thread. Instead, use cached + update strategy.
            //Set<Map.Entry<String, RcloneRcd.ConfigDumpRemote>> entries = rcd.configDump().entrySet();
            if (null == remotes || remotes.isEmpty()) {
                Bundle extras = new Bundle(1);
                extras.putString(DocumentsContract.EXTRA_INFO, context.getString(R.string.virtual_content_provider_no_remotes));
                FLog.d(TAG, "getRemotesAsCursor: No remotes, returning empty cursor");
                return new ExtrasMatrixCursor(projection, extras);
            }
            for (RemoteItem item : remotes.values()) {
                // Exclude from results - no need to loop back
                if (null != remoteName && remoteName.equals(item.getName())) {
                    if (remoteName.equals(item.getName())) {
                        cursor.addRow(getForProjection(item, projection));
                        break;
                    }
                } else {
                    cursor.addRow(getForProjection(item, projection));
                };
            }
            FLog.d(TAG, "getRemotesAsCursor: Returning cursor with remotes");
            return cursor;
        } catch (RcloneRcd.RcdOpException e) {
            Bundle extras = new Bundle(1);
            extras.putString(DocumentsContract.EXTRA_ERROR,
                    context.getString(R.string.virtual_content_provider_exception_error,
                            RemoteErrorMessagePolicy.forUser(e.getError())));
            return new ExtrasMatrixCursor(projection, extras);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        documentId = requireNonRootDocumentId(documentId);
        requireConfiguredRemote(documentId);
        FLog.d(TAG, "openDocument: %s, mode=%s, package=%s",
                getRootedDocumentId(documentId), mode, getCallingPackage());
        ListItem document = getFileItem(documentId);
        // Some misbehaved client apps just don't understand that openDocument() does not work on directories
        if (document == null || document.isDir) {
            throw new FileNotFoundException();
        }
        if (null == signal) {
            signal = new CancellationSignal();
        }

        long len = document.size;
        if ("r".equals(mode)) {
            ParcelFileDescriptor consumer;
            ParcelFileDescriptor producer;
            try {
                ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createReliablePipe();
                consumer = descriptors[0];
                producer = descriptors[1];
                PipeTransferThread pipeTransfer = new PipeTransferThread(rclone.downloadToPipe(documentId, signal),
                        new ParcelFileDescriptor.AutoCloseOutputStream(producer), signal, len);
                pipeTransfer.start();
                return consumer;
            } catch (IOException e) {
                throw new IllegalArgumentException();
            }
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            ParcelFileDescriptor consumer;
            ParcelFileDescriptor producer;
            try {
                ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createReliablePipe();
                consumer = descriptors[0];
                producer = descriptors[1];
                PipeTransferThread pipeTransfer = new PipeTransferThread(
                        new ParcelFileDescriptor.AutoCloseInputStream(consumer),
                        rclone.uploadFromPipe(documentId, signal), signal, len);
                pipeTransfer.start();
                return producer;
            } catch (IOException e) {
                throw new IllegalArgumentException();
            }
        }
        throw new UnsupportedOperationException();
    }

    private void grantPermission(String documentId) {
        Context context = getContext();
        boolean isEnabled = preferences.getBoolean(context.getString(R.string.pref_key_vcp_grant_all), false);
        if (!isEnabled) {
            return;
        }
        Uri uri = DocumentsContract.buildDocumentUri(BuildConfig.VCP_AUTHORITY, documentId);

        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (PackageManager.PERMISSION_GRANTED == context.checkCallingUriPermission(uri, flags)) {
            return;
        }

        ContentResolver contentResolver = context.getContentResolver();
        List<UriPermission> permissions = contentResolver.getOutgoingPersistedUriPermissions();
        for(UriPermission permission : permissions) {
            if (permission.getUri().equals(uri)) {
                FLog.v(TAG, "Client already has persisted permission, not granting additional permissions");
                return;
            }
        }
        String callingPackage = getCallingPackage();
        FLog.d(TAG, "granting permisssions to client %s", callingPackage);
        context.grantUriPermission(callingPackage, uri, flags);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void revokePermission(String documentId) {
        Context context = getContext();
        assert null != context;
        Uri uri = DocumentsContract.buildDocumentUri(BuildConfig.VCP_AUTHORITY, documentId);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.revokeUriPermission(getCallingPackage(), uri, flags);
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        assert null != context;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean(context.getString(R.string.pref_key_enable_vcp), false)) {
            Intent intent = new Intent(context, RcdService.class);
            FLog.d(TAG, "onCreate: initiating service connection");
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
        return true;
    }

    public static Uri getRootUri() {
        return DocumentsContract.buildRootsUri(BuildConfig.VCP_AUTHORITY);
    }

    private RemoteItem getRemoteItem(String name) {
        RemoteItem item = remotes.get(name);
        if (null == item) {
            reloadRemotesIfRequired();
            item = remotes.get(name);
            if (null == item) {
                throw new IllegalArgumentException("Remote " + name + " does not exist");
            }
        }
        return item;
    }

    /**
     * Checks if the config file was changed and then reloads it
     */
    synchronized void reloadRemotesIfRequired() {
        synchronized (configFile) {
            long lastModified = configFile.lastModified();
            if (lastModified > configModifiedTimestamp) {
                configModifiedTimestamp = lastModified;
                FLog.d(TAG, "reloadRemotesIfRequired(): requesting new remote config data");
                ArrayList<String> newRemotes = new ArrayList<String>();
                for (RemoteItem remoteItem : rclone.getRemotes()) {
                    if (RemoteItem.SAFW == remoteItem.getType() || RemoteItem.LOCAL == remoteItem.getType()) {
                        continue;
                    }
                    remotes.put(remoteItem.getName(), remoteItem);
                    newRemotes.add(remoteItem.getName());
                }
                // Remove remotes that no longer exist. If instead
                // remotes.clear() were to be used, 'remotes' would need to be
                // locked for all access while it is being updated.
                for (String remote : remotes.keySet()) {
                    if(!newRemotes.contains(remote)){
                        remotes.remove(remote);
                    }
                }
                FLog.v(TAG, "reloadRemotesIfRequired(): remote config data updated");
            }
        }
    }

    @VisibleForTesting()
    static String getRelativeItemPath(String path) {
        if (0 != path.indexOf("/")) {
            path = "/" + path;
        }
        return path;
    }

    private static class FsCache {
        private static final int ESTIMATED_ENTRY_BYTES = 384;
        private final int maxSize;
        private int size;
        // Access order preserves the Android LruCache contract while keeping this policy JVM-testable.
        private final LinkedHashMap<String, ListItem> entries = new LinkedHashMap<>(16, 0.75f, true);

        FsCache(int maxSize) {
            if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be positive");
            this.maxSize = maxSize;
        }

        synchronized ListItem get(String key) {
            return entries.get(key);
        }

        synchronized void put(String key, ListItem value) {
            if (key == null || value == null) throw new NullPointerException("key and value must be non-null");
            ListItem previous = entries.put(key, value);
            if (previous == null) size += ESTIMATED_ENTRY_BYTES;
            trimToSize();
        }

        synchronized void remove(String key) {
            if (entries.remove(key) != null) size -= ESTIMATED_ENTRY_BYTES;
        }

        synchronized Map<String, ListItem> snapshot() {
            return new LinkedHashMap<>(entries);
        }

        synchronized void evictAll() {
            entries.clear();
            size = 0;
        }

        private void trimToSize() {
            Iterator<Map.Entry<String, ListItem>> iterator = entries.entrySet().iterator();
            while (size > maxSize && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
                size -= ESTIMATED_ENTRY_BYTES;
            }
        }

        /**
         * Search the cached items. Uses a *very* simple search matching algorithm
         * @param search search term(s)
         * @return a tuple of (List(results), List(documentIds))
         */
        public List[] search(String search, String remote) {
            List<ListItem> results = new ArrayList<>();
            List<String> documentIds = new ArrayList<>();
            Map<String, ListItem> map = snapshot();
            Set<String> paths = map.keySet();
            String[] terms = search.toLowerCase(Locale.ROOT).split(" ");
            for (String path : paths) {
                // filter by remote
                if (!path.startsWith(remote)) {
                    continue;
                }
                // filter by terms
                for (int i = 0; i < terms.length; i++) {
                    // a path must contain all terms in the path, the order does not matter
                    if (path.toLowerCase(Locale.ROOT).contains(terms[i])) {
                        if (terms.length - 1 == i) {
                            results.add(map.get(path));
                            documentIds.add(path);
                            break;
                        }
                        continue;
                    }
                    break;
                }
            }
            return new List[]{results, documentIds};
        }
    }

    /**
     * A simple caching map like structure that caches temporary items and
     * stores sticky items. Temporary items may be evicted from cache, sticky
     * items are always kept in memory.
     */
    static class FsState {
        private final Map<String, FsStateNode> stickyMap = new HashMap<>();
        private final FsCache fsCache = new FsCache(500 * 1024);

        public synchronized void put(String noRootId, ListItem item, int flags) {
            if ((flags & FsStateNode.STICKY) == 1) {
                FLog.v(TAG, "storing sticky: %s", noRootId);
                FsStateNode node = new FsStateNode(item, flags);
                stickyMap.put(noRootId, node);
            } else {
                FLog.v(TAG, "storing cached: %s", noRootId);
                fsCache.put(noRootId, item);
            }
        }

        public synchronized FsStateNode get(String noRootId) {
            FLog.v(TAG, "retrieving node: %s", noRootId);
            FsStateNode node = stickyMap.get(noRootId);
            if (null == node) {
                ListItem item = fsCache.get(noRootId);
                node = new FsStateNode(item, FsStateNode.CACHED);
            }
            return node;
        }

        public synchronized void remove(String noRootId) {
            FLog.v(TAG, "removing node: %s", noRootId);
            FsStateNode node = stickyMap.remove(noRootId);
            if (null == node) {
                fsCache.remove(noRootId);
            }
        }

        public synchronized void removeSubtree(String noRootId) {
            for (String id : new ArrayList<>(stickyMap.keySet())) {
                if (isSameOrDescendantCacheId(id, noRootId)) {
                    stickyMap.remove(id);
                }
            }
            for (String id : new ArrayList<>(fsCache.snapshot().keySet())) {
                if (isSameOrDescendantCacheId(id, noRootId)) {
                    fsCache.remove(id);
                }
            }
        }

        public synchronized Map<String, FsStateNode> search(String searchTerm) {
            FLog.v(TAG, "searching: %s", searchTerm);
            String normalizedSearch = searchTerm.toLowerCase(Locale.ROOT).trim();
            Map<String, FsStateNode> results = new HashMap<>();
            for (Map.Entry<String, FsStateNode> entry : stickyMap.entrySet()) {
                if (entry.getValue().item.name.contains(normalizedSearch)) {
                    results.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, ListItem> entry : fsCache.snapshot().entrySet()) {
                if (entry.getValue().name.contains(normalizedSearch)) {
                    results.put(entry.getKey(), new FsStateNode(entry.getValue(), FsStateNode.CACHED));
                }
            }
            return results;
        }

        public synchronized Map<String, FsStateNode> getStickies() {
            return Collections.unmodifiableMap(new HashMap<>(stickyMap));
        }

        public synchronized void clearCache() {
            this.fsCache.evictAll();
        }
    }

    private static class FsStateNode {

        @IntDef(value = {SOURCE_ITEM, DESTINATION_ITEM, STICKY, CACHED}, flag = true)
        public @interface StateFlag {
        }

        /**
         * A node that should be retained. This is usually a marker that there
         * is a difference between remote and locally virtualized remote
         * content, e.g. a createfile marker, or for items involved in
         * transfers.
         */
        public static final int STICKY = 1;

        /**
         * A node that is retrieved from a cached view and may no longer be accurate.
         */
        public static final int CACHED = 1 << 1;

        /**
         * A node which is the source location of a copy/move/rename operation.
         * Items (and the complete child hierarchy, if this is a directory)
         * are write-protected.
         */
        public static final int SOURCE_ITEM = 1 << 2;

        /**
         * A node which is the target location of a copy/move/rename operation.
         * Items (and the complete child hierarchy, if this is a directory)
         * are write-protected.
         */
        public static final int DESTINATION_ITEM = 1 << 3;

        /**
         * A local copy is currently open for writing.
         */
        public static final int LOCAL_WRITING = 1 << 4;

        private ListItem item;
        private int flags;

        public FsStateNode(ListItem item) {
            this.item = item;
        }

        public FsStateNode(ListItem item, int flags) {
            this.item = item;
            this.flags = flags;
        }

        public int getFlags() {
            return flags;
        }

        public void setFlags(@StateFlag int flags) {
            this.flags = flags;
        }
    }

    private static class PipeTransferThread extends Thread {
        private final InputStream is;
        private final OutputStream os;
        private final CancellationSignal cancellationSignal;

        private boolean checkOverrun = false;
        private long streamLength;

        public PipeTransferThread(InputStream srcIs, OutputStream dstOs, CancellationSignal signal, long streamLength) {
            this.is = srcIs;
            this.os = dstOs;
            this.cancellationSignal = signal;
            this.streamLength = streamLength;
        }

        public PipeTransferThread(InputStream is, OutputStream os, CancellationSignal cancellationSignal, boolean checkOverrun, long streamLength) {
            this.is = is;
            this.os = os;
            this.cancellationSignal = cancellationSignal;
            this.checkOverrun = checkOverrun;
            this.streamLength = streamLength;
        }

        @Override
        public void run() {
            byte[] buf = new byte[128 * 1024];
            int len;
            long lengthBarrier = this.streamLength;
            try {
                FLog.v(TAG, "Running Pipe Transfer...");
                while ((len = is.read(buf)) > 0) {
                    lengthBarrier -= len;
                    os.write(buf, 0, len);
                    if (checkOverrun && lengthBarrier < 0) {
                        FLog.d(TAG, "run: stopping transfer, end of stream");
                        break;
                    }
                    if (cancellationSignal.isCanceled()) {
                        FLog.d(TAG, "run: stopping transfer, cancelled");
                        break;
                    }
                }
                os.flush();
            } catch (IOException e) {
                cancellationSignal.cancel();
                if (e.getCause() instanceof ErrnoException && ((ErrnoException) e.getCause()).errno == OsConstants.EPIPE) {
                    FLog.v(TAG, "Pipe closed unexpectedly, Pipe Transfer stopped");
                } else {
                    FLog.e(TAG, "PipeTransferThread, cancelled=%s", cancellationSignal.isCanceled(), e);
                }
            } finally {
                try { is.close(); } catch (IOException ignored) { }
                try { os.close(); } catch (IOException ignored) { }
            }
        }
    }

    // TODO: Remove when target api >= 23, MatrixCursor.setExtras(...)
    private static final class ExtrasMatrixCursor extends MatrixCursor {

        private final Bundle extras;

        public ExtrasMatrixCursor(String[] columnNames, Bundle extras) {
            super(columnNames);
            this.extras = extras;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

    }

}
