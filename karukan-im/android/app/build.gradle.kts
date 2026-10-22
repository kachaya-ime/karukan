import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.kachaya.karukan"

    defaultConfig {
        applicationId = "io.github.kachaya.karukan"
        minSdk = 25
        compileSdk = 37
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

fun downloadFileWithRedirects(urlString: String, targetFile: File) {
    val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
    if (tempFile.exists()) tempFile.delete()

    var currentUrl = urlString
    var redirects = 0
    var activeConn: HttpURLConnection? = null

    try {
        while (true) {
            val uri = URI.create(currentUrl)
            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; KarukanIME Gradle Build)")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            activeConn = conn

            val responseCode = conn.responseCode
            if (responseCode in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location != null && redirects < 10) {
                    currentUrl = location
                    redirects++
                    conn.disconnect()
                    continue
                }
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode returned from $currentUrl")
            }
            break
        }

        activeConn.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    } catch (e: Exception) {
        if (tempFile.exists()) tempFile.delete()
        throw IOException("Failed to download $urlString: ${e.message}", e)
    } finally {
        activeConn?.disconnect()
    }
}

// ビルド前に HuggingFace からモデルおよび tokenizer を自動ダウンロードするタスク
val downloadAssetsTask = tasks.register("downloadAssets") {
    val assetsDir = file("${projectDir}/src/main/assets")
    outputs.dir(assetsDir)
    outputs.upToDateWhen {
        val tokenizer = File(assetsDir, "tokenizer.json")
        val model = File(assetsDir, "jinen-v2-xsmall-Q5_K_M.gguf")
        tokenizer.exists() && tokenizer.length() > 0L && model.exists() && model.length() > 0L
    }

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        val downloads = mapOf(
            "tokenizer.json" to "https://huggingface.co/togatogah/jinen-v2-xsmall.gguf/resolve/main/tokenizer.json",
            "jinen-v2-xsmall-Q5_K_M.gguf" to "https://huggingface.co/togatogah/jinen-v2-xsmall.gguf/resolve/main/jinen-v2-xsmall-Q5_K_M.gguf"
        )

        for ((fileName, urlString) in downloads) {
            val targetFile = File(assetsDir, fileName)
            if (!targetFile.exists() || targetFile.length() == 0L) {
                logger.lifecycle("Downloading $fileName for Karukan IME from HuggingFace...")
                downloadFileWithRedirects(urlString, targetFile)
                logger.lifecycle("Successfully downloaded $fileName (${targetFile.length()} bytes)")
            }
        }
    }
}

// ビルド前にリポジトリルートからライセンス情報・権利表記ファイルを assets/legal/ へ自動コピーするタスク
val copyLegalInfoTask = tasks.register<Copy>("copyLegalInfo") {
    val repoRootDir = project.rootDir.parentFile.parentFile
    from(file("$repoRootDir/THIRD_PARTY_LICENSES"))
    from(file("$repoRootDir/LICENSE-MIT"))
    from(file("$repoRootDir/LICENSE-APACHE"))
    into(file("${projectDir}/src/main/assets/legal"))
}

// Gradle ビルド前に Rust (.so) を自動ビルド＆同期するタスク
val buildRustTask = tasks.register<Exec>("buildRust") {
    val ps1Script = file("${project.rootDir}/build.ps1")

    // Android Studio / Gradle が認識している NDK パスを自動取得して渡す
    val ndkDir = android.ndkPath
    if (!ndkDir.isNullOrEmpty()) {
        environment("ANDROID_NDK_HOME", ndkDir)
        environment("ANDROID_NDK_ROOT", ndkDir)
        environment("NDK_ROOT", ndkDir)
        environment("ANDROID_NDK", ndkDir)
    }

    // Android Studio が認識している SDK パスも渡す
    val sdkDir = android.sdkDirectory.absolutePath
    if (sdkDir.isNotEmpty()) {
        environment("ANDROID_HOME", sdkDir)
        environment("ANDROID_SDK_ROOT", sdkDir)
    }

    commandLine("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", ps1Script.absolutePath)
}

// preBuild (Android ビルド準備段階) に自動タスクを連携
tasks.named("preBuild") {
    dependsOn(copyLegalInfoTask)
    dependsOn(downloadAssetsTask)
    dependsOn(buildRustTask)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.constraintlayout)
}
