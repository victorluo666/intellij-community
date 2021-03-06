// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.AppTopics;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.startup.ServiceNotReadyException;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.EditorHighlighterCache;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.openapi.vfs.newvfs.AsyncEventSupport;
import com.intellij.openapi.vfs.newvfs.ManagingFS;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry;
import com.intellij.openapi.vfs.newvfs.persistent.FlushingDaemon;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.psi.impl.PsiDocumentTransactionListener;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.impl.PsiTreeChangeEventImpl;
import com.intellij.psi.impl.cache.impl.id.PlatformIdTableBuilding;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.SerializationManagerEx;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Processor;
import com.intellij.util.SmartFMap;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.gist.GistManager;
import com.intellij.util.indexing.hash.FileContentHashIndex;
import com.intellij.util.indexing.hash.FileContentHashIndexExtension;
import com.intellij.util.indexing.hash.MergedInvertedIndex;
import com.intellij.util.indexing.provided.ProvidedIndexExtension;
import com.intellij.util.indexing.provided.ProvidedIndexExtensionLocator;
import com.intellij.util.indexing.snapshot.IndexedHashesSupport;
import com.intellij.util.indexing.snapshot.SnapshotSingleValueIndexStorage;
import com.intellij.util.indexing.snapshot.UpdatableSnapshotInputMappingIndex;
import com.intellij.util.io.storage.HeavyProcessLatch;
import com.intellij.util.messages.MessageBusConnection;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.*;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileBasedIndexImpl extends FileBasedIndexEx {
  private static final ThreadLocal<VirtualFile> ourIndexedFile = new ThreadLocal<>();
  private static final ThreadLocal<VirtualFile> ourFileToBeIndexed = new ThreadLocal<>();
  private static final ThreadLocal<DumbModeAccessType> ourDumbModeAccessType = new ThreadLocal<>();
  static final Logger LOG = Logger.getInstance(FileBasedIndexImpl.class);
  static final String CORRUPTION_MARKER_NAME = "corruption.marker";

  private volatile RegisteredIndexes myRegisteredIndexes;

  private final PerIndexDocumentVersionMap myLastIndexedDocStamps = new PerIndexDocumentVersionMap();

  // findExtensionOrFail is thread safe
  private final NotNullLazyValue<ChangedFilesCollector> myChangedFilesCollector = NotNullLazyValue.createValue(() -> {
    return AsyncEventSupport.EP_NAME.findExtensionOrFail(ChangedFilesCollector.class);
  });

  private final List<IndexableFileSet> myIndexableSets = ContainerUtil.createLockFreeCopyOnWriteList();
  private final Map<IndexableFileSet, Project> myIndexableSetToProjectMap = new THashMap<>();

  private final MessageBusConnection myConnection;
  private final FileDocumentManager myFileDocumentManager;

  private final Set<ID<?, ?>> myUpToDateIndicesForUnsavedOrTransactedDocuments = ContainerUtil.newConcurrentSet();
  private volatile SmartFMap<Document, PsiFile> myTransactionMap = SmartFMap.emptyMap();

  private final boolean myIsUnitTestMode;

  @Nullable
  private Runnable myShutDownTask;
  @Nullable
  private ScheduledFuture<?> myFlushingFuture;

  private final AtomicInteger myLocalModCount = new AtomicInteger();
  private final AtomicInteger myFilesModCount = new AtomicInteger();
  private final Set<Project> myProjectsBeingUpdated = ContainerUtil.newConcurrentSet();

  private final Lock myReadLock;
  final Lock myWriteLock;

  private IndexConfiguration getState() {
    return myRegisteredIndexes.getConfigurationState();
  }

  void dropRegisteredIndexes() {
    ScheduledFuture<?> flushingFuture = myFlushingFuture;
    LOG.assertTrue(flushingFuture == null || flushingFuture.isCancelled() || flushingFuture.isDone());
    LOG.assertTrue(myUpToDateIndicesForUnsavedOrTransactedDocuments.isEmpty());
    LOG.assertTrue(myProjectsBeingUpdated.isEmpty());
    LOG.assertTrue(!getChangedFilesCollector().isUpdateInProgress());
    LOG.assertTrue(myTransactionMap.isEmpty());

    myRegisteredIndexes = null;
  }

  public FileBasedIndexImpl() {
    ReadWriteLock lock = new ReentrantReadWriteLock();
    myReadLock = lock.readLock();
    myWriteLock = lock.writeLock();

    myFileDocumentManager = FileDocumentManager.getInstance();
    myIsUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();

    MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect();
    connection.subscribe(DynamicPluginListener.TOPIC, new FileBasedIndexPluginListener(this));

    connection.subscribe(PsiDocumentTransactionListener.TOPIC, new PsiDocumentTransactionListener() {
      @Override
      public void transactionStarted(@NotNull final Document doc, @NotNull final PsiFile file) {
        myTransactionMap = myTransactionMap.plus(doc, file);
        clearUpToDateIndexesForUnsavedOrTransactedDocs();
      }

      @Override
      public void transactionCompleted(@NotNull final Document doc, @NotNull final PsiFile file) {
        myTransactionMap = myTransactionMap.minus(doc);
      }
    });

    connection.subscribe(FileTypeManager.TOPIC, new FileBasedIndexFileTypeListener());

    connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerListener() {
      @Override
      public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
        cleanupMemoryStorage(true);
      }

      @Override
      public void unsavedDocumentsDropped() {
        cleanupMemoryStorage(false);
      }
    });

    connection.subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
      @Override
      public void appWillBeClosed(boolean isRestart) {
        if (!myRegisteredIndexes.areIndexesReady()) {
          ProgressManager.getInstance().run(new Task.Modal(null, "Preparing Indexes to Shutdown", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
              myRegisteredIndexes.waitUntilAllIndicesAreInitialized();
            }
          });
        }
      }
    });

    myConnection = connection;

    FileBasedIndexExtension.EXTENSION_POINT_NAME.addExtensionPointListener(new ExtensionPointListener<FileBasedIndexExtension<?, ?>>() {
      @Override
      public void extensionRemoved(@NotNull FileBasedIndexExtension<?, ?> extension, @NotNull PluginDescriptor pluginDescriptor) {
        ID.unloadId(extension.getName());
      }
    }, ApplicationManager.getApplication());

    initComponent();
  }

  void scheduleFullIndexesRescan(@NotNull Collection<ID<?, ?>> indexesToRebuild) {
    cleanupProcessedFlag();
    doClearIndices(id -> indexesToRebuild.contains(id));
    String rebuiltIndexesLog = indexesToRebuild.isEmpty()
      ? ""
      : "; indexes \" + indexesToRebuild + \" will be rebuild completely due to version change\" ";
    scheduleIndexRebuild("File type change" + rebuiltIndexesLog);
  }

  @VisibleForTesting
  void doClearIndices(@NotNull Predicate<ID<?, ?>> filter) {
    try {
      waitUntilIndicesAreInitialized();
    }
    catch (ProcessCanceledException e) {
      // will be rebuilt on re-scan
      return;
    }
    IndexingStamp.flushCaches();

    List<ID<?, ?>> clearedIndexes = new ArrayList<>();
    List<ID<?, ?>> survivedIndexes = new ArrayList<>();
    for (ID<?, ?> indexId : getState().getIndexIDs()) {
      if (filter.test(indexId)) {
        try {
          clearIndex(indexId);
        } catch (StorageException e) {
          LOG.info(e);
        } catch (Exception e) {
          LOG.error(e);
        }
        clearedIndexes.add(indexId);
      } else {
        survivedIndexes.add(indexId);
      }
    }

    LOG.info("indexes cleared: " + clearedIndexes.stream().map(id -> id.getName()).collect(Collectors.joining(", ")) + "\n" +
             "survived indexes: " + survivedIndexes.stream().map(id -> id.getName()).collect(Collectors.joining(", ")));
  }

  boolean processChangedFiles(@NotNull Project project, @NotNull Processor<? super VirtualFile> processor) {
    // avoid missing files when events are processed concurrently
    return Stream.concat(getChangedFilesCollector().getEventMerger().getChangedFiles(),
                         getChangedFilesCollector().getFilesToUpdate())
      .filter(filesToBeIndexedForProjectCondition(project))
      .distinct()
      .mapToInt(f -> processor.process(f) ? 1 : 0)
      .allMatch(success -> success == 1);
  }

  RegisteredIndexes getRegisteredIndexes() {
    return myRegisteredIndexes;
  }

  void setUpShutDownTask() {
    myShutDownTask = new MyShutDownTask();
    ShutDownTracker.getInstance().registerShutdownTask(myShutDownTask);
  }

  static class MyShutDownTask implements Runnable {
    @Override
    public void run() {
      ((FileBasedIndexImpl)FileBasedIndex.getInstance()).performShutdown(false);
    }
  }

  public static boolean isProjectOrWorkspaceFile(@NotNull VirtualFile file, @Nullable FileType fileType) {
    return ProjectCoreUtil.isProjectOrWorkspaceFile(file, fileType);
  }

  static boolean belongsToScope(VirtualFile file, VirtualFile restrictedTo, GlobalSearchScope filter) {
    if (!(file instanceof VirtualFileWithId) || !file.isValid()) {
      return false;
    }

    return (restrictedTo == null || Comparing.equal(file, restrictedTo)) &&
           (filter == null || restrictedTo != null || filter.accept(file));
  }

  @Override
  public void requestReindex(@NotNull final VirtualFile file) {
    GistManager.getInstance().invalidateData();
    // todo: this is the same vfs event handling sequence that is produces after events of FileContentUtilCore.reparseFiles
    // but it is more costly than current code, see IDEA-192192
    //myChangedFilesCollector.invalidateIndicesRecursively(file, false);
    //myChangedFilesCollector.buildIndicesForFileRecursively(file, false);
    ChangedFilesCollector changedFilesCollector = getChangedFilesCollector();
    changedFilesCollector.invalidateIndicesRecursively(file, true, changedFilesCollector.getEventMerger());
    if (myRegisteredIndexes.isInitialized()) {
      changedFilesCollector.ensureUpToDateAsync();
    }
  }

  void initComponent() {
    LOG.assertTrue(myRegisteredIndexes == null);
    myRegisteredIndexes = new RegisteredIndexes(myFileDocumentManager, this);
    myRegisteredIndexes.initializeIndexes(new FileBasedIndexDataInitialization(this));
  }

  @Override
  void waitUntilIndicesAreInitialized() {
    if (myRegisteredIndexes == null) {
      // interrupt all calculation while plugin reload
      throw new ProcessCanceledException();
    }
    myRegisteredIndexes.waitUntilIndicesAreInitialized();
  }

  /**
   * @return true if registered index requires full rebuild for some reason, e.g. is just created or corrupted
   */
  static <K, V> void registerIndexer(@NotNull final FileBasedIndexExtension<K, V> extension, @NotNull IndexConfiguration state,
                                     @NotNull IndicesRegistrationResult registrationStatusSink) throws IOException {
    final ID<K, V> name = extension.getName();
    final int version = extension.getVersion();

    final File versionFile = IndexInfrastructure.getVersionFile(name);

    if (IndexingStamp.versionDiffers(name, version)) {
      final boolean versionFileExisted = versionFile.exists();

      if (versionFileExisted) registrationStatusSink.registerIndexAsChanged(name);
      else registrationStatusSink.registerIndexAsInitiallyBuilt(name);

      if (extension.hasSnapshotMapping() && versionFileExisted) {
        FileUtil.deleteWithRenaming(IndexInfrastructure.getPersistentIndexRootDir(name));
      }
      File rootDir = IndexInfrastructure.getIndexRootDir(name);
      if (versionFileExisted) FileUtil.deleteWithRenaming(rootDir);
      IndexingStamp.rewriteVersion(name, version);
    } else {
      registrationStatusSink.registerIndexAsUptoDate(name);
    }

    initIndexStorage(extension, version, state);
  }

  private static <K, V> void initIndexStorage(@NotNull FileBasedIndexExtension<K, V> extension,
                                              int version,
                                              @NotNull IndexConfiguration state)
    throws IOException {
    VfsAwareIndexStorage<K, V> storage = null;
    final ID<K, V> name = extension.getName();
    boolean contentHashesEnumeratorOk = false;

    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        if (extension.hasSnapshotMapping()) {
          IndexedHashesSupport.initContentHashesEnumerator();
          contentHashesEnumeratorOk = true;
        }

        boolean createSnapshotStorage = VfsAwareMapReduceIndex.hasSnapshotMapping(extension) && extension instanceof SingleEntryFileBasedIndexExtension;
        if (createSnapshotStorage) {
          storage = new SnapshotSingleValueIndexStorage<K, V, FileContent>();
        }
        else {
          storage = new VfsAwareMapIndexStorage<>(
            IndexInfrastructure.getStorageFile(name).toPath(),
            extension.getKeyDescriptor(),
            extension.getValueExternalizer(),
            extension.getCacheSize(),
            extension.keyIsUniqueForIndexedFile(),
            extension.traceKeyHashToVirtualFileMapping()
          );
        }

        final InputFilter inputFilter = extension.getInputFilter();

        final Set<FileType> addedTypes;
        if (inputFilter instanceof FileBasedIndex.FileTypeSpecificInputFilter) {
          addedTypes = new THashSet<>();
          ((FileBasedIndex.FileTypeSpecificInputFilter)inputFilter).registerFileTypesUsedForIndexing(type -> {
            if (type != null) addedTypes.add(type);
          });
        }
        else {
          addedTypes = null;
        }

        UpdatableIndex<K, V, FileContent> index = createIndex(extension, new MemoryIndexStorage<>(storage, name));

        if (!(extension instanceof FileContentHashIndexExtension)) {
          List<ProvidedIndexExtension<K, V>> providedExtensions = ProvidedIndexExtensionLocator.findProvidedIndexExtensionFor(extension);
          if (!providedExtensions.isEmpty()) {
            Path[] paths = ContainerUtil.map2Array(providedExtensions, Path.class, ex -> ex.getIndexPath());
            FileContentHashIndex contentHashIndex = ((FileBasedIndexImpl)FileBasedIndex.getInstance()).getFileContentHashIndex(paths, state);

            index = MergedInvertedIndex.wrapWithProvidedIndex(providedExtensions, extension, index, contentHashIndex);
          }
        }

        state.registerIndex(name,
                            index,
                            file -> {
                              return file instanceof VirtualFileWithId && inputFilter.acceptInput(file) &&
                                     !GlobalIndexFilter.isExcludedFromIndexViaFilters(file, name);
                            },
                            version + GlobalIndexFilter.getFiltersVersion(name),
                            addedTypes);
        break;
      }
      catch (Exception e) {
        LOG.info(e);
        boolean instantiatedStorage = storage != null;
        try {
          if (storage != null) storage.close();
          storage = null;
        }
        catch (Exception ignored) {
        }

        FileUtil.deleteWithRenaming(IndexInfrastructure.getIndexRootDir(name));

        if (extension.hasSnapshotMapping() && (!contentHashesEnumeratorOk || instantiatedStorage)) {
          FileUtil.deleteWithRenaming(IndexInfrastructure.getPersistentIndexRootDir(name)); // todo there is possibility of corruption of storage and content hashes
        }
        IndexingStamp.rewriteVersion(name, version);
      }
    }
  }

  @NotNull
  private static <K, V> UpdatableIndex<K, V, FileContent> createIndex(@NotNull final FileBasedIndexExtension<K, V> extension,
                                                                      @NotNull final MemoryIndexStorage<K, V> storage)
    throws StorageException, IOException {
    return extension instanceof CustomImplementationFileBasedIndexExtension
           ? ((CustomImplementationFileBasedIndexExtension<K, V>)extension).createIndexImplementation(extension, storage)
           : new VfsAwareMapReduceIndex<>(extension, storage);
  }

  void performShutdown(boolean keepConnection) {
    RegisteredIndexes registeredIndexes = myRegisteredIndexes;
    if (registeredIndexes == null || !registeredIndexes.performShutdown()) {
      return; // already shut down
    }

    registeredIndexes.waitUntilAllIndicesAreInitialized();
    try {
      if (myShutDownTask != null) {
        ShutDownTracker.getInstance().unregisterShutdownTask(myShutDownTask);
      }
      if (myFlushingFuture != null) {
        myFlushingFuture.cancel(false);
        myFlushingFuture = null;
      }
    }
    finally {
      LOG.info("START INDEX SHUTDOWN");
      try {
        PersistentIndicesConfiguration.saveConfiguration();

        for (VirtualFile file : getChangedFilesCollector().getAllFilesToUpdate()) {
          if (!file.isValid()) {
            removeDataFromIndicesForFile(Math.abs(getIdMaskingNonIdBasedFile(file)), file);
          }
        }
        IndexingStamp.flushCaches();

        IndexConfiguration state = getState();
        for (ID<?, ?> indexId : state.getIndexIDs()) {
          try {
            final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
            assert index != null;
            if (!RebuildStatus.isOk(indexId)) {
              index.clear(); // if the index was scheduled for rebuild, only clean it
            }
            index.dispose();
          } catch (Throwable throwable) {
            LOG.info("Problem disposing " + indexId, throwable);
          }
        }

        IndexedHashesSupport.flushContentHashes();
        SharedIndicesData.flushData();
        if (!keepConnection) {
          myConnection.disconnect();
        }
      }
      catch (Throwable e) {
        LOG.error("Problems during index shutdown", e);
      }
      LOG.info("END INDEX SHUTDOWN");
    }
  }

  private void removeDataFromIndicesForFile(int fileId, VirtualFile file) {
    VirtualFile originalFile = file instanceof DeletedVirtualFileStub ? ((DeletedVirtualFileStub)file).getOriginalFile() : file;
    final List<ID<?, ?>> states = IndexingStamp.getNontrivialFileIndexedStates(fileId);

    if (!states.isEmpty()) {
      ProgressManager.getInstance().executeNonCancelableSection(() -> removeFileDataFromIndices(states, fileId, originalFile));
    }
  }

  private void removeFileDataFromIndices(@NotNull Collection<? extends ID<?, ?>> affectedIndices, int inputId, VirtualFile file) {
    // document diff can depend on previous value that will be removed
    removeTransientFileDataFromIndices(affectedIndices, inputId, file);

    Throwable unexpectedError = null;
    for (ID<?, ?> indexId : affectedIndices) {
      try {
        updateSingleIndex(indexId, null, inputId, null);
      }
      catch (ProcessCanceledException pce) {
        LOG.error(pce);
      }
      catch (Throwable e) {
        LOG.info(e);
        if (unexpectedError == null) {
          unexpectedError = e;
        }
      }
    }
    IndexingStamp.flushCache(inputId);

    if (unexpectedError != null) {
      LOG.error(unexpectedError);
    }
  }

  private void removeTransientFileDataFromIndices(Collection<? extends ID<?, ?>> indices, int inputId, VirtualFile file) {
    for (ID<?, ?> indexId : indices) {
      final UpdatableIndex<?, ?, FileContent> index = myRegisteredIndexes.getState().getIndex(indexId);
      assert index != null;
      index.removeTransientDataForFile(inputId);
    }

    Document document = myFileDocumentManager.getCachedDocument(file);
    if (document != null) {
      myLastIndexedDocStamps.clearForDocument(document);
      document.putUserData(ourFileContentKey, null);
    }

    clearUpToDateIndexesForUnsavedOrTransactedDocs();
  }

  void flushAllIndices(final long modCount) {
    if (HeavyProcessLatch.INSTANCE.isRunning()) {
      return;
    }
    IndexingStamp.flushCaches();
    IndexConfiguration state = getState();
    for (ID<?, ?> indexId : new ArrayList<>(state.getIndexIDs())) {
      if (HeavyProcessLatch.INSTANCE.isRunning() || modCount != myLocalModCount.get()) {
        return; // do not interfere with 'main' jobs
      }
      try {
        final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
        if (index != null) {
          index.flush();
        }
      }
      catch (Throwable e) {
        requestRebuild(indexId, e);
      }
    }

    IndexedHashesSupport.flushContentHashes();
    SharedIndicesData.flushData();
  }

  private static final ThreadLocal<Integer> myUpToDateCheckState = new ThreadLocal<>();

  public static <T,E extends Throwable> T disableUpToDateCheckIn(@NotNull ThrowableComputable<T, E> runnable) throws E {
    disableUpToDateCheckForCurrentThread();
    try {
      return runnable.compute();
    }
    finally {
      enableUpToDateCheckForCurrentThread();
    }
  }
  private static void disableUpToDateCheckForCurrentThread() {
    final Integer currentValue = myUpToDateCheckState.get();
    myUpToDateCheckState.set(currentValue == null ? 1 : currentValue.intValue() + 1);
  }

  private static void enableUpToDateCheckForCurrentThread() {
    final Integer currentValue = myUpToDateCheckState.get();
    if (currentValue != null) {
      final int newValue = currentValue.intValue() - 1;
      if (newValue != 0) {
        myUpToDateCheckState.set(newValue);
      }
      else {
        myUpToDateCheckState.remove();
      }
    }
  }

  static boolean isUpToDateCheckEnabled() {
    final Integer value = myUpToDateCheckState.get();
    return value == null || value.intValue() == 0;
  }

  private final ThreadLocal<Boolean> myReentrancyGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);

  @ApiStatus.Internal
  @ApiStatus.Experimental
  @Override
  public void ignoreDumbMode(@NotNull Runnable runnable,
                             @NotNull Project project,
                             @NotNull DumbModeAccessType dumbModeAccessType) {
    assert ApplicationManager.getApplication().isReadAccessAllowed();
    if (DumbService.isDumb(project) && FileBasedIndex.isIndexAccessDuringDumbModeEnabled()) {
      boolean setAccessType = true;
      DumbModeAccessType currentAccessType = ourDumbModeAccessType.get();
      if (currentAccessType != null) {
        if (currentAccessType == dumbModeAccessType) {
          setAccessType = false;
        } else {
          throw new AssertionError("Reentrant dumb mode ignorance. Current mode: " + currentAccessType + ", Requested mode: " + dumbModeAccessType);
        }
      }
      if (setAccessType) ourDumbModeAccessType.set(dumbModeAccessType);
      try {
        runnable.run();
      }
      finally {
        if (setAccessType) ourDumbModeAccessType.set(null);
      }
    } else {
      runnable.run();
    }
  }

  @Override
  <K> void ensureUpToDate(@NotNull final ID<K, ?> indexId,
                          @Nullable Project project,
                          @Nullable GlobalSearchScope filter,
                          @Nullable VirtualFile restrictedFile) {
    ProgressManager.checkCanceled();
    getChangedFilesCollector().ensureUpToDate();
    ApplicationManager.getApplication().assertReadAccessAllowed();

    NoAccessDuringPsiEvents.checkCallContext();

    if (!needsFileContentLoading(indexId)) {
      return; //indexed eagerly in foreground while building unindexed file list
    }
    if (filter == GlobalSearchScope.EMPTY_SCOPE) {
      return;
    }
    if (ourDumbModeAccessType.get() == null && ActionUtil.isDumbMode(project)) {
      handleDumbMode(project);
    }

    if (myReentrancyGuard.get().booleanValue()) {
      //assert false : "ensureUpToDate() is not reentrant!";
      return;
    }
    myReentrancyGuard.set(Boolean.TRUE);

    try {
      if (isUpToDateCheckEnabled()) {
        try {
          if (!RebuildStatus.isOk(indexId)) {
            throw new ServiceNotReadyException();
          }
          if (ourDumbModeAccessType.get() == null) {
            forceUpdate(project, filter, restrictedFile);
          }
          if (!areUnsavedDocumentsIndexed(indexId)) { // todo: check scope ?
            indexUnsavedDocuments(indexId, project, filter, restrictedFile);
          }
        }
        catch (RuntimeException e) {
          final Throwable cause = e.getCause();
          if (cause instanceof StorageException || cause instanceof IOException) {
            scheduleRebuild(indexId, e);
          }
          else {
            throw e;
          }
        }
      }
    }
    finally {
      myReentrancyGuard.set(Boolean.FALSE);
    }
  }

  private boolean areUnsavedDocumentsIndexed(@NotNull ID<?, ?> indexId) {
    return myUpToDateIndicesForUnsavedOrTransactedDocuments.contains(indexId);
  }

  private static void handleDumbMode(@Nullable Project project) {
    ProgressManager.checkCanceled();
    throw IndexNotReadyException.create(project == null ? null : DumbServiceImpl.getInstance(project).getDumbModeStartTrace());
  }

  private static final Key<SoftReference<ProjectIndexableFilesFilter>> ourProjectFilesSetKey = Key.create("projectFiles");

  @TestOnly
  public void cleanupForNextTest() {
    getChangedFilesCollector().ensureUpToDate();

    myTransactionMap = SmartFMap.emptyMap();
    IndexConfiguration state = getState();
    for (ID<?, ?> indexId : state.getIndexIDs()) {
      final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
      assert index != null;
      index.cleanupForNextTest();
    }
  }

  @ApiStatus.Internal
  public ChangedFilesCollector getChangedFilesCollector() {
    return myChangedFilesCollector.getValue();
  }

  void incrementFilesModCount() {
    myFilesModCount.incrementAndGet();
  }

  void filesUpdateStarted(Project project) {
    getChangedFilesCollector().ensureUpToDate();
    myProjectsBeingUpdated.add(project);
    incrementFilesModCount();
  }

  void filesUpdateFinished(@NotNull Project project) {
    myProjectsBeingUpdated.remove(project);
    incrementFilesModCount();
  }

  private final Lock myCalcIndexableFilesLock = new ReentrantLock();

  @Override
  @Nullable
  public ProjectIndexableFilesFilter projectIndexableFiles(@Nullable Project project) {
    if (project == null || project.isDefault() || getChangedFilesCollector().isUpdateInProgress()) return null;
    if (myProjectsBeingUpdated.contains(project)) return null;

    SoftReference<ProjectIndexableFilesFilter> reference = project.getUserData(ourProjectFilesSetKey);
    ProjectIndexableFilesFilter data = com.intellij.reference.SoftReference.dereference(reference);
    int currentFileModCount = myFilesModCount.get();
    if (data != null && data.getModificationCount() == currentFileModCount) return data;

    if (myCalcIndexableFilesLock.tryLock()) { // make best effort for calculating filter
      try {
        reference = project.getUserData(ourProjectFilesSetKey);
        data = com.intellij.reference.SoftReference.dereference(reference);
        if (data != null && data.getModificationCount() == currentFileModCount) {
          return data;
        }

        long start = System.currentTimeMillis();

        final TIntArrayList filesSet = new TIntArrayList();
        iterateIndexableFiles(fileOrDir -> {
          if (fileOrDir instanceof VirtualFileWithId) {
            filesSet.add(((VirtualFileWithId)fileOrDir).getId());
          }
          return true;
        }, project, SilentProgressIndicator.create());
        ProjectIndexableFilesFilter filter = new ProjectIndexableFilesFilter(filesSet, currentFileModCount);
        project.putUserData(ourProjectFilesSetKey, new SoftReference<>(filter));

        long finish = System.currentTimeMillis();
        LOG.debug(filesSet.size() + " files iterated in " + (finish - start) + " ms");

        return filter;
      }
      finally {
        myCalcIndexableFilesLock.unlock();
      }
    }
    return null; // ok, no filtering
  }

  @Nullable
  public static Throwable getCauseToRebuildIndex(@NotNull RuntimeException e) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      // avoid rebuilding index in tests since we do it synchronously in requestRebuild and we can have readAction at hand
      return null;
    }
    if (e instanceof ProcessCanceledException) return null;
    if (e instanceof IndexOutOfBoundsException) return e; // something wrong with direct byte buffer
    Throwable cause = e.getCause();
    if (cause instanceof StorageException || cause instanceof IOException ||
        cause instanceof IllegalArgumentException) return cause;
    return null;
  }

  private static void scheduleIndexRebuild(String reason) {
    LOG.info("scheduleIndexRebuild, reason: " + reason);
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      DumbService.getInstance(project).queueTask(new UnindexedFilesUpdater(project));
    }
  }

  void clearIndicesIfNecessary() {
    waitUntilIndicesAreInitialized();
    for (ID<?, ?> indexId : getState().getIndexIDs()) {
      try {
        RebuildStatus.clearIndexIfNecessary(indexId, getIndex(indexId)::clear);
      }
      catch (StorageException e) {
        requestRebuild(indexId);
        LOG.error(e);
      }
    }
  }

  void clearIndex(@NotNull final ID<?, ?> indexId) throws StorageException {
    advanceIndexVersion(indexId);

    final UpdatableIndex<?, ?, FileContent> index = myRegisteredIndexes.getState().getIndex(indexId);
    assert index != null : "Index with key " + indexId + " not found or not registered properly";
    index.clear();
  }

  private void advanceIndexVersion(ID<?, ?> indexId) {
    try {
      IndexingStamp.rewriteVersion(indexId, myRegisteredIndexes.getState().getIndexVersion(indexId));
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @NotNull
  private Set<Document> getUnsavedDocuments() {
    Document[] documents = myFileDocumentManager.getUnsavedDocuments();
    if (documents.length == 0) return Collections.emptySet();
    if (documents.length == 1) return Collections.singleton(documents[0]);
    return ContainerUtil.set(documents);
  }

  @NotNull
  private Set<Document> getTransactedDocuments() {
    return myTransactionMap.keySet();
  }

  private void indexUnsavedDocuments(@NotNull final ID<?, ?> indexId,
                                     @Nullable Project project,
                                     final GlobalSearchScope filter,
                                     final VirtualFile restrictedFile) {
    if (myUpToDateIndicesForUnsavedOrTransactedDocuments.contains(indexId)) {
      return; // no need to index unsaved docs        // todo: check scope ?
    }

    Collection<Document> documents = getUnsavedDocuments();
    boolean psiBasedIndex = myRegisteredIndexes.isPsiDependentIndex(indexId);
    if(psiBasedIndex) {
      Set<Document> transactedDocuments = getTransactedDocuments();
      if (documents.isEmpty()) {
        documents = transactedDocuments;
      }
      else if (!transactedDocuments.isEmpty()) {
        documents = new THashSet<>(documents);
        documents.addAll(transactedDocuments);
      }
      Document[] uncommittedDocuments = project != null ? PsiDocumentManager.getInstance(project).getUncommittedDocuments() : Document.EMPTY_ARRAY;
      if (uncommittedDocuments.length > 0) {
        List<Document> uncommittedDocumentsCollection = Arrays.asList(uncommittedDocuments);
        if (documents.isEmpty()) documents = uncommittedDocumentsCollection;
        else {
          if (!(documents instanceof THashSet)) documents = new THashSet<>(documents);

          documents.addAll(uncommittedDocumentsCollection);
        }
      }
    }

    if (!documents.isEmpty()) {
      Collection<Document> documentsToProcessForProject = ContainerUtil.filter(documents,
                                                                               document -> belongsToScope(myFileDocumentManager.getFile(document), restrictedFile, filter));

      if (!documentsToProcessForProject.isEmpty()) {
        UpdateTask<Document> task = myRegisteredIndexes.getUnsavedDataUpdateTask(indexId);
        assert task != null : "Task for unsaved data indexing was not initialized for index " + indexId;

        if(myStorageBufferingHandler.runUpdate(true, () -> task.processAll(documentsToProcessForProject, project)) &&
           documentsToProcessForProject.size() == documents.size() &&
           !hasActiveTransactions()
          ) {
          ProgressManager.checkCanceled();
          myUpToDateIndicesForUnsavedOrTransactedDocuments.add(indexId);
        }
      }
    }
  }

  private boolean hasActiveTransactions() {
    return !myTransactionMap.isEmpty();
  }


  private static final Key<WeakReference<FileContentImpl>> ourFileContentKey = Key.create("unsaved.document.index.content");

  // returns false if doc was not indexed because it is already up to date
  // return true if document was indexed
  // caller is responsible to ensure no concurrent same document processing
  void indexUnsavedDocument(@NotNull final Document document, @NotNull final ID<?, ?> requestedIndexId, final Project project,
                            @NotNull final VirtualFile vFile) {
    final PsiFile dominantContentFile = project == null ? null : findLatestKnownPsiForUncomittedDocument(document, project);

    final DocumentContent content;
    if (dominantContentFile != null && dominantContentFile.getViewProvider().getModificationStamp() != document.getModificationStamp()) {
      content = new PsiContent(document, dominantContentFile);
    }
    else {
      content = new AuthenticContent(document);
    }

    boolean psiBasedIndex = myRegisteredIndexes.isPsiDependentIndex(requestedIndexId);

    final long currentDocStamp = psiBasedIndex ? PsiDocumentManager.getInstance(project).getLastCommittedStamp(document) : content.getModificationStamp();

    final long previousDocStamp = myLastIndexedDocStamps.get(document, requestedIndexId);
    if (previousDocStamp == currentDocStamp) return;

    final CharSequence contentText = content.getText();
    getFileTypeManager().freezeFileTypeTemporarilyIn(vFile, () -> {
      if (getAffectedIndexCandidates(vFile).contains(requestedIndexId) &&
          getInputFilter(requestedIndexId).acceptInput(vFile)) {
        final int inputId = Math.abs(getFileId(vFile));

        if (!isTooLarge(vFile, contentText.length())) {
          // Reasonably attempt to use same file content when calculating indices as we can evaluate them several at once and store in file content
          WeakReference<FileContentImpl> previousContentRef = document.getUserData(ourFileContentKey);
          FileContentImpl previousContent = com.intellij.reference.SoftReference.dereference(previousContentRef);
          final FileContentImpl newFc;
          if (previousContent != null && previousContent.getStamp() == currentDocStamp) {
            newFc = previousContent;
          }
          else {
            newFc = new FileContentImpl(vFile, contentText, currentDocStamp);
            if (FileBasedIndex.ourSnapshotMappingsEnabled) {
              newFc.putUserData(UpdatableSnapshotInputMappingIndex.FORCE_IGNORE_MAPPING_INDEX_UPDATE, Boolean.TRUE);
            }
            document.putUserData(ourFileContentKey, new WeakReference<>(newFc));
          }

          initFileContent(newFc, project, dominantContentFile);
          newFc.ensureThreadSafeLighterAST();

          if (content instanceof AuthenticContent) {
            newFc.putUserData(PlatformIdTableBuilding.EDITOR_HIGHLIGHTER,
                              EditorHighlighterCache.getEditorHighlighterForCachesBuilding(document));
          }

          markFileIndexed(vFile);
          try {
            getIndex(requestedIndexId).update(inputId, newFc).compute();
          }
          finally {
            unmarkBeingIndexed();
            cleanFileContent(newFc, dominantContentFile);
          }
        }
        else { // effectively wipe the data from the indices
          getIndex(requestedIndexId).update(inputId, null).compute();
        }
      }

      long previousState = myLastIndexedDocStamps.set(document, requestedIndexId, currentDocStamp);
      assert previousState == previousDocStamp;
    });
  }

  private final StorageBufferingHandler myStorageBufferingHandler = new StorageBufferingHandler() {
    @NotNull
    @Override
    protected Stream<UpdatableIndex<?, ?, ?>> getIndexes() {
      IndexConfiguration state = getState();
      return state.getIndexIDs().stream().map(id -> state.getIndex(id));
    }
  };

  @ApiStatus.Internal
  public void runCleanupAction(@NotNull Runnable cleanupAction) {
    Computable<Boolean> updateComputable = () -> {
      cleanupAction.run();
      return true;
    };
    myStorageBufferingHandler.runUpdate(false, updateComputable);
    myStorageBufferingHandler.runUpdate(true, updateComputable);
  }

  void cleanupMemoryStorage(boolean skipPsiBasedIndices) {
    myLastIndexedDocStamps.clear();
    IndexConfiguration state = myRegisteredIndexes.getState();
    if (state == null) {
      // avoid waiting for end of indices initialization (IDEA-173382)
      // in memory content will appear on indexing (in read action) and here is event dispatch (write context)
      return;
    }
    for (ID<?, ?> indexId : state.getIndexIDs()) {
      if (skipPsiBasedIndices && myRegisteredIndexes.isPsiDependentIndex(indexId)) continue;
      final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
      assert index != null;
      index.cleanupMemoryStorage();
    }
  }

  @Override
  public void requestRebuild(@NotNull final ID<?, ?> indexId, final Throwable throwable) {
    if (!myRegisteredIndexes.isExtensionsDataLoaded()) {
      IndexInfrastructure.submitGenesisTask(() -> {
        waitUntilIndicesAreInitialized(); // should be always true here since the genesis pool is sequential
        doRequestRebuild(indexId, throwable);
        return null;
      });
    }
    else {
      doRequestRebuild(indexId, throwable);
    }
  }

  private void doRequestRebuild(@NotNull ID<?, ?> indexId, Throwable throwable) {
    cleanupProcessedFlag();
    if (!myRegisteredIndexes.isExtensionsDataLoaded()) reportUnexpectedAsyncInitState();

    if (RebuildStatus.requestRebuild(indexId)) {
      String message = "Rebuild requested for index " + indexId;
      Application app = ApplicationManager.getApplication();
      if (app.isUnitTestMode() && app.isReadAccessAllowed() && !app.isDispatchThread()) {
        // shouldn't happen in tests in general; so fail early with the exception that caused index to be rebuilt.
        // otherwise reindexing will fail anyway later, but with a much more cryptic assertion
        LOG.error(message, throwable);
      }
      else {
        LOG.info(message, throwable);
      }

      cleanupProcessedFlag();

      if (!myRegisteredIndexes.isInitialized()) return;
      advanceIndexVersion(indexId);

      Runnable rebuildRunnable = () -> scheduleIndexRebuild("checkRebuild");

      if (myIsUnitTestMode) {
        rebuildRunnable.run();
      }
      else {
        // we do invoke later since we can have read lock acquired
        app.invokeLater(rebuildRunnable, app.getDisposed());
      }
    }
  }

  private static void reportUnexpectedAsyncInitState() {
    LOG.error("Unexpected async indices initialization problem");
  }

  @Override
  public <K, V> UpdatableIndex<K, V, FileContent> getIndex(ID<K, V> indexId) {
    return getState().getIndex(indexId);
  }

  private InputFilter getInputFilter(@NotNull ID<?, ?> indexId) {
    if (!myRegisteredIndexes.isInitialized()) {
      // 1. early vfs event that needs invalidation
      // 2. pushers that do synchronous indexing for contentless indices
      waitUntilIndicesAreInitialized();
    }

    return getState().getInputFilter(indexId);
  }

  @NotNull
  Collection<VirtualFile> getFilesToUpdate(final Project project) {
    return ContainerUtil.filter(getChangedFilesCollector().getAllFilesToUpdate(), filesToBeIndexedForProjectCondition(project)::test);
  }

  @NotNull
  private Predicate<VirtualFile> filesToBeIndexedForProjectCondition(Project project) {
    return virtualFile -> {
        if (!virtualFile.isValid()) {
          return true;
        }

        for (IndexableFileSet set : myIndexableSets) {
          final Project proj = myIndexableSetToProjectMap.get(set);
          if (proj != null && !proj.equals(project)) {
            continue; // skip this set as associated with a different project
          }
          if (ReadAction.compute(() -> set.isInSet(virtualFile))) {
            return true;
          }
        }
        return false;
      };
  }

  public boolean isFileUpToDate(VirtualFile file) {
    return !getChangedFilesCollector().isScheduledForUpdate(file);
  }

  // caller is responsible to ensure no concurrent same document processing
  void processRefreshedFile(@Nullable Project project, @NotNull final com.intellij.ide.caches.FileContent fileContent) {
    // ProcessCanceledException will cause re-adding the file to processing list
    final VirtualFile file = fileContent.getVirtualFile();
    if (getChangedFilesCollector().isScheduledForUpdate(file)) {
      indexFileContent(project, fileContent);
    }
  }

  public void indexFileContent(@Nullable Project project, @NotNull com.intellij.ide.caches.FileContent content) {
    VirtualFile file = content.getVirtualFile();
    final int fileId = Math.abs(getIdMaskingNonIdBasedFile(file));

    boolean setIndexedStatus = true;
    try {
      // if file was scheduled for update due to vfs events then it is present in myFilesToUpdate
      // in this case we consider that current indexing (out of roots backed CacheUpdater) will cover its content
      if (file.isValid() && content.getTimeStamp() != file.getTimeStamp()) {
        content = new com.intellij.ide.caches.FileContent(file);
      }
      if (!file.isValid() || isTooLarge(file)) {
        removeDataFromIndicesForFile(fileId, file);
        if (file instanceof DeletedVirtualFileStub && ((DeletedVirtualFileStub)file).isResurrected()) {
          doIndexFileContent(project, new com.intellij.ide.caches.FileContent(((DeletedVirtualFileStub)file).getOriginalFile()));
        }
      }
      else {
        setIndexedStatus = doIndexFileContent(project, content);
      }
    }
    finally {
      IndexingStamp.flushCache(fileId);
    }

    getChangedFilesCollector().removeFileIdFromFilesScheduledForUpdate(fileId);
    if (file instanceof VirtualFileSystemEntry && setIndexedStatus) ((VirtualFileSystemEntry)file).setFileIndexed(true);
  }

  private boolean doIndexFileContent(@Nullable Project project, @NotNull final com.intellij.ide.caches.FileContent content) {
    final VirtualFile file = content.getVirtualFile();
    Ref<Boolean> setIndexedStatus = Ref.create(Boolean.TRUE);
    getFileTypeManager().freezeFileTypeTemporarilyIn(file, () -> {

      FileContentImpl fc = new FileContentImpl(file, content.getBytesOrEmptyArray());
      PsiFile psiFile = content.getUserData(IndexingDataKeys.PSI_FILE);
      initFileContent(fc, project == null ? ProjectUtil.guessProjectForFile(file) : project, psiFile);

      if (FileBasedIndex.ourSnapshotMappingsEnabled) {
        IndexedHashesSupport.initIndexedHash(fc);
      }

      int inputId = Math.abs(getFileId(file));
      Set<ID<?, ?>> currentIndexedStates = new THashSet<>(IndexingStamp.getNontrivialFileIndexedStates(inputId));

      final List<ID<?, ?>> affectedIndexCandidates = getAffectedIndexCandidates(file);
      //noinspection ForLoopReplaceableByForEach
      for (int i = 0, size = affectedIndexCandidates.size(); i < size; ++i) {
        final ID<?, ?> indexId = affectedIndexCandidates.get(i);
        if (shouldIndexFile(fc, indexId)) {
          try {
            ProgressManager.checkCanceled();
            if (!updateSingleIndex(indexId, file, inputId, fc)) {
              setIndexedStatus.set(Boolean.FALSE);
            }
            currentIndexedStates.remove(indexId);
          }
          catch (ProcessCanceledException e) {
            cleanFileContent(fc, psiFile);
            throw e;
          }
        }
      }

      if (psiFile != null) {
        psiFile.putUserData(PsiFileImpl.BUILDING_STUB, null);
      }

      for(ID<?, ?> indexId:currentIndexedStates) {
        if(!getIndex(indexId).isIndexedStateForFile(inputId, fc)) {
          ProgressManager.checkCanceled();
          if (!updateSingleIndex(indexId, file, inputId, null)) {
            setIndexedStatus.set(Boolean.FALSE);
          }
        }
      }
    });
    return setIndexedStatus.get();
  }

  public boolean isIndexingCandidate(@NotNull VirtualFile file, @NotNull ID<?, ?> indexId) {
    return !isTooLarge(file) && getAffectedIndexCandidates(file).contains(indexId);
  }

  @NotNull
  List<ID<?, ?>> getAffectedIndexCandidates(@NotNull VirtualFile file) {
    if (file.isDirectory()) {
      return isProjectOrWorkspaceFile(file, null) ? Collections.emptyList() : myRegisteredIndexes.getIndicesForDirectories();
    }
    FileType fileType = file.getFileType();
    if(isProjectOrWorkspaceFile(file, fileType)) return Collections.emptyList();

    return getState().getFileTypesForIndex(fileType);
  }

  private static void cleanFileContent(@NotNull FileContentImpl fc, PsiFile psiFile) {
    if (psiFile != null) psiFile.putUserData(PsiFileImpl.BUILDING_STUB, null);
    fc.putUserData(IndexingDataKeys.PSI_FILE, null);
  }

  private static void initFileContent(@NotNull FileContentImpl fc, Project project, PsiFile psiFile) {
    if (psiFile != null) {
      psiFile.putUserData(PsiFileImpl.BUILDING_STUB, true);
      fc.putUserData(IndexingDataKeys.PSI_FILE, psiFile);
    }

    fc.setProject(project);
  }

  boolean updateSingleIndex(@NotNull ID<?, ?> indexId, @Nullable VirtualFile file, int inputId, @Nullable FileContent currentFC) {
    if (!myRegisteredIndexes.isExtensionsDataLoaded()) reportUnexpectedAsyncInitState();
    if (!RebuildStatus.isOk(indexId) && !myIsUnitTestMode) {
      return false; // the index is scheduled for rebuild, no need to update
    }
    myLocalModCount.incrementAndGet();

    final UpdatableIndex<?, ?, FileContent> index = getIndex(indexId);
    assert index != null;

    markFileIndexed(file);
    boolean updateCalculated = false;
    try {
      // important: no hard referencing currentFC to avoid OOME, the methods introduced for this purpose!
      // important: update is called out of try since possible indexer extension is HANDLED as single file fail / restart indexing policy
      final Computable<Boolean> update = index.update(inputId, currentFC);
      updateCalculated = true;

      runIndexUpdate(indexId, update, currentFC, inputId);
    }
    catch (RuntimeException exception) {
      Throwable causeToRebuildIndex = getCauseToRebuildIndex(exception);
      if (causeToRebuildIndex != null && (updateCalculated || causeToRebuildIndex instanceof IOException)) {
        requestRebuild(indexId, exception);
        return false;
      }
      throw exception;
    }
    finally {
      unmarkBeingIndexed();
    }
    return true;
  }

  private static void markFileIndexed(@Nullable VirtualFile file) {
    if (ourIndexedFile.get() != null) throw new AssertionError("Reentrant indexing");
    ourIndexedFile.set(file);
  }

  private static void unmarkBeingIndexed() {
    ourIndexedFile.remove();
  }

  @Override
  public VirtualFile getFileBeingCurrentlyIndexed() {
    VirtualFile file = ourIndexedFile.get();
    return file != null ? file : ourFileToBeIndexed.get();
  }

  @Override
  public DumbModeAccessType getCurrentDumbModeAccessType() {
    return ourDumbModeAccessType.get();
  }

  private class VirtualFileUpdateTask extends UpdateTask<VirtualFile> {
    @Override
    void doProcess(VirtualFile item, Project project) {
      processRefreshedFile(project, new com.intellij.ide.caches.FileContent(item));
    }
  }

  private final VirtualFileUpdateTask myForceUpdateTask = new VirtualFileUpdateTask();
  private volatile long myLastOtherProjectInclusionStamp;

  private void forceUpdate(@Nullable Project project, @Nullable final GlobalSearchScope filter, @Nullable final VirtualFile restrictedTo) {
    Collection<VirtualFile> allFilesToUpdate = getChangedFilesCollector().getAllFilesToUpdate();

    if (!allFilesToUpdate.isEmpty()) {
      boolean includeFilesFromOtherProjects = restrictedTo == null && System.currentTimeMillis() - myLastOtherProjectInclusionStamp > 100;
      List<VirtualFile> virtualFilesToBeUpdatedForProject = ContainerUtil.filter(
        allFilesToUpdate,
        new ProjectFilesCondition(projectIndexableFiles(project), filter, restrictedTo,
                                  includeFilesFromOtherProjects)
      );

      if (!virtualFilesToBeUpdatedForProject.isEmpty()) {
        myForceUpdateTask.processAll(virtualFilesToBeUpdatedForProject, project);
      }
      if (includeFilesFromOtherProjects) {
        myLastOtherProjectInclusionStamp = System.currentTimeMillis();
      }
    }
  }

  private void runIndexUpdate(@NotNull ID<?, ?> indexId,
                              @NotNull Computable<Boolean> update,
                              @Nullable IndexedFile file,
                              int inputId) {
    if (myStorageBufferingHandler.runUpdate(false, update)) {
      ConcurrencyUtil.withLock(myReadLock, () -> {
        UpdatableIndex<?, ?, FileContent> index = getIndex(indexId);
        if (file != null) {
          index.setIndexedStateForFile(inputId, file);
        }
        else {
          index.resetIndexedStateForFile(inputId);
        }
      });
    }
  }

  boolean needsFileContentLoading(@NotNull ID<?, ?> indexId) {
    return !myRegisteredIndexes.isNotRequiringContentIndex(indexId);
  }

  @Nullable IndexableFileSet getIndexableSetForFile(VirtualFile file) {
    for (IndexableFileSet set : myIndexableSets) {
      if (set.isInSet(file)) {
        return set;
      }
    }
    return null;
  }

  @NotNull List<IndexableFileSet> getIndexableSets() {
    return myIndexableSets;
  }

  void doTransientStateChangeForFile(int fileId, @NotNull VirtualFile file) {
    waitUntilIndicesAreInitialized();
    if (!clearUpToDateStateForPsiIndicesOfUnsavedDocuments(file, IndexingStamp.getNontrivialFileIndexedStates(fileId))) {
      // change in persistent file
      clearUpToDateStateForPsiIndicesOfVirtualFile(file);
    }
  }

  void doInvalidateIndicesForFile(int fileId, @NotNull VirtualFile file, boolean contentChanged) {
    waitUntilIndicesAreInitialized();
    cleanProcessedFlag(file);

    List<ID<?, ?>> nontrivialFileIndexedStates = IndexingStamp.getNontrivialFileIndexedStates(fileId);
    Collection<ID<?, ?>> fileIndexedStatesToUpdate = ContainerUtil.intersection(nontrivialFileIndexedStates, myRegisteredIndexes.getRequiringContentIndices());

    // transient index value can depend on disk value because former is diff to latter
    // it doesn't matter content hanged or not: indices might depend on file name too
    removeTransientFileDataFromIndices(nontrivialFileIndexedStates, fileId, file);

    if (contentChanged) {
      // only mark the file as outdated, reindex will be done lazily
      if (!fileIndexedStatesToUpdate.isEmpty()) {
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0, size = nontrivialFileIndexedStates.size(); i < size; ++i) {
          final ID<?, ?> indexId = nontrivialFileIndexedStates.get(i);
          if (needsFileContentLoading(indexId)) {
            getIndex(indexId).resetIndexedStateForFile(fileId);
          }
        }

        // the file is for sure not a dir and it was previously indexed by at least one index
        if (file.isValid()) {
          if (!isTooLarge(file)) {
            getChangedFilesCollector().scheduleForUpdate(file);
          }
          else getChangedFilesCollector().scheduleForUpdate(new DeletedVirtualFileStub((VirtualFileWithId)file));
        }
        else {
          LOG.info("Unexpected state in update:" + file);
        }
      }
    }
    else { // file was removed
      for (ID<?, ?> indexId : nontrivialFileIndexedStates) {
        if (myRegisteredIndexes.isNotRequiringContentIndex(indexId)) {
          updateSingleIndex(indexId, null, fileId, null);
        }
      }
      if (!fileIndexedStatesToUpdate.isEmpty()) {
        // its data should be (lazily) wiped for every index
        getChangedFilesCollector().scheduleForUpdate(new DeletedVirtualFileStub((VirtualFileWithId)file));
      }
      else {
        getChangedFilesCollector().removeScheduledFileFromUpdate(file); // no need to update it anymore
      }
    }
  }

  void scheduleFileForIndexing(int fileId, @NotNull VirtualFile file, boolean contentChange) {
    // handle 'content-less' indices separately
    boolean fileIsDirectory = file.isDirectory();

    if (!contentChange) {
      FileContent fileContent = null;
      for (ID<?, ?> indexId : getContentLessIndexes(fileIsDirectory)) {
        if (getInputFilter(indexId).acceptInput(file)) {
          if (fileContent == null) {
            fileContent = new IndexedFileWrapper(new IndexedFileImpl(file, null));
          }
          updateSingleIndex(indexId, file, fileId, fileContent);
        }
      }
    }
    // For 'normal indices' schedule the file for update and reset stamps for all affected indices (there
    // can be client that used indices between before and after events, in such case indices are up to date due to force update
    // with old content)
    if (!fileIsDirectory) {
      if (!file.isValid() || isTooLarge(file)) {
        // large file might be scheduled for update in before event when its size was not large
        getChangedFilesCollector().removeScheduledFileFromUpdate(file);
      }
      else {
        ourFileToBeIndexed.set(file);
        try {
          getFileTypeManager().freezeFileTypeTemporarilyIn(file, () -> {
            final List<ID<?, ?>> candidates = getAffectedIndexCandidates(file);

            boolean scheduleForUpdate = false;

            //noinspection ForLoopReplaceableByForEach
            for (int i = 0, size = candidates.size(); i < size; ++i) {
              final ID<?, ?> indexId = candidates.get(i);
              if (needsFileContentLoading(indexId) && getInputFilter(indexId).acceptInput(file)) {
                getIndex(indexId).resetIndexedStateForFile(fileId);
                scheduleForUpdate = true;
              }
            }

            if (scheduleForUpdate) {
              IndexingStamp.flushCache(fileId);
              getChangedFilesCollector().scheduleForUpdate(file);
            }
            else if (file instanceof VirtualFileSystemEntry) {
              ((VirtualFileSystemEntry)file).setFileIndexed(true);
            }
          });
        } finally {
          ourFileToBeIndexed.remove();
        }
      }
    }
  }

  @NotNull
  Collection<ID<?, ?>> getContentLessIndexes(boolean isDirectory) {
    return isDirectory ? myRegisteredIndexes.getIndicesForDirectories() : myRegisteredIndexes.getNotRequiringContentIndices();
  }

  static FileTypeManagerImpl getFileTypeManager() {
    return (FileTypeManagerImpl)FileTypeManager.getInstance();
  }

  private boolean clearUpToDateStateForPsiIndicesOfUnsavedDocuments(@NotNull VirtualFile file, Collection<? extends ID<?, ?>> affectedIndices) {
    clearUpToDateIndexesForUnsavedOrTransactedDocs();

    Document document = myFileDocumentManager.getCachedDocument(file);

    if (document != null && myFileDocumentManager.isDocumentUnsaved(document)) {   // will be reindexed in indexUnsavedDocuments
      myLastIndexedDocStamps.clearForDocument(document); // Q: non psi indices
      document.putUserData(ourFileContentKey, null);

      return true;
    }

    removeTransientFileDataFromIndices(ContainerUtil.intersection(affectedIndices, myRegisteredIndexes.getPsiDependentIndices()), getFileId(file), file);
    return false;
  }

  void clearUpToDateIndexesForUnsavedOrTransactedDocs() {
    if (!myUpToDateIndicesForUnsavedOrTransactedDocuments.isEmpty()) {
      myUpToDateIndicesForUnsavedOrTransactedDocuments.clear();
    }
  }

  static int getIdMaskingNonIdBasedFile(@NotNull VirtualFile file) {
    return file instanceof VirtualFileWithId ?((VirtualFileWithId)file).getId() : IndexingStamp.INVALID_FILE_ID;
  }

  boolean shouldIndexFile(@NotNull IndexedFile file, @NotNull ID<?, ?> indexId) {
    VirtualFile virtualFile = file.getFile();
    return getInputFilter(indexId).acceptInput(virtualFile) &&
            (isMock(virtualFile) || !getIndex(indexId).isIndexedStateForFile(((NewVirtualFile) virtualFile).getId(), file));
  }

  static boolean isMock(final VirtualFile file) {
    return !(file instanceof NewVirtualFile);
  }

  boolean isTooLarge(@NotNull VirtualFile file) {
    if (SingleRootFileViewProvider.isTooLargeForIntelligence(file)) {
      return !myRegisteredIndexes.skipLimitCheck(file) || SingleRootFileViewProvider.isTooLargeForContentLoading(file);
    }
    return false;
  }

  private boolean isTooLarge(@NotNull VirtualFile file, long contentSize) {
    if (SingleRootFileViewProvider.isTooLargeForIntelligence(file, contentSize)) {
      return !myRegisteredIndexes.skipLimitCheck(file) || SingleRootFileViewProvider.isTooLargeForContentLoading(file, contentSize);
    }
    return false;
  }

  @Override
  public void registerIndexableSet(@NotNull IndexableFileSet set, @Nullable Project project) {
    myIndexableSets.add(set);
    myIndexableSetToProjectMap.put(set, project);
    if (project != null) {
      ((PsiManagerImpl)PsiManager.getInstance(project)).addTreeChangePreprocessor(event -> {
        if (event.isGenericChange() &&
            event.getCode() == PsiTreeChangeEventImpl.PsiEventType.CHILDREN_CHANGED) {
          PsiFile file = event.getFile();

          if (file != null) {
            VirtualFile virtualFile = file.getVirtualFile();
            if (virtualFile instanceof VirtualFileWithId) {
              getChangedFilesCollector().getEventMerger().recordTransientStateChangeEvent(virtualFile);
            }
          }
        }
      });
    }
  }

  private void clearUpToDateStateForPsiIndicesOfVirtualFile(VirtualFile virtualFile) {
    if (virtualFile instanceof VirtualFileWithId) {
      int fileId = ((VirtualFileWithId)virtualFile).getId();
      boolean wasIndexed = false;
      List<ID<?, ?>> candidates = getAffectedIndexCandidates(virtualFile);
      for (ID<?, ?> candidate : candidates) {
        if (myRegisteredIndexes.isPsiDependentIndex(candidate)) {
          if(getInputFilter(candidate).acceptInput(virtualFile)) {
            getIndex(candidate).resetIndexedStateForFile(fileId);
            wasIndexed = true;
          }
        }
      }
      if (wasIndexed) {
        getChangedFilesCollector().scheduleForUpdate(virtualFile);
        IndexingStamp.flushCache(fileId);
      }
    }
  }

  @Override
  public void removeIndexableSet(@NotNull IndexableFileSet set) {
    if (!myIndexableSetToProjectMap.containsKey(set)) return;
    myIndexableSets.remove(set);
    myIndexableSetToProjectMap.remove(set);

    ChangedFilesCollector changedFilesCollector = getChangedFilesCollector();
    for (VirtualFile file : changedFilesCollector.getAllFilesToUpdate()) {
      final int fileId = Math.abs(getIdMaskingNonIdBasedFile(file));
      if (!file.isValid()) {
        removeDataFromIndicesForFile(fileId, file);
        changedFilesCollector.removeFileIdFromFilesScheduledForUpdate(fileId);
      }
      else if (getIndexableSetForFile(file) == null) { // todo remove data from indices for removed
        changedFilesCollector.removeFileIdFromFilesScheduledForUpdate(fileId);
      }
    }

    IndexingStamp.flushCaches();
  }

  @Override
  public VirtualFile findFileById(Project project, int id) {
    return IndexInfrastructure.findFileById((PersistentFS)ManagingFS.getInstance(), id);
  }

  @Nullable
  private static PsiFile findLatestKnownPsiForUncomittedDocument(@NotNull Document doc, @NotNull Project project) {
    return PsiDocumentManager.getInstance(project).getCachedPsiFile(doc);
  }

  static void cleanupProcessedFlag() {
    final VirtualFile[] roots = ManagingFS.getInstance().getRoots();
    for (VirtualFile root : roots) {
      cleanProcessedFlag(root);
    }
  }

  static void cleanProcessedFlag(@NotNull final VirtualFile file) {
    if (!(file instanceof VirtualFileSystemEntry)) return;

    final VirtualFileSystemEntry nvf = (VirtualFileSystemEntry)file;
    nvf.setFileIndexed(false);
    if (file.isDirectory()) {
      for (VirtualFile child : nvf.getCachedChildren()) {
        cleanProcessedFlag(child);
      }
    }
  }

  void setUpFlusher() {
    myFlushingFuture = FlushingDaemon.everyFiveSeconds(new Runnable() {
      private final SerializationManagerEx mySerializationManager = SerializationManagerEx.getInstanceEx();
      private int lastModCount;

      @Override
      public void run() {
        mySerializationManager.flushNameStorage();

        int currentModCount = myLocalModCount.get();
        if (lastModCount == currentModCount) {
          flushAllIndices(lastModCount);
        }
        lastModCount = currentModCount;
      }
    });
  }

  @Override
  public void invalidateCaches() {
    File indexRoot = PathManager.getIndexRoot();
    LOG.info("Requesting explicit indices invalidation");
    try {
      final File corruptionMarker = new File(indexRoot, CORRUPTION_MARKER_NAME);
      //noinspection IOResourceOpenedButNotSafelyClosed
      new FileOutputStream(corruptionMarker).close();
    }
    catch (Throwable ignore) {
    }
  }

  @Override
  @ApiStatus.Internal
  @NotNull
  public IntPredicate getAccessibleFileIdFilter(@Nullable Project project) {
    boolean dumb = ActionUtil.isDumbMode(project);
    if (!dumb) return f -> true;

    DumbModeAccessType dumbModeAccessType = getCurrentDumbModeAccessType();
    if (dumbModeAccessType == null) {
      //throw new IllegalStateException("index access is not allowed in dumb mode");
      return f -> true;
    }

    if (dumbModeAccessType == DumbModeAccessType.RAW_INDEX_DATA_ACCEPTABLE) return f -> true;

    assert dumbModeAccessType == DumbModeAccessType.RELIABLE_DATA_ONLY;
    return fileId -> !getChangedFilesCollector().containsFileId(fileId);
  }

  public synchronized FileContentHashIndex getFileContentHashIndex(Path @Nullable [] enumeratorPaths, @NotNull IndexConfiguration state) {
    UpdatableIndex<Long, Void, FileContent> index = state.getIndex(FileContentHashIndexExtension.HASH_INDEX_ID);
    if (index == null) {
      LOG.assertTrue(enumeratorPaths != null);
      IndicesRegistrationResult registrationResult = new IndicesRegistrationResult();
      try {
        registerIndexer(FileContentHashIndexExtension.create(enumeratorPaths, ApplicationManager.getApplication()), state, registrationResult);
        registrationResult.logChangedAndFullyBuiltIndices(LOG, "Version was changed for:", "Index is to be rebuilt:");
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else return (FileContentHashIndex)index;
    return (FileContentHashIndex)state.getIndex(FileContentHashIndexExtension.HASH_INDEX_ID);
  }

  private static final boolean INDICES_ARE_PSI_DEPENDENT_BY_DEFAULT = SystemProperties.getBooleanProperty("idea.indices.psi.dependent.default", true);
  public static boolean isPsiDependentIndex(@NotNull IndexExtension<?, ?, ?> extension) {
    if (INDICES_ARE_PSI_DEPENDENT_BY_DEFAULT) {
      return extension instanceof FileBasedIndexExtension &&
             ((FileBasedIndexExtension<?, ?>)extension).dependsOnFileContent() &&
             !(extension instanceof DocumentChangeDependentIndex);
    }
    else {
      return extension instanceof PsiDependentIndex;
    }
  }
}