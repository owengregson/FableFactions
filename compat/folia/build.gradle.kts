plugins { `java-library` }

dependencies {
    // FoliaScheduling only, FQN-loaded behind caps.folia() (AM-12). Output classes are
    // folded into core's shadowJar, so core's jvmdg legs union in this compile classpath.
    compileOnly(project(":platform"))
    compileOnly(libs.paper.api.folia)
    compileOnly(libs.jetbrains.annotations)
}
