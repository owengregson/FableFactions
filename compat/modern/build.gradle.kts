import org.gradle.api.attributes.java.TargetJvmVersion

plugins { `java-library` }

dependencies {
    // ModernItemCodec (serializeAsBytes), BrigadierInstaller, AsyncChunks — FQN-loaded
    // behind probes (AM-1: no chat renderer in v1). Compiled against modern symbols
    // (paper 1.20.6); output folded into core's shadowJar like compat-folia.
    compileOnly(project(":platform"))
    compileOnly(libs.paper.api.modern)
    compileOnly(libs.jetbrains.annotations)
}

// paper-api 1.20.6 is a Java-21-targeted artifact (its Gradle metadata declares
// jvm.version=21), but this module compiles to v61 (options.release=17) — legitimate,
// because -release only constrains the JDK PLATFORM API, never classpath libraries.
// Tell dependency resolution this consumer can run on 21 so it accepts the modern API
// (a 17-targeted lib like :platform still resolves — 21 >= its target). These classes
// are FQN-loaded only on modern (Java 21+) servers, so nothing links them below that.
configurations.named("compileClasspath") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}
