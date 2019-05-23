val implementation = configurations.create("implementation")

// tag::generated-file-dependencies[]
dependencies {
    implementation(files("$buildDir/classes") {
        builtBy("implementation")
    })
}

tasks.register("compile") {
    doLast {
        println("compiling classes")
    }
}

tasks.register("list") {
    dependsOn(configurations["implementation"])
    doLast {
        println("classpath = ${configurations["implementation"].map { file: File -> file.name }}")
    }
}
// end::generated-file-dependencies[]
