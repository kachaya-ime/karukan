# Karukan IME Android アーキテクチャ設計書 (Design Document)

本ドキュメントは、Android 向け Karukan IME フロントエンドモジュール (`karukan-im/android`) の全体アーキテクチャ、コンポーネント設計、データフロー、および非同期処理・メモリ管理の設計仕様について記述します。

---

## 1. アーキテクチャ概要

Karukan IME は、Android の標準 IME フレームワーク (`InputMethodService`) をベースに、Rust 製のオンデバイス言語モデルエンジン (`karukan-engine`) を JNI (`libkarukan_android.so`) 経由で統合したハイブリッドアーキテクチャを採用しています。

```mermaid
graph TD
    subgraph Android OS
        IME[InputMethodService API / SoftKeyboard Window]
    end

    subgraph Karukan IME Frontend (Kotlin / Android)
        IMS[KarukanInputMethodService] --> KIV[KarukanInputView]
        KIV --> KB[Keyboard Layout Providers & Renderers]
        KIV --> EP[EmojiPaletteController]
        IMS --> KE[KarukanEngine (JNI Bridge)]
        KE --> PM[PreferencesManager & Settings]
        UI[Settings & Management Activities] --> PM
    end

    subgraph Native Engine (Rust)
        KE <--> JNI[libkarukan_android.so]
        JNI --> GGUF[GGUF Model / Beam Search Inference]
        JNI --> Dict[System / User Dictionaries]
    end
```

---

## 2. レイヤー構成と主要コンポーネント

本モジュールは大きく分けて以下の 4 つのレイヤーで構成されています。

### 2.1. サービス・UI レイヤー (`io.github.kachaya.karukan`)
- **`KarukanInputMethodService.kt`**:
  - Android IME のライフサイクル (`onCreate`, `onStartInputView`, `onWindowHidden`, `onFinishInputView`) を管理。
  - 物理キーボードおよびソフトキーボードからのキーイベント (`onKeyDown`, `onKeyUp`) を処理し、確定文字列のコミットや変換セッションを制御。
- **`KarukanInputView.kt`**:
  - ソフトキーボードのビュー階層のルート。
  - インライン候補バー、メインキーボード、絵文字・記号パレット、候補展開フローの表示状態を統合管理。
- **候補描画・オーバーレイコンポーネント**:
  - `CandidateRenderer.kt`: 水平バー、展開フロー、フローティング候補のビュー生成・スタイリング・レンダリングの共通ロジック。
  - `FloatingCandidateOverlay.kt`: 物理キーボード接続時にカーソル位置に追従する独立した `PopupWindow` フローティング候補表示マネージャー。
  - `FloatingTooltipOverlay.kt`: ショートカット実行時や状態通知用のフローティングツールチップ表示。

### 2.2. エンジンブリッジ・設定レイヤー
- **`KarukanEngine.kt`**:
  - Native メソッド (`nativeConvert`, `nativeUpdateEngineConfig`, `nativeRecordLearning` 等) の宣言および JNI ラッパー。
  - 演算スレッド数の動的最適化 (`calculateOptimalNThreads()`)。
- **`EngineAssetPreparer.kt`**:
  - アセット領域 (`assets/`) に配置された GGUF モデル (`jinen-v2-xsmall-Q8_0.gguf`), Tokenizer (`tokenizer.json`), システム辞書 (`dict.tgz`) を内部ストレージへ非同期展開・準備するドライバ。
- **`PreferencesManager.kt` & `KarukanSettings.kt`**:
  - `SharedPreferences` と連携し、推論設定・キーボード動作・UIスタイル・ペルソナ・ショートカット設定を永続化・管理。

### 2.3. キーボードレイアウト・手書き認識レイヤー
- **レイアウト管理**:
  - `KeyboardLayoutRegistry.kt`: `qwerty`, `tablet`, `niko`, `builtin`, `graffiti` の各レイアウトプロバイダを統合管理。
  - `JsonKeyboardLayoutParser.kt`: `assets/layouts/*.json` からグリッドキーボード定義を動的にパース。
  - `StandardGridKeyboardProvider.kt`: QWERTY、タブレット、ビルトインの各レイアウトの共通グリッド配置ロジック。
  - `NikoKeyboardLayout.kt`: パナソニック製「ニコタッチ」12キー配列（数字・通常モード対応）の独自レイアウト。
  - `GraffitiKeyboardLayout.kt` & `GraffitiAreaView.kt`: Palm OS Graffiti (unistroke) 手書き入力エリアとジェスチャー描画ビュー。
  - `GraffitiStrokeRecognizer.kt`: テンプレートマッチングによる手書きストローク文字・ジェスチャー認識エンジン。
- **絵文字・パレット**:
  - `EmojiPaletteController.kt`: `ViewPager2` と `RecyclerView` を用いたカテゴリ別絵文字・記号パレットの効率的なグリッド描画とタブ切り替え。

### 2.4. 辞書・学習・拡張マネージャー層
- **`UserDictFileManager.kt`**: TSV 形式のユーザー辞書・追加辞書の CRUD、プリセット辞書切り替え管理。
- **`CandidateFilterManager.kt`**: 候補ソース（ユーザー辞書、追加辞書、AIモデル、絵文字、システム辞書、学習履歴）のフィルタリングおよび絵文字検索。
- **`TextTransformUtils.kt`**: アノテーション除去 (`extractPureCandidate`)、全角/半角カタカナ・英数変換、括弧ペア判定。

---

## 3. データフローとスレッドモデル

### 3.1. 非同期推論フロー
かな漢字変換のリアルタイム推論 (`nativeConvert`) は、メインスレッドのブロッキングを防ぐため、Kotlin Coroutines (`Dispatchers.IO`) を用いて非同期実行されます。

1. ユーザーがキー入力を行うと、`KarukanInputMethodService` がクエリ文字列を生成。
2. `KarukanEngine.convert()` が `Dispatchers.IO` 上でネイティブ関数 `nativeConvert` を呼び出し。
3. 推論中の新たな入力発生時には、`nativeCancelQuery(queryId)` により既存の推論を即座にキャンセルし、レスポンス遅延を防止。
4. 取得された候補リストをメインスレッドにディスパッチし、`CandidateRenderer` および `KarukanInputView` を更新。

### 3.2. アセット初期化パイプライン
アプリ起動時に `EngineAssetPreparer` がバックグラウンドスレッドで以下を実行:
- `assets/dict.tgz` の展開検証および内部ストレージへのコピー。
- GGUF モデルおよび Tokenizer ファイルの存在確認とロード準備。

---

## 4. メモリ管理とライフサイクル方針

- **ウィンドウ非表示時のクリーンアップ**:
  - `onWindowHidden()` 呼び出し時に `resetKeyboardState()` を実行し、絵文字パレットや展開候補ウィンドウを即座に閉じてメモリおよびリソースを解放。
- **ネイティブメモリ**:
  - GGUF モデルおよびネイティブエンジンインスタンスのライフサイクルは JNI 層と密結合し、アプリ終了時およびエンジンリセット時に安全に解放。
