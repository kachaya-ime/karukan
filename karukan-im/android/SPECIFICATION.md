# Karukan IME Android 機能・インターフェース仕様書 (Specification)

本ドキュメントは、Android 向け Karukan IME フロントエンドモジュール (`karukan-im/android`) の機能仕様、入力方式、変換・学習ロジック、JNI インターフェース、および UI/UX 仕様を定義します。

---

## 1. 動作環境・システム要件

- **対応 Android バージョン**: Android 10.0 (API 29) 以上推奨、Android 15 (API 35) / Android 16 (API 37) 完全対応
- **ビルド環境**:
  - `compileSdk`: 37
  - `targetSdk`: 37
  - `minSdk`: 29
- **言語・ツール**: Kotlin 1.9+, Android Gradle Plugin 8.x, Rust (NDK aarch64-linux-android ターゲット)

---

## 2. 入力インターフェース仕様

Karukan IME は、多様な入力デバイスおよびユーザーの好みに対応するため、以下の多彩な入力レイアウトを提供します。

### 2.1. QWERTY ソフトウェアキーボード
- ローマ字かな入力に対応。
- 1タップでの記号・数字モード切替、シフトキーによる大文字・特殊記号入力。
- `JsonKeyboardLayoutParser` により `assets/layouts/qwerty_layout.json` からレイアウト構造を動的構築。

### 2.2. タブレットレイアウト
- フルキーボード配置 (`assets/layouts/tablet_layout.json`)。タブレットや大型画面デバイス向けに最適化されたキーピッチと補助キー配置。

### 2.3. ビルトインキーボード
- 物理キーボード接続時や特定端末向けの内蔵補助レイアウト (`assets/layouts/builtin_layout.json`)。

### 2.4. ニコタッチ入力 (Niko Touch)
- パナソニック製携帯電話等で採用されていた12キーベースの「ニコタッチ」方式。
- 通常モードおよび数字専用モード (`NikoKeyboardLayout.kt`) を搭載。

### 2.5. グラフィティ手書き入力 (Graffiti)
- Palm OS の Graffiti (unistroke) 仕様に基づく手書き文字認識。
- `GraffitiAreaView` でのストローク描画と `GraffitiStrokeRecognizer` によるテンプレートマッチング。
- ジェスチャー認識によるスペース、バックスペース、改行等の特殊操作に対応。

### 2.6. 絵文字・記号パレット
- `EmojiPaletteController` と `ViewPager2` により、カテゴリ別グリッド表示による絵文字・顔文字・記号の高速入力。

---

## 3. 変換・推論仕様

### 3.1. ローマ字→ひらがな変換
- ローマ字入力文字列をリアルタイムにひらがなに変換 (`RomajiConverter` / `nativeRomajiToHiragana`)。

### 3.2. かな漢字変換 (GGUF モデル & 辞書統合)
- GGUF 形式のオンデバイス言語モデルによるビームサーチ推論 (`KanaKanjiConverter`)。
- 以下の変換ソースおよびフィルターを統合 (`CandidateFilterManager`):
  1. 日付・時刻フレーズ展開
  2. ユーザー辞書・追加辞書
  3. AIモデル推論候補
  4. 絵文字・記号検索
  5. システム辞書 (`dict.bin`)
  6. 学習履歴キャッシュ

### 3.3. テキスト装飾・変換ユーティリティ (`TextTransformUtils`)
- アノテーション除去 (`extractPureCandidate`)
- 全角カタカナ・半角カタカナ変換
- 半角英数・全角英数変換
- ペア括弧の自動補完・判定

---

## 4. 学習・辞書管理仕様

### 4.1. 辞書管理
- **システム辞書**: `dict.bin` (起動時自動ロード)
- **ユーザー辞書・追加辞書**: TSV 形式によるエントリの追加・編集・削除 (`UserDictFileManager`)。プリセット辞書の動的切り替え。

### 4.2. 学習履歴システム
- 確定された変換候補（読みと表記のペア）を自動学習 (`nativeRecordLearning`)。
- 学習エントリの削除 (`nativeRemoveLearning` または `Ctrl+Backspace` / `Ctrl+Delete`)。
- 学習データの TSV 形式によるインポート (`nativeLoadLearning`) およびエクスポート (`nativeSaveLearning`)。

### 4.3. カスタマイズ設定
- **AIペルソナ管理**: キャラクターや応答スタイルの切り替え (`PersonaListActivity`)。
- **日付・時刻フレーズ**: カスタム日付フォーマットの設定 (`DateConfigData`)。
- **キーボードショートカット**: ユーザー定義のキーショートカット割り当て (`KeyShortcutListActivity`)。

---

## 5. JNI インターフェース仕様一覧

Kotlin 側の `KarukanEngine.kt` と Rust 側の `src/lib.rs` 間で定義される Native 関数群の仕様:

| JNI関数名 (`src/lib.rs`) | 入力パラメータ (Kotlin) | 戻り値 / 出力 | 概要 |
|---|---|---|---|
| `nativeInit` | modelPath, tokenizerPath | boolean | GGUF モデルのロード・初期化 |
| `nativeLoadSystemDict` | dictPath | boolean | システム辞書のロード |
| `nativeLoadUserDicts` | pathsArray | boolean | ユーザー辞書のロード |
| `nativeUpdateEngineConfig` | settingsBundle | void | 推論パラメータ・スタイル設定の一括更新 |
| `nativeConvert` | query, maxCandidates, context, queryId, sourceFilter | Candidate[] | かな漢字変換候補の生成 |
| `nativeCancelQuery` | queryId | void | 実行中クエリのキャンセル |
| `nativeResetEngine` | none | void | エンジンのリセット |
| `nativeRecordLearning` | reading, surface | boolean | 学習履歴の記録 |
| `nativeRemoveLearning` | reading, surface | boolean | 学習履歴の削除 |
| `nativeSaveLearning` | outputStream | boolean | 学習履歴のTSVエクスポート |
| `nativeLoadLearning` | inputStream | boolean | 学習履歴のTSVインポート |
| `nativeClearLearning` | none | void | 学習キャッシュのクリア |
| `nativeRomajiToHiragana` | query | String | ローマ字→ひらがな変換 |
| `nativeRomajiToHiraganaFlush` | query | String | ローマ字→ひらがな変換フラッシュ |
| `nativeToHankakuKatakana` | input | String | 半角カタカナ変換 |
| `nativeToZenkakuAlphanumeric` | input | String | 全角英数変換 |
| `nativeSetDateConfigJson` | jsonString | void | 日付設定の伝達 |

---

## 6. UI/UX・Edge-to-Edge 仕様

- **Android 15 Edge-to-Edge 準拠**:
  - `WindowInsetsCompat` を用いてシステムバー（ステータスバー・ナビゲーションバー）領域のインセットを動的調整し、キーボードや設定画面のレイアウト重畳を防止。
- **テーマカラー**:
  - `ThemeColors` およびマテリアルデザイン 3 (Material 3) に準拠したダークモード対応カラースキーム。
