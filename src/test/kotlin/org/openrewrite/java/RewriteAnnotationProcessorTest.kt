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
package org.openrewrite.java

import org.assertj.core.api.Assertions.assertThat
import org.joor.CompileOptions
import org.joor.Reflect
import org.junit.jupiter.api.Test
import org.openrewrite.java.tree.J
import java.nio.file.spi.FileSystemProvider

class RewriteAnnotationProcessorTest {

    @Test
    fun serializeAsts() {
        val p = RewriteAnnotationProcessor()

        FileSystemProvider.installedProviders()

        System.setProperty("rewrite.activeRecipes", "org.openrewrite.java.RemoveUnusedImports");

        Reflect.compile(
            "org.openrewrite.Test",
            //language=java
            """
                package org.openrewrite;
                
                import java.util.List;
                
                public class Test {
                }
            """.trimIndent(),
            CompileOptions().processors(p)
        ).create()

        assertThat(p.results.size).isEqualTo(1)
        val result = p.results[0]
        assertThat(result.after).isNotNull
        assertThat(result.after).isInstanceOf(J.CompilationUnit::class.java)
        val after = result.after as J.CompilationUnit
        assertThat(after.imports.size).isEqualTo(0)
    }
}
