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

import org.joor.CompileOptions
import org.joor.Reflect
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.spi.FileSystemProvider

class RewriteAnnotationProcessorTest {
    @Disabled
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
        ).create().get<Any>()
    }
}
