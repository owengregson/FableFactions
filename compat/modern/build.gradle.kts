plugins { `java-library` }

dependencies {
    // ModernItemCodec (serializeAsBytes), BrigadierInstaller, AsyncChunks — FQN-loaded
    // behind probes. Compiled against modern symbols; output folded into core's shadowJar.
    compileOnly(project(":platform"))
    compileOnly(libs.paper.api.modern)
    compileOnly(libs.jetbrains.annotations)
}
