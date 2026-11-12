import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }
// No local JDK 21 (Android Studio JBR is 25); target 21 bytecode with the running JDK.
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
dependencies { testImplementation(libs.junit) }
