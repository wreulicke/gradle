/*
 * Copyright 2019 the original author or authors.
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

import org.gradle.gradlebuild.unittestandcompile.ModuleType
import org.gradle.gradlebuild.unittestandcompile.UnitTestAndCompileExtension

tasks.register<BuildStructureViz>("buildStructureViz") {
    moduleTypes = ModuleType.values().asList() - ModuleType.INTERNAL

    dotFile = File(buildDir, "subproject-dependencies.dot")
    pngFile = File(buildDir, "subproject-dependencies.png")
}

open class BuildStructureViz: DefaultTask() {

    @Input
    val projectHash = project.rootProject.hashCode()

    @Input
    lateinit var moduleTypes: List<ModuleType>

    @OutputFile
    lateinit var dotFile: File

    @OutputFile
    lateinit var pngFile: File

    private
    fun Project.moduleType() = extensions.findByType<UnitTestAndCompileExtension>()?.moduleType?:ModuleType.INTERNAL

    private
    fun color(moduleType: ModuleType) = when (moduleType) {
        ModuleType.INTERNAL -> "gray95"
        ModuleType.STARTUP -> "chartreuse3"
        ModuleType.WORKER -> "lightblue"
        ModuleType.CORE -> "azure3"
        else -> "gray95"
    }

    private
    fun File.writeDotFile(content: String) = writeText("""
        digraph build_structure {
            graph [ dpi = 100, fontname="Sans"];
            node [fontname = "Sans"];
            edge [fontname = "Sans"];

            $content
        }
    """)

    private
    fun File.generateFrom(dotFile: File) = project.exec {
        commandLine("dot", "-Tpng", dotFile.path, "-o", path)
    }

    private
    fun addNode(name: String, color: String) =
        "\"$name\" [shape=\"box\", label=<<B>$name</B>>, color=\"$color\", bgcolor=\"$color\", style=\"filled\"]\n"

    private
    fun addEdge(from: String, to: String, color: String) =
        "\"$from\" -> \"$to\" [color=\"$color\"]\n"

    @TaskAction
    fun generate() {
        val nodes = mutableSetOf<String>()
        val moduleTypeDependencies = mutableSetOf<String>()
        var content = ""


        with(project.rootProject) {
            val projectsByType = subprojects.filter { it.moduleType() in moduleTypes }.groupBy { it.moduleType() }
            projectsByType.forEach { type, projects ->
                content += "subgraph cluster_${type.name.toLowerCase()} {\n"
                //content += "label=<<B>${type.name}</B>>\n"
                content += "color=blue\n"
                content += "${type.name}\n"
                projects.forEach { project ->
                    val color = color(type)
                    content += addNode(project.name, color)
                }
                content += "}\n"
            }
            projectsByType.forEach { type, projects ->
                projects.forEach { project ->
                    project.configurations.filter { it.name in listOf("runtime", "compile", "implementation", "api", "compileOnly") }.forEach { config ->
                        val edgeColor = if (config.name == "runtime") "firebrick1" else if (config.name == "compile") "deeppink" else "black"
                        config.dependencies.forEach { dep ->
                            if (dep is ProjectDependency) {
                                content += addEdge(project.name, dep.dependencyProject.name, edgeColor)
                                if (project.moduleType() != dep.dependencyProject.moduleType()) {
                                    //moduleTypeDependencies.add(addEdge(project.moduleType().name, dep.dependencyProject.moduleType().name, "black"))
                                }
                            }
                        }
                    }
                }
            }
            //content += moduleTypeDependencies.joinToString("")
        }

        dotFile.writeDotFile(content)
        pngFile.generateFrom(dotFile)
    }

}
