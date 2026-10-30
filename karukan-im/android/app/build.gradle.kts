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

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = project.findProperty("KEYSTORE_FILE")?.toString() ?: System.getenv("KEYSTORE_FILE")
            if (!keystoreFile.isNullOrEmpty() && File(keystoreFile).exists()) {
                storeFile = File(keystoreFile)
                storePassword = project.findProperty("KEYSTORE_PASSWORD")?.toString() ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = project.findProperty("KEY_ALIAS")?.toString() ?: System.getenv("KEY_ALIAS") ?: ""
                keyPassword = project.findProperty("KEY_PASSWORD")?.toString() ?: System.getenv("KEY_PASSWORD") ?: ""
            } else {
                // keystore未指定時はdebug署名をフォールバックとして使用（releaseビルド時もテスト可能にするため）
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            outputs.all {
                val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output?.outputFileName = "kanakan-${variant.versionName}.apk"
            }
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
        val modelXSmall = File(assetsDir, "jinen-v2-xsmall-Q5_K_M.gguf")
        val modelSmall = File(assetsDir, "jinen-v2-small-Q5_K_M.gguf")
        tokenizer.exists() && tokenizer.length() > 0L &&
            modelXSmall.exists() && modelXSmall.length() > 0L &&
            modelSmall.exists() && modelSmall.length() > 0L
    }

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        val downloads = mapOf(
            "tokenizer.json" to "https://huggingface.co/togatogah/jinen-v2-xsmall.gguf/resolve/main/tokenizer.json",
            "jinen-v2-xsmall-Q5_K_M.gguf" to "https://huggingface.co/togatogah/jinen-v2-xsmall.gguf/resolve/main/jinen-v2-xsmall-Q5_K_M.gguf",
            "jinen-v2-small-Q5_K_M.gguf" to "https://huggingface.co/togatogah/jinen-v2-small.gguf/resolve/main/jinen-v2-small-Q5_K_M.gguf"
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

// ビルド前にローカルのライセンス情報・権利表記ファイルを assets/legal/ へ自動コピーするタスク
val copyLegalInfoTask = tasks.register<Copy>("copyLegalInfo") {
    from(file("${projectDir}/legal/THIRD_PARTY_LICENSES"))
    from(file("${projectDir}/legal/LICENSE-MIT"))
    from(file("${projectDir}/legal/LICENSE-APACHE"))
    into(file("${projectDir}/src/main/assets/legal"))
}

// ビルド前にリポジトリ等からユーザー辞書ファイルを assets/user_dicts/ へ自動コピーするタスク
val copyUserDictsTask = tasks.register("copyUserDicts") {
    val assetsUserDictsDir = file("${projectDir}/src/main/assets/user_dicts")

    doLast {
        if (!assetsUserDictsDir.exists()) {
            assetsUserDictsDir.mkdirs()
        }

        val repoRootDir = project.rootDir.parentFile.parentFile

        // karukan-im/core/config/default_user_dict.txt をコピー
        val coreConfigDict = file("$repoRootDir/karukan-im/core/config/default_user_dict.txt")
        if (coreConfigDict.exists()) {
            coreConfigDict.copyTo(File(assetsUserDictsDir, "default_user_dict.txt"), overwrite = true)
            logger.lifecycle("Copied default user dict from ${coreConfigDict.path} to ${assetsUserDictsDir.path}")
        }

        val searchLocations = listOf(
            file("$repoRootDir/user_dict.bin"),
            file("$repoRootDir/user_dict.txt"),
            file("$repoRootDir/user_dicts")
        )

        for (loc in searchLocations) {
            if (loc.isFile) {
                loc.copyTo(File(assetsUserDictsDir, loc.name), overwrite = true)
                logger.lifecycle("Copied user dict from ${loc.path} to ${assetsUserDictsDir.path}")
            } else if (loc.isDirectory) {
                loc.copyRecursively(assetsUserDictsDir, overwrite = true)
                logger.lifecycle("Copied user dicts from directory ${loc.path}")
            }
        }
    }
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

// ビルド前に公式システム辞書 (dict.tgz) を自動ダウンロードして assets/dict.tgz (システム辞書) へ配置するタスク
val downloadDictTask = tasks.register("downloadDict") {
    val assetsDir = file("${projectDir}/src/main/assets")
    val assetsUserDictsDir = file("${projectDir}/src/main/assets/user_dicts")
    val dictTgz = File(assetsDir, "dict.tgz")

    outputs.file(dictTgz)
    outputs.upToDateWhen {
        dictTgz.exists() && dictTgz.length() > 0L
    }

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        // ユーザー辞書フォルダに以前の dict.* が残っていればクリーンアップ
        File(assetsUserDictsDir, "dict.tgz").takeIf { it.exists() }?.delete()
        File(assetsUserDictsDir, "dict.bin").takeIf { it.exists() }?.delete()
        File(assetsUserDictsDir, "dict.bin.gz").takeIf { it.exists() }?.delete()

        if (!dictTgz.exists() || dictTgz.length() == 0L) {
            val dictUrl = "https://github.com/togatoga/karukan/releases/latest/download/dict.tgz"
            logger.lifecycle("Downloading official system dictionary archive ($dictUrl)...")
            downloadFileWithRedirects(dictUrl, dictTgz)
            logger.lifecycle("Successfully installed official system dictionary archive dict.tgz (${dictTgz.length()} bytes)")
        }
    }
}

// ビルド前に unicode.org から emoji-test.txt をダウンロードして絵文字をカテゴリ別に抽出・assets/emoji_categories.txtへ配置するタスク
val generateEmojiListTask = tasks.register("generateEmojiList") {
    val assetsDir = file("${projectDir}/src/main/assets")
    val outputFile = File(assetsDir, "emoji_categories.txt")
    val cacheFile = file("${layout.buildDirectory.get().asFile}/emoji-test.txt")

    outputs.file(outputFile)

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        val emojiTestTxtUrl = "https://raw.githubusercontent.com/unicode-org/unicodetools/refs/heads/main/unicodetools/data/emoji/dev/emoji-test.txt"
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            cacheFile.parentFile.mkdirs()
            logger.lifecycle("Downloading emoji-test.txt from $emojiTestTxtUrl...")
            downloadFileWithRedirects(emojiTestTxtUrl, cacheFile)
        }

        val lines = cacheFile.readLines(Charsets.UTF_8)

        // Pass 1: Componentグループのコードポイントを収集（スキンゾーン等を除外するため）
        val componentCodePoints = mutableSetOf<String>()
        var currentGroup = "Smileys & Emotion"
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("# group:")) {
                currentGroup = trimmed.substring(8).trim()
                continue
            }

            if (trimmed.startsWith("#")) continue

            val semicolonIdx = trimmed.indexOf(';')
            if (semicolonIdx != -1) {
                if (currentGroup.equals("Component", ignoreCase = true) || currentGroup.lowercase().contains("component")) {
                    val codePointsPart = trimmed.substring(0, semicolonIdx).trim()
                    for (cp in codePointsPart.split(Regex("""\s+"""))) {
                        if (cp.isNotEmpty()) {
                            componentCodePoints.add(cp)
                        }
                    }
                }
            }
        }

        // Pass 2: fully-qualified な絵文字をグループ別に分類
        val groupMap = LinkedHashMap<String, MutableList<String>>()
        currentGroup = "Smileys & Emotion"
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("# group:")) {
                currentGroup = trimmed.substring(8).trim()
                continue
            }

            if (trimmed.startsWith("#")) continue

            val semicolonIdx = trimmed.indexOf(';')
            if (semicolonIdx != -1 && trimmed.contains("fully-qualified")) {
                if (currentGroup.equals("Component", ignoreCase = true) || currentGroup.lowercase().contains("component")) {
                    continue
                }

                val codePointsPart = trimmed.substring(0, semicolonIdx).trim()
                var containsComponentCode = false
                for (cp in codePointsPart.split(Regex("""\s+"""))) {
                    if (cp in componentCodePoints) {
                        containsComponentCode = true
                        break
                    }
                }
                if (containsComponentCode) {
                    continue
                }

                val hashIdx = trimmed.indexOf('#')
                if (hashIdx != -1) {
                    val commentPart = trimmed.substring(hashIdx + 1).trim()
                    val parts = commentPart.split(Regex("""\s+"""), 3)
                    if (parts.isNotEmpty()) {
                        val emoji = parts[0]
                        groupMap.getOrPut(currentGroup) { mutableListOf() }.add(emoji)
                    }
                }
            }
        }

        val categoryNameMap = mapOf(
            "Smileys & Emotion" to "顔・感情",
            "People & Body" to "人・身体",
            "Animals & Nature" to "動物・自然",
            "Food & Drink" to "食べ物",
            "Travel & Places" to "乗り物・建物",
            "Activities" to "アクティビティ",
            "Objects" to "モノ",
            "Symbols" to "記号・マーク",
            "Flags" to "国旗"
        )

        val sb = StringBuilder()
        for ((group, emojiList) in groupMap) {
            val catName = categoryNameMap[group] ?: group
            if (emojiList.isNotEmpty()) {
                sb.append(catName).append("=").append(emojiList.joinToString(",")).append("\n")
            }
        }

        outputFile.writeText(sb.toString(), Charsets.UTF_8)
        File(assetsDir, "emoji_list.txt").takeIf { it.exists() }?.delete()
        logger.lifecycle("Successfully generated emoji categories in assets/emoji_categories.txt from emoji-test.txt")
    }
}

// preBuild (Android ビルド準備段階) に自動タスクを連携
tasks.named("preBuild") {
    dependsOn(copyLegalInfoTask)
    dependsOn(downloadAssetsTask)
    dependsOn(downloadDictTask)
    dependsOn(copyUserDictsTask)
    dependsOn(generateEmojiListTask)
    dependsOn(buildRustTask)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.commons.compress)
}
