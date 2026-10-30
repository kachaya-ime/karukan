# karukan-im (Android)

Android 向け Karukan IME フロントエンドモジュールです。  
`karukan-engine` (Rust) を JNI (`libkarukan_android.so`) 経由で呼び出し、オンデバイスで動作する言語モデルを用いたかな漢字変換機能を提供します。

## ドキュメント

- [実装仕様書 (IMPLEMENTATION.md)](IMPLEMENTATION.md) - JNI (Rust) 連携仕様、コンポーネント構成、および物理キーボードショートカット一覧
- [ユーザーマニュアル (MANUAL.md)](MANUAL.md) - 各種キーボードレイアウトの使い方、設定、辞書管理、および学習データバックアップ・復元の手順

## 機能仕様

- **入力インターフェース**:
  - QWERTY ソフトウェアキーボード（ローマ字入力対応、ワンタップ記号切替）
  - タブレットレイアウト（フルキーボード型）
  - ニコタッチ入力（12キー配列、通常・数字専用モード）
  - 絵文字・記号パレット (`EmojiPaletteController`, ViewPager2 によるカテゴリ別グリッド描画)
  - 物理キーボード / エミュレータからの KeyEvent (`onKeyDown` / ショートカット) サポート
- **変換処理**:
  - ローマ字→ひらがな変換 (`RomajiConverter`)
  - GGUF モデルによるビームサーチ推論 (`KanaKanjiConverter`)
  - クエリキャンセル (`cancelQuery`)
  - 候補バーおよびフローティング候補表示、全画面多段展開表示
- **辞書・学習機能**:
  - システム辞書 (`dict.bin`)、追加プリセット辞書、ユーザー辞書の管理
  - 選択候補の学習履歴記録および学習削除 (`Ctrl+Backspace` / `Ctrl+Delete`)
  - 学習データの TSV 形式によるバックアップ・復元
- **システム対応**:
  - Android 15 (API レベル 35) Edge-to-Edge 仕様に準拠 (`WindowInsetsCompat` によるシステムバー領域のインセット調整)
- **設定およびライセンス表示**:
  - 設定画面 (`SettingsActivity` / `PreferenceFragmentCompat`)
  - アセットから読み込むサードパーティライセンス (`THIRD_PARTY_LICENSES`) および MIT / Apache 2.0 ライセンス表示

## ビルドタスク (Gradle Tasks)

`app/build.gradle.kts` にて以下の自動ビルドタスクが `preBuild` に依存して実行されます。

- **`downloadAssets`**: HuggingFace から GGUF モデル (`jinen-v2-xsmall-Q5_K_M.gguf`) および `tokenizer.json` を取得
- **`downloadDict`**: 公式システム辞書アーカイブ (`dict.tgz`) を取得・展開
- **`copyUserDicts`**: ユーザー辞書領域の初期化
- **`generateEmojiList`**: `unicode.org` から `emoji-test.txt` を取得・処理し、カテゴリ別絵文字リスト (`emoji_categories.txt`) を生成
- **`copyLegalInfo`**: リポジトリルートからライセンスファイルを `src/main/assets/legal/` に同期
- **`buildRust`**: NDK を利用した Rust コード (`src/lib.rs`) の自動クロスコンパイル

## ディレクトリ構成

```text
android/
├── app/                      # Android アプリケーションモジュール (Kotlin)
│   ├── src/main/java/        # IME サービス、エンジンブリッジ、設定アクティビティ、キーボードUI
│   ├── src/main/res/         # レイアウト XML、Preference XML、各種リソース
│   └── build.gradle.kts      # モジュールレベル Gradle ビルドスクリプト
├── src/                      # JNI ブリッジ用 Rust ソースコード (lib.rs)
├── Cargo.toml                # Rust クレート設定
├── build.ps1                 # Rust NDK クロスコンパイル用 PowerShell スクリプト
└── .gitignore                # モジュール専用 Git 除外設定
```

## ビルド環境および手順

### 前提条件
- **JDK**: 17
- **Android SDK**: API レベル 35 以上 (compileSdk 37)
- **Android NDK**: 設定された NDK バージョン
- **Rust Toolchain**: `aarch64-linux-android` 等のターゲットが組み込まれていること

### ビルドコマンド

1. モジュールディレクトリへ移動:
   ```bash
   cd karukan-im/android
   ```

2. デバッグ用 APK のビルド:
   ```bash
   ./gradlew assembleDebug
   ```

3. ターゲット端末へのインストール:
   ```bash
   ./gradlew installDebug
   ```

## ライセンス

本 Android アプリモジュール（フロントエンド・UI）は、オリジナルプロジェクト（Karukan）のライセンス規定およびデュアルライセンスに準拠します。

- **Karukan (本体・エンジン)**: Copyright (c) togatoga, MIT License / Apache License 2.0 (デュアルライセンス)
- **Karukan IME (Android フロントエンド・UI・インテグレーション)**: Copyright (c) kachaya, MIT License / Apache License 2.0 (デュアルライセンス)
- **サードパーティデータ・素材**:
  - Mozc 由来の辞書データ・記号ルールに関する著作権表記については `THIRD_PARTY_LICENSES` を参照してください。
  - **ニコタッチ方式 (Niko Touch)**: パナソニック株式会社の携帯電話等で採用されていた「ニコタッチ」入力方式に基づくレイアウト・マッピング仕様を採用しています（詳細は `THIRD_PARTY_LICENSES` を参照）。
  - **アプリアイコン (Android Robot)**: Android ロボットは、Google が作成および提供している作品から複製または変更したものであり、クリエイティブ・コモンズ 3.0 表示ライセンス (CC BY 3.0) に記載された条件に従って使用しています。
