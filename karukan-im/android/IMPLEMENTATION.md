# Karukan Android 実装仕様書 (Implementation Guide)

Karukan IME Android 移植版 (`karukan-im/android`) のモジュール構造、JNI 連携、キーボード処理、辞書管理、手書き認識（Graffiti）、およびライフサイクル制御などの実装情報です。

---

## 1. アプリ・コンポーネント構成

- **入力サービス・表示ビュー**:
  - `KarukanInputMethodService.kt`: IME ライフサイクル管理（ウィンドウ非表示、入力ビュー終了・開始時のキーボード状態リセット）、キーイベント処理、ライブ変換・確定制御
  - `KarukanInputView.kt`: キーボード表示、候補インライン表示、フローティング候補表示の親ビュー。絵文字・記号パレットおよび候補展開の管理 (`resetKeyboardState()`, `hideEmojiPalette()`)
  - `CandidateFlowLayout.kt`: 候補一覧のフローレイアウト管理
  - `FloatingCandidateOverlay.kt`: 物理キーボード接続時、カーソル位置追従PopupWindowフローティング表示の独立オーバーレイ管理
- **エンジンブリッジ・設定管理**:
  - `KarukanEngine.kt`: JNI ブリッジ、GGUF モデル切り替え、Native 辞書・設定・推論制御 (`Companion.calculateOptimalNThreads()` 等)
  - `EngineAssetPreparer.kt`: HuggingFace GGUF モデル、Tokenizer、システム辞書 (`dict.tgz` / `dict.bin`) アセットの非同期解凍・コピープリペアドライバ
  - `PreferencesManager.kt`: `SharedPreferences` の読み込みと設定 (`KarukanSettings`) 生成
  - `KarukanSettings.kt`: 設定データクラス（変換・パフォーマンス・学習・拡張設定・UI・キーショートカット）
  - `ThemeColors.kt`: テーマカラー管理
- **キーボードUI・マッパー・手書き認識**:
  - `KeyboardCommon.kt`: キーボード共通UIモデル、スタイル、イベント定義
  - `KeyboardLayoutBuilder.kt`: ソフトキーボードレイアウト構築オーケストレーター
  - `KeyboardLayoutRegistry.kt`: レイアウトプロバイダレジストリ（`qwerty`, `tablet`, `niko`, `builtin`, `graffiti`）
  - `StandardGridKeyboardProvider.kt`: 標準グリッド型レイアウトプロバイダ
  - `QwertyKeyboardLayout.kt`: QWERTY キーボードレイアウト定義（ベクターアイコン対応絵文字キー含む）
  - `TabletKeyboardLayout.kt`: タブレットキーボードレイアウト定義
  - `BuiltinKeyboardLayout.kt`: 内蔵物理キーボード（Unihertz Titan等）向け特殊記号グリッドレイアウト
  - `NikoKeyboardLayout.kt`: ニコタッチ入力レイアウト & マッパー（ベクターアイコン対応絵文字キー含む）
  - `GraffitiKeyboardLayout.kt`: Palm OS Graffiti (unistroke) 手書き入力レイアウトプロバイダ
  - `GraffitiAreaView.kt`: 手書きストローク描画ビュー
  - `GraffitiStrokeRecognizer.kt`: テンプレートマッチングによる手書きストローク文字・ジェスチャー認識エンジン（`graffiti_templates.json`）
  - `GraffitiHelpDialog.kt`: 手書き認識のヘルプ・テンプレート一覧ダイアログ
  - `KeyEventMapper.kt`: 物理キーボード KeyEvent 変換マッパー
  - `KeyAction.kt`: キーボードキーアクション定義
  - `EmojiPaletteController.kt`: ViewPager2 + RecyclerView を用いた絵文字・記号パレット制御（カテゴリ別タブ・グリッド描画、通常/シンボルモード対応）
- **辞書・学習管理・拡張設定・アクティビティ**:
  - `UserDictFileManager.kt`: TSVユーザー辞書エントリのCRUD、追加辞書インポート、プリセット辞書の切り替え・削除マネージャー
  - `CandidateFilterManager.kt`: AIモデル・ユーザー辞書・学習履歴・リライターのソース絞り込みフィルターおよび絵文字検索候補マネージャー
  - `TextTransformUtils.kt`: アノテーション除去 (`extractPureCandidate`)、全角/半角カタカナ・英数変換、ペア括弧判定ユーティリティ
  - `DictUtils.kt`: 辞書ファイルサイズ表記・Uriファイル名・進捗表示ダイアログユーティリティ
  - `SettingsActivity.kt`: 設定画面 (`PreferenceFragmentCompat`)
  - `UserDictContentActivity.kt`: ユーザー辞書・追加辞書編集アクティビティ
  - `PresetDictActivity.kt`: プリセット辞書管理アクティビティ
  - `LearningListActivity.kt`: 学習履歴表示・編集アクティビティ
  - `PersonaListActivity.kt`: AIペルソナ管理アクティビティ (`PersonaItem`)
  - `DatePhraseListActivity.kt`: 日付・時刻フレーズ設定アクティビティ (`DateConfigData`)
  - `KeyShortcutListActivity.kt`: キーボードショートカットカスタマイズアクティビティ (`KeyShortcutItem`)

---

## 2. IME ライフサイクルとキーボード状態管理

Karukan IME では、IME のライフサイクルイベントに応じてキーボードの表示状態（メインキーボード、絵文字・記号パレット、候補展開ビューなど）を制御する仕組みを備えています。

- **`onWindowHidden()`**: IME ウィンドウが非表示になった際、`KarukanInputView.resetKeyboardState()` を呼び出してパレットや展開候補を非表示状態にします。
- **`onFinishInputView(boolean finishingInput)`**: 入力ビューが終了する際、キーボード表示状態を標準のメインキーボードに復元します。
- **`onFinishInput()`**: 入力セッションの終了時にオーバーレイやパレットを閉じます。
- **`onStartInputView(EditorInfo attribute, boolean restarting)`**: 新しい入力セッションの開始時にキーボード状態をリセットし、メインキーボードとパレット等の重畳表示を防止します。

---

## 3. JNI (Rust / Native) 連携仕様

JNI層 (`src/lib.rs`) で提供されている全ネイティブ関数と、Kotlin層 (`KarukanEngine.kt`) のメソッドは 1 対 1 で対応しています。

| JNI関数名 (`src/lib.rs`) | Kotlin側メソッド (`KarukanEngine.kt`) | 主な機能・役割 |
|---|---|---|
| `nativeInit` | `init { ... }` (初期化) | AIモデル (`.gguf`) のロードと初期化 |
| `nativeLoadSystemDict` | `loadSystemDict()` | システム辞書 (`dict.bin`) のロード |
| `nativeLoadUserDicts` | `loadUserDicts()` | ユーザー辞書・追加辞書のロード |
| `nativeUpdateEngineConfig` | `updateEngineConfig(settings)` | 全設定の一括更新 (推論スレッド数, ストラテジー, チャンク, ビームサーチ, ペルソナ, 記号・幅スタイル, 最大学習数) |
| `nativeConvert` | `convert(query, maxCandidates, context, queryId)` | かな漢字変換の候補生成（日付・ユーザー辞書・AIモデル・カタカナ・絵文字・システム辞書・学習履歴の結合とキャンセル判定） |
| `nativeCancelQuery` | `cancelQuery(queryId)` | ライブ変換（リアルタイム推論）のキャンセル |
| `nativeRecordLearning` | `recordLearning(reading, surface)` | 選択された変換候補の学習履歴への記録 |
| `nativeRemoveLearning` | `removeLearningEntry(reading, surface)` | 学習履歴から特定のエントリを削除 |
| `nativeSaveLearning` | `backupLearningToStream` | 学習履歴のバックアップ（TSVファイルストリーム出力） |
| `nativeLoadLearning` | `restoreLearningFromStream` | 学習履歴の復元（TSVファイルを内部学習辞書へ上書きコピーし、ネイティブパースによりカウント・タイムスタンプをロスレスで読み込み） |
| `nativeClearLearning` | `clearLearning()` | メモリ上の学習キャッシュのクリア |
| `nativeRomajiToHiragana` | `romajiToHiragana(query)` | ローマ字→ひらがな変換 |
| `nativeRomajiToHiraganaFlush` | `romajiToHiraganaFlush(query)` | ローマ字→ひらがな変換のフラッシュ（末尾の未確定ローマ字の確定処理） |

### 設定更新 (`nativeUpdateEngineConfig`) で伝達されるパラメータ

`KarukanSettings` の各種設定値は `nativeUpdateEngineConfig` 1 つの JNI 関数を介して Native 側に一括伝達されます。

- **推論パラメータ**: `nThreads`, `strategy`, `maxLatencyMs`, `chunkChars`, `chunkSymbols`, `chunkDigits`, `chunkAlphabets`, `beamWidth`, `beamChars`, `persona`, `useContext`, `contextChars`
- **記号・文字幅スタイル**: `punctuationStyle`, `bracketStyle`, `slashStyle`, `spaceStyle`, `kanaSymbolWidth`, `asciiWidth`, `digitWidth`
- **変換学習設定**: `maxLearningEntries`, `isLearningEnabled`, `isDateConversionEnabled`

---

## 4. ショートカットキー・物理キーボード対応仕様 (Keyboard Shortcuts)

他フロントエンド（fcitx5 / macOS）との互換性を持つ物理キーボードおよびソフトキーボードのショートカット仕様です。
設定画面の「キーショートカットの設定」から、ユーザーがキーの組み合わせとアクションを自由に変更・定義・未割り当て化できます。（※Android標準の編集ショートカット Ctrl+A, C, V, X, Z は安全のため予約済みとして固定されています）

### ファンクションキー変換 (Function Keys)
| キー | 機能 | 動作・処理 |
|---|---|---|
| **F6** | ひらがな変換 | 入力中の文字列をひらがなへ変換（未確定表示） |
| **F7** | 全角カタカナ変換 | 入力中の文字列を全角カタカナへ変換（未確定表示） |
| **F8** | 半角カタカナ変換 | 入力中の文字列を半角カタカナへ変換（未確定表示） |
| **F9** | 全角英数変換 | 入力中の文字列を全角英数へ変換（連続押下で小文字/大文字/頭大文字トグル、未確定表示） |
| **F10** | 半角英数変換 | 入力中の文字列を半角英数へ変換（連続押下で小文字/大文字/頭大文字トグル、未確定表示） |

### Ctrl キーショートカット (Ctrl Shortcuts)
| ショートカット | 機能 | 動作・制御条件 | 仕様区分 |
|---|---|---|---|
| **Ctrl + A** | 全選択 | 編集領域のテキスト全選択 (`selectAllEditingArea` -> `InputConnection.selectAll`) | 共通仕様 |
| **Ctrl + B** | カーソル左移動 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準の太字設定等へ委譲） | 共通仕様 |
| **Ctrl + C** | コピー | 編集領域のテキストコピー (`copyEditingArea` -> `InputConnection.copy`) | 共通仕様 |
| **Ctrl + D** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + E** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + F** | カーソル右移動 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準検索等へ委譲） | 共通仕様 |
| **Ctrl + G** | キャンセル | 変換・候補選択のキャンセル (`onEscape`) | 共通仕様 |
| **Ctrl + H** | 文字削除 | 前の1文字を削除 (`onBackspace`) | 共通仕様 |
| **Ctrl + I** | 🤖AI 候補ビューオープン | AI 候補ビューを直接開く (`filterByAiSource`) | 共通仕様 |
| **Ctrl + J** | Chunk区切り・固定 | 未確定表示のまま Chunk を区切り固定 (`fixChunkPreedit`、確定・改行なし) | 共通仕様 |
| **Ctrl + K** | カタカナモード切替 | カタカナ入力モードへのトグル切替 (`commitKatakanaOrToggleMode`) | 共通仕様 |
| **Ctrl + L** | ライブ変換トグル | リアルタイムライブ変換の有効/無効切替 (`onToggleLiveConversion`) | **Android独自** |
| **Ctrl + M** | 確定 | 入力内容を確定 (`onCommitKey`) | 共通仕様 |
| **Ctrl + N** | 次候補 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準の新規作成等へ移譲） | 共通仕様 |
| **Ctrl + O** | *未割当* | 常時 OS / アプリ標準機能へ委ねる | - |
| **Ctrl + P** | 前候補 | 入力中・候補選択中のみ動作 (`onPreviousCandidate`)（未入力時は OS/アプリ標準印刷等へ移譲） | 共通仕様 |
| **Ctrl + Q** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + R** | 🔄記号変換ビューオープン | 記号変換ソース絞り込みビューを直接開く (`activeSourceFilter = SourceFilter.SYMBOL`) | 共通仕様 |
| **Ctrl + S** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + T** | 📝学習ソースビューオープン | 学習ソース絞り込みビューを直接開く (`activeSourceFilter = SourceFilter.LEARNING`) | 共通仕様 |
| **Ctrl + U** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + V** | 貼り付け | クリップボードから貼り付け (`pasteEditingArea` -> `InputConnection.paste`) | 共通仕様 |
| **Ctrl + W** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + X** | 切り取り | 編集領域のテキスト切り取り (`cutEditingArea` -> `InputConnection.cut`) | 共通仕様 |
| **Ctrl + Y** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + Z** | 元に戻す (Undo) | 直前の編集操作を元に戻す (`undoEditingArea` -> `InputConnection.undo`) | 共通仕様 |
| **Ctrl + 1 〜 9** | 候補番号選択・確定 | 表示中の 1〜9 番目の候補を直接選択・確定 (`SelectCandidateNumber`) | 共通仕様 |
| **Ctrl + Backspace / Delete** | 候補学習削除 | 選択中の変換候補を学習辞書から削除 (`onDeleteSelectedCandidate`) | 共通仕様 |
| **Ctrl + Space** | モード切替 | 日本語(ひらがな) / 英語(英数) モードのトグル切替 (`onToggleLanguageMode`) | 共通仕様 |

### Shift キー組み合わせ操作 (Shift Combinations)
| ショートカット | 機能 | 動作・処理 |
|---|---|---|
| **Shift + ← / → / ↑ / ↓** | テキスト範囲選択 | 選択範囲を変更する KeyEvent を送信 (`sendKeyEvent` with `META_SHIFT_ON`) |
| **Shift + Tab** | 逆方向移動 / 前候補 | 候補選択時は前候補選択、非入力時は逆方向フォーカス移動 |
| **Shift + Space** | 全角スペース / 前候補 | 入力中/変換時: 前候補選択 (`onPreviousCandidate`) / 非入力時: 全角スペースを入力 (`onFullWidthSpace`) |

---

## 5. 他の Karukan フロントエンドとの動作仕様比較 (Cross-Platform Frontend Comparison)

`karukan-im` リポジトリ内の各フロントエンド（`karukan-im/fcitx5`, `karukan-im/macos`, `karukan-im/windows`）と `karukan-im/android` のキーバインド・アクション比較です。

| ショートカット | `karukan-im / fcitx5` | `karukan-im / macos` | `karukan-im / windows` | `karukan-im / android` | 補足・仕様区分 |
|---|---|---|---|---|---|
| **Ctrl + A** | 行頭へ移動 | 行頭へ移動 | 全選択 | **全選択** | モバイル向け割り当て |
| **Ctrl + B** | カーソル左移動 | カーソル左移動 | カーソル左移動 | **カーソル左移動** | Emacsバインド共通 |
| **Ctrl + C** | OS標準 | OS標準 | OS標準 | **コピー** | モバイル標準コピー |
| **Ctrl + F** | カーソル右移動 | カーソル右移動 | カーソル右移動 | **カーソル右移動** | Emacsバインド共通 |
| **Ctrl + G** | キャンセル | キャンセル | キャンセル | **キャンセル** | 共通キャンセル |
| **Ctrl + H** | 1文字削除 | 1文字削除 | 1文字削除 | **1文字削除** | Emacsバインド共通 |
| **Ctrl + I** | AI候補 | AI候補 | AI候補 | **AI候補** | Karukan共通機能 |
| **Ctrl + J** | Chunk固定 | Chunk固定 | Chunk固定 | **Chunk固定** | Karukan共通機能 |
| **Ctrl + K** | カタカナ切替 | カタカナ切替 | カタカナ切替 | **カタカナ切替** | Karukan共通機能 |
| **Ctrl + L** | - | - | - | **ライブ変換切替** | Android独自 |
| **Ctrl + M** | 確定 | 確定 | 確定 | **確定** | 共通確定 |
| **Ctrl + N** | 次候補 | 次候補 | 次候補 | **次候補** | 共通次候補 |
| **Ctrl + P** | 前候補 | 前候補 | 前候補 | **前候補** | 共通前候補 |
| **Ctrl + R** | 記号・日付 | 記号・日付 | 記号・日付 | **記号・日付** | Karukan共通機能 |
| **Ctrl + T** | 学習ソース | 学習ソース | 学習ソース | **学習ソース** | Karukan共通機能 |
| **Ctrl + V** | OS標準 | OS標準 | OS標準 | **貼り付け** | モバイル標準貼り付け |
| **Ctrl + X** | OS標準 | OS標準 | OS標準 | **切り取り** | モバイル標準切り取り |
| **Ctrl + Z** | OS標準 | OS標準 | OS標準 | **Undo** | モバイル標準Undo |
| **Ctrl + 1〜9** | 候補番号選択 | 候補番号選択 | 候補番号選択 | **候補番号選択** | Karukan共通機能 |
| **Ctrl + Space** | モード切替 | モード切替 | モード切替 | **モード切替** | モード切替 |
