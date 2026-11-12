# Karukan Android 実装仕様書 (Implementation Guide)

Karukan IME Android 移植版 (`karukan-im/android`) のモジュール構造、JNI 連携、キーボード処理、辞書管理、およびライフサイクル制御などの実装情報です。

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
  - `KarukanSettings.kt`: 設定データモデル
- **キーボードUI・マッパー**:
  - `KeyboardCommon.kt`: キーボード共通UIモデル、スタイル、イベント定義
  - `KeyboardLayoutBuilder.kt`: ソフトキーボードレイアウト構築オーケストレーター
  - `QwertyKeyboardLayout.kt`: QWERTY キーボードレイアウト定義（ベクターアイコン対応絵文字キー含む）
  - `TabletKeyboardLayout.kt`: タブレットキーボードレイアウト定義
  - `NikoKeyboardLayout.kt`: ニコタッチ入力レイアウト & マッパー（ベクターアイコン対応絵文字キー含む）
  - `KeyEventMapper.kt`: 物理キーボード KeyEvent 変換マッパー
  - `KeyAction.kt`: キーボードキーアクション定義
  - `EmojiPaletteController.kt`: ViewPager2 + RecyclerView を用いた絵文字・記号パレット制御（カテゴリ別タブ・グリッド描画、通常/シンボルモード対応）
- **辞書・学習管理・フィルター・テキスト操作**:
  - `UserDictFileManager.kt`: TSVユーザー辞書エントリのCRUD、追加辞書インポート、プリセット辞書の切り替え・削除マネージャー
  - `CandidateFilterManager.kt`: AIモデル・ユーザー辞書・学習履歴・リライターのソース絞り込みフィルターおよび絵文字検索候補マネージャー
  - `TextTransformUtils.kt`: アノテーション除去 (`extractPureCandidate`)、全角/半角カタカナ・英数変換、ペア括弧判定ユーティリティ
  - `DictUtils.kt`: 辞書ファイルサイズ表記・Uriファイル名・進捗表示ダイアログユーティリティ
  - `PresetDictActivity.kt`: プリセット辞書管理アクティビティ
  - `UserDictContentActivity.kt`: ユーザー辞書・追加辞書編集アクティビティ
  - `LearningListActivity.kt`: 学習履歴表示・編集アクティビティ
  - `SettingsActivity.kt`: 設定画面 (`PreferenceFragmentCompat`)

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
| `nativeUpdateEngineConfig` | `updateEngineConfig(settings)` | 全設定の一括更新 (推論スレッド数, ストラテジー, チャンク, ビームサーチ, 記号・幅スタイル, 最大学習数) |
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

`nativeConvert` は共通エンジンと同じチャンク境界を使い、ビーム幅を末尾のチャンク範囲に適用します。ビーム範囲より前は各チャンクを先頭候補で変換し、その結果を次のチャンクの左文脈に含めます。純粋な数字・英字・記号チャンクはそのまま残します。

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
| **Ctrl + U** | *未割当* | 常時 OS / アプリ標準機能へ委ねる | - |
| **Ctrl + V** | 貼り付け | クリップボードから貼り付け (`pasteEditingArea` -> `InputConnection.paste`) | 共通仕様 |
| **Ctrl + W** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + X** | 切り取り | 編集領域のテキスト切り取り (`cutEditingArea` -> `InputConnection.cut`) | 共通仕様 |
| **Ctrl + Y** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + Z** | 元に戻す (Undo) | 直前の編集操作を元に戻す (`undoEditingArea` -> `InputConnection.undo`) | 共通仕様 |
| **Ctrl + 1 〜 9** | 候補番号選択・確定 | 表示中の 1〜9 番目の候補を直接選択・確定 (`SelectCandidateNumber`) | 共通仕様 |
| **Ctrl + Backspace / Delete** | 候補学習削除 | 選択中の変換候補を学習辞書から削除 (`onDeleteSelectedCandidate`) | 共通仕様 |
| **Ctrl + Shift + L** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + Shift + V** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + Space** | モード切替 | 日本語(ひらがな) / 英語(英数) モードのトグル切替 (`onToggleLanguageMode`) | 共通仕様 |

### Shift キー組み合わせ操作 (Shift Combinations)
| ショートカット | 機能 | 動作・処理 |
|---|---|---|
| **Shift + ← / → / ↑ / ↓** | テキスト範囲選択 | 選択範囲を変更する KeyEvent を送信 (`sendKeyEvent` with `META_SHIFT_ON`) |
| **Shift + Tab** | 逆方向移動 / 前候補 | 候補選択時は前候補選択、非入力時は逆方向フォーカス移動 |
| **Shift + Space** | 全角スペース / 前候補 | 入力中/変換時: 前候補選択 (`onPreviousCandidate`) / 非入力時: 全角スペースを入力 (`onFullWidthSpace`) |

### JIS配列・特殊物理キー (JIS & Physical Special Keys)
| キー (シンボル名) | 機能 | 動作・処理 |
|---|---|---|
| **変換 (HENKAN)** | 変換 / 次候補 / 再変換 | 入力中: 変換・次候補 (`onSpaceKey`) / 未入力時: 確定取り消し・再変換 (`reconvert`) |
| **無変換 (MUHENKAN)** | カタカナ・ひらがな変換 | 入力中: 全角カタカナ→半角カタカナ→ひらがな巡回 / 未入力時: カタカナモード切替 |
| **かな (KANA)** | ひらがなモード切替 | 入力中: ひらがな変換 / 未入力時: ひらがな入力モード切替 (`onKanaKey`) |
| **ひらがな / カタカナ (KATAKANA_HIRAGANA)** | ひらがなモード切替 | 入力中: ひらがな変換 / 未入力時: ひらがな入力モード切替 (`onKanaKey`) |
| **半角/全角 (ZENKAKU_HANKAKU / GRAVE)** | 言語モード切替 | 日本語 / 英語モード切替 (`onToggleLanguageMode` ※Shift+Graveは `~` 入力) |
| **英数 (EISU)** | 英数モード切替 | 英数(アルファベット)入力モードに直接切替 (`onEisuKey`) |
| **Delete (FORWARD_DEL)** | 後方削除 | カーソル後方の1文字削除 (`onDeleteForward`) |
| **Home (MOVE_HOME)** | 先頭移動 | カーソルを行頭（先頭）へ移動 (`moveCursorToStartOfLine`) |
| **End (MOVE_END)** | 末尾移動 | カーソルを行末（末尾）へ移動 (`moveCursorToEndOfLine`) |
| **Escape (ESCAPE)** | キャンセル | 変換・候補選択のキャンセル (`onEscape`) |
| **Tab (TAB)** | 次候補 / フォーカス | 次の変換候補を選択またはフォーカス移動 (`onTabKey`) |
| **PageUp (PAGE_UP)** | ページアップ | 候補一覧または画面のページアップ (`onPageUp`) |
| **PageDown (PAGE_DOWN)** | ページダウン | 候補一覧または画面のページダウン (`onPageDown`) |
| **SYM (SYM)** | 絵文字・記号パレット | 絵文字・記号パレットの表示切替 (`ToggleEmojiPalette`) |

---

## 5. 他の Karukan フロントエンドとの動作仕様比較 (Cross-Platform Frontend Comparison)

`karukan-im` リポジトリ内の各フロントエンド（`karukan-im/fcitx5`, `karukan-im/macos`, `karukan-im/windows`）と `karukan-im/android` のキーバインド・アクション比較です。

### 共通ショートカットキー比較表
| ショートカット | `karukan-im / fcitx5` (Linux) | `karukan-im / macos` (macOS) | `karukan-im / windows` (Windows) | `karukan-im / android` (Android 最新) | 補足・仕様区分 |
|---|---|---|---|---|---|
| **Ctrl + A** | **行頭へ移動** (Emacs風) | **行頭へ移動** (※全選択は `Cmd+A`) | **全選択** (OS標準) | **全選択** | モバイル向けに全選択に割り当て |
| **Ctrl + B** | **カーソル左移動** | **カーソル左移動** | **カーソル左移動** | **カーソル左移動** | Emacsバインド共通 |
| **Ctrl + C** | OS標準コピーへ委譲 | OS標準コピー (`Cmd+C`) | OS標準コピー | **コピー** | モバイル標準コピー |
| **Ctrl + D** | *未割当* | 文字削除 (Delete) | *未割当* | **未割当** | - |
| **Ctrl + E** | **行末へ移動** (Emacs風) | **行末へ移動** (Emacs風) | *未割当* | **未割当** | モバイル標準へ委譲 |
| **Ctrl + F** | **カーソル右移動** | **カーソル右移動** | **カーソル右移動** | **カーソル右移動** | Emacsバインド共通 |
| **Ctrl + G** | **キャンセル** | **キャンセル** | **キャンセル** | **キャンセル** | 共通キャンセル |
| **Ctrl + H** | **1文字削除** (Backspace) | **1文字削除** | **1文字削除** | **1文字削除** | Emacsバインド共通 |
| **Ctrl + I** | **🤖 AI 候補ビュー** | **🤖 AI 候補ビュー** | **🤖 AI 候補ビュー** | **🤖 AI 候補ビュー** | Karukan共通機能 |
| **Ctrl + J** | **Chunk区切り・固定** | **Chunk区切り・固定** | **Chunk区切り・固定** | **Chunk区切り・固定** | Karukan共通機能 |
| **Ctrl + K** | **カタカナモード切替** | **カタカナモード切替** | **カタカナモード切替** | **カタカナモード切替** | Karukan共通機能 |
| **Ctrl + L** | *未割当* | *未割当* | *未割当* | **ライブ変換 ON/OFF** | Android独自 |
| **Ctrl + M** | **確定** (Enter) | **確定** (Enter) | **確定** (Enter) | **確定** | 共通確定 |
| **Ctrl + N** | **次候補** | **次候補** | **次候補** | **次候補** | 共通次候補 |
| **Ctrl + P** | **前候補** | **前候補** | **前候補** | **前候補** | 共通前候補 |
| **Ctrl + R** | **🔄 記号・日付ビュー** | **🔄 記号・日付ビュー** | **🔄 記号・日付ビュー** | **🔄 記号・日付ビュー** | Karukan共通機能 |
| **Ctrl + T** | **📝 学習ソースビュー** | **📝 学習ソースビュー** | **📝 学習ソースビュー** | **📝 学習ソースビュー** | Karukan共通機能 |
| **Ctrl + V** | OS標準貼り付けへ委譲 | OS標準貼り付け (`Cmd+V`) | OS標準貼り付け | **貼り付け** | モバイル標準貼り付け |
| **Ctrl + X** | OS標準切り取りへ委譲 | OS標準切り取り (`Cmd+X`) | OS標準切り取り | **切り取り** | モバイル標準切り取り |
| **Ctrl + Z** | OS標準Undoへ委譲 | OS標準Undo (`Cmd+Z`) | OS標準Undo | **Undo (元に戻す)** | モバイル標準Undo |
| **Ctrl + 1〜9** | **候補番号直接選択** | **候補番号直接選択** | **候補番号直接選択** | **候補番号直接選択** | Karukan共通機能 |
| **Ctrl + Backspace / Delete** | **学習履歴から削除** | **学習履歴から削除** | **学習履歴から削除** | **学習履歴から削除** | Karukan共通機能 |
| **Ctrl + Shift + L** | **ライブ変換 ON/OFF** | **ライブ変換 ON/OFF** | **ライブ変換 ON/OFF** | *未割当* | - |
| **Ctrl + Shift + V** | **詳細表示トグル** | **詳細表示トグル** | **詳細表示トグル** | *未割当* | - |
| **Ctrl + Space** | モード切替 | モード切替 | モード切替 | **モード切替** | モード切替 |
| **Shift + Space** | **全角スペース** / 前候補 | **全角スペース** / 前候補 | **全角スペース** / 前候補 | **全角スペース / 前候補** | 空白入力 / 前候補 |

### ファンクションキー (F6〜F10) 比較表（全フロントエンド共通）
| キー | `fcitx5` | `macos` | `windows` | `android` | 動作詳細 |
|---|---|---|---|---|---|
| **F6** | **ひらがな変換** | **ひらがな変換** | **ひらがな変換** | **ひらがな変換** | 全フロントエンド共通（未確定表示） |
| **F7** | **全角カタカナ変換** | **全角カタカナ変換** | **全角カタカナ変換** | **全角カタカナ変換** | 全フロントエンド共通（未確定表示） |
| **F8** | **半角カタカナ変換** | **半角カタカナ変換** | **半角カタカナ変換** | **半角カタカナ変換** | 全フロントエンド共通（未確定表示） |
| **F9** | **全角英数変換** | **全角英数変換** | **全角英数変換** | **全角英数変換** | 全フロントエンド共通（小/大/頭大文字） |
| **F10** | **半角英数変換** | **半角英数変換** | **半角英数変換** | **半角英数変換** | 全フロントエンド共通（小/大/頭大文字） |

### 各フロントエンド固有のキー機能（OSプラットフォーム依存）
| フロントエンド | 固有のキー操作・機能 | 役割・補足 |
|---|---|---|
| **`karukan-im / fcitx5`** (Linux) | **右Super** / **右Alt** / **変換 (JIS)** | 英数字/カタカナモードから**ひらがなモードへ一発復帰** |
| **`karukan-im / macos`** (macOS) | **かな (JIS)** / **右⌘ (Right Command) 単独タップ** | **ひらがなモードへ復帰**（右⌘タップはUS配列向けの代用ジェスチャー） |
| | **英数 (JIS)** | 変換中テキストを確定して**直接入力モードへ切替** |
| **`karukan-im / windows`** (Windows) | **半角/全角** | IME ON/OFF |
| | **変換 (JIS)** | ひらがな復帰 / 未入力時: **確定取り消し・再変換** |
| **`karukan-im / android`** (Android) | **かな** / **英数** / **無変換** / **変換** / **半角全角** | タッチパネル・物理キーボードの両方に対応した一元化アクション |
