plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Reads the Karoo's offline map files. Plain Java, LGPL-3.0, used unmodified. 0.25.0 is the
    // version karoo-routegraph ships against Hammerhead's maps.
    api("org.mapsforge:mapsforge-map-reader:0.25.0")
    // The engine has no Android in it, so its tests run on the Mac with no Karoo.
    testImplementation("junit:junit:4.13.2")
}
