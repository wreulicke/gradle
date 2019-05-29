/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.file.collections;

import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskOutputs;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.nativeintegration.services.FileSystems;
import org.gradle.util.DeferredUtil;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

public class DefaultFileCollectionResolveContextRecursive implements ResolvableFileCollectionResolveContext {
    protected final PathToFileResolver fileResolver;
    private final Converter<? extends FileCollectionInternal> fileCollectionConverter;
    private final Converter<? extends FileTreeInternal> fileTreeConverter;
    private final Queue<Object> queue = new ArrayDeque<Object>();

    public DefaultFileCollectionResolveContextRecursive(FileResolver fileResolver) {
        this(fileResolver, new FileCollectionConverter(fileResolver.getPatternSetFactory()), new FileTreeConverter(fileResolver.getPatternSetFactory()));
    }

    private DefaultFileCollectionResolveContextRecursive(PathToFileResolver fileResolver, Converter<? extends FileCollectionInternal> fileCollectionConverter, Converter<? extends FileTreeInternal> fileTreeConverter) {
        this.fileResolver = fileResolver;
        this.fileCollectionConverter = fileCollectionConverter;
        this.fileTreeConverter = fileTreeConverter;
    }

    @Override
    public FileCollectionResolveContext add(Object element) {
        if (element != null) {
            queue.add(element);
        }
        return this;
    }

    @Override
    public FileCollectionResolveContext push(PathToFileResolver fileResolver) {
        ResolvableFileCollectionResolveContext nestedContext = newContext(fileResolver);
        add(nestedContext);
        return nestedContext;
    }

    protected DefaultFileCollectionResolveContextRecursive newContext(PathToFileResolver fileResolver) {
        return new DefaultFileCollectionResolveContextRecursive(fileResolver, fileCollectionConverter, fileTreeConverter);
    }

    @Override
    public final DefaultFileCollectionResolveContextRecursive newContext() {
        return newContext(fileResolver);
    }

    /**
     * Resolves the contents of this context as a list of atomic {@link FileTree} instances.
     */
    @Override
    public List<FileTreeInternal> resolveAsFileTrees() {
        return doResolve(fileTreeConverter);
    }

    /**
     * Resolves the contents of this context as a list of atomic {@link FileCollection} instances.
     */
    @Override
    public List<FileCollectionInternal> resolveAsFileCollections() {
        return doResolve(fileCollectionConverter);
    }

    /**
     * Resolves the contents of this context as a list of atomic {@link MinimalFileCollection} instances.
     */
    public List<MinimalFileCollection> resolveAsMinimalFileCollections() {
        return doResolve(new MinimalFileCollectionConverter());
    }

    private <T> List<T> doResolve(Converter<? extends T> converter) {
        List<T> result = new ArrayList<T>(queue.size());
        while (!queue.isEmpty()) {
            Object element = queue.remove();
            resolveElement(converter, result, element);
        }
        return result;
    }

    // TODO - need to sync with BuildDependenciesOnlyFileCollectionResolveContext
    private <T> void resolveElement(Converter<? extends T> converter, List<T> result, Object element) {
        if (element instanceof DefaultFileCollectionResolveContextRecursive) {
            converter.convertInto(element, result, fileResolver);
        } else if (element instanceof FileCollectionContainer) {
            resolveNested(converter, result, (FileCollectionContainer) element);
        } else if (element instanceof FileCollection || element instanceof MinimalFileCollection) {
            converter.convertInto(element, result, fileResolver);
        } else if (element instanceof Task) {
            resolveElement(converter, result, ((Task) element).getOutputs().getFiles());
        } else if (element instanceof TaskOutputs) {
            resolveElement(converter, result, ((TaskOutputs) element).getFiles());
        } else if (DeferredUtil.isDeferred(element)) {
            resolveElement(converter, result, DeferredUtil.unpack(element));
        } else if (element instanceof Path) {
            resolveElement(converter, result, ((Path) element).toFile());
        } else {
            if (element instanceof Object[]) {
                element = Arrays.asList((Object[]) element);
            }

            if (element instanceof Iterable) {
                resolveElements(converter, result, (Iterable<?>) element);
            } else if (element != null) {
                converter.convertInto(element, result, fileResolver);
            }
        }
    }

    private <T> void resolveElements(Converter<? extends T> converter, List<T> result, Iterable<?> element) {
        for (Object elem : element) {
            resolveElement(converter, result, elem);
        }
    }

    private <T> void resolveNested(Converter<? extends T> converter, List<T> result, FileCollectionContainer element) {
        DefaultFileCollectionResolveContextRecursive context = newContext();
        element.visitContents(context);
        resolveElements(converter, result, context.queue);
    }

    protected interface Converter<T> {
        void convertInto(Object element, Collection<? super T> result, PathToFileResolver resolver);
    }

    public static class FileCollectionConverter implements Converter<FileCollectionInternal> {
        private final Factory<PatternSet> patternSetFactory;

        public FileCollectionConverter(Factory<PatternSet> patternSetFactory) {
            this.patternSetFactory = patternSetFactory;
        }

        @Override
        public void convertInto(Object element, Collection<? super FileCollectionInternal> result, PathToFileResolver fileResolver) {
            if (element instanceof DefaultFileCollectionResolveContextRecursive) {
                DefaultFileCollectionResolveContextRecursive nestedContext = (DefaultFileCollectionResolveContextRecursive) element;
                result.addAll(nestedContext.resolveAsFileCollections());
            } else if (element instanceof FileCollection) {
                FileCollection fileCollection = (FileCollection) element;
                result.add(Cast.cast(FileCollectionInternal.class, fileCollection));
            } else if (element instanceof MinimalFileTree) {
                MinimalFileTree fileTree = (MinimalFileTree) element;
                result.add(new FileTreeAdapter(fileTree, patternSetFactory));
            } else if (element instanceof MinimalFileSet) {
                MinimalFileSet fileSet = (MinimalFileSet) element;
                result.add(new FileCollectionAdapter(fileSet));
            } else if (element instanceof MinimalFileCollection) {
                throw new UnsupportedOperationException(String.format("Cannot convert instance of %s to FileTree", element.getClass().getSimpleName()));
            } else if (element instanceof TaskDependency) {
                // Ignore
                return;
            } else {
                result.add(new FileCollectionAdapter(new ListBackedFileSet(fileResolver.resolve(element))));
            }
        }
    }

    public static class FileTreeConverter implements Converter<FileTreeInternal> {
        private final Factory<PatternSet> patternSetFactory;

        public FileTreeConverter(Factory<PatternSet> patternSetFactory) {
            this.patternSetFactory = patternSetFactory;
        }

        @Override
        public void convertInto(Object element, Collection<? super FileTreeInternal> result, PathToFileResolver fileResolver) {
            if (element instanceof DefaultFileCollectionResolveContextRecursive) {
                DefaultFileCollectionResolveContextRecursive nestedContext = (DefaultFileCollectionResolveContextRecursive) element;
                result.addAll(nestedContext.resolveAsFileTrees());
            } else if (element instanceof FileTree) {
                FileTree fileTree = (FileTree) element;
                result.add(Cast.cast(FileTreeInternal.class, fileTree));
            } else if (element instanceof MinimalFileTree) {
                MinimalFileTree fileTree = (MinimalFileTree) element;
                result.add(new FileTreeAdapter(fileTree, patternSetFactory));
            } else if (element instanceof MinimalFileSet) {
                MinimalFileSet fileSet = (MinimalFileSet) element;
                for (File file : fileSet.getFiles()) {
                    convertFileToFileTree(file, result);
                }
            } else if (element instanceof FileCollection) {
                FileCollection fileCollection = (FileCollection) element;
                for (File file : fileCollection) {
                    convertFileToFileTree(file, result);
                }
            } else if (element instanceof MinimalFileCollection) {
                throw new UnsupportedOperationException(String.format("Cannot convert instance of %s to FileTree", element.getClass().getSimpleName()));
            } else if (element instanceof TaskDependency) {
                // Ignore
                return;
            } else {
                convertFileToFileTree(fileResolver.resolve(element), result);
            }
        }

        private void convertFileToFileTree(File file, Collection<? super FileTreeInternal> result) {
            if (file.isDirectory()) {
                result.add(new FileTreeAdapter(new DirectoryFileTree(file, patternSetFactory.create(), FileSystems.getDefault()), patternSetFactory));
            } else if (file.isFile()) {
                result.add(new FileTreeAdapter(new DefaultSingletonFileTree(file), patternSetFactory));
            }
        }
    }

    public static class MinimalFileCollectionConverter implements Converter<MinimalFileCollection> {
        @Override
        public void convertInto(Object element, Collection<? super MinimalFileCollection> result, PathToFileResolver resolver) {
            if (element instanceof DefaultFileCollectionResolveContextRecursive) {
                DefaultFileCollectionResolveContextRecursive nestedContext = (DefaultFileCollectionResolveContextRecursive) element;
                result.addAll(nestedContext.resolveAsMinimalFileCollections());
            } else if (element instanceof MinimalFileCollection) {
                MinimalFileCollection collection = (MinimalFileCollection) element;
                result.add(collection);
            } else if (element instanceof FileCollection) {
                throw new UnsupportedOperationException(String.format("Cannot convert instance of %s to MinimalFileCollection", element.getClass().getSimpleName()));
            } else if (element instanceof TaskDependency) {
                // Ignore
                return;
            } else {
                result.add(new ListBackedFileSet(resolver.resolve(element)));
            }
        }
    }
}
