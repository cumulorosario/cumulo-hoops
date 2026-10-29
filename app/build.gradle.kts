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
        // Clave de pruebas (incluida en el repo): solo para compilar en una computadora.
        create("pruebas") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Clave privada de Cúmulo Ingeniería: la carga GitHub Actions desde los secretos
        // del repositorio (HOOPS_KEYSTORE_BASE64, HOOPS_KEYSTORE_PASSWORD, HOOPS_KEY_PASSWORD).
        // Nunca se guarda en el repositorio.
        System.getenv("HOOPS_KEYSTORE_FILE")?.let { path ->
            create("cumulo") {
                storeFile = file(path)
                storePassword = System.getenv("HOOPS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOOPS_KEY_ALIAS") ?: "cumulo"
                keyPassword = System.getenv("HOOPS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("pruebas")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("cumulo") ?: signingConfigs.getByName("pruebas")
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
