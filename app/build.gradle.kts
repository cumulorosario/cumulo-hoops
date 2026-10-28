plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// En GitHub Actions cada compilación sube el número de versión, así el APK nuevo
// se instala encima del anterior sin perder el partido guardado.
val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ar.cumulo.hoops"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.cumulo.hoops"
        minSdk = 26
        targetSdk = 35
        versionCode = build
        versionName = "0.2.$build"
    }

    signingConfigs {
        // Clave fija de pruebas (incluida en el repo) para que todas las versiones
        // se puedan actualizar entre sí. Antes de publicar en Play Store hay que
        // reemplazarla por una clave privada guardada como secreto.
        create("pruebas") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("pruebas")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pruebas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Las pantallas web (tablero, reloj de 24, operador) se comparten con la versión de PC.
    sourceSets {
        getByName("main") {
            assets.srcDirs("../web")
        }
    }

    lint {
        // No frenar la compilación por avisos de estilo mientras es un prototipo.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
