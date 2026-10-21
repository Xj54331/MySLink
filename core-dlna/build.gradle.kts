import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // 本机无 JDK 17：由任意 JDK(>=17) 编译，显式输出 17 字节码，保证兼容性。
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 与 Kotlin 的 jvmTarget 对齐，否则 compileJava 跟随守护进程 JDK 报
// 「Inconsistent JVM Target Compatibility」。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
