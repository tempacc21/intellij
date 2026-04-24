/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.sync.projectstructure;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.prefetch.FetchExecutor;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.sync.projectview.ImportRoots;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Directory structure representation used by {@link ContentEntryEditor}.
 *
 * <p>The purpose of this class is to pull out all file system operations out of the project
 * structure commit step, as this step locks the UI.
 */
public class DirectoryStructure {

  private static final Logger logger = Logger.getInstance(DirectoryStructure.class);

  final ImmutableMap<WorkspacePath, DirectoryStructure> directories;

  private static final DirectoryStructure EMPTY = new DirectoryStructure(ImmutableMap.of());

  private DirectoryStructure(ImmutableMap<WorkspacePath, DirectoryStructure> directories) {
    this.directories = directories;
  }

  public static ListenableFuture<DirectoryStructure> getRootDirectoryStructure(
      Project project, WorkspaceRoot workspaceRoot, ProjectViewSet projectViewSet) {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    try {
      ListenableFuture<DirectoryStructure> future =
          FetchExecutor.EXECUTOR.submit(
              () ->
                  computeRootDirectoryStructure(project, workspaceRoot, projectViewSet, cancelled));
      future.addListener(() -> cancelled.set(true), MoreExecutors.directExecutor());
      return future;

    } catch (Throwable e) {
      cancelled.set(true);
      return Futures.immediateFailedFuture(e);
    }
  }

  private static DirectoryStructure computeRootDirectoryStructure(
      Project project,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      AtomicBoolean cancelled)
      throws ExecutionException, InterruptedException {
    FileOperationProvider fileOperationProvider = FileOperationProvider.getInstance();
    ImportRoots importRoots =
        ImportRoots.builder(workspaceRoot, Blaze.getBuildSystemName(project))
            .add(projectViewSet)
            .build();
    Collection<WorkspacePath> rootDirectories = importRoots.rootDirectories();
    Set<WorkspacePath> excludeDirectories = importRoots.excludeDirectories();
    logger.info("[DirectoryStructure] Starting walk. rootDirectories=" + rootDirectories.size()
        + " excludeDirectories=" + excludeDirectories.size()
        + " roots=" + rootDirectories);
    long startMs = System.currentTimeMillis();
    AtomicInteger walkedCount = new AtomicInteger(0);
    AtomicInteger skippedSymlinkCount = new AtomicInteger(0);
    // Track canonical paths already visited to prevent cycles from pnpm workspace symlinks.
    Set<String> visitedCanonicalPaths = ConcurrentHashMap.newKeySet();
    String workspaceCanonicalPath;
    try {
      workspaceCanonicalPath = workspaceRoot.directory().getCanonicalPath();
    } catch (IOException e) {
      workspaceCanonicalPath = workspaceRoot.directory().getAbsolutePath();
    }
    List<ListenableFuture<PathStructurePair>> futures =
        Lists.newArrayListWithExpectedSize(rootDirectories.size());
    for (WorkspacePath rootDirectory : rootDirectories) {
      futures.add(
          walkDirectoryStructure(
              workspaceRoot,
              excludeDirectories,
              fileOperationProvider,
              FetchExecutor.EXECUTOR,
              rootDirectory,
              cancelled,
              walkedCount,
              skippedSymlinkCount,
              visitedCanonicalPaths,
              workspaceCanonicalPath));
    }
    ImmutableMap.Builder<WorkspacePath, DirectoryStructure> result = ImmutableMap.builder();
    for (PathStructurePair pair : Futures.allAsList(futures).get()) {
      if (pair != null) {
        result.put(pair.path, pair.directoryStructure);
      }
    }
    long elapsedMs = System.currentTimeMillis() - startMs;
    logger.info("[DirectoryStructure] Walk complete in " + elapsedMs + "ms."
        + " directoriesWalked=" + walkedCount.get()
        + " symlinkSkipped=" + skippedSymlinkCount.get());
    return new DirectoryStructure(result.build());
  }

  private static ListenableFuture<PathStructurePair> walkDirectoryStructure(
      WorkspaceRoot workspaceRoot,
      Set<WorkspacePath> excludeDirectories,
      FileOperationProvider fileOperationProvider,
      ListeningExecutorService executorService,
      WorkspacePath workspacePath,
      AtomicBoolean cancelled,
      AtomicInteger walkedCount,
      AtomicInteger skippedSymlinkCount,
      Set<String> visitedCanonicalPaths,
      String workspaceCanonicalPath) {
    if (cancelled.get() || excludeDirectories.contains(workspacePath)) {
      return Futures.immediateFuture(null);
    }
    File file = workspaceRoot.fileForPath(workspacePath);
    if (!fileOperationProvider.isDirectory(file)) {
      return Futures.immediateFuture(null);
    }
    if (Files.isSymbolicLink(file.toPath())) {
      try {
        String canonicalPath = file.getCanonicalPath();
        // Skip symlinks pointing outside the workspace (e.g. bazel-bin, bazel-out, bazel-<repo>).
        if (!canonicalPath.startsWith(workspaceCanonicalPath)) {
          logger.info("[DirectoryStructure] Skipping external symlink: " + workspacePath + " -> " + canonicalPath);
          skippedSymlinkCount.incrementAndGet();
          return Futures.immediateFuture(null);
        }
        // Already visited via another path — register as a leaf so navigation works,
        // but don't recurse (prevents infinite cycles from pnpm workspace symlinks).
        if (!visitedCanonicalPaths.add(canonicalPath)) {
          logger.info("[DirectoryStructure] Already visited (cycle): " + workspacePath + " -> " + canonicalPath);
          skippedSymlinkCount.incrementAndGet();
          return Futures.immediateFuture(new PathStructurePair(workspacePath, EMPTY));
        }
        logger.info("[DirectoryStructure] Following internal symlink: " + workspacePath + " -> " + canonicalPath);
      } catch (IOException e) {
        logger.info("[DirectoryStructure] IOException resolving symlink: " + workspacePath + " " + e.getMessage());
        return Futures.immediateFuture(null);
      }
    }
    walkedCount.incrementAndGet();
    ListenableFuture<File[]> childrenFuture =
        executorService.submit(() -> fileOperationProvider.listFiles(file));
    return Futures.transformAsync(
        childrenFuture,
        children -> {
          if (cancelled.get() || children == null) {
            return Futures.immediateFuture(null);
          }
          List<ListenableFuture<PathStructurePair>> futures =
              Lists.newArrayListWithExpectedSize(children.length);
          for (File child : children) {
            WorkspacePath childWorkspacePath;
            try {
              childWorkspacePath = workspaceRoot.workspacePathFor(child);
            } catch (IllegalArgumentException e) {
              // stop at directories with unhandled characters.
              continue;
            }
            futures.add(
                walkDirectoryStructure(
                    workspaceRoot,
                    excludeDirectories,
                    fileOperationProvider,
                    executorService,
                    childWorkspacePath,
                    cancelled,
                    walkedCount,
                    skippedSymlinkCount,
                    visitedCanonicalPaths,
                    workspaceCanonicalPath));
          }
          return Futures.transform(
              Futures.allAsList(futures),
              (Function<List<PathStructurePair>, PathStructurePair>)
                  pairs -> {
                    Builder<WorkspacePath, DirectoryStructure> result = ImmutableMap.builder();
                    for (PathStructurePair pair : pairs) {
                      if (pair != null) {
                        result.put(pair.path, pair.directoryStructure);
                      }
                    }
                    return new PathStructurePair(
                        workspacePath, new DirectoryStructure(result.build()));
                  },
              executorService);
        },
        executorService);
  }

  private static class PathStructurePair {
    final WorkspacePath path;
    final DirectoryStructure directoryStructure;

    PathStructurePair(WorkspacePath path, DirectoryStructure directoryStructure) {
      this.path = path;
      this.directoryStructure = directoryStructure;
    }
  }
}
