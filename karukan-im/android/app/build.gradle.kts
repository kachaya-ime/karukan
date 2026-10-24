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

// ビルド前に karukan-engine/data/emoji.yml から絵文字をカテゴリ別に抽出して assets/emoji_categories.txt へ配置するタスク
val generateEmojiListTask = tasks.register("generateEmojiList") {
    val repoRootDir = project.rootDir.parentFile.parentFile
    val emojiYml = file("$repoRootDir/karukan-engine/data/emoji.yml")
    val assetsDir = file("${projectDir}/src/main/assets")
    val outputFile = File(assetsDir, "emoji_categories.txt")

    inputs.file(emojiYml)
    outputs.file(outputFile)

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        if (emojiYml.exists()) {
            class EmojiItem(val char: String, val readings: List<String>)
            val items = mutableListOf<EmojiItem>()
            var currentChar: String? = null
            var currentReadings = mutableListOf<String>()
            var inReadings = false
            val seen = mutableSetOf<String>()

            emojiYml.forEachLine { line ->
                val charMatch = Regex("""^\s*-\s*char:\s*['"]?([^'"]+)['"]?""").find(line)
                if (charMatch != null) {
                    if (currentChar != null && seen.add(currentChar!!)) {
                        items.add(EmojiItem(currentChar!!, currentReadings.toList()))
                    }
                    currentChar = charMatch.groupValues[1].trim()
                    currentReadings.clear()
                    inReadings = false
                } else if (currentChar != null) {
                    val trimmed = line.trim()
                    if (trimmed == "readings:") {
                        inReadings = true
                    } else if (trimmed.startsWith("triggers:") || trimmed.startsWith("- char:")) {
                        inReadings = false
                    } else if (inReadings && trimmed.startsWith("-")) {
                        val r = trimmed.removePrefix("-").trim()
                        if (r.isNotEmpty()) currentReadings.add(r)
                    }
                }
            }
            if (currentChar != null && seen.add(currentChar!!)) {
                items.add(EmojiItem(currentChar!!, currentReadings.toList()))
            }

            fun categorize(item: EmojiItem): String {
                val r = item.readings
                val c = item.char
                if (r.any { it in listOf("かお", "えがお", "かんじょう", "なきがお", "おこり", "しょうじょう", "すまいる") }) return "顔・感情"
                if (r.any { it in listOf("からだ", "て", "あし", "ひと", "ふく", "ポーズ", "かみ") }) return "人・身体"
                if (r.any { it in listOf("どうぶつ", "とり", "さかな", "むし", "いぬ", "ねこ", "はな", "き", "てんき", "つき", "ほし") }) return "動物・自然"
                if (r.any { it in listOf("たべもの", "のみもの", "くだもの", "やさい", "りょうり", "スイーツ", "おかし", "ぱん", "ごはん") }) return "食べ物"
                if (r.any { it in listOf("のりもの", "くるま", "でんしゃ", "ひこうき", "ふね", "たてもの", "いえ", "ばしょ") }) return "乗り物・建物"
                if (r.any { it in listOf("すぽーつ", "あそび", "おんがく", "がっき", "いべんと", "おもちゃ", "ゲーム") }) return "アクティビティ"
                if (r.any { it in listOf("きごう", "すうじ", "まーく", "はーと", "やじるし", "こくき", "ふらぐ", "かこみすうじ") }) return "記号・マーク"

                val cp = if (c.isNotEmpty()) c.codePointAt(0) else 0
                return when {
                    cp in 0x1F600..0x1F64F -> "顔・感情"
                    cp in 0x1F300..0x1F32F -> "動物・自然"
                    cp in 0x1F330..0x1F343 -> "動物・自然"
                    cp in 0x1F344..0x1F37F -> "食べ物"
                    cp in 0x1F380..0x1F3C4 -> "アクティビティ"
                    cp in 0x1F3C5..0x1F3FF -> "人・身体"
                    cp in 0x1F400..0x1F43F -> "動物・自然"
                    cp in 0x1F440..0x1F4A9 -> "人・身体"
                    cp in 0x1F680..0x1F6FF -> "乗り物・建物"
                    cp in 0x1F900..0x1F9FF -> "人・身体"
                    cp in 0x2600..0x27BF -> "記号・マーク"
                    else -> "記号・その他"
                }
            }

            val categoriesMap = LinkedHashMap<String, MutableList<String>>()
            val categoryNames = listOf("顔・感情", "人・身体", "動物・自然", "食べ物", "乗り物・建物", "アクティビティ", "記号・マーク", "記号・その他")
            for (cat in categoryNames) {
                categoriesMap[cat] = mutableListOf()
            }

            for (item in items) {
                val cat = categorize(item)
                categoriesMap.getOrPut(cat) { mutableListOf() }!!.add(item.char)
            }

            val sb = StringBuilder()
            for ((cat, list) in categoriesMap) {
                if (list.isNotEmpty()) {
                    sb.append(cat).append("=").append(list.joinToString(",")).append("\n")
                }
            }

            outputFile.writeText(sb.toString(), Charsets.UTF_8)
            File(assetsDir, "emoji_list.txt").takeIf { it.exists() }?.delete()
            logger.lifecycle("Generated emoji categories in assets/emoji_categories.txt from ${emojiYml.path}")
        } else {
            logger.warn("emoji.yml not found at ${emojiYml.path}")
        }
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
}
