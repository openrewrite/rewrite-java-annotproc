/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import org.openrewrite.*;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@SupportedAnnotationTypes("*")
public class RewriteAnnotationProcessor extends AbstractProcessor {
    private boolean rewriteDisabled = false;
    private Trees trees;
    private Collection<RefactorVisitor<?>> visitors;
    private Collection<JavaStyle> styles;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        String activeRecipes = System.getProperty("rewrite.activeRecipes");

        processingEnv.getMessager().printMessage(Kind.NOTE, "Running Rewrite");

        if (System.getProperty("rewrite.disable") != null || activeRecipes == null) {
            rewriteDisabled = true;
            return;
        }

        this.trees = Trees.instance(processingEnv);

        Environment env = Environment
                .builder(System.getProperties())
                .scanClasspath(Arrays.stream(System.getProperty("java.class.path").split("\\Q" + System.getProperty("path.separator") + "\\E"))
                        .map(cpEntry -> new File(cpEntry).toPath())
                        .collect(toList()))
                .scanUserHome()
                .build();

        visitors = env.visitors(activeRecipes.split(","));

        String activeStyles = System.getProperty("rewrite.activeStyles");
        styles = env.styles(activeStyles == null ? new String[0] : activeStyles.split(","))
                .stream()
                .filter(JavaStyle.class::isInstance)
                .map(JavaStyle.class::cast)
                .collect(Collectors.toList());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (rewriteDisabled || roundEnv.processingOver()) {
            return false;
        }

        Collection<J.CompilationUnit> compilationUnits = new ArrayList<>(roundEnv.getRootElements().size());

        Map<String, JavaType.Class> sharedClassTypes = new HashMap<>();

        for (Element element : roundEnv.getRootElements()) {
            JCCompilationUnit cu = toUnit(element);

            if (cu == null) {
                continue;
            }

            try {
                URI sourcePath = cu.getSourceFile().toUri();
                String userDir = System.getProperty("user.dir");
                if (userDir != null) {
                    sourcePath = Paths.get(userDir).toUri().relativize(sourcePath).normalize();
                }

                String source;
                try {
                    source = StringUtils.readFully(cu.getSourceFile().openInputStream());
                } catch (Throwable ignored) {
                    source = cu.getSourceFile().getCharContent(true).toString();
                }

                Java11ParserVisitor parser = new Java11ParserVisitor(sourcePath, source, false,
                        styles, sharedClassTypes);

                compilationUnits.add((J.CompilationUnit) parser.scan(cu, Formatting.EMPTY));
            } catch (Throwable t) {
                StringWriter exceptionWriter = new StringWriter();
                t.printStackTrace(new PrintWriter(exceptionWriter));
                processingEnv.getMessager().printMessage(Kind.ERROR, "Unable to map compilation unit to Rewrite AST at " +
                        cu.getSourceFile().toUri() + ": " + exceptionWriter.toString());
            }
        }

        Collection<Change> changes = new Refactor().visit(visitors).fix(compilationUnits, 3);

        if (!changes.isEmpty()) {
            for (Change change : changes) {
                logVisitorsThatMadeChanges(change);
            }

            //noinspection ResultOfMethodCallIgnored
            new File("./.rewrite").mkdirs();

            Path patchFile = new File("./.rewrite").toPath().resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                for (Change change : changes) {
                    String diff = change.diff();
                    try {
                        writer.write(diff + "\n");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                writer.flush();
            } catch (Exception e) {
                StringWriter exceptionWriter = new StringWriter();
                e.printStackTrace(new PrintWriter(exceptionWriter));
                processingEnv.getMessager().printMessage(Kind.ERROR, "Unable to generate rewrite diff file: " +
                        exceptionWriter.toString());
            }
        }

        return false;
    }

    private void logVisitorsThatMadeChanges(Change change) {
        processingEnv.getMessager().printMessage(Kind.WARNING, change.getOriginal().getSourcePath());
        for (String visitor : change.getVisitorsThatMadeChanges()) {
            processingEnv.getMessager().printMessage(Kind.WARNING, "  " + visitor);
        }
    }

    /**
     * We just return the latest version of whatever JDK we run on.
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Nullable
    private JCCompilationUnit toUnit(Element element) {
        TreePath path = null;
        if (trees != null) {
            try {
                path = trees.getPath(element);
            } catch (NullPointerException ignore) {
                // Happens if a package-info.java doesn't conatin a package declaration.
                // We can safely ignore those, since they do not need any processing
            }
        }

        if (path == null) {
            return null;
        }

        return (JCCompilationUnit) path.getCompilationUnit();
    }
}
