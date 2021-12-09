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
import com.sun.tools.javac.util.Context;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.config.Environment;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.style.NamedStyles;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@SupportedAnnotationTypes("*")
public class RewriteAnnotationProcessor extends AbstractProcessor {
    private boolean rewriteDisabled;

    @Nullable
    private Trees trees;

    @Nullable
    private Recipe recipe;

    private List<NamedStyles> styles = Collections.emptyList();

    public List<Result> getResults() {
        if(results == null) {
            return Collections.emptyList();
        }
        return results;
    }

    @Nullable
    private List<Result> results;

    @Nullable
    private static String getConfig(String key) {
        String result = System.getProperty(key);
        if(result == null) {
            result = System.getenv(key);
        }
        // some shells make it annoying to set environment variables whose names contain a "."
        if(result == null) {
            result = System.getenv(key.replace(".", "_"));
        }
        return result;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        String activeRecipes = getConfig("rewrite.activeRecipes");

        processingEnv.getMessager().printMessage(Kind.NOTE, "Running Rewrite with active recipes: " + activeRecipes);

        if (getConfig("rewrite.disable") != null || activeRecipes == null) {
            rewriteDisabled = true;
            return;
        }

        this.trees = Trees.instance(processingEnv);

        Environment env = Environment
                .builder(System.getProperties())
                .scanRuntimeClasspath()
                .scanUserHome()
                .build();

        recipe = env.activateRecipes(activeRecipes.split(","));

        String activeStyles = getConfig("rewrite.activeStyles");
        styles = env.activateStyles(activeStyles == null ? new String[0] : activeStyles.split(","));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (recipe == null || rewriteDisabled || roundEnv.processingOver()) {
            return false;
        }

        List<J.CompilationUnit> compilationUnits = new ArrayList<>(roundEnv.getRootElements().size());

        for (Element element : roundEnv.getRootElements()) {
            JCCompilationUnit cu = toUnit(element);

            if (cu == null) {
                continue;
            }

            try {
                Path sourcePath = Paths.get(cu.getSourceFile().toUri());
                String userDir = System.getProperty("user.dir");
                if (userDir != null) {
                    try {
                        sourcePath = Paths.get(userDir).relativize(sourcePath).normalize();
                    } catch (IllegalArgumentException e) {
                        // This is only a problem when running tests via StringFileSystemProvider on Windows
                    }
                }

                String source;
                try {
                    source = StringUtils.readFully(cu.getSourceFile().openInputStream());
                } catch (Throwable ignored) {
                    source = cu.getSourceFile().getCharContent(true).toString();
                }

                Java11ParserVisitor parser = new Java11ParserVisitor(sourcePath, source, styles, new InMemoryExecutionContext(), new Context());

                compilationUnits.add((J.CompilationUnit) parser.scan(cu, Space.EMPTY));
            } catch (Throwable t) {
                StringWriter exceptionWriter = new StringWriter();
                t.printStackTrace();
                t.printStackTrace(new PrintWriter(exceptionWriter));
                processingEnv.getMessager().printMessage(Kind.ERROR, "Unable to map compilation unit to Rewrite AST at " +
                        cu.getSourceFile().toUri() + ": " + exceptionWriter);
            }
        }

        results = recipe.run(compilationUnits);
        if(results.isEmpty()) {
            processingEnv.getMessager().printMessage(Kind.NOTE, "Rewrite run produced no results, no patch file generated");
        } else {
            //noinspection ResultOfMethodCallIgnored
            new File("./.rewrite").mkdirs();

            Path patchFile = new File("./.rewrite").toPath().resolve("rewrite.patch");
            processingEnv.getMessager().printMessage(Kind.NOTE, "Writing Rewrite patch file to: " + patchFile.toAbsolutePath());
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                for (Result result : results) {
                    String diff = result.diff();
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
                        exceptionWriter);
            }
        }

        return false;
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
                // Happens if a package-info.java doesn't contain a package declaration.
                // We can safely ignore those, since they do not need any processing
            }
        }

        if (path == null) {
            return null;
        }

        return (JCCompilationUnit) path.getCompilationUnit();
    }
}
