plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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

application {
    mainClass.set("au.com.fjellconsulting.roadahead.replay.MainKt")
    // The PNG writer uses Java's drawing classes. Without this, on a Mac the JVM can sit
    // waiting on the window system after the pictures are written and never exit.
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

dependencies {
    implementation(project(":core"))
}
