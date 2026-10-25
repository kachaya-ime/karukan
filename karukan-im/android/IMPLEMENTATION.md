# Karukan Android 実装仕様書 (Implementation Guide)

Karukan IME Android 移植版 (`karukan-im/android`) のモジュール構造、JNI 連携、キーボード処理、辞書管理などの実装情報です。

---

## 1. JNI (Rust / Native) 連携仕様

JNI層 (`src/lib.rs`) で提供されている全ネイティブ関数と、Kotlin層 (`KarukanEngine.kt`) のメソッドは 1 対 1 で対応しています。

| JNI関数名 (`src/lib.rs`) | Kotlin側メソッド (`KarukanEngine.kt`) | 主な機能・役割 |
|---|---|---|
| `nativeInit` | `init { ... }` (初期化) | AIモデル (`.gguf`) のロードと初期化 |
| `nativeLoadSystemDict` | `loadSystemDict()` | システム辞書 (`dict.bin`) のロード |
| `nativeLoadUserDicts` | `loadUserDicts()` | ユーザー辞書・追加辞書のロード |
| `nativeUpdateEngineConfig` | `updateEngineConfig(settings)` | 全設定の一括更新 (推論スレッド数, ストラテジー, チャンク, ビームサーチ, 記号・幅スタイル, 最大学習数) |
| `nativeConvert` | `convert(query, maxCandidates, context)` | かな漢字変換の候補生成（日付・ユーザー辞書・AIモデル・カタカナ・絵文字・システム辞書・学習履歴の結合） |
| `nativeCancelQuery` | `cancelQuery(queryId)` | ライブ変換（リアルタイム推論）のキャンセル |
| `nativeRecordLearning` | `recordLearning(reading, surface)` | 選択された変換候補の学習履歴への記録 |
| `nativeClearLearning` | `clearLearning()` | メモリ上の学習キャッシュのクリア |
| `nativeRomajiToHiragana` | `romajiToHiragana(query)` | ローマ字→ひらがな変換 |
| `nativeRomajiToHiraganaFlush` | `romajiToHiraganaFlush(query)` | ローマ字→ひらがな変換のフラッシュ（末尾の未確定ローマ字の確定処理） |

### 設定更新 (`nativeUpdateEngineConfig`) で伝達されるパラメータ

`KarukanSettings` の各種設定値は `nativeUpdateEngineConfig` 1 つの JNI 関数を介して Native 側に一括伝達されます。

- **推論パラメータ**: `nThreads`, `strategy`, `maxLatencyMs`, `chunkChars`, `chunkSymbols`, `chunkDigits`, `chunkAlphabets`, `beamWidth`, `beamChars`
- **記号・文字幅スタイル**: `punctuationStyle`, `bracketStyle`, `slashStyle`, `spaceStyle`, `kanaSymbolWidth`, `asciiWidth`, `digitWidth`
- **変換学習設定**: `maxLearningEntries`

---

## 2. アプリ・コンポーネント構成 (随時追加予定)

- **入力サービス**: `KarukanInputMethodService.kt`
- **エンジンブリッジ**: `KarukanEngine.kt`
- **設定管理**: `PreferencesManager.kt`, `KarukanSettings.kt`
- **キーボードUI**: `KeyboardLayoutBuilder.kt`, `EmojiPaletteController.kt`
- **辞書・学習管理**: `DictUtils.kt`, `PresetDictActivity.kt`, `UserDictContentActivity.kt`, `LearningListActivity.kt`
