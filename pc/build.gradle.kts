// Versión para PC: el mismo núcleo en Kotlin que la app (app/src/.../core) y las mismas
// pantallas (web/), empaquetados en un solo .jar que corre con Java 17 o superior.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDirs("src", "../app/src/main/java/ar/cumulo/hoops/core")
}

tasks.jar {
    archiveFileName.set("cumulo-hoops-pc.jar")
    manifest { attributes("Main-Class" to "ar.cumulo.hoops.pc.MainKt") }
    from("../web") { into("web") }
    // Incluye la biblioteca de Kotlin: un solo archivo, sin instalar nada más que Java
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
}
