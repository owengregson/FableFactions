plugins { `java-library` }

dependencies {
    // FoliaScheduling only, FQN-loaded behind caps.folia() (AM-12). Compiled against the
    // Folia scheduler symbols (paper 1.20.4); its output classes are folded into core's
    // shadowJar, so core's DowngradeJar classpath must union in this compile classpath.
    compileOnly(project(":platform"))
    compileOnly(libs.paper.api.folia)
    compileOnly(libs.jetbrains.annotations)
}
