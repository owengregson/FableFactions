plugins { `kotlin-dsl` }

repositories { mavenCentral() }

dependencies {
    // ASM for the in-process bytecode gates (AM-13 floor scans).
    implementation(libs.asm)
}
